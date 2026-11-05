package cn.quizlock.aerial.quizlock

import android.content.Context

/**
 * QuizLock's parent-set policy, kept in Aerial's own prefs. The phone page (and later the
 * parent app) writes these; the child-side never edits them (QuizLock rule I4).
 */
data class QuizPolicy(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 20,
    val unlockMinutes: Int = 15,
    val maxRetry: Int = 3,
    val grade: String = "G5",
    val term: String = "UP",
    val textbook: String = "PEP",
    /** Weights by subject label; subjects are data, not an enum (ADR-017). */
    val subjectWeights: Map<String, Double> = mapOf("数学" to 0.4, "语文" to 0.3, "英语" to 0.3),
    val currentUnit: String = "",
    val configVersion: Int = 1,
) {
    companion object {
        fun load(ctx: Context): QuizPolicy {
            val p = ctx.getSharedPreferences("quizlock", Context.MODE_PRIVATE)
            return QuizPolicy(
                enabled = p.getBoolean("enabled", true),
                intervalMinutes = p.getInt("interval_minutes", 20),
                unlockMinutes = p.getInt("unlock_minutes", 15),
                maxRetry = p.getInt("max_retry", 3),
                grade = p.getString("grade", "G5") ?: "G5",
                term = p.getString("term", "UP") ?: "UP",
                textbook = p.getString("textbook", "PEP") ?: "PEP",
                subjectWeights = mapOf("数学" to 0.4, "语文" to 0.3, "英语" to 0.3),
                currentUnit = p.getString("current_unit", "") ?: "",
            )
        }

        fun save(ctx: Context, policy: QuizPolicy) {
            ctx.getSharedPreferences("quizlock", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", policy.enabled)
                .putInt("interval_minutes", policy.intervalMinutes)
                .putInt("unlock_minutes", policy.unlockMinutes)
                .putInt("max_retry", policy.maxRetry)
                .putString("grade", policy.grade)
                .putString("term", policy.term)
                .putString("textbook", policy.textbook)
                .putString("current_unit", policy.currentUnit)
                .apply()
        }

        /** Adapter to the picker's own policy shape. */
        fun toPickerPolicy(p: QuizPolicy) = PickerPolicy(
            grade = p.grade, term = p.term, textbook = p.textbook,
            subjectWeights = p.subjectWeights, currentUnit = p.currentUnit,
        )
    }
}

/** What the picker needs from the parent's settings. */
data class PickerPolicy(
    val grade: String,
    val term: String,
    val textbook: String,
    val subjectWeights: Map<String, Double>,
    val currentUnit: String,
)
