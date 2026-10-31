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
import androidx.compose.ui.graphics.drawscope.DrawScope
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


/** 공유(점 세 개를 선으로 이은 모양) */
@Composable
fun ShareIcon(c: Color = Gray) {
    Canvas(Modifier.size(26.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        val a = Offset(s * .74f, s * .22f); val b = Offset(s * .26f, s * .50f); val d = Offset(s * .74f, s * .78f)
        drawLine(c, a, b, strokeWidth = w); drawLine(c, b, d, strokeWidth = w)
        for (p in listOf(a, b, d)) drawCircle(c, radius = s * .11f, center = p, style = Stroke(w))
    }
}

/** 정보(동그라미 안의 i) */
@Composable
fun InfoIcon(c: Color = Gray) {
    Canvas(Modifier.size(26.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        drawCircle(c, radius = s * .40f, center = Offset(s / 2, s / 2), style = Stroke(w))
        drawLine(c, Offset(s / 2, s * .46f), Offset(s / 2, s * .68f), strokeWidth = w, cap = StrokeCap.Round)
        drawCircle(c, radius = w * .75f, center = Offset(s / 2, s * .32f))
    }
}

/** 체크 칸(네모 안의 ✓) */
@Composable
fun CheckBoxIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        drawRoundRect(c, Offset(s * .14f, s * .14f), Size(s * .72f, s * .72f), CornerRadius(s * .12f), style = Stroke(w))
        drawLine(c, Offset(s * .30f, s * .52f), Offset(s * .45f, s * .66f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(s * .45f, s * .66f), Offset(s * .72f, s * .36f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** 그림(네모 안의 산과 해) */
@Composable
fun ImageIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        drawRoundRect(c, Offset(s * .12f, s * .16f), Size(s * .76f, s * .68f), CornerRadius(s * .10f), style = Stroke(w))
        drawCircle(c, radius = s * .07f, center = Offset(s * .34f, s * .36f), style = Stroke(w))
        drawLine(c, Offset(s * .14f, s * .74f), Offset(s * .40f, s * .52f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(s * .40f, s * .52f), Offset(s * .62f, s * .70f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(s * .55f, s * .64f), Offset(s * .70f, s * .50f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(s * .70f, s * .50f), Offset(s * .86f, s * .66f), strokeWidth = w, cap = StrokeCap.Round)
    }
}


// ---- 편집 도구줄 아이콘: 같은 굵기(2dp)의 둥근 선으로 통일
private fun DrawScope.ln(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float) =
    drawLine(c, Offset(x1, y1), Offset(x2, y2), strokeWidth = w, cap = StrokeCap.Round)

/** 제목(H) */
@Composable
fun HeadingIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        ln(c, s * .26f, s * .22f, s * .26f, s * .78f, w); ln(c, s * .74f, s * .22f, s * .74f, s * .78f, w); ln(c, s * .26f, s * .50f, s * .74f, s * .50f, w) }
}

/** 글머리 목록 */
@Composable
fun ListIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        for (y in listOf(.28f, .50f, .72f)) { drawCircle(c, radius = w * .8f, center = Offset(s * .20f, s * y)); ln(c, s * .36f, s * y, s * .84f, s * y, w) } }
}

/** 들여쓰기(→) · 내어쓰기(←) */
@Composable
fun IndentIcon(out: Boolean, c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        ln(c, s * .14f, s * .22f, s * .86f, s * .22f, w); ln(c, s * .14f, s * .78f, s * .86f, s * .78f, w)
        ln(c, s * .50f, s * .42f, s * .86f, s * .42f, w); ln(c, s * .50f, s * .58f, s * .86f, s * .58f, w)
        val x0 = if (out) s * .30f else s * .14f; val x1 = if (out) s * .14f else s * .30f
        ln(c, x0, s * .50f, x1, s * .50f, w)
        val tip = x1; val back = if (out) tip + s * .08f else tip - s * .08f
        ln(c, tip, s * .50f, back, s * .42f, w); ln(c, tip, s * .50f, back, s * .58f, w) }
}

/** 코드(</>) */
@Composable
fun CodeIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        ln(c, s * .34f, s * .30f, s * .16f, s * .50f, w); ln(c, s * .16f, s * .50f, s * .34f, s * .70f, w)
        ln(c, s * .66f, s * .30f, s * .84f, s * .50f, w); ln(c, s * .84f, s * .50f, s * .66f, s * .70f, w)
        ln(c, s * .56f, s * .24f, s * .44f, s * .76f, w) }
}

