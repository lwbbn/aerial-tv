package cn.quizlock.aerial.quizlock

import kotlin.random.Random

/**
 * The question model, carried over from QuizLock's Dart core (docs/08 §4). A question hangs off
 * a curriculum-level knowledge point (ADR-016), never a chapter number; options are shuffled per
 * asking so the child cannot memorize positions.
 */
data class QuestionOption(val id: String, val text: String)

data class Question(
    val id: String,
    val stage: String = "primary",
    val grade: String = "G5",
    val term: String = "UP",
    val textbook: String = "PEP",
    val subject: String,
    val unit: String,
    val knowledgePoint: String,
    val difficulty: Int = 2,
    val variantGroup: String,
    val stem: String,
    val options: List<QuestionOption>,
    val correctOptionId: String,
    val explanation: String = "",
) {
    fun isCorrect(optionId: String): Boolean = optionId == correctOptionId

    /** Shuffles options with [seed], so the same question never shows the same layout twice. */
    fun shuffled(seed: Int): Question {
        val list = options.toMutableList()
        // Fisher-Yates with a seeded generator, same idea as the Dart original.
        val rnd = Random(seed)
        for (i in list.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
        // Relabel by position: the letters always read A B / C D on screen, while the correct
        // answer still moves around, so a child cannot memorize "it's always A".
        val correct = options.firstOrNull { it.id == correctOptionId }
        val relabeled = list.mapIndexed { i, o -> o.copy(id = ('A' + i).toString()) }
        val newCorrectId = relabeled.firstOrNull { it.text == correct?.text }?.id ?: correctOptionId
        return copy(options = relabeled, correctOptionId = newCorrectId)
    }
}

/**
 * The picker, a faithful port of QuizLock's QuestionPicker (docs/08 §5): tag filter → weighted
 * random → LRU dedup → variant swap → graded fallback. Wrong answers prefer the same variant
 * group, then the same knowledge point, then the same unit; never out-of-syllabus.
 */
class QuestionPicker(
    private val bank: List<Question>,
    private val random: Random = Random.Default,
    private val noRepeatWindow: Int = 10,
) {
    private val recent = ArrayDeque<String>()
    private val wrongByKp = HashMap<String, Int>()
    private var shuffleSeed = 1

    fun recordAnswer(q: Question, correct: Boolean) {
        if (!correct) wrongByKp[q.knowledgePoint] = (wrongByKp[q.knowledgePoint] ?: 0) + 1
        else {
            val v = wrongByKp[q.knowledgePoint] ?: 0
            if (v > 0) wrongByKp[q.knowledgePoint] = v - 1
        }
    }

    /** Picks the next question; relaxes filters level by level so the lock can never jam. */
    fun pick(policy: PickerPolicy, excludeId: String? = null): Question {
        for (level in 0..4) {
            val candidates = filter(policy, level, excludeId)
            if (candidates.isEmpty()) continue
            val q = weightedPick(candidates, policy)
            remember(q.id)
            return q.shuffled(shuffleSeed++)
        }
        if (bank.isEmpty()) throw IllegalStateException("题库为空")
        val q = bank.firstOrNull { it.id != excludeId } ?: bank.first()
        remember(q.id)
        return q.shuffled(shuffleSeed++)
    }

    /**
     * A wrong answer swaps in a similar question: same variant group → same knowledge point →
     * same unit → graded fallback. Repeating a same-group question beats jumping out of syllabus.
     */
    fun pickSimilar(origin: Question, policy: PickerPolicy, extraExcludeId: String? = null): Question {
        val excluded = mutableSetOf(origin.id)
        if (extraExcludeId != null) excluded.add(extraExcludeId)
        val recentSet = recent.toSet()

        fun pool(match: (Question) -> Boolean): List<Question> {
            val fresh = ArrayList<Question>(); val seen = ArrayList<Question>()
            for (q in bank) {
                if (excluded.contains(q.id) || !match(q)) continue
                if (q.id in recentSet) seen.add(q) else fresh.add(q)
            }
            return fresh + seen
        }

        val sameGroup = pool { it.variantGroup == origin.variantGroup }
        val sameKp = pool { it.knowledgePoint == origin.knowledgePoint }
        val sameUnit = pool { it.subject == origin.subject && it.unit == origin.unit }
        val chosen = when {
            sameGroup.isNotEmpty() -> weightedPick(sameGroup, policy)
            sameKp.isNotEmpty() -> weightedPick(sameKp, policy)
            sameUnit.isNotEmpty() -> weightedPick(sameUnit, policy)
            else -> fallbackPick(policy, excluded)
        }
        remember(chosen.id)
        return chosen.shuffled(shuffleSeed++)
    }

    private fun fallbackPick(policy: PickerPolicy, excluded: Set<String>): Question {
        for (level in 0..4) {
            val candidates = filter(policy, level, null).filter { it.id !in excluded }
            if (candidates.isEmpty()) continue
            return weightedPick(candidates, policy)
        }
        return bank.firstOrNull { it.id !in excluded } ?: bank.first()
    }

    /** Level 0 strictest, relaxing to all: unit → textbook → term → grade → whole bank. */
    private fun filter(policy: PickerPolicy, level: Int, excludeId: String?): List<Question> {
        val recentSet = recent.toSet()
        // docs/08 §4: 8-1 月按上册, 2-7 月按下册. A calendar can only infer the TERM; it can
        // never infer the current unit (school pace, holidays and the 2026 textbook revision all
        // vary), which is why currentUnit stays a parent-set field.
        val term = if (policy.term == "AUTO") {
            val m = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
            if (m >= 8 || m <= 1) "UP" else "DOWN"
        } else policy.term
        val subjects = policy.subjectWeights.keys
        return bank.filter { q ->
            if (excludeId != null && q.id == excludeId) return@filter false
            if (level <= 3 && q.grade != policy.grade) return@filter false
            if (level <= 2 && q.term != term) return@filter false
            if (level <= 1 && q.textbook != policy.textbook) return@filter false
            if (level == 0) {
                if (policy.currentUnit.isNotEmpty() && q.unit != policy.currentUnit) return@filter false
                if (subjects.isNotEmpty() && q.subject !in subjects) return@filter false
            }
            q.id !in recentSet
        }
    }

    private fun weightedPick(candidates: List<Question>, policy: PickerPolicy): Question {
        val weights = DoubleArray(candidates.size)
        var total = 0.0
        for ((i, q) in candidates.withIndex()) {
            var w = policy.subjectWeights[q.subject] ?: 0.2
            if (policy.currentUnit.isNotEmpty() && q.unit == policy.currentUnit) w *= 2.0
            val wrong = wrongByKp[q.knowledgePoint] ?: 0
            if (wrong > 0) w *= 1.0 + kotlin.math.min(3, wrong) * 0.3
            weights[i] = w
            total += w
        }
        var r = random.nextDouble() * total
        for (i in candidates.indices) {
            r -= weights[i]
            if (r <= 0) return candidates[i]
        }
        return candidates.last()
    }

    private fun remember(id: String) {
        recent.remove(id)
        recent.addLast(id)
        val limit = minOf(noRepeatWindow, (bank.size * 0.6).toInt())
        while (recent.size > limit) recent.removeFirst()
    }
}
