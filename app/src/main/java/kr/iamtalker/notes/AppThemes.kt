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
    private val darkBase = Palette(true, 0xFFA78BFA, 0xFF1F2128, 0xFF2A2D36, 0xFF32353F, 0xFFE6E8EE, 0xFF8D95A5, 0xFFB7BDC9)

    val all: Map<String, Palette> = linkedMapOf(
        "라이트 보라" to lightBase,
        "라이트 파랑" to Palette(false, 0xFF2563EB, lightBase.bg, lightBase.panel, lightBase.line, lightBase.text, lightBase.muted, lightBase.sub),
        "세피아" to Palette(false, 0xFFB45309, 0xFFFBF5E9, 0xFFF3EAD8, 0xFFE4D7BD, 0xFF3B2F20, 0xFF8C7B62, 0xFF5D4E3A),
        "다크 보라" to darkBase,
        "다크 파랑" to Palette(true, 0xFF60A5FA, darkBase.bg, darkBase.panel, darkBase.line, darkBase.text, darkBase.muted, darkBase.sub),
    )

    fun names(): List<String> = listOf(SYSTEM) + all.keys

    /** 강조색 직접 고르기용 팔레트(바탕은 고른 테마 그대로, 강조색만 바꾼다) */
    val accents: List<Long> = listOf(0xFF2563EB, 0xFF0EA5E9, 0xFF059669, 0xFF65A30D, 0xFFD97706, 0xFFDC2626, 0xFFDB2777, 0xFF7C3AED, 0xFF475569)

    /** 이름 → (색 구성, 어두운 테마인가). 시스템이면 예전처럼 파랑 밝게/어둡게 */
    fun scheme(name: String, systemDark: Boolean, accent: Long? = null): Pair<ColorScheme, Boolean> {
        val (cs, dark) = base(name, systemDark)
        return (if (accent != null) cs.copy(primary = Color(accent)) else cs) to dark
    }

    private fun base(name: String, systemDark: Boolean): Pair<ColorScheme, Boolean> {
        if (name == SYSTEM || name !in all) return if (systemDark) {
            darkColorScheme(primary = Color(0xFF5AAEF0), background = Color(0xFF15181C), surface = Color(0xFF15181C), surfaceVariant = Color(0xFF22262B),
                outline = Color(0xFF8C939B), outlineVariant = Color(0xFF30353B), onSurface = Color(0xFFE6E8EB)) to true
        } else {
            lightColorScheme(primary = Accent, background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF2F4F6),
                outline = Color(0xFF8A9099), outlineVariant = Color(0xFFE5E8EB), onSurface = Color(0xFF22262B)) to false
        }
        val p = all.getValue(name)
        val base = if (p.dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(p.accent), background = Color(p.bg), surface = Color(p.bg), surfaceVariant = Color(p.panel),
            outline = Color(p.muted), outlineVariant = Color(p.line), onSurface = Color(p.text), onSurfaceVariant = Color(p.sub),
            onBackground = Color(p.text), surfaceContainerHigh = Color(p.panel), surfaceContainer = Color(p.panel),
        ) to p.dark
    }
}