/** 인용(세로 막대 + 글줄) */
@Composable
fun QuoteIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        ln(c, s * .20f, s * .24f, s * .20f, s * .76f, w * 1.5f); ln(c, s * .40f, s * .32f, s * .84f, s * .32f, w); ln(c, s * .40f, s * .50f, s * .84f, s * .50f, w); ln(c, s * .40f, s * .68f, s * .68f, s * .68f, w) }
}

/** 링크(맞물린 두 고리) */
@Composable
fun LinkIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        drawRoundRect(c, Offset(s * .10f, s * .36f), Size(s * .46f, s * .28f), CornerRadius(s * .14f), style = Stroke(w))
        drawRoundRect(c, Offset(s * .44f, s * .36f), Size(s * .46f, s * .28f), CornerRadius(s * .14f), style = Stroke(w))
        ln(c, s * .38f, s * .50f, s * .62f, s * .50f, w) }
}

/** 노트 링크([[ ]]) */
@Composable
fun WikiLinkIcon(c: Color = Gray) {
    Canvas(Modifier.size(24.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        for (x in listOf(.12f, .24f)) { ln(c, s * x, s * .24f, s * x, s * .76f, w) }
        ln(c, s * .12f, s * .24f, s * .24f, s * .24f, w); ln(c, s * .12f, s * .76f, s * .24f, s * .76f, w)
        for (x in listOf(.88f, .76f)) { ln(c, s * x, s * .24f, s * x, s * .76f, w) }
        ln(c, s * .88f, s * .24f, s * .76f, s * .24f, w); ln(c, s * .88f, s * .76f, s * .76f, s * .76f, w) }
}


/** 좌우 화살표(달 넘기기) */
@Composable
fun ChevronSideIcon(left: Boolean, c: Color = Gray) {
    Canvas(Modifier.size(22.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        val a = if (left) .62f else .38f; val b = if (left) .38f else .62f
        drawLine(c, Offset(s * a, s * .26f), Offset(s * b, s * .50f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(s * b, s * .50f), Offset(s * a, s * .74f), strokeWidth = w, cap = StrokeCap.Round) }
}

/** 더하기 */
@Composable
fun PlusIcon(c: Color = Gray) {
    Canvas(Modifier.size(22.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        drawLine(c, Offset(s * .50f, s * .22f), Offset(s * .50f, s * .78f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, Offset(s * .22f, s * .50f), Offset(s * .78f, s * .50f), strokeWidth = w, cap = StrokeCap.Round) }
}

/** 할일 체크 칸: 비어 있으면 테두리만, 끝났으면 채우고 ✓ */
@Composable
fun TodoBox(done: Boolean, accent: Color, line: Color) {
    Canvas(Modifier.size(22.dp)) { val w = 2.0.dp.toPx(); val s = size.width
        if (done) {
            drawRoundRect(accent, Offset(s * .12f, s * .12f), Size(s * .76f, s * .76f), CornerRadius(s * .18f))
            drawLine(Color.White, Offset(s * .30f, s * .52f), Offset(s * .44f, s * .66f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(s * .44f, s * .66f), Offset(s * .72f, s * .36f), strokeWidth = w, cap = StrokeCap.Round)
        } else drawRoundRect(line, Offset(s * .12f, s * .12f), Size(s * .76f, s * .76f), CornerRadius(s * .18f), style = Stroke(w)) }
}
