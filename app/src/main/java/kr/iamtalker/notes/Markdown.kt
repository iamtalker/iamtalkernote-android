package kr.iamtalker.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.asImageBitmap
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
import android.content.Context
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView

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
    /** 웹 스크랩의 `<video controls src="…"></video>` 한 줄. src 는 http(s) 주소 또는 .resources 상대 경로 */
    data class Video(val src: String) : Block()
    object Rule : Block()
}

/** 마크다운 표. header: 둘째 줄이 구분줄(| --- | :-: |)일 때만 표로 본다. aligns: 열마다 "left"·"right"·"center"·"" */
class MdTable(val rows: List<List<String>>, val aligns: List<String>) {
    val cols: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

private val tableSep = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

/** 표 한 줄을 칸으로 나눈다(앞뒤 | 는 버리고, 글자 속 \\| 는 그냥 |) */
fun splitCells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    return s.split(Regex("(?<!\\\\)\\|")).map { it.replace("\\|", "|").trim() }
}

/** 표 블록 글을 표로 읽는다. 구분줄이 없으면 null(그럴 땐 예전처럼 글자 그대로 보인다). 구분줄의 대시는 1개 이상(:-- --: :-:) */
fun parseTable(text: String): MdTable? {
    val lines = text.split("\n").filter { it.isNotBlank() }
    if (lines.size < 2 || !tableSep.matches(lines[1]) || !(lines[1].contains("|") || lines[1].contains("---"))) return null
    val aligns = splitCells(lines[1]).map { c ->
        when {
            c.startsWith(":") && c.endsWith(":") -> "center"
            c.endsWith(":") -> "right"
            c.startsWith(":") -> "left"
            else -> ""
        }
    }
    return MdTable(listOf(splitCells(lines[0])) + lines.drop(2).map { splitCells(it) }, aligns)
}

private val heading = Regex("^(#{1,6})\\s+(.*)$")
private val rule = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
private val item = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val check = Regex("^\\[([ xX])\\]\\s+(.*)$")
// 줄 끝에 글자가 더 붙은 그림(웹 스크랩의 '이미지 크게 보기' 등)도 그림으로 보고, 붙은 글은 문단으로 남긴다.
private val imageLine = Regex("^!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)(.*)$")
// 링크로 감싼 그림 [![그림](src)](원본 주소) — 웹 스크랩이 이 형식으로 만든다. 바깥 링크는 버리고 그림만 보인다.
private val linkedImageLine = Regex("^\\[!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\]\\([^)]*\\)(.*)$")
// 줄 안의 로컬 그림(.resources)과, 그 그림을 감싼 링크 [![그림](src)글](주소) — 웹 스크랩 프로필 아이콘·카드 등
// 동영상 한 줄: <video controls="" src="주소"></video> (PC 앱 노트와 같은 형식, 대소문자·따옴표 무관)
private val videoLine = Regex("^<video\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>.*$", RegexOption.IGNORE_CASE)
private val localImage = Regex("!\\[([^\\]]*)\\]\\(((?:\\.\\./)?\\.resources/[^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
private val wrappedLocalImage = Regex("\\[(!\\[[^\\]]*\\]\\((?:\\.\\./)?\\.resources/[^)\\s]+\\))([^\\]]*)\\]\\([^)]*\\)")

/** 줄에 로컬 그림이 있으면 그림 블록과 글 블록으로 나눈다. 없으면 null. */
private fun splitLocalImages(text: String): List<Block>? {
    if (!localImage.containsMatchIn(text)) return null
    val s = wrappedLocalImage.replace(text) { it.groupValues[1] + it.groupValues[2] }
    val out = ArrayList<Block>()
    var pos = 0
    for (m in localImage.findAll(s)) {
        val before = s.substring(pos, m.range.first).trim()
        if (before.isNotEmpty()) out.add(Block.P(before))
        out.add(Block.Img(m.groupValues[1], m.groupValues[2]))
        pos = m.range.last + 1
    }
    val rest = s.substring(pos).trim()
    if (rest.isNotEmpty()) out.add(Block.P(rest))
    return out
}

private val inline = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)|\\[([^\\]]+)\\]\\(([^)\\s]*)[^)]*\\)|\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|~~(.+?)~~|\\*([^*\\n]+?)\\*|\\[\\[([^\\]]+)\\]\\]")

