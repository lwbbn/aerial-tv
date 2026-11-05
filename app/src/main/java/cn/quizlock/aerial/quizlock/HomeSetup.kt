@file:android.annotation.SuppressLint("NewApi")
package cn.quizlock.aerial.quizlock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/**
 * "Make QuizLock the home screen", done inside the app with NO computer and NO ADB.
 *
 * Upstream's guard flavor declares no CATEGORY_HOME, so the TV's default-home list never showed
 * it and pressing Home left at once. The guard/AndroidManifest.xml in this build adds the filter;
 * this file asks Android's RoleManager for ROLE_HOME, which pops the system's own confirmation
 * dialog ("Use QuizLock as your home app?"). The parent presses OK once and it survives reboots.
 * Where RoleManager is missing or the firmware hides its dialog, we fall back to the standard
 * default-app pages and finally to a HOME chooser. We never write Settings.Secure ourselves:
 * a third-party app cannot, and the parent must stay in control of this system choice.
 *
 * Accessibility (Home Guard) is a second, optional layer. VIDAA exposes no standard
 * ACCESSIBILITY_SETTINGS activity, so that row opens the TV's main settings page. Being the
 * default home is already enough to stop Home from leaving QuizLock.
 *
 * Everything here lives in QuizLock's own package: upstream keeps only two small QL-BEGIN hooks.
 */
object HomeSetup {
    private const val ROLE_HOME = "android.app.role.HOME"

    /**
     * RoleManager is API 29, while this build still supports API 21. Reaching it through the
     * string-based service lookup and a public-method call keeps lint's fatal NewApi check quiet
     * without a single hidden-API call, and does one obvious thing on older TVs.
     */
    private fun roleService(context: Context): Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { context.getSystemService("role") }.getOrNull()
        else null

    private fun roleInvoke(service: Any, method: String): Any? = runCatching {
        service.javaClass.getMethod(method, String::class.java).invoke(service, ROLE_HOME)
    }.getOrNull()

    /** The RoleManager answer first: VIDAA's resolveActivity(HOME) can still name the stock home. */
    fun isHomeRoleHeld(context: Context): Boolean {
        val role = roleService(context) ?: return isResolvedHome(context)
        val held = roleInvoke(role, "isRoleHeld") as? Boolean == true
        return held || isResolvedHome(context)
    }

    /** Fallback for pre-10 TVs and for firmwares that answer HOME without using roles. */
    fun isResolvedHome(context: Context): Boolean =
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName

    /** Whether our accessibility guard is enabled in the system list or currently bound. */
    fun isGuardOn(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        }.getOrDefault("")
        return "HomeGuardService" in enabled ||
            runCatching { app.orchard.tv.guard.HomeGuardService.isRunning }.getOrDefault(false)
    }

    /**
     * The one-tap path. The RoleManager request MUST go through Activity.startActivityForResult:
     * RequestRoleActivity reads getCallingPackage(), and a plain startActivity leaves it null, on
     * which the system closes the dialog at once (seen on MuMu / Android 12: "Package name cannot
     * be null or empty"). With an Activity we ask the system's own confirmation dialog; without
     * one (or when the role is already ours) we fall back to the default-home settings page.
     */
    @Suppress("DEPRECATION")
    fun requestHomeRole(context: Context, activity: Activity? = null): Boolean {
        val role = roleService(context)
        if (role != null && activity != null && !isHomeRoleHeld(context)) {
            val request = roleInvoke(role, "createRequestRoleIntent") as? Intent
            if (request != null) {
                val started = runCatching {
                    activity.startActivityForResult(request, 7101)
                    true
                }.getOrDefault(false)
                if (started) return true
            }
        }
        return openHomeSettings(context)
    }

    /** First resolvable default-home page, for Control Center's PIN-wrapped onOpen callback. */
    fun homeSettingsIntent(context: Context): Intent = firstResolvable(context, homeCandidates())

    /** First resolvable accessibility / main settings page, ditto. */
    fun guardSettingsIntent(context: Context): Intent = firstResolvable(context, guardCandidates())

    fun openHomeSettings(context: Context): Boolean = launch(context, homeCandidates())

    fun openGuardSettings(context: Context): Boolean = launch(context, guardCandidates())

    private fun homeCandidates(): List<Intent> = listOf(
        // On Android 10+ this is the same RoleManager UI the one-tap path uses, in page form.
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        Intent.createChooser(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "选择主屏幕"),
        Intent(Settings.ACTION_SETTINGS),
        // VIDAA: its own settings app, in case ACTION_SETTINGS is filtered out.
        Intent(Settings.ACTION_SETTINGS).setPackage("com.android.tv.settings"),
    )

    private fun guardCandidates(): List<Intent> = listOf(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).setPackage("com.android.tv.settings"),
        Intent(Settings.ACTION_SETTINGS),
        Intent(Settings.ACTION_SETTINGS).setPackage("com.android.tv.settings"),
    )

    private fun firstResolvable(context: Context, candidates: List<Intent>): Intent {
        for (intent in candidates) {
            val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { i.resolveActivity(context.packageManager) != null }.getOrDefault(false)) return i
        }
        return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun launch(context: Context, candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { i.resolveActivity(context.packageManager) == null }.getOrDefault(true)) continue
            if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return true
        }
        return false
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The three rows shown in Control Center's setup page. Status is re-read on every ON_RESUME, so
 * coming back from the system dialog immediately shows "already set" without reopening the panel.
 * [onOpen] is the upstream callback (parental-PIN wrapped); when null we start the intents here,
 * which is what the QuizLock parent panel does.
 */
@Composable
fun HomeSetupRows(onOpen: ((Intent) -> Unit)? = null) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(activity) {
        val owner = activity as? LifecycleOwner
        if (owner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) tick++
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    val defaultHome = remember(tick) { HomeSetup.isHomeRoleHeld(context) }
    val guardOn = remember(tick) { HomeSetup.isGuardOn(context) }
    val open: (Intent) -> Unit = onOpen ?: { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }

    Column {
        MenuRow(
            Glyph.Shield,
            "① 设为默认桌面",
            trailing = if (defaultHome) "已设置 ✓" else "去设置 ›",
            onClick = { HomeSetup.requestHomeRole(context, activity) },
        )
        MenuRow(
            Glyph.Check,
            "② 开启桌面守护",
            trailing = if (guardOn) "已开启 ✓" else "去开启 ›",
            onClick = { open(HomeSetup.guardSettingsIntent(context)) },
        )
        MenuRow(
            Glyph.Moon,
            "系统默认桌面设置",
            trailing = "打开 ›",
            onClick = { open(HomeSetup.homeSettingsIntent(context)) },
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                if (defaultHome) "已生效：开机和按 Home 都会回到小课间。" else "点第 ① 项，在系统弹窗里确认即可，不用电脑和 ADB；按 Home 就不会再退出。",
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                "第 ② 项可选；第 ③ 项能手动选桌面或换回原桌面。VIDAA 没有直达无障碍页面，会打开系统设置。",
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
        }
    }
}

/** The whole setup page for the derived build. Replaces upstream's ADB/QR instructions. */
@Composable
fun QuizSetupPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(
            Glyph.ChevronLeft, "桌面与守护 · 让小课间不被退出",
            focusRequester = first,
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) },
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                "现在按 Home 会退回电视原桌面，答题守护跟着失效。下面第 ① 项点一次，在系统弹窗里确认即可。",
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
        }
        Spacer(Modifier.height(10.dp))
        HomeSetupRows()
        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                "改出题间隔、教材与当前单元：回控制面板 →「小课间 · 家长设置」→ 手机扫码。",
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
