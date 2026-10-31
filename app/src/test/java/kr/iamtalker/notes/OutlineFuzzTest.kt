package kr.iamtalker.notes

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 아웃라이너 편집 계산을 무작위 조작으로 마구 돌려 예외가 나지 않는지 본다(폰에서 앱이 꺼진 원인을 찾는 시험) */
class OutlineFuzzTest {
    private val pieces = listOf("- 가", "    - 나", "        - 다", "\t- 탭", "- [ ] 체크", "- [x] 끝", "1. 번호", "    1) 둘", "메모 줄", "", "  - 두칸", "      - 여섯칸", "- ", "-", "- [ ]")

    @Test fun randomOperationsNeverThrow() {
        val rnd = Random(7)
        repeat(3000) { round ->
            var lines: List<String> = List(rnd.nextInt(8)) { pieces[rnd.nextInt(pieces.size)] }
            repeat(40) { step ->
                val i = if (lines.isEmpty()) rnd.nextInt(3) - 1 else rnd.nextInt(lines.size + 2) - 1      // 범위 밖 번호도 던진다
                try {
                    when (rnd.nextInt(7)) {
                        0 -> lines = OutlineEdit.setText(lines, i, "새 글 $step")
                        1 -> lines = OutlineEdit.enter(lines, i).first
                        2 -> lines = OutlineEdit.indent(lines, i) ?: lines
                        3 -> lines = OutlineEdit.outdent(lines, i) ?: lines
                        4 -> lines = OutlineEdit.toggleDone(lines, i)
                        5 -> lines = OutlineEdit.delete(lines, i).first
                        else -> { OutlineEdit.hasChildren(lines, maxOf(0, i)); OutlineEdit.subtreeEnd(lines, minOf(maxOf(0, i), maxOf(0, lines.size - 1))) }
                    }
                } catch (e: Exception) {
                    throw AssertionError("round=$round step=$step i=$i lines=$lines", e)
                }
                assertTrue(lines.all { !it.contains('\n') })
            }
        }
    }

    @Test fun zoomHelpersNeverThrow() {
        val rnd = Random(11)
        repeat(2000) {
            val lines = List(rnd.nextInt(10)) { pieces[rnd.nextInt(pieces.size)] }
            for (i in -1..lines.size + 1) {
                OutlineZoom.range(lines, i)
                OutlineZoom.crumbs(lines, i)
            }
        }
    }
}
