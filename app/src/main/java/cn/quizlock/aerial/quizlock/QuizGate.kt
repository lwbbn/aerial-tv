package cn.quizlock.aerial.quizlock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.orchard.tv.MainActivity
import app.orchard.tv.dream.AerialDream
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.guard.KidsWatch
import app.orchard.tv.settings.Restricted
import app.orchard.tv.system.DiagLog

/**
 * QuizLock's watcher, a small mirror of Aerial's own KidsWatch: it keeps its own loop that counts
 * watching time and, at the interval set on the phone, raises a quiz over the home screen. The
 * child answers on the remote; a right answer grants [QuizPolicy.unlockMinutes] more of watching
 * (and, when Aerial's own kids mode is on too, extends its daily allowance through
 * [Restricted.allow] so the two stay in step).
 *
 * Watching means: screen interactive, QuizLock enabled, Aerial's home not in front (an app is),
 * and no screensaver. The home-in-front flag is Aerial's [KidsWatch.homeInFront], maintained by
 * MainActivity's onResume/onPause — one source of truth for both watchers.
 *
 * Only three hooks in upstream code (each a few lines, marked QL-BEGIN..QL-END):
 * MainActivity.launch() intercepts a launch while a quiz is up; HomeScreen renders QuizOverlay;
 * MainActivity.onStart starts this loop.
 */
object QuizGate {
    private const val TAG = "QL"
    private const val TICK_MS = 1_000L

    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val loop = Runnable {
        tick()
        scheduleNext()
    }
    private var picker = QuestionPicker(bank = QuizBank.all)
    private var policy = QuizPolicy()
    private var running = false
    private var heartbeat = 0

    // Watching-time accounting. Persisted per day so a TV reboot cannot reset the clock.
    private var watchMs = 0L
    private var lastTickAt = 0L
    private var day = ""

    /** A quiz is up: the only state the UI needs. Null while simply watching. */
    var quiz by mutableStateOf<Quiz?>(null)
        private set

    /** True while the answer is being checked / a short result hint shows; UI keeps the lock on. */
    var busy by mutableStateOf(false)
        private set

    val enabled: Boolean get() = policy.enabled

    /** A message for the home screen to show after a correct answer (fades by itself). */
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * Which of the four options the remote is on (row-major 0..3). We own this instead of relying
     * on Compose focus: Aerial's HomeScreen has its own key handler on the tile grid, and a focus
     * fight there left the quiz with dead arrow keys. MainActivity's QL block consumes every key
     * while a quiz is up and feeds [moveFocus] / [answerFocused].
     */
    var focusIndex by mutableIntStateOf(0)
        private set

    /** Explanation of the last wrong answer, and the brief "差一点" flash. */
    var hint by mutableStateOf<String?>(null)
        private set
    var flash by mutableStateOf<String?>(null)
        private set

    /**
     * Wall-clock deadline of a mandatory rest after too many wrong answers. Persisted, so a
     * reboot, a force-stop or a policy reload cannot shorten it (docs/01 F-06 / F-07: the rest
     * is a hard eye-care constraint, never skippable by answering). App restarts re-read it.
     */
    private var restUntilMs = 0L
    var resting by mutableStateOf(false)
        private set

    /** True while a quiz OR a rest holds the TV: no app launch, no accumulated watch time. */
    val blocking: Boolean get() = quiz != null || resting

