@file:Suppress("NewApi")
package cn.quizlock.aerial.quizlock

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * QuizLock's HOME takeover, driven by the accessibility service this app already declares.
 *
 * Why this exists: on Hisense VIDAA the Home key does not dispatch an ACTION_MAIN/CATEGORY_HOME
 * intent at all. The firmware starts its own launcher *by component* (com.jamdeo.tv.vod's
 * LauncherActivity, or com.keylab.speech.view.vidaa's SecondScreen), and the vendor property
 * persist.vt.use_home_pkg decides which one. Neither ROLE_HOME nor the system's "home app" page
 * changes that, so being the default home is not enough: something has to bring QuizLock back
 * when the stock desktop appears. That is exactly how the Halo derivative on the same TV worked.
 *
 * Upstream's HomeGuardService only recognises launchers it found through queryIntentActivities,
 * which misses these component-started ones. We add one small QL-BEGIN hook at the top of its
 * onAccessibilityEvent and keep all of the policy here, in QuizLock's own package.
 *
 * This service never reads window content, never filters keys and never touches system settings.
 * The parent can switch the whole behaviour off from the setup page at any time.
 */
object QuizHomeTakeover {
    private const val TAG = "QLHome"
    private const val PREFS = "quizlock_home"
    private const val KEY_ENABLED = "takeover_enabled"
    private const val COOLDOWN_MS = 1_200L

    /** Launchers that TV firmwares start by component and that expose no HOME intent filter. */
    private val EXTRA_HOME_PACKAGES = setOf(
        "com.jamdeo.tv.vod",
        "com.keylab.speech.view.vidaa",
        "com.hisense.tv",
    )

    @Volatile private var cachedHomes: Set<String>? = null
    @Volatile private var lastBringBack = 0L
    @Volatile private var allowUntil = 0L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** The upstream guard service this build already declares, addressed by its full component. */
    private fun guardComponent(context: Context) =
        "${context.packageName}/app.orchard.tv.guard.HomeGuardService"

    /** True when the system actually lists our service as enabled. */
    fun isGuardOn(context: Context): Boolean = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().contains(guardComponent(context))
    }.getOrDefault(false)

    /**
     * One-tap enable, with no system page and no ADB at the moment of use.
     *
     * Some TV firmwares (Hisense VIDAA among them) disable the AOSP accessibility settings
     * activity entirely, so the parent cannot reach that switch by hand. WRITE_SECURE_SETTINGS
     * is a development permission: it can be granted once when the app is installed, after which
     * the app may add its own service to the enabled list. Every existing entry is preserved, and
     * nothing else in the system is touched. Without the grant this returns false and the setup
     * page falls back to opening the TV's own settings app.
     */
    fun tryEnableGuard(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val resolver = context.contentResolver
        val me = guardComponent(context)
        val current = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        }.getOrDefault("")
        if (current.split(':').any { it.equals(me, ignoreCase = true) }) return true
        val next = (current.split(':').filter { it.isNotBlank() } + me).joinToString(":")
        return runCatching {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /**
     * The other half of [tryEnableGuard]: remove ONLY our own component from the system's enabled
     * list, leaving every other service untouched. This is exactly what the system accessibility
     * page does when the parent switches our service off -- but it works on firmwares that disable
     * that page (ordinary VIDAA mode disables the whole com.android.settings package, so no third
     * party can reach its switch). Callers must also pause the takeover via [setEnabled], otherwise
     * [ensureOnLaunch] would add the component straight back on the next resume.
     */
    fun tryDisableGuard(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val resolver = context.contentResolver
        val me = guardComponent(context)
        val current = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        }.getOrDefault("")
        val kept = current.split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
        if (kept.size == current.split(':').count { it.isNotBlank() }) return true
        return runCatching {
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                kept.joinToString(":"),
            )
            true
        }.getOrDefault(false)
    }

    /** Called before deliberately opening a system page, so the parent is not pulled back. */
    fun allowLeave(ms: Long = 30_000L) {
        allowUntil = SystemClock.elapsedRealtime() + ms
    }

    /**
     * Whether the key filter must stay on, called from upstream's updateKeyFilter. Takeover on
     * means the filter stays on even where upstream would leave it off (Android 12+ with another
     * app in front); upstream's own keysPassThrough / pad / mouse guards are applied by the
     * caller, because on VIDAA the Home key never travels Android's input path at all -- the
     * firmware daemon answers it (a "reason=homekey" broadcast plus a component start), so a
     * filter would only detour every keypress for nothing.
     */
    fun filterDesired(context: Context): Boolean = isEnabled(context)

    /**
     * Replaces upstream's onKeyEvent while our filter is on.
     * true  = consume (system never sees it); false = pass through unchanged;
     * null  = no opinion, upstream's original key handling runs (takeover paused).
     * Passing non-HOME keys through is what keeps VIDAA's firmware volume working: upstream's
     * takeVolumeKey() must NOT run with the flag enabled on this TV.
     */
    fun onKeyEvent(service: AccessibilityService, event: KeyEvent): Boolean? {
        val enabled = isEnabled(service)
        if (!enabled) {
            Log.i(TAG, "key ${event.keyCode} while takeover paused -> pass to upstream")
            return null
        }
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        val waiting = SystemClock.elapsedRealtime() < allowUntil
        if (waiting) {
            Log.i(TAG, "HOME during allowLeave window -> pass to system")
            return false
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            bringToFront(service)
            Log.i(TAG, "HOME consumed before the system saw it")
        }
        return true
    }

    private fun bringToFront(service: AccessibilityService) {
        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName) ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        // Accessibility services are exempt from Android 10+'s background activity start limit.
        runCatching { service.startActivity(intent) }
    }

    private fun homePackages(context: Context): Set<String> = cachedHomes ?: run {
        val homes = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_ALL,
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        homes += EXTRA_HOME_PACKAGES
        homes -= context.packageName
        val frozen = homes.toSet()
        cachedHomes = frozen
        frozen
    }

    /** Only a launcher *activity* counts: the same package also hosts video players and dialogs. */
    private fun isHomeWindow(pkg: String, cls: String): Boolean {
        if (pkg !in homePackagesIgnoringSelf) return false
        val c = cls.lowercase()
        return "home" in c || "launcher" in c || "secondscreen" in c
    }

    // Filled on the first event, where a Context is available.
    @Volatile private var homePackagesIgnoringSelf: Set<String> = emptySet()

    /**
     * Called from MainActivity.onResume. When the install-time grant is present the app enables
     * its own service on first launch, so the parent never has to find a hidden system page.
     * A parent who pauses the takeover is never switched back on automatically.
     */
    fun ensureOnLaunch(context: Context) {
        if (!isEnabled(context) || isGuardOn(context)) return
        tryEnableGuard(context)
    }

    /**
     * @return true when this window was the stock desktop and QuizLock handled it, so upstream's
     *         generic launcher logic should not also run for this event.
     */
    fun onWindow(service: AccessibilityService, pkg: String, cls: String): Boolean {
        if (!isEnabled(service)) return false
        if (pkg == service.packageName) return false
        homePackagesIgnoringSelf = homePackages(service)
        if (!isHomeWindow(pkg, cls)) return false
        if (SystemClock.elapsedRealtime() < allowUntil) return true

        val now = SystemClock.elapsedRealtime()
        if (now - lastBringBack < COOLDOWN_MS) return true
        lastBringBack = now

        // Fallback for pathways that bypass the key filter (voice assistant, CEC, ADB injection,
        // recent tasks): the stock desktop may still appear, so keep the window-event pull-back.
        bringToFront(service)
        return true
    }
}