/** 목록 앞 공백의 깊이: 탭은 4칸, 두 칸이 한 단계 */
fun indentOf(lead: String): Int = lead.replace("\t", "    ").length / 2

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
        val img = imageLine.find(line.trim()) ?: linkedImageLine.find(line.trim())
        val locals = if (h == null && li == null && !line.trimStart().startsWith(">")) splitLocalImages(line.trim()) else null
        val video = videoLine.find(line.trim())
        when {
            h != null -> { flushPara(); out.add(Block.H(h.groupValues[1].length, h.groupValues[2])) }
            rule.matches(line) -> { flushPara(); out.add(Block.Rule) }
            video != null -> { flushPara(); out.add(Block.Video(video.groupValues[1])) }
            locals != null -> { flushPara(); out.addAll(locals) }
            img != null -> {
                flushPara()
                out.add(Block.Img(img.groupValues[1], img.groupValues[2]))
                val rest = img.groupValues[3].trim()
                if (rest.isNotEmpty()) out.add(Block.P(rest))
            }
            li != null -> {
                flushPara()
                val c = check.find(li.groupValues[3])
                if (c != null) out.add(Block.Li(indentOf(li.groupValues[1]), li.groupValues[2], c.groupValues[2], c.groupValues[1] != " ", no))
                else out.add(Block.Li(indentOf(li.groupValues[1]), li.groupValues[2], li.groupValues[3], null, no))
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
    fun plain(t: String) {
        for ((piece, title) in WikiAuto.split(t)) {
            if (title == null) append(piece)
            else { pushStringAnnotation("NOTE", title); withStyle(SpanStyle(color = link)) { append(piece) }; pop() }
        }
    }
    for (m in inline.findAll(s)) {
        plain(s.substring(pos, m.range.first))
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
    plain(s.substring(pos))
}

@Composable
private fun NoteImage(block: Block.Img, cacheKey: String, loader: suspend (String) -> ImageBitmap?, onOpen: (String) -> Unit) {
    val key = cacheKey + "|" + block.src
    var bmp by remember(key) { mutableStateOf(ImageCache.get(key)) }      // 보관함에 있으면 바로 보인다
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (bmp == null) {
            val b = ImageCache.load(key) { loader(block.src) }
            bmp = b
            failed = b == null
        }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = block.alt, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clickable { onOpen(block.src) }.padding(vertical = 6.dp))
    } else {
        // 읽는 동안에도 자리를 잡아 두어 아래 글이 덜 출렁이게 한다
        Text(if (failed) "[그림을 찾지 못함: ${block.src.substringAfterLast('/')}]" else "[그림 불러오는 중…]",
            color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().heightIn(min = if (failed) 24.dp else 120.dp).padding(vertical = 4.dp))
    }
}

