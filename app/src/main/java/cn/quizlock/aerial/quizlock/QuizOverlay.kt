package cn.quizlock.aerial.quizlock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * The quiz over the home screen: subject and unit tags, the stem, four options in a 2×2 grid the
 * remote moves through (D-pad by row and column, not linearly), and a hint from the last wrong
 * answer. A wrong answer swaps in a similar question (same knowledge point); the gate never
 * locks the child in — attempts exhausted also unlocks, with the hint left to read.
 */
@Composable
fun QuizOverlay() {
    val quiz = QuizGate.quiz ?: return
    val question = quiz.question
    val hint = QuizGate.hint
    val wrong = QuizGate.flash
    val first = remember { FocusRequester() }

    BackHandler(enabled = true) {}
    LaunchedEffect(question.id) {
        runCatching { first.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .consumeTaps()
            .background(Color(0xFF07080A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 900.dp).padding(horizontal = 24.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tag(question.subject)
                if (question.unit.isNotEmpty()) Tag(question.unit)
            }
            Spacer(Modifier.height(22.dp))
            BasicText(
                question.stem,
                style = Type.Title.copy(
                    fontSize = 36.sp, fontWeight = FontWeight.W600, lineHeight = 46.sp,
                    textAlign = TextAlign.Center, color = Palette.Label,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            hint?.let {
                BasicText(
                    "上一题小提示：$it",
                    style = Type.Headline.copy(
                        fontSize = 20.sp, color = Palette.Orange,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            wrong?.let {
                BasicText(it, style = Type.Headline.copy(fontSize = 20.sp, color = Palette.Red))
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(18.dp))
            val columns = 2
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                question.options.chunked(columns).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEachIndexed { c, opt ->
                            val index = r * columns + c
                            val focused = index == QuizGate.focusIndex
                            Pressable(
                                onClick = { QuizGate.answer(opt.id) },
                                focusRequester = if (index == 0) first else null,
                                modifier = Modifier.weight(1f, fill = false),
                            ) { _ ->
                                Box(
                                    Modifier
                                        .widthIn(min = 320.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        // A 3dp bright-blue frame on focus: at two metres the
                                        // default 6% scale-up alone is invisible on a TV.
                                        .background(if (focused) Color(0xFF12233A) else Palette.Glass)
                                        .then(
                                            if (focused) Modifier.border(
                                                3.dp, Color(0xFF4C8DFF), RoundedCornerShape(12.dp)
                                            ) else Modifier
                                        )
                                        .padding(horizontal = 24.dp, vertical = 18.dp),
                                ) {
                                    BasicText(
                                        "${opt.id}. ${opt.text}",
                                        style = Type.Headline.copy(
                                            fontSize = 24.sp, fontWeight = FontWeight.W500,
                                            color = if (focused) Color.White else Palette.Label,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            BasicText(
                "遥控器方向键选择 · 确定键提交",
                style = Type.Headline.copy(fontSize = 16.sp, color = Palette.TertiaryLabel),
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Glass)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        BasicText(text, style = Type.Headline.copy(fontSize = 16.sp, color = Palette.SecondaryLabel))
    }
}

/** A quiet fading notice at the bottom of the home screen after a correct answer. */
@Composable
fun QuizNotice() {
    val notice = QuizGate.notice ?: return
    var visible by remember(notice) { mutableStateOf(true) }
    LaunchedEffect(notice) {
        delay(3_000)
        QuizGate.dismissNotice()
    }
    Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.Glass)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            BasicText(notice, style = Type.Headline.copy(fontSize = 20.sp, color = Palette.Label))
        }
    }
}