    fun restRemainingMs(): Long = (restUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Moves the option focus by a grid step; [dRow]/[dCol] keep the 2×2 layout, not linear ±1. */
    fun moveFocus(dRow: Int, dCol: Int) {
        val q = quiz ?: return
        val cols = 2
        val rows = (q.question.options.size + cols - 1) / cols
        val row = (focusIndex / cols + dRow).coerceIn(0, rows - 1)
        val col = (focusIndex % cols + dCol).coerceIn(0, cols - 1)
        focusIndex = (row * cols + col).coerceIn(0, q.question.options.size - 1)
    }

    /** Submits the focused option; returns true when the quiz was lifted. */
    fun answerFocused(): Boolean {
        val q = quiz ?: return true
        val id = q.question.options.getOrNull(focusIndex)?.id ?: return false
        return answer(id)
    }

    class Quiz(val question: Question) {
        var attempts = 0

        /** Number of wrong answers allowed in this round before the mandatory rest. */
        val maxAttempts: Int get() = policy.maxRetry
        var lastExplanation: String? = null


        fun answer(optionId: String): Boolean {
            val right = question.isCorrect(optionId)
            picker.recordAnswer(question, right)
            attempts++
            if (!right) lastExplanation = question.explanation
            return right
        }
    }

    /** Starts (or restarts) the watcher. Safe to call on every Activity start. */
    fun start(context: Context) {
        if (app == null) {
            app = context.applicationContext
            policy = QuizPolicy.load(app!!)
            loadState()
            // Expand the local pack right away so the parent's status page shows the real bank
            // size even before the first quiz has ever been raised.
            picker.ensureExpanded(QuizPolicy.toPickerPolicy(policy))
        }
        if (running) return
        running = true
        lastTickAt = 0L
        handler.removeCallbacks(loop)
        handler.postDelayed(loop, TICK_MS)
    }

    fun poke() {
        if (app != null && running) {
            handler.removeCallbacks(loop)
            handler.postDelayed(loop, TICK_MS)
        }
    }

    private fun scheduleNext() {
        if (!running) return
        handler.removeCallbacks(loop)
        handler.postDelayed(loop, TICK_MS)
    }

    private fun tick() {
        val ctx = app ?: return
        val at = SystemClock.elapsedRealtime()
        if (lastTickAt == 0L) lastTickAt = at
        val since = (at - lastTickAt).coerceAtLeast(0L)
        lastTickAt = at
        // A mandatory rest holds the loop: no watching time accumulates, no quiz is raised,
        // and the rest layer over Home keeps the TV unavailable until its deadline passes.
        if (resting) {
            if (System.currentTimeMillis() >= restUntilMs) endRest()
            return
        }
        // A quiz (or a result hint) holds the loop: no watching time accumulates while it is up.
        if (quiz != null || busy || !policy.enabled) return

        // getSystemService(Class) is API 23; Aerial still supports Android 5.0 (minSdk 21).
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive != false
        val watching = interactive && !KidsWatch.homeInFront && !AerialDream.showing
        if (watching) {
            rollDay()
            watchMs += since.coerceAtMost(2 * TICK_MS)  // a long gap is standby, not watching
            saveState()
            if (watchMs >= nextQuizAtMs()) raiseQuiz("interval")
        }
        heartbeat++
        if (heartbeat % 10 == 0) {
            DiagLog.i("$TAG: tick watching=$watching home=${KidsWatch.homeInFront} " +
                "watch=$watchMs/${nextQuizAtMs()}ms interval=${policy.intervalMinutes}min")
        }
    }

    /** Watching time that has passed since the last quiz, in ms. */
    fun watchedSinceQuizMs(): Long = watchMs

    /** Local pack size after expansion, for the parent's status page. */
    fun poolSize(): Int = picker.size
    fun generatedCount(): Int = picker.generatedCount
    fun knowledgePointCount(): Int = picker.knowledgePointCount

    fun nextQuizAtMs(): Long = policy.intervalMinutes * 60_000L

    private fun raiseQuiz(why: String) {
        val q = picker.pick(QuizPolicy.toPickerPolicy(policy))
        quiz = Quiz(q)
        focusIndex = 0
        hint = null
        flash = null
        busy = false
        DiagLog.i("$TAG: quiz up ($why), ${q.knowledgePoint} id=${q.id} " +
            "correct=${q.correctOptionId} fresh=${q.fresh} pool=${picker.size}")
        bringHome()
    }

    /** Brings Aerial's home back over the app the child was watching. */
    private fun bringHome() {
        val ctx = app ?: return
        if (!HomeGuardService.bringAerialBack()) {
            runCatching { ctx.startActivity(MainActivity.bringBackIntent(ctx)) }
        }
    }

    /**
     * The child answered. True when the answer was right: the gate grants [QuizPolicy.unlockMinutes]
     * and lifts the quiz. A wrong answer swaps in a similar question (same knowledge point) until
     * [Quiz.maxAttempts] is reached, after which the quiz is passed anyway — a wrong answer never
     * punishes (QuizLock's own rule) and the child is never stuck.
     */
    fun answer(optionId: String): Boolean {
        if (resting) return true
        val q = quiz ?: return false
        busy = true
        val right = q.answer(optionId)
        if (right) {
            notice = "答对啦，可再看 ${policy.unlockMinutes} 分钟"
            hint = null
            flash = null
            grantUnlock()
            quiz = null
            watchMs = 0L
            saveState()
            scheduleNext()
            return true
        }
        if (q.attempts >= q.maxAttempts) {
            // Wrong-answer limit reached: an eye-care rest, never a punishment (docs/01 F-06).
            // No unlock is granted — watching stays frozen until the countdown ends.
            startRest()
            return true
        }
        val next = Quiz(picker.pickSimilar(q.question, QuizPolicy.toPickerPolicy(policy)))
        // The round's wrong-answer count must survive the swap, otherwise the limit above can
        // never be reached and the child could keep guessing forever.
        next.attempts = q.attempts
        next.lastExplanation = q.question.explanation
        quiz = next
        focusIndex = 0
        hint = q.question.explanation
        flash = "差一点，再想想～"
        DiagLog.i("$TAG: swap ${next.question.knowledgePoint} id=${next.question.id} " +
            "correct=${next.question.correctOptionId} fresh=${next.question.fresh} attempts=${next.attempts}")
        busy = false
        scheduleNext()
        return false
    }

    /** Starts the non-skippable rest and brings Home (with the rest layer) back to the front. */
    private fun startRest() {
        restUntilMs = System.currentTimeMillis() + policy.restMinutes * 60_000L
        resting = true
        quiz = null
        busy = false
        hint = null
        flash = null
        notice = null
        watchMs = 0L
        saveState()
        bringHome()
        DiagLog.i("$TAG: wrong-answer limit reached, resting ${policy.restMinutes}min")
    }

    private fun endRest() {
        resting = false
        restUntilMs = 0L
        watchMs = 0L
        lastTickAt = 0L
        notice = "休息好啦，可以继续看了"
        saveState()
        scheduleNext()
        DiagLog.i("$TAG: rest over, watching clock restarted")
    }

    private fun grantUnlock() {
        watchMs = 0L
        // Keep Aerial's own kids mode, if on, in step with ours.
        if (Restricted.active) runCatching { Restricted.allow(policy.unlockMinutes) }
    }

    fun dismissNotice() {
        notice = null
    }

    fun refresh() {
        val ctx = app ?: return
        policy = QuizPolicy.load(ctx)
        picker = QuestionPicker(bank = QuizBank.all)
        picker.ensureExpanded(QuizPolicy.toPickerPolicy(policy))
        scheduleNext()
    }

    /**
     * A parent-approved policy from the phone page. Kept here (not in QuizControl) so the timing
     * loop, the in-memory policy and the persisted one can never disagree. Safe from the phone
     * server's IO thread: state changes are posted back to the main looper.
     */
    fun applyPolicy(ctx: Context, next: QuizPolicy) {
        QuizPolicy.save(ctx, next)
        handler.post {
            if (app == null) { app = ctx.applicationContext; loadState() }
            policy = next
            if (!next.enabled) {
                quiz = null; notice = null; hint = null; flash = null
                resting = false; restUntilMs = 0L  // a parent switching the guard off lifts the rest
            }
            picker = QuestionPicker(bank = QuizBank.all)
            picker.ensureExpanded(QuizPolicy.toPickerPolicy(next))
            watchMs = 0L
            lastTickAt = 0L
            saveState()
            scheduleNext()
            DiagLog.i("$TAG: policy v${next.configVersion} on=${next.enabled} " +
                "interval=${next.intervalMinutes}min unlock=${next.unlockMinutes}min")
        }
    }

    fun setEnabled(on: Boolean) {
        policy = policy.copy(enabled = on)
        QuizPolicy.save(app!!, policy)
        if (!on) {
            quiz = null; notice = null
            resting = false; restUntilMs = 0L
        }
        saveState()
        scheduleNext()
    }

    /** For the parent panel: a preview quiz raised by hand. Never cuts a rest short. */
    fun preview() {
        if (resting) return
        val picked = picker.pick(QuizPolicy.toPickerPolicy(policy))
        quiz = Quiz(picked)
        focusIndex = 0
        hint = null
        flash = null
        busy = false
        DiagLog.i("$TAG: preview ${picked.knowledgePoint} id=${picked.id} " +
            "correct=${picked.correctOptionId} fresh=${picked.fresh} pool=${picker.size}")
        scheduleNext()
    }

    // --- day-rolled persistence, in Aerial's own prefs ---

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quizlock", Context.MODE_PRIVATE)

    private fun rollDay() {
        val today = todayStr()
        if (today == day) return
        day = today
        watchMs = 0L
    }

    private fun todayStr(): String {
        val c = java.util.Calendar.getInstance()
        return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    private fun loadState() {
        val ctx = app ?: return
        val p = prefs(ctx)
        day = p.getString("day", "") ?: ""
        watchMs = p.getLong("watch_ms", 0L)
        restUntilMs = p.getLong("rest_until_ms", 0L)
        resting = restUntilMs > System.currentTimeMillis()
        if (day != todayStr()) { day = todayStr(); watchMs = 0L }
    }

    private fun saveState() {
        val ctx = app ?: return
        prefs(ctx).edit()
            .putString("day", day)
            .putLong("watch_ms", watchMs)
            .putLong("rest_until_ms", if (resting) restUntilMs else 0L)
            .apply()
    }
}
