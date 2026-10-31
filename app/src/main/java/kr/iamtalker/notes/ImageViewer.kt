package kr.iamtalker.notes

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ImageSave {
    /** 그림 파일(확장자가 없을 수 있음)의 형식에 맞는 확장자 */
    fun extFor(mime: String?): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/bmp" -> "bmp"; else -> "jpg"
    }

    /** 사진 앱에서 보이도록 Pictures/iamtalkernote 에 원본 그대로 복사한다. 성공하면 true */
    fun save(ctx: Context, src: Uri, name: String): Boolean = runCatching {
        val mime = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { o -> ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, o) } }.outMimeType
        val base = name.substringBeforeLast('.', name).ifEmpty { "image" }
        val file = "$base.${extFor(mime)}"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file)
                put(MediaStore.Images.Media.MIME_TYPE, mime ?: "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/iamtalkernote")
            }
            val out = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
            ctx.contentResolver.openOutputStream(out)?.use { o -> ctx.contentResolver.openInputStream(src)?.use { it.copyTo(o) } }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "iamtalkernote").apply { mkdirs() }
            File(dir, file).outputStream().use { o -> ctx.contentResolver.openInputStream(src)?.use { it.copyTo(o) } }
        }
        true
    }.getOrDefault(false)
}

/** 그림을 화면 가득 보여 준다: 두 손가락으로 확대·이동, 두 번 누르면 확대/원래대로, [저장]은 사진 앱(Pictures/iamtalkernote)에 복사 */
@Composable
fun ImageViewer(uri: Uri, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var scale by remember(uri) { mutableStateOf(1f) }
    var off by remember(uri) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(uri) { bmp = withContext(Dispatchers.IO) { runCatching { NoteRepo.loadBitmap(ctx, uri, 2048)?.asImageBitmap() }.getOrNull() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val b = bmp
            if (b == null) Text("불러오는 중…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            else Image(bitmap = b, contentDescription = name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            off = if (scale <= 1f) Offset.Zero else off + pan
                        }
                    }
                    .pointerInput(uri) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f }) }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y))
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).align(Alignment.TopCenter)) {
                TextButton(onClick = onClose) { Text("닫기", color = Color.White, fontSize = 16.sp) }
                Box(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ImageSave.save(ctx, uri, name) }
                        Toast.makeText(ctx, if (ok) "사진 앱의 iamtalkernote 폴더에 저장했습니다" else "저장하지 못했습니다", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("저장", color = Color.White, fontSize = 16.sp) }
            }
        }
    }
}
