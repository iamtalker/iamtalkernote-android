package kr.iamtalker.notes

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** 노트 안 그림을 읽어 둔 메모리 보관함: 화면 밖으로 나갔다 돌아와도 다시 읽지 않는다. 크기(바이트) 합이 한도를 넘으면 오래 안 쓴 것부터 버린다.
 *  같은 그림을 동시에 두 번 읽지 않는다(미리 읽기와 화면에 나온 것이 겹칠 때). */
object ImageCache {
    private const val MAX_BYTES = 96L * 1024 * 1024
    private val lru = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var bytes = 0L
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    private fun size(b: ImageBitmap) = b.width.toLong() * b.height * 4

    @Synchronized fun get(key: String): ImageBitmap? = lru[key]

    @Synchronized fun put(key: String, b: ImageBitmap) {
        lru.put(key, b)?.let { bytes -= size(it) }
        bytes += size(b)
        val it = lru.entries.iterator()
        while (bytes > MAX_BYTES && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= size(e.value); it.remove()
        }
    }

    @Synchronized fun clear() { lru.clear(); bytes = 0 }

    /** 있으면 바로, 없으면 loader 로 읽어 보관한다. 이미 읽는 중이면 그 결과를 기다린다 */
    suspend fun load(key: String, loader: suspend () -> ImageBitmap?): ImageBitmap? {
        get(key)?.let { return it }
        val mine = CompletableDeferred<ImageBitmap?>()
        inflight.putIfAbsent(key, mine)?.let { return it.await() }
        var r: ImageBitmap? = null
        try {
            r = runCatching { loader() }.getOrNull()
            if (r != null) put(key, r)
            return r
        } finally {
            mine.complete(r)          // 취소돼도 기다리던 쪽이 멈추지 않게
            inflight.remove(key)
        }
    }
}
