package kr.iamtalker.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 화면 글자의 언어(한국어·영어). 코드의 한국어 문장이 곧 키이고, 영어일 때만 [EN] 에서 찾아 바꾼다(없으면 한국어 그대로).
 *  컴퓨터 앱(i18n.py)과 같은 방식. 언어는 설정 "lang" 에 저장하고, 처음에는 폰의 표시 언어를 따른다(한국어 → 한국어, 그 밖 → 영어). */
object Lang {
    /** 화면이 읽는 상태값이라 바꾸면 tr() 을 쓰는 화면이 다시 그려진다 */
    var code by mutableStateOf("ko")
        private set

    fun init(ctx: Context) {
        val saved = ctx.getSharedPreferences("settings", 0).getString("lang", null)
        code = if (saved == "en" || saved == "ko") saved else if (Locale.getDefault().language == "ko") "ko" else "en"
    }

    fun set(ctx: Context, c: String) {
        code = c
        ctx.getSharedPreferences("settings", 0).edit().putString("lang", c).apply()
    }

    val names = linkedMapOf("en" to "English", "ko" to "한국어")
    /** 달력 요일 머리글(일요일부터) */
    fun weekdays(): List<String> = if (code == "en") listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa") else "일월화수목금토".map { it.toString() }

    val flags = mapOf("en" to "🇺🇸", "ko" to "🇰🇷")   // 🇺🇸 🇰🇷
}

fun tr(s: String): String = if (Lang.code == "en") I18nEn.map[s] ?: s else s

/** 값이 끼는 문장: trf("노트 %d개", n) — 영어 문장도 같은 자리표시자(%d %s)를 쓴다 */
fun trf(s: String, vararg args: Any?): String = tr(s).format(*args)