/** 동영상의 한 장면(썸네일)을 읽는다. 1초 지점(없으면 처음)을 쓰고, 폭 640px 이하로 줄인다. 실패하면 null. */
private fun videoFrame(ctx: Context, uri: Uri): ImageBitmap? {
    val r = android.media.MediaMetadataRetriever()
    return try {
        if (uri.scheme == "http" || uri.scheme == "https") r.setDataSource(uri.toString(), HashMap<String, String>())
        else r.setDataSource(ctx, uri)
        val frame = r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        frame?.let { f ->
            val small = if (f.width > 640) android.graphics.Bitmap.createScaledBitmap(f, 640, (f.height * 640f / f.width).toInt().coerceAtLeast(1), true) else f
            small.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}

private fun fmtTime(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** 폰 재생기: 화면을 채우는 영상 위에 재생 위치 막대, 재생·일시정지, 음량(음소거·슬라이더), 전체 화면 단추를 둔다.
 *  startMs 부터 재생하고, 재생 위치를 onPos 로 알려 준다. */
@Composable
private fun VideoPlayer(
    uri: Uri, startMs: Int, autoPlay: Boolean, fullscreen: Boolean,
    onPos: (Int) -> Unit, onToggleFull: () -> Unit, onFail: () -> Unit
) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(startMs) }
    var dur by remember { mutableStateOf(0) }
    var vol by remember { mutableStateOf(1f) }
    var seeking by remember { mutableStateOf<Int?>(null) }        // 막대를 끄는 중이면 그 위치
    var seekTarget by remember { mutableStateOf<Int?>(null) }     // 방금 옮긴 위치(옮기는 동안은 옛 위치를 읽지 않는다)
    var seekDeadline by remember { mutableStateOf(0L) }

    // 재생 위치와 재생 중 여부를 0.25초마다 읽는다(막대를 끄는 중·옮기는 중에는 옛 위치로 되돌리지 않는다)
    LaunchedEffect(prepared) {
        while (prepared) {
            val v = view
            if (v != null) {
                playing = v.isPlaying
                val t = seekTarget
                if (t != null) {
                    // 재생 중에는 옮긴 뒤에도 옛 위치가 잠깐 나오므로, 가까워지거나 2초가 지나면 다시 읽는다
                    if (Math.abs(v.currentPosition - t) < 1000 || System.currentTimeMillis() > seekDeadline) seekTarget = null
                    pos = t
                } else if (seeking == null) {
                    pos = v.currentPosition
                }
                onPos(pos)
            }
            delay(250)
        }
    }
    // 음량은 재생기 쪽에 적용한다(VideoView 의 MediaPlayer 를 onPrepared 에서 받아 둔다)
    var mp by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    LaunchedEffect(mp, vol) { mp?.setVolume(vol, vol) }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
        AndroidView(
            factory = { c ->
                VideoView(c).also { v ->
                    v.setOnPreparedListener { p ->
                        mp = p
                        dur = p.duration.coerceAtLeast(0)
                        p.setVolume(vol, vol)
                        prepared = true
                        if (startMs > 0) v.seekTo(startMs)
                        if (autoPlay) v.start()
                    }
                    v.setOnErrorListener { _, _, _ -> onFail(); true }
                    v.setVideoURI(uri)
                    view = v
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.stopPlayback(); view = null }     // 화면에서 벗어나면 소리도 멈춘다
        )
        // 영상을 누르면 재생·일시정지(아래 조절 막대 영역은 제외: 막대를 끌 때 일시정지가 눌리지 않게)
        Box(Modifier.fillMaxSize().padding(bottom = 96.dp).clickable {
            val v = view ?: return@clickable
            if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true }
        })
        if (!prepared) CircularProgressIndicator(color = Color.White)
        if (prepared) {
            Column(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                // 재생 위치 막대
                Slider(
                    value = (seeking ?: pos).toFloat(),
                    valueRange = 0f..dur.coerceAtLeast(1).toFloat(),
                    onValueChange = { seeking = it.toInt() },
                    onValueChangeFinished = {
                        seeking?.let { target ->
                            view?.seekTo(target)
                            pos = target
                            seekTarget = target
                            seekDeadline = System.currentTimeMillis() + 2000
                        }
                        seeking = null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (playing) "일시정지" else "재생", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            val v = view ?: return@clickable
                            if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true }
                        }.padding(horizontal = 6.dp, vertical = 8.dp))
                    Text("${fmtTime(seeking ?: pos)} / ${fmtTime(dur)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (vol == 0f) "소리 켬" else "음소거", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.clickable { vol = if (vol == 0f) 1f else 0f }.padding(horizontal = 6.dp, vertical = 8.dp))
                    Slider(value = vol, onValueChange = { vol = it }, valueRange = 0f..1f, modifier = Modifier.width(72.dp))
                    Text(if (fullscreen) "닫기" else "전체 화면", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.clickable { onToggleFull() }.padding(horizontal = 6.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** 동영상 블록: 처음에는 썸네일과 재생 단추만 그린다(영상을 미리 여는 것은 하지 않는다). 누르면 그때 폰의 재생기로 재생한다. */
@Composable
private fun NoteVideo(src: String, cacheKey: String, loadUri: suspend (String) -> Uri?) {
    val ctx = LocalContext.current
    var uri by remember(src) { mutableStateOf<Uri?>(null) }
    var failed by remember(src) { mutableStateOf(false) }
    var playing by remember(src) { mutableStateOf(false) }
    var fullscreen by remember(src) { mutableStateOf(false) }
    var resumeAt by remember(src) { mutableStateOf(0) }     // 전체 화면과 본문 재생기가 서로 이어 재생할 위치
    val key = cacheKey + "|video|" + src
    var poster by remember(key) { mutableStateOf(ImageCache.get(key)) }   // 보관함에 있으면 바로 보인다
    LaunchedEffect(src) {
        val u = loadUri(src)
        uri = u
        if (u == null) failed = true
    }
    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        if (poster == null) poster = ImageCache.load(key) { withContext(Dispatchers.IO) { videoFrame(ctx, u) } }
    }
    val u = uri
    // 상자 비율을 영상 자체 비율에 맞춘다(세로 영상은 세로로 크게). 썸네일이 아직 없으면 16:9 로 둔다.
    val ratio = poster?.let { p -> if (p.height > 0) p.width.toFloat() / p.height.toFloat() else null } ?: (16f / 9f)
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).aspectRatio(ratio).background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (playing && u != null && !failed && !fullscreen) {
            VideoPlayer(
                uri = u, startMs = resumeAt, autoPlay = true, fullscreen = false,
                onPos = { resumeAt = it },
                onToggleFull = { fullscreen = true },
                onFail = { failed = true }
            )
        } else if (fullscreen && u != null) {
            // 전체 화면에서 재생 중: 본문 자리에는 썸네일만 둔다
            poster?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Text("전체 화면에서 재생 중", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            Dialog(
                onDismissRequest = { fullscreen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayer(
                        uri = u, startMs = resumeAt, autoPlay = true, fullscreen = true,
                        onPos = { resumeAt = it },
                        onToggleFull = { fullscreen = false },
                        onFail = { failed = true; fullscreen = false }
                    )
                }
            }
        } else {
            poster?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            if (failed) {
                Text("[동영상을 재생하지 못함: ${src.substringAfterLast('/')}]", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            } else {
                // 재생 단추(흰 원 안의 삼각형). 썸네일을 읽는 동안에도 누를 수 있다.
                Canvas(Modifier.size(56.dp).clickable { playing = true }) {
                    drawCircle(color = Color.Black.copy(alpha = 0.55f))
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.40f, size.height * 0.30f)
                        lineTo(size.width * 0.72f, size.height * 0.50f)
                        lineTo(size.width * 0.40f, size.height * 0.70f)
                        close()
                    }
                    drawPath(path, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun MarkdownView(
    text: String, modifier: Modifier = Modifier,
    loadImage: suspend (String) -> ImageBitmap? = { null },
    loadVideo: suspend (String) -> Uri? = { null },
    onToggle: (Int) -> Unit = {},
    onNoteLink: (String) -> Unit = {},
    onImage: (String) -> Unit = {},
    cacheKey: String = "",
    outliner: Boolean = false
) {
    val parts = remember(text) {
        val (_, body) = Meta.split(text)
        val offset = text.substring(0, text.length - body.length).count { it == '\n' }
        offset to parseBlocks(body)
    }
    val (offset, blocks) = parts
    // 문서를 열자마자 그림을 위에서부터 미리 읽어 둔다(스크롤해서 나타날 때 이미 준비돼 있게)
    LaunchedEffect(parts, cacheKey) {
        for (b in blocks) if (b is Block.Img) ImageCache.load(cacheKey + "|" + b.src) { loadImage(b.src) }
    }
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.outline
    val color = MaterialTheme.colorScheme.onSurface
    val uri = LocalUriHandler.current

    @Composable
    fun Rich(s: String, size: Int, weight: FontWeight? = null, modifier: Modifier = Modifier, deco: TextDecoration? = null, align: TextAlign = TextAlign.Unspecified) {
        val a = remember(s, WikiAuto.version, WikiAuto.enabled) { inlineText(s, link, codeBg, muted) }
        ClickableText(
            text = a, modifier = modifier,
            style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.5f).sp, fontWeight = weight, color = color, textDecoration = deco, textAlign = align),
            onClick = { off ->
                a.getStringAnnotations("URL", off, off).firstOrNull()?.let { runCatching { uri.openUri(it.item) } }
                a.getStringAnnotations("NOTE", off, off).firstOrNull()?.let { onNoteLink(it.item) }
            }
        )
    }

    // 한 블록을 그리는 부분(평소 보기와 아웃라이너의 '메모 블록'이 함께 쓴다)
    @Composable
    fun Blk(b: Block) {
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
            is Block.Table -> {
                val t = remember(b.text) { parseTable(b.text) }
                if (t == null) {
                    Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color,
                        modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()))
                } else {
                    // 열마다 가장 넓은 칸에 맞추고(IntrinsicSize.Max), 칸마다 가는 테두리. 머리행은 굵게+배경. 넓으면 가로로 밀어서 본다
                    val line = muted.copy(alpha = 0.55f)
                    Row(Modifier.padding(vertical = 6.dp).horizontalScroll(rememberScrollState())) {
                        for (c in 0 until t.cols) Column(Modifier.width(IntrinsicSize.Max)) {
                            val al = when (t.aligns.getOrNull(c)) { "right" -> TextAlign.End; "center" -> TextAlign.Center; else -> TextAlign.Start }
                            for ((ri, row) in t.rows.withIndex()) {
                                val head = ri == 0
                                Box(Modifier.fillMaxWidth().height(34.dp).border(0.5.dp, line).background(if (head) codeBg else Color.Transparent).padding(horizontal = 8.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {   // 열마다 따로 쌓으므로 칸 높이를 같게 고정해야 가로선이 맞는다
                                    Rich(row.getOrElse(c) { "" }, 13, if (head) FontWeight.Bold else null, Modifier.fillMaxWidth(), align = al)
                                }
                            }
                        }
                    }
                }
            }
            is Block.Img -> NoteImage(b, cacheKey, loadImage, onImage)
            is Block.Video -> NoteVideo(b.src, cacheKey, loadVideo)
            Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }
    }

    val outlineOn = outliner && Outline.hasList(blocks)
    var zoom by remember(cacheKey) { mutableStateOf<Int?>(null) }              // 줌인한 항목 번호(없으면 노트 전체)
    val nodes = remember(blocks) { Outline.nodes(blocks) }
    if (outlineOn && zoom != null) BackHandler { zoom = Outline.parentId(nodes, zoom!!) }   // 뒤로가기는 한 단계 위로
    val rows = remember(blocks, zoom, outlineOn) { if (outlineOn) Outline.rows(blocks, zoom) else emptyList() }

    LazyColumn(modifier.padding(horizontal = 16.dp)) {
        if (!outlineOn) {
            itemsIndexed(blocks) { _, b -> Blk(b) }
        } else {
            itemsIndexed(rows) { _, r ->
                when (r) {
                    is Outline.Row.Memo -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) { Blk(r.block) }
                    is Outline.Row.Bullet -> Row(Modifier.padding(start = (minOf(r.depth, 6) * 14).dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Box(Modifier.size(32.dp).clickable { zoom = r.node.id }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Box(Modifier.size(if (r.node.children.isEmpty()) 7.dp else 9.dp)
                                .background(if (r.node.children.isEmpty()) muted else link, androidx.compose.foundation.shape.CircleShape))
                        }
                        Row(Modifier.padding(top = 5.dp, bottom = 5.dp)) {
                            if (r.node.marker.first().isDigit()) Text(r.node.marker + " ", color = muted, fontSize = 15.sp)
                            Rich(r.node.text, 15)
                        }
                    }
                    is Outline.Row.Crumbs -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("노트", color = link, fontSize = 14.sp, modifier = Modifier.clickable { zoom = null }.padding(end = 2.dp))
                        for (a in r.path) {
                            Text("  ›  ", color = muted, fontSize = 14.sp)
                            Text(a.text.take(24) + (if (a.text.length > 24) "…" else ""), color = link, fontSize = 14.sp, maxLines = 1, modifier = Modifier.clickable { zoom = a.id })
                        }
                    }
                    is Outline.Row.Title -> Rich(r.node.text, 17, null, Modifier.padding(top = 6.dp, bottom = 8.dp))
                    Outline.Row.Empty -> Text("(하위 항목이 없습니다)", color = muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
