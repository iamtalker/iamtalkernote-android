package kr.iamtalker.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 마크다운 보기용 단순한 렌더러: 제목·목록(체크박스)·인용·코드·표(글자 그대로)·그림·**굵게**·*기울임*·`코드`·링크. */
sealed class Block {
    data class H(val level: Int, val text: String) : Block()
    data class P(val text: String) : Block()
    /** checked: null 이면 일반 목록, 아니면 체크박스. line: 본문(머리말 뒤) 안의 줄 번호(0부터) */
    data class Li(val indent: Int, val marker: String, val text: String, val checked: Boolean?, val line: Int) : Block()
    data class Quote(val text: String) : Block()
    data class Code(val text: String) : Block()
    data class Table(val text: String) : Block()
    data class Img(val alt: String, val src: String) : Block()
    object Rule : Block()
}

private val heading = Regex("^(#{1,6})\\s+(.*)$")
private val rule = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
private val item = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val check = Regex("^\\[([ xX])\\]\\s+(.*)$")
private val imageLine = Regex("^!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")
private val inline = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)|\\[([^\\]]+)\\]\\(([^)\\s]*)[^)]*\\)|\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|~~(.+?)~~|\\*([^*\\n]+?)\\*|\\[\\[([^\\]]+)\\]\\]")

fun parseBlocks(body: String): List<Block> {
    val out = ArrayList<Block>()
    val para = ArrayList<String>()
    val code = ArrayList<String>()
    val table = ArrayList<String>()
    var fence = false
    fun flushPara() { if (para.isNotEmpty()) { out.add(Block.P(para.joinToString("\n"))); para.clear() } }
    fun flushTable() { if (table.isNotEmpty()) { out.add(Block.Table(table.joinToString("\n"))); table.clear() } }
    for ((no, raw) in body.lines().withIndex()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
            if (fence) { out.add(Block.Code(code.joinToString("\n"))); code.clear() } else { flushPara(); flushTable() }
            fence = !fence
            continue
        }
        if (fence) { code.add(raw); continue }
        if (line.trimStart().startsWith("|")) { flushPara(); table.add(line); continue } else flushTable()
        if (line.isBlank()) { flushPara(); continue }
        val h = heading.find(line)
        val li = item.find(line)
        val img = imageLine.find(line.trim())
        when {
            h != null -> { flushPara(); out.add(Block.H(h.groupValues[1].length, h.groupValues[2])) }
            rule.matches(line) -> { flushPara(); out.add(Block.Rule) }
            img != null -> { flushPara(); out.add(Block.Img(img.groupValues[1], img.groupValues[2])) }
            li != null -> {
                flushPara()
                val c = check.find(li.groupValues[3])
                if (c != null) out.add(Block.Li(li.groupValues[1].length / 2, li.groupValues[2], c.groupValues[2], c.groupValues[1] != " ", no))
                else out.add(Block.Li(li.groupValues[1].length / 2, li.groupValues[2], li.groupValues[3], null, no))
            }
            line.startsWith(">") -> { flushPara(); out.add(Block.Quote(line.removePrefix(">").trim())) }
            else -> para.add(line)
        }
    }
    if (fence && code.isNotEmpty()) out.add(Block.Code(code.joinToString("\n")))
    flushPara(); flushTable()
    return out
}

/** 전체 글(머리말 포함)에서 absLine 번째 줄의 체크박스를 뒤집은 새 글 */
fun toggleCheckbox(text: String, absLine: Int): String {
    val lines = text.split("\n").toMutableList()
    if (absLine !in lines.indices) return text
    val l = lines[absLine]
    lines[absLine] = when {
        l.contains("[ ]") -> l.replaceFirst("[ ]", "[x]")
        l.contains("[x]") -> l.replaceFirst("[x]", "[ ]")
        l.contains("[X]") -> l.replaceFirst("[X]", "[ ]")
        else -> l
    }
    return lines.joinToString("\n")
}

