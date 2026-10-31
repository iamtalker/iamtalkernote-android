package kr.iamtalker.notes

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 컴퓨터 앱(notes_shared.py THEMES)과 같은 이름·색의 테마. "시스템"은 폰의 밝게/어둡게 설정을 따른다 */
object AppThemes {
    const val SYSTEM = "시스템 설정 따라가기"

    class Palette(val dark: Boolean, val accent: Long, val bg: Long, val panel: Long, val line: Long, val text: Long, val muted: Long, val sub: Long)

    private val lightBase = Palette(false, 0xFF7C3AED, 0xFFFFFFFF, 0xFFF6F7F9, 0xFFE6E8EC, 0xFF1F2937, 0xFF8A93A2, 0xFF4B5563)

    val all: Map<String, Palette> by lazy {
        linkedMapOf(
            "라이트 보라" to lightBase,
            "라이트 파랑" to Palette(false, 0xFF2563EB, lightBase.bg, lightBase.panel, lightBase.line, lightBase.text, lightBase.muted, lightBase.sub),
            "라이트 초록" to Palette(false, 0xFF059669, lightBase.bg, lightBase.panel, lightBase.line, lightBase.text, lightBase.muted, lightBase.sub),
            "다크 검정" to custom(0xFF111111, 0xFFFFFFFF),
            "다크 회색" to custom(0xFF22272E, 0xFF539BF5),
        )
    }

    /** 없앤 프리셋(컴퓨터 앱과 같음): 같은 색의 내 테마로 옮긴다 → (배경색, 강조색) */
    val legacy: Map<String, Pair<Long, Long>> = mapOf(
        "세피아" to (0xFFFBF5E9 to 0xFFB45309), "다크 보라" to (0xFF1F2128 to 0xFFA78BFA), "다크 파랑" to (0xFF1F2128 to 0xFF60A5FA))

    /** 사용자가 배경색·강조색 두 가지로 만드는 여섯 번째 테마(컴퓨터 앱의 "내 테마"와 같은 이름·같은 계산) */
    const val CUSTOM = "내 테마"
    const val CUSTOM_BG = 0xFFFFFFFF
    const val CUSTOM_ACCENT = 0xFF7C3AED

    fun names(): List<String> = listOf(SYSTEM) + all.keys + CUSTOM

    private fun lum(c: Long): Double = (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0

    /** a 와 b 를 섞는다(t=0 이면 a, 1 이면 b) */
    private fun blend(a: Long, b: Long, t: Double): Long {
        fun ch(sh: Int) = Math.round(((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toLong()
        return 0xFF000000 or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** 배경색 하나로 나머지 바탕색을 정한다: 어두우면 밝은 글자, 밝으면 어두운 글자(컴퓨터 앱 custom_base) */
    fun custom(bg: Long, accent: Long): Palette =
        if (lum(bg) < 0.5) Palette(true, accent, bg, blend(bg, 0xFFFFFFFF, 0.06), blend(bg, 0xFFFFFFFF, 0.12), 0xFFE6E8EE, 0xFF8D95A5, 0xFFB7BDC9)
        else Palette(false, accent, bg, blend(bg, 0xFF000000, 0.03), blend(bg, 0xFF000000, 0.08), 0xFF1F2937, 0xFF8A93A2, 0xFF4B5563)

    /** 이름 → (색 구성, 어두운 테마인가). 시스템이면 예전처럼 파랑 밝게/어둡게. 내 테마면 customBg·customAccent 로 만든다 */
    fun scheme(name: String, systemDark: Boolean, customBg: Long = CUSTOM_BG, customAccent: Long = CUSTOM_ACCENT): Pair<ColorScheme, Boolean> =
        base(name, systemDark, if (name == CUSTOM) custom(customBg, customAccent) else all[name])

    private fun base(name: String, systemDark: Boolean, palette: Palette?): Pair<ColorScheme, Boolean> {
        if (name == SYSTEM || palette == null) return if (systemDark) {
            darkColorScheme(primary = Color(0xFF5AAEF0), background = Color(0xFF15181C), surface = Color(0xFF15181C), surfaceVariant = Color(0xFF22262B),
                outline = Color(0xFF8C939B), outlineVariant = Color(0xFF30353B), onSurface = Color(0xFFE6E8EB)) to true
        } else {
            lightColorScheme(primary = Accent, background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF2F4F6),
                outline = Color(0xFF8A9099), outlineVariant = Color(0xFFE5E8EB), onSurface = Color(0xFF22262B)) to false
        }
        val p = palette
        val base = if (p.dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(p.accent), background = Color(p.bg), surface = Color(p.bg), surfaceVariant = Color(p.panel),
            outline = Color(p.muted), outlineVariant = Color(p.line), onSurface = Color(p.text), onSurfaceVariant = Color(p.sub),
            onBackground = Color(p.text), surfaceContainerHigh = Color(p.panel), surfaceContainer = Color(p.panel),
        ) to p.dark
    }
}
