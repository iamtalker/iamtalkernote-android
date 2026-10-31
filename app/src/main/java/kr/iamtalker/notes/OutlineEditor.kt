package kr.iamtalker.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 아웃라이너 편집기: 항목마다 입력칸이 있어 눌러서 바로 고친다(컴퓨터 앱 WorkFlowy 식 편집기와 같은 생각).
 *  Enter(키보드 완료) = 새 항목, 아래 막대 = 들여쓰기·내어쓰기(하위 함께)·완료·삭제. 글은 그대로 마크다운 목록이라 다른 편집기에서도 열린다. */
@Composable
fun OutlineEditor(text: String, fontSize: Int, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val body = remember(text) { Meta.split(text).second }
    val head = text.substring(0, text.length - body.length)
    val lines = remember(text) { body.split("\n") }
    var focusIdx by remember { mutableIntStateOf(-1) }
    var pending by remember { mutableIntStateOf(-1) }          // 새로 만든 항목에 커서를 옮기라는 요청
    val color = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    fun put(new: List<String>) = onChange(head + new.joinToString("\n"))

    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            itemsIndexed(lines) { i, line ->
                val p = OutlineEdit.parse(line)
                if (p == null) {
                    if (line.isNotBlank()) Text(line, fontSize = fontSize.sp, color = muted, modifier = Modifier.padding(vertical = 4.dp))
                    else Spacer(Modifier.height(4.dp))
                } else {
                    val req = remember(i) { FocusRequester() }
                    LaunchedEffect(pending, i) { if (pending == i) { runCatching { req.requestFocus() }; pending = -1 } }
                    Row(Modifier.padding(start = (minOf(p.depth, 8) * 14).dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (p.check != null) {
                            Text(if (p.check == ' ') "☐" else "☑", fontSize = (fontSize + 3).sp, color = color, modifier = Modifier.clickable { put(OutlineEdit.toggleDone(lines, i)) }.padding(end = 8.dp))
                        } else {
                            Box(Modifier.padding(end = 10.dp, start = 4.dp).size(if (OutlineEdit.hasChildren(lines, i)) 9.dp else 7.dp).clip(CircleShape)
                                .background(if (OutlineEdit.hasChildren(lines, i)) accent else muted).clickable { pending = i })
                        }
                        BasicTextField(
                            value = p.text, onValueChange = { put(OutlineEdit.setText(lines, i, it)) }, singleLine = true,
                            textStyle = TextStyle(fontSize = fontSize.sp, color = if (p.check == 'x' || p.check == 'X') muted else color),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { val (n, at) = OutlineEdit.enter(lines, i); put(n); pending = at }),
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(req).onFocusChanged { if (it.isFocused) focusIdx = i }
                        )
                    }
                }
            }
            item {
                Text("＋ 항목 추가", fontSize = 14.sp, color = accent, modifier = Modifier.fillMaxWidth().clickable {
                    val at = lines.indexOfLast { OutlineEdit.isItem(lines, lines.indexOf(it)) }
                    if (lines.all { it.isBlank() } || at < 0) { put(lines.filter { it.isNotBlank() } + "- "); pending = lines.count { it.isNotBlank() } }
                    else { val top = lines.indices.last { OutlineEdit.isItem(lines, it) }; val (n, idx) = OutlineEdit.enter(lines, top); put(n); pending = idx }
                }.padding(vertical = 14.dp, horizontal = 4.dp))
                Spacer(Modifier.height(60.dp))
            }
        }
        if (focusIdx in lines.indices && OutlineEdit.isItem(lines, focusIdx)) {
            val i = focusIdx
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { OutlineEdit.outdent(lines, i)?.let { put(it) } }) { Text("⇤ 내어쓰기") }
                    TextButton(onClick = { OutlineEdit.indent(lines, i)?.let { put(it) } }) { Text("⇥ 들여쓰기") }
                    TextButton(onClick = { put(OutlineEdit.toggleDone(lines, i)) }) { Text("☑ 완료") }
                    TextButton(onClick = { val (n, f) = OutlineEdit.delete(lines, i); put(n); focusIdx = -1; if (f >= 0) pending = f }) { Text("삭제") }
                }
            }
        }
    }
}
