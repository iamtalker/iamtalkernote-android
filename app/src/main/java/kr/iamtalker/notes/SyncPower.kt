package kr.iamtalker.notes

/** Monotonic time; stale inventory counts must never keep the device awake. */
class SyncPower {
    private var previous: List<Any>? = null
    private var changedAt = 0L

    fun active(r: ForkSync.Report?, now: Long): Boolean {
        val folders = r?.folders.orEmpty().filter { f ->
            f.id !in r!!.blocked && r.gone.none { it.first == f.id } &&
                f.failed == 0 && f.state != "error" && f.state != "paused"
        }
        if (r?.connected != true || r.error != null ||
            folders.none { it.needFiles > 0 || it.needBytes > 0 || it.upNeed > 0 }) {
            previous = null
            return false
        }
        val signature = folders.sortedBy { it.id }.map {
            listOf(it.id, it.needFiles, it.needBytes, it.upNeed, it.totalFiles, it.totalBytes)
        }
        if (signature != previous) { previous = signature; changedAt = now }
        return now - changedAt < 180_000L
    }

    fun pollMs(active: Boolean, settling: Boolean, idleMs: Long = DEFAULT_IDLE_MS): Long =
        if (active || settling) 5_000L else idleMs.coerceIn(MIN_IDLE_MS, MAX_IDLE_MS)

    companion object {
        const val DEFAULT_IDLE_MS = 30_000L
        const val MIN_IDLE_MS = 30_000L
        const val MAX_IDLE_MS = 86_400_000L
        const val PREF_IDLE_SEC = "syncIdlePollSec"
    }
}
