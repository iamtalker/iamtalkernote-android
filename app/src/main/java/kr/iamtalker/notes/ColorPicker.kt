package kr.iamtalker.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 색 고르는 창: 위쪽 사각형(가로 채도 · 세로 밝기)을 누르거나 끌고, 아래 막대로 색상을 고른다. 확인을 누르면 0xAARRGGBB 로 돌려준다 */
@Composable
fun ColorPickerDialog(title: String, initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) } }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    val color = Color.hsv(h, s, v)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        fun set(o: Offset) { s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f) }
                        detectTapGestures { set(it) }
                    }
                    .pointerInput(Unit) {
                        fun set(o: Offset) { s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f) }
                        detectDragGestures(onDragStart = { set(it) }) { c, _ -> set(c.position) }
                    }) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(h, 1f, 1f))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(s * size.width, (1f - v) * size.height)
                    drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black, 12.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
                Canvas(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
                    .pointerInput(Unit) { detectTapGestures { h = (it.x / size.width).coerceIn(0f, 1f) * 360f } }
                    .pointerInput(Unit) { detectDragGestures(onDragStart = { h = (it.x / size.width).coerceIn(0f, 1f) * 360f }) { c, _ -> h = (c.position.x / size.width).coerceIn(0f, 1f) * 360f } }) {
                    drawRect(Brush.horizontalGradient(List(7) { Color.hsv(it * 60f, 1f, 1f) }))
                    val c = Offset(h / 360f * size.width, size.height / 2)
                    drawCircle(Color.White, 10.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black, 11.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    Text("#%06X".format(color.toArgb() and 0xFFFFFF), fontSize = 15.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = { TextButton({ onPick(color.toArgb().toLong() and 0xFFFFFFFFL) }) { Text(tr("확인")) } },
        dismissButton = { TextButton(onDismiss) { Text(tr("취소")) } },
    )
}
