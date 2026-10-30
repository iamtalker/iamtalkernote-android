package kr.iamtalker.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** 서랍을 여는 가로줄 세 개(☰) */
@Composable
fun HamburgerIcon(c: Color = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { Spacer(Modifier.width(22.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(c)) }
    }
}

/** 더보기 메뉴의 세로 점 세 개(⋮) */
@Composable
fun DotsIcon(c: Color = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(3) { Spacer(Modifier.size(4.5.dp).clip(CircleShape).background(c)) }
    }
}

/** 돋보기 */
@Composable
fun SearchIcon(c: Color = Color.White) {
    Canvas(Modifier.size(24.dp)) {
        val w = 2.2.dp.toPx()
        val r = size.minDimension * 0.30f
        val cx = size.width * 0.42f
        val cy = size.height * 0.42f
        drawCircle(c, radius = r, center = Offset(cx, cy), style = Stroke(width = w))
        val d = r * 0.72f
        drawLine(c, Offset(cx + d, cy + d), Offset(size.width * 0.86f, size.height * 0.86f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** 정렬(길이가 줄어드는 가로줄 세 개) */
@Composable
fun SortIcon(c: Color = Color.White) {
    Canvas(Modifier.size(24.dp)) {
        val w = 2.2.dp.toPx()
        val x0 = size.width * 0.10f
        for ((i, f) in listOf(0.80f, 0.58f, 0.36f).withIndex()) {
            val y = size.height * (0.28f + 0.22f * i)
            drawLine(c, Offset(x0, y), Offset(size.width * (0.10f + f), y), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/** 뒤로(←) */
@Composable
fun BackIcon(c: Color = Color.White) {
    Canvas(Modifier.size(24.dp)) {
        val w = 2.2.dp.toPx()
        val y = size.height / 2
        drawLine(c, Offset(size.width * 0.12f, y), Offset(size.width * 0.9f, y), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.12f, y), Offset(size.width * 0.44f, y - size.height * 0.30f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.12f, y), Offset(size.width * 0.44f, y + size.height * 0.30f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** 닫기(×) */
@Composable
fun CloseIcon(c: Color = Color.White) {
    Canvas(Modifier.size(20.dp)) {
        val w = 2.2.dp.toPx()
        drawLine(c, Offset(size.width * 0.15f, size.height * 0.15f), Offset(size.width * 0.85f, size.height * 0.85f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.85f, size.height * 0.15f), Offset(size.width * 0.15f, size.height * 0.85f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

private val Gray = Color(0xFF8A9099)

/** 아래 막대의 노트북(책) */
@Composable
fun BookIcon(c: Color = Gray) {
    Canvas(Modifier.size(26.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        drawRoundRect(c, Offset(s * .22f, s * .10f), Size(s * .58f, s * .80f), CornerRadius(s * .08f), style = Stroke(w))
        drawLine(c, Offset(s * .34f, s * .10f), Offset(s * .34f, s * .90f), strokeWidth = w)
        drawLine(c, Offset(s * .46f, s * .30f), Offset(s * .68f, s * .30f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** 가로 점 세 개 */
@Composable
fun HDotsIcon(c: Color = Gray) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { Spacer(Modifier.size(5.dp).clip(CircleShape).background(c)) }
    }
}

/** 새 노트(연필) */
@Composable
fun PencilIcon(c: Color = Color.White) {
    Canvas(Modifier.size(26.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        val p = Path().apply {
            moveTo(s * .18f, s * .82f); lineTo(s * .26f, s * .56f); lineTo(s * .64f, s * .18f)
            lineTo(s * .82f, s * .36f); lineTo(s * .44f, s * .74f); close()
        }
        drawPath(p, c, style = Stroke(w, join = StrokeJoin.Round))
        drawLine(c, Offset(s * .56f, s * .26f), Offset(s * .74f, s * .44f), strokeWidth = w)
    }
}

/** 접기/펴기 화살표 */
@Composable
fun ChevronIcon(down: Boolean = true, c: Color = Gray) {
    Canvas(Modifier.size(22.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        val a = if (down) .38f else .62f; val b = if (down) .62f else .38f
        drawLine(c, Offset(s * .22f, s * a), Offset(s * .5f, s * b), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(s * .5f, s * b), Offset(s * .78f, s * a), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** 모든 노트(겹친 종이) */
@Composable
fun NotesIcon(c: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = 1.8.dp.toPx(); val s = size.width
        drawRoundRect(c, Offset(s * .30f, s * .08f), Size(s * .56f, s * .72f), CornerRadius(s * .08f), style = Stroke(w))
        drawRoundRect(c, Offset(s * .14f, s * .22f), Size(s * .56f, s * .72f), CornerRadius(s * .08f), style = Stroke(w))
        for (y in listOf(.42f, .54f, .66f)) drawLine(c, Offset(s * .26f, s * y), Offset(s * .58f, s * y), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** 깔때기(미분류) */
@Composable
fun FilterIcon(c: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = 1.8.dp.toPx(); val s = size.width
        val p = Path().apply {
            moveTo(s * .12f, s * .16f); lineTo(s * .88f, s * .16f); lineTo(s * .58f, s * .52f)
            lineTo(s * .58f, s * .84f); lineTo(s * .42f, s * .72f); lineTo(s * .42f, s * .52f); close()
        }
        drawPath(p, c, style = Stroke(w, join = StrokeJoin.Round))
    }
}

/** 고정 핀 */
@Composable
fun PinIcon(c: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = 1.8.dp.toPx(); val s = size.width
        rotate(45f, Offset(s / 2, s / 2)) {
            drawRoundRect(c, Offset(s * .38f, s * .08f), Size(s * .24f, s * .38f), CornerRadius(s * .05f), style = Stroke(w))
            drawLine(c, Offset(s * .26f, s * .46f), Offset(s * .74f, s * .46f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(c, Offset(s * .5f, s * .46f), Offset(s * .5f, s * .92f), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/** 설정 목록의 흰 그림들(색 동그라미 안) */
@Composable
fun GlyphIcon(kind: String) {
    val c = Color.White
    Canvas(Modifier.size(24.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        when (kind) {
            "sync" -> {
                drawArc(c, 200f, 150f, false, Offset(s * .16f, s * .16f), Size(s * .68f, s * .68f), style = Stroke(w, cap = StrokeCap.Round))
                drawArc(c, 20f, 150f, false, Offset(s * .16f, s * .16f), Size(s * .68f, s * .68f), style = Stroke(w, cap = StrokeCap.Round))
                drawLine(c, Offset(s * .78f, s * .12f), Offset(s * .78f, s * .34f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(s * .78f, s * .34f), Offset(s * .56f, s * .34f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(s * .22f, s * .88f), Offset(s * .22f, s * .66f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(s * .22f, s * .66f), Offset(s * .44f, s * .66f), strokeWidth = w, cap = StrokeCap.Round)
            }
            "theme" -> {
                drawCircle(c, s * .36f, style = Stroke(w))
                drawArc(c, 90f, 180f, true, Offset(s * .14f, s * .14f), Size(s * .72f, s * .72f))
            }
            "general" -> {
                drawLine(c, Offset(s * .12f, s * .34f), Offset(s * .88f, s * .34f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(s * .12f, s * .68f), Offset(s * .88f, s * .68f), strokeWidth = w, cap = StrokeCap.Round)
                drawCircle(c, s * .11f, Offset(s * .36f, s * .34f), style = Stroke(w))
                drawCircle(c, s * .11f, Offset(s * .64f, s * .68f), style = Stroke(w))
            }
            "tools" -> {
                for (x in listOf(.14f, .54f)) for (y in listOf(.14f, .54f))
                    drawRoundRect(c, Offset(s * x, s * y), Size(s * .32f, s * .32f), CornerRadius(s * .07f), style = Stroke(w))
            }
        }
    }
}
