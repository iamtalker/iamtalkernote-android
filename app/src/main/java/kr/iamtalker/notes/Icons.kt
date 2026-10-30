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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
