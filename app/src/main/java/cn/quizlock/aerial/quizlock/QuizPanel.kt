package cn.quizlock.aerial.quizlock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.PhoneServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The parent's door into QuizLock, reached from Control Center's top-right panel. It shows the QR
 * code of Aerial's own phone page: a phone on the same Wi-Fi opens it in the browser, no app to
 * install. The child's home screen stays empty; this page only exists while the parent holds the
 * panel open, and changes need the parental PIN once one is set.
 *
 * Kept in QuizLock's own package: upstream ControlCenter only gains one small QL-BEGIN call site.
 */
@Composable
fun QuizPanel(vm: HomeViewModel, takeFocus: Boolean) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    var address by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
        address = url
        if (url != null) qr = withContext(Dispatchers.Default) { PhoneServer.qr(url, 6).asImageBitmap() }
    }

    // Read again whenever the page is composed with the panel open (the phone may have just saved).
    val policy = QuizPolicy.load(context)
    val watchedMin = QuizGate.watchedSinceQuizMs() / 60_000L
    val leftMin = (policy.intervalMinutes - watchedMin).coerceAtLeast(0)

    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(
            Glyph.ChevronLeft, "小课间 · 家长设置",
            focusRequester = first,
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) },
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
            Box(Modifier.size(112.dp).smoothBackground(Color.White, 12.dp).padding(7.dp)) {
                qr?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText("手机扫码配置", style = Type.Body)
                Spacer(Modifier.height(5.dp))
                BasicText("连同一个 WiFi，用手机浏览器打开", style = Type.Caption.copy(color = Palette.SecondaryLabel))
                Spacer(Modifier.height(5.dp))
                BasicText(address ?: "电视未连网", style = Type.Caption.copy(color = Palette.TertiaryLabel, fontSize = 9.sp))
            }
        }

        Spacer(Modifier.height(12.dp))
        MenuRow(
            Glyph.Kids,
            if (policy.enabled) "答题守护：已开启" else "答题守护：已暂停",
            trailing = if (policy.enabled) "暂停 ›" else "开启 ›",
            onClick = {
                QuizGate.applyPolicy(context, policy.copy(enabled = !policy.enabled))
                vm.overlay = Overlay.None
            },
        )
        MenuRow(
            Glyph.Calendar,
            "每 ${policy.intervalMinutes} 分钟出一道题 · 答对续看 ${policy.unlockMinutes} 分钟",
            onClick = {},
        )
        MenuRow(
            Glyph.Play, "立即出一道题（预览）",
            onClick = { QuizGate.preview(); vm.overlay = Overlay.None },
        )

        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 14.dp)) {
            BasicText(
                "本轮已看 $watchedMin 分钟，约 $leftMin 分钟后出题 · 题库 ${QuizBank.all.size} 道",
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                "修改时长、教材版本与当前单元在手机页面上完成；若电视设了家长 PIN，手机保存时需要填写。",
                style = Type.Caption.copy(color = Palette.TertiaryLabel, textAlign = TextAlign.Start),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
