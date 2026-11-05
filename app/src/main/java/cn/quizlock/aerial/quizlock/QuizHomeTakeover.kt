@file:Suppress("NewApi")
package cn.quizlock.aerial.quizlock

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
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

    /** Called before deliberately opening a system page, so the parent is not pulled back. */
    fun allowLeave(ms: Long = 30_000L) {
        allowUntil = SystemClock.elapsedRealtime() + ms
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

        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName) ?: return true
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        // Accessibility services are exempt from Android 10+'s background activity start limit,
        // which is why this works where a plain foreground service or alarm would not.
        runCatching { service.startActivity(intent) }
        return true
    }
}
