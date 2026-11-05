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
 * On firmwares like Hisense VIDAA the Home key ignores ROLE_HOME entirely: the vendor starts its
 * own launcher by component (persist.vt.use_home_pkg), so the home-app page alone cannot take
 * Home over. The bound accessibility service is the real takeover path there, see
 * QuizHomeTakeover. VIDAA disables the AOSP accessibility settings activity, hence that row falls
 * back to the TV's own settings home page with an on-screen path for the parent.
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

    /** Only says the system's role list picked us. On VIDAA this is NOT where the Home key goes. */
    fun isRoleHeld(context: Context): Boolean {
        val role = roleService(context) ?: return false
        return roleInvoke(role, "isRoleHeld") as? Boolean == true
    }

    /**
     * The real answer: what PackageManager resolves HOME to. VIDAA keeps a vendor-preferred
     * stock launcher here (`persist.vt.use_home_pkg` / SecondScreen) even after ROLE_HOME was
     * granted to us, so the role alone made the panel claim "already set" while Home still went
     * to the TV's own desktop. Every visible "is it set" answer must go through this, not [isRoleHeld].
     */
    fun isResolvedHome(context: Context): Boolean =
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName

    /** Kept for the call sites that ask "are we the home app": only the real resolution counts. */
    fun isHomeRoleHeld(context: Context): Boolean = isResolvedHome(context)

    /** Whether OUR accessibility service is enabled in the system list or currently bound. */
    fun isGuardOn(context: Context): Boolean =
        QuizHomeTakeover.isGuardOn(context) ||
            runCatching { app.orchard.tv.guard.HomeGuardService.isRunning }.getOrDefault(false)

    /**
     * The one-tap path. The RoleManager request MUST go through Activity.startActivityForResult:
     * RequestRoleActivity reads getCallingPackage(), and a plain startActivity leaves it null, on
     * which the system closes the dialog at once (seen on MuMu / Android 12: "Package name cannot
     * be null or empty"). Once the role is already recorded (VIDAA), asking again changes nothing,
     * so we open the system's own home-app list instead: the parent can see and pick 小课间 there,
     * which is exactly the page Halo TV opens.
     */
    @Suppress("DEPRECATION")
    fun requestHomeRole(context: Context, activity: Activity? = null): Boolean {
        QuizHomeTakeover.allowLeave()
        val role = roleService(context)
        if (role != null && activity != null && !isRoleHeld(context)) {
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
    fun homeSettingsIntent(context: Context): Intent {
        QuizHomeTakeover.allowLeave()
        return firstResolvable(context, homeCandidates())
    }

    /** First resolvable accessibility / main settings page, ditto. */
    fun guardSettingsIntent(context: Context): Intent {
        QuizHomeTakeover.allowLeave()
        return firstResolvable(context, guardCandidates())
    }

    fun openHomeSettings(context: Context): Boolean {
        QuizHomeTakeover.allowLeave()
        return launch(context, homeCandidates())
    }

    fun openGuardSettings(context: Context): Boolean {
        QuizHomeTakeover.allowLeave()
        return launch(context, guardCandidates())
    }

    private fun homeCandidates(): List<Intent> = listOf(
        // On Android 10+ this is the same RoleManager UI the one-tap path uses, in page form.
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        // A plain HOME intent (not createChooser) lets Android offer "Always", which older TVs
        // still use to store the preferred home instead of the RoleManager role.
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
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
 * The setup rows: home-app picker, accessibility page, and a pause switch for the takeover.
 * The status line comes from what PackageManager actually resolves HOME to, so a TV that recorded
 * ROLE_HOME but still shows its own desktop is never reported as "already set".
 * [onOpen] is the upstream callback (parental-PIN wrapped); when null we start the intents here,
 * which is what the QuizLock parent panel does.
 */
@Composable
fun HomeSetupRows(onOpen: ((Intent) -> Unit)? = null) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var tick by remember { mutableIntStateOf(0) }
    var toggleTick by remember { mutableIntStateOf(0) }

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

    val isHome = remember(tick) { HomeSetup.isResolvedHome(context) }
    val guardOn = remember(tick, toggleTick) { HomeSetup.isGuardOn(context) }
    val takeover = remember(tick, toggleTick) { QuizHomeTakeover.isEnabled(context) }
    val open: (Intent) -> Unit = onOpen ?: { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }

    Column {
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                "当前主屏幕应用：${if (isHome) "小课间" else "电视桌面"}",
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                when {
                    !guardOn -> "主屏幕接管：需开启下面的「辅助功能」"
                    !takeover -> "主屏幕接管：已暂停"
                    else -> "主屏幕接管：已生效"
                },
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
        }
        Spacer(Modifier.height(8.dp))
        MenuRow(
            Glyph.Shield,
            "主屏幕应用",
            trailing = "选择 ›",
            onClick = { HomeSetup.requestHomeRole(context, activity) },
        )
        MenuRow(
            Glyph.Check,
            "辅助功能",
            trailing = if (guardOn) "已开启 ✓" else "去开启 ›",
            onClick = {
                if (!QuizHomeTakeover.tryEnableGuard(context)) {
                    open(HomeSetup.guardSettingsIntent(context))
                }
                toggleTick++
            },
        )
        MenuRow(
            Glyph.Moon,
            "返回主屏幕时回到小课间",
            trailing = if (takeover) "已开启" else "已暂停",
            onClick = {
                QuizHomeTakeover.setEnabled(context, !takeover)
                toggleTick++
            },
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                "「主屏幕应用」在系统列表里选择「小课间」；部分电视会直接弹出确认框。",
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                "「辅助功能」开启后，按返回主屏幕会直接进入小课间。少数电视的系统设置没有这个入口，安装时授权一次后，点这里即可开启。",
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
            Glyph.ChevronLeft, "主屏幕设置",
            focusRequester = first,
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) },
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                "设置电视返回主屏幕时显示的应用。",
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
        }
        Spacer(Modifier.height(10.dp))
        HomeSetupRows()
        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                "调整出题间隔、教材与当前单元：回控制面板 →「小课间 · 家长设置」→ 手机扫码。",
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
