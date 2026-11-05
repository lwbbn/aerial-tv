package cn.quizlock.aerial.quizlock

import android.content.Context
import app.orchard.tv.settings.AerialSettings
import org.json.JSONObject

/**
 * The parent's side of QuizLock, reached through Aerial's own phone page (PhoneServer :8790).
 *
 * Aerial already solves the hard parts for us: a random token in the address (shown only on the
 * TV as a QR code), a parental PIN with a try limit, and a page a phone can open without installing
 * anything. Reusing it means the TV needs no second HTTP server, no pairing protocol and no extra
 * permission. Field names follow docs/01 §6 so the phone page and any later parent app agree.
 *
 * The child's side never edits this (rule I4): the QR code lives inside the parent-only Control
 * Center page, and every write is refused without the parental PIN once one is set.
 */
object QuizControl {

    fun state(ctx: Context): JSONObject {
        val p = QuizPolicy.load(ctx)
        val watchedMs = QuizGate.watchedSinceQuizMs()
        return JSONObject()
            .put("enabled", p.enabled)
            .put("quiz_interval_min", p.intervalMinutes)
            .put("unlock_duration_min", p.unlockMinutes)
            .put("max_retry", p.maxRetry)
            .put("grade", p.grade)
            .put("term", p.term)
            .put("textbook_version", p.textbook)
            .put("current_unit", p.currentUnit)
            .put("watched_min", watchedMs / 60_000L)
            .put("next_quiz_min", ((p.intervalMinutes * 60_000L - watchedMs) / 60_000L).coerceAtLeast(0))
            .put("quiz_up", QuizGate.quiz != null)
            .put("question_count", QuizBank.all.size)
            .put("config_version", p.configVersion)
            .put("pin_required", AerialSettings.pinSet)
    }

    /**
     * Applies a policy sent from the phone. Unknown or out-of-range values fall back to the current
     * ones rather than being written; a malformed request can never disable the gate by accident.
     */
    fun configure(ctx: Context, form: Map<String, String>): JSONObject {
        val cur = QuizPolicy.load(ctx)
        fun int(key: String, fallback: Int, min: Int, max: Int) =
            form[key]?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: fallback
        fun text(key: String, fallback: String, allowed: Set<String>) =
            form[key]?.trim()?.takeIf { it in allowed } ?: fallback

        val next = cur.copy(
            enabled = form["enabled"]?.let { it == "1" } ?: cur.enabled,
            intervalMinutes = int("quiz_interval_min", cur.intervalMinutes, 5, 120),
            unlockMinutes = int("unlock_duration_min", cur.unlockMinutes, 1, 120),
            maxRetry = int("max_retry", cur.maxRetry, 1, 10),
            grade = text("grade", cur.grade, setOf("G5")),
            term = text("term", cur.term, setOf("AUTO", "UP", "DOWN")),
            textbook = text("textbook_version", cur.textbook, setOf("PEP", "BSD", "SJ", "CUSTOM")),
            currentUnit = form["current_unit"]?.trim()?.take(40) ?: cur.currentUnit,
            configVersion = cur.configVersion + 1,
        )
        QuizGate.applyPolicy(ctx, next)
        return state(ctx).put("saved", true)
    }
}
