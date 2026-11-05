package cn.quizlock.aerial.quizlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.orchard.tv.system.DiagLog

/**
 * QuizLock's local command channel. Today it's the ADB hook used to raise a quiz on demand and
 * set the interval while testing (and it is handy for a parent debugging their own TV); later the
 * phone page / parent app can send the same explicit broadcasts. Protected by a signature-ish
 * scheme: the receiver is not exported, so only the shell (adb) or the app itself can reach it.
 *
 *   adb shell am broadcast -a cn.quizlock.aerial.QUIZ_FORCE
 *   adb shell am broadcast -a cn.quizlock.aerial.QUIZ_SET --ei interval 1
 *   adb shell am broadcast -a cn.quizlock.aerial.QUIZ_STATUS
 */
class QuizControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        QuizGate.start(context)
        when (intent.action) {
            ACTION_FORCE -> {
                QuizGate.preview()
                DiagLog.i("QL: quiz forced by local command")
            }
            ACTION_SET -> {
                val ctx = context.applicationContext
                val old = QuizPolicy.load(ctx)
                val p = old.copy(
                    enabled = intent.getBooleanExtra("enabled", old.enabled),
                    intervalMinutes = intent.getIntExtra("interval", old.intervalMinutes),
                    unlockMinutes = intent.getIntExtra("unlock", old.unlockMinutes),
                    maxRetry = intent.getIntExtra("retry", old.maxRetry),
                )
                QuizPolicy.save(ctx, p)
                QuizGate.refresh()
                DiagLog.i("QL: policy set interval=${p.intervalMinutes} unlock=${p.unlockMinutes} enabled=${p.enabled}")
            }
            ACTION_STATUS -> DiagLog.i(
                "QL: watching=${QuizGate.watchedSinceQuizMs()}ms " +
                    "next=${QuizGate.nextQuizAtMs()}ms quiz=${QuizGate.quiz != null}"
            )
        }
    }

    companion object {
        const val ACTION_FORCE = "cn.quizlock.aerial.QUIZ_FORCE"
        const val ACTION_SET = "cn.quizlock.aerial.QUIZ_SET"
        const val ACTION_STATUS = "cn.quizlock.aerial.QUIZ_STATUS"
    }
}
