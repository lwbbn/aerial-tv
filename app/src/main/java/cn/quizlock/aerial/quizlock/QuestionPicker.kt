package cn.quizlock.aerial.quizlock

import java.util.Calendar
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
    /** True for entries produced by [QuestionFactory] when the local pack was expanded. */
    val fresh: Boolean = false,
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
 *
 * The bank is expanded once per process by [QuestionFactory]: every knowledge point gets a batch
 * of template variants with fresh numbers/lines/words, so the child meets new stems every run.
 * Picking itself only ever reads from that bank — it first **randomises over knowledge points**
 * (weighted by subject, current unit and past mistakes) and then draws one question from the
 * chosen point's own questions. No stem is invented at ask time.
 */
class QuestionPicker(
    bank: List<Question>,
    private val random: Random = Random.Default,
    private val noRepeatWindow: Int = 24,
) {
    private val pool = bank.toMutableList()
    private val recent = ArrayDeque<String>()
    private val wrongByKp = HashMap<String, Int>()
    private var shuffleSeed = 1
    private var expanded = false

    val size: Int get() = pool.size
    val generatedCount: Int get() = pool.count { it.fresh }
    val knowledgePointCount: Int get() = pool.map { it.knowledgePoint }.toSet().size

    /** Expands the local pack once, using the current policy's grade / term / textbook tags. */
    fun ensureExpanded(policy: PickerPolicy) {
        if (expanded) return
        expanded = true
        pool.addAll(QuestionFactory.expand(policy, PER_KIND))
    }

    fun recordAnswer(q: Question, correct: Boolean) {
        if (!correct) wrongByKp[q.knowledgePoint] = (wrongByKp[q.knowledgePoint] ?: 0) + 1
        else {
            val v = wrongByKp[q.knowledgePoint] ?: 0
            if (v > 0) wrongByKp[q.knowledgePoint] = v - 1
        }
    }

    /** Picks the next question; relaxes filters level by level so the lock can never jam. */
    fun pick(policy: PickerPolicy, excludeId: String? = null): Question {
        ensureExpanded(policy)
        for (level in 0..4) {
            val candidates = filter(policy, level, excludeId)
            if (candidates.isEmpty()) continue
            val q = weightedPick(candidates, policy)
            remember(q.id)
            return q.shuffled(shuffleSeed++)
        }
        if (pool.isEmpty()) throw IllegalStateException("题库为空")
        val q = pool.firstOrNull { it.id != excludeId } ?: pool.first()
        remember(q.id)
        return q.shuffled(shuffleSeed++)
    }

    /**
     * A wrong answer swaps in a similar question: same variant group → same knowledge point →
     * same unit → graded fallback. Repeating a same-group question beats jumping out of syllabus.
     */
    fun pickSimilar(origin: Question, policy: PickerPolicy, extraExcludeId: String? = null): Question {
        ensureExpanded(policy)
        val excluded = mutableSetOf(origin.id)
        if (extraExcludeId != null) excluded.add(extraExcludeId)
        val recentSet = recent.toSet()

        fun inPool(match: (Question) -> Boolean): List<Question> {
            val fresh = ArrayList<Question>(); val seen = ArrayList<Question>()
            for (q in this.pool) {
                if (excluded.contains(q.id) || !match(q)) continue
                if (q.id in recentSet) seen.add(q) else fresh.add(q)
            }
            return fresh + seen
        }

        val sameGroup = inPool { it.variantGroup == origin.variantGroup }
        val sameKp = inPool { it.knowledgePoint == origin.knowledgePoint }
        val sameUnit = inPool { it.subject == origin.subject && it.unit == origin.unit }
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
        return pool.firstOrNull { it.id !in excluded } ?: pool.first()
    }

    /** Level 0 strictest, relaxing to all: unit → textbook → term → grade → whole bank. */
    private fun filter(policy: PickerPolicy, level: Int, excludeId: String?): List<Question> {
        val recentSet = recent.toSet()
        val term = resolveTerm(policy)
        val subjects = policy.subjectWeights.keys
        return pool.filter { q ->
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

    /**
     * Randomises the **knowledge point** first, then draws one question from that point's own
     * questions. Subject weights, the current unit and recent mistakes bias which point is
     * chosen; the number of questions in a point does not, so points are mixed evenly.
     */
    private fun weightedPick(candidates: List<Question>, policy: PickerPolicy): Question {
        val groups = candidates.groupBy { it.knowledgePoint }.toList()
        val weights = DoubleArray(groups.size)
        var total = 0.0
        for ((i, group) in groups.withIndex()) {
            val w = weightOf(group.second.first(), policy)
            weights[i] = w
            total += w
        }
        var r = random.nextDouble() * total
        var chosenIndex = groups.lastIndex
        for (i in groups.indices) {
            r -= weights[i]
            if (r <= 0) { chosenIndex = i; break }
        }
        val chosen = groups[chosenIndex].second
        return chosen[random.nextInt(chosen.size)]
    }

    private fun weightOf(q: Question, policy: PickerPolicy): Double {
        var w = policy.subjectWeights[q.subject] ?: 0.2
        if (policy.currentUnit.isNotEmpty() && q.unit == policy.currentUnit) w *= 2.0
        val wrong = wrongByKp[q.knowledgePoint] ?: 0
        if (wrong > 0) w *= 1.0 + kotlin.math.min(3, wrong) * 0.3
        return w
    }

    private fun remember(id: String) {
        recent.remove(id)
        recent.addLast(id)
        val limit = minOf(noRepeatWindow, (pool.size * 0.6).toInt())
        while (recent.size > limit) recent.removeFirst()
    }

    companion object {
        /** Variants laid out per knowledge point when the local pack expands. */
        const val PER_KIND = 36

        /** docs/08 §4: 8-1 月按上册, 2-7 月按下册. A calendar can only infer the TERM; the
         * current unit stays a parent-set field (school pace, holidays, 2026 textbook revision). */
        fun resolveTerm(policy: PickerPolicy): String {
            if (policy.term != "AUTO") return policy.term
            val m = Calendar.getInstance().get(Calendar.MONTH) + 1
            return if (m >= 8 || m <= 1) "UP" else "DOWN"
        }
    }
}