fun inlineText(s: String, link: Color, codeBg: Color, muted: Color): AnnotatedString = buildAnnotatedString {
    var pos = 0
    for (m in inline.findAll(s)) {
        append(s.substring(pos, m.range.first))
        pos = m.range.last + 1
        val g = m.groups
        when {
            g[1] != null -> withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) { append("[그림" + (if (g[1]!!.value.isNotEmpty()) ": " + g[1]!!.value else "") + "]") }
            g[3] != null -> {
                val url = g[4]!!.value
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(g[3]!!.value) }
                    pop()
                } else withStyle(SpanStyle(color = link)) { append(g[3]!!.value) }
            }
            g[5] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[5]!!.value) }
            g[6] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[6]!!.value) }
            g[7] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[7]!!.value) }
            g[8] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[8]!!.value) }
            g[9] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[9]!!.value) }
            g[10] != null -> {   // 노트 링크 [[제목]]
                pushStringAnnotation("NOTE", g[10]!!.value)
                withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(g[10]!!.value) }
                pop()
            }
        }
    }
    append(s.substring(pos))
}

@Composable
private fun NoteImage(block: Block.Img, loader: suspend (String) -> ImageBitmap?, onOpen: (String) -> Unit) {
    var bmp by remember(block.src) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(block.src) { mutableStateOf(false) }
    LaunchedEffect(block.src) {
        val b = runCatching { loader(block.src) }.getOrNull()
        bmp = b
        failed = b == null
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = block.alt, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clickable { onOpen(block.src) }.padding(vertical = 6.dp))
    } else {
        Text(if (failed) "[그림을 찾지 못함: ${block.src.substringAfterLast('/')}]" else "[그림 불러오는 중…]",
            color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
fun MarkdownView(
    text: String, modifier: Modifier = Modifier,
    loadImage: suspend (String) -> ImageBitmap? = { null },
    onToggle: (Int) -> Unit = {},
    onNoteLink: (String) -> Unit = {},
    onImage: (String) -> Unit = {}
) {
    val parts = remember(text) {
        val (_, body) = Meta.split(text)
        val offset = text.substring(0, text.length - body.length).count { it == '\n' }
        offset to parseBlocks(body)
    }
    val (offset, blocks) = parts
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.outline
    val color = MaterialTheme.colorScheme.onSurface
    val uri = LocalUriHandler.current

    @Composable
    fun Rich(s: String, size: Int, weight: FontWeight? = null, modifier: Modifier = Modifier, deco: TextDecoration? = null) {
        val a = inlineText(s, link, codeBg, muted)
        ClickableText(
            text = a, modifier = modifier,
            style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.5f).sp, fontWeight = weight, color = color, textDecoration = deco),
            onClick = { off ->
                a.getStringAnnotations("URL", off, off).firstOrNull()?.let { runCatching { uri.openUri(it.item) } }
                a.getStringAnnotations("NOTE", off, off).firstOrNull()?.let { onNoteLink(it.item) }
            }
        )
    }

    LazyColumn(modifier.padding(horizontal = 16.dp)) {
        itemsIndexed(blocks) { _, b ->
            when (b) {
                is Block.H -> Rich(b.text, when (b.level) { 1 -> 24; 2 -> 21; 3 -> 18; else -> 16 }, FontWeight.Bold, Modifier.padding(top = 14.dp, bottom = 4.dp))
                is Block.P -> Rich(b.text, 15, modifier = Modifier.padding(vertical = 4.dp))
                is Block.Li -> Row(Modifier.padding(start = (b.indent * 16).dp, top = 1.dp, bottom = 1.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (b.checked != null) {
                        Checkbox(checked = b.checked, onCheckedChange = { onToggle(offset + b.line) })
                        Rich(b.text, 15, deco = if (b.checked) TextDecoration.LineThrough else null)
                    } else {
                        Text(if (b.marker.first().isDigit()) b.marker else "•", color = color, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                        Rich(b.text, 15)
                    }
                }
                is Block.Quote -> Row(Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.width(3.dp).height(22.dp).background(muted))
                    Spacer(Modifier.width(10.dp))
                    Rich(b.text, 15)
                }
                is Block.Code -> Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(codeBg).horizontalScroll(rememberScrollState()).padding(10.dp))
                is Block.Table -> Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color,
                    modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()))
                is Block.Img -> NoteImage(b, loadImage, onImage)
                Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 10.dp))
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
