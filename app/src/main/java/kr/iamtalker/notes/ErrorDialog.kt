package kr.iamtalker.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** 오류 안내 창: [복사] 로 글을 통째로 클립보드에 넣는다(개발자에게 그대로 붙여 보내기 쉽게) */
@Composable
fun ErrorDialog(text: String, title: String? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = title?.let { { Text(it) } },
        text = { SelectionContainer { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
        dismissButton = {
            TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("오류", text))
                android.widget.Toast.makeText(ctx, "복사했습니다", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("복사") }
        }
    )
}
