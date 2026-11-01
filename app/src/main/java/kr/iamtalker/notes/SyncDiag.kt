package kr.iamtalker.notes

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/** 동기화가 왜 느린지 보려고 10초마다 엔진 로그(동기화 화면 → 로그 복사)에 한 줄 남긴다. 받는 중일 때만 쓴다.
 *  tick: 앱의 5초 작업 한 번이 걸린 시간, db: 폴더 상태를 묻는 데 걸린 시간, sys: 엔진 상태(고루틴·메모리), in: 컴퓨터에서 받은 바이트(지난 줄 이후),
 *  thermal·saver·idle·bucket: 폰의 발열·절전·도즈·앱 대기 단계(백그라운드 제한 확인용) */
object SyncDiag {
    private var last = 0L
    private var lastIn = 0L

    fun sample(ctx: Context, api: ForkSync.Api, pcId: String, tickMs: Long) {
        val now = System.currentTimeMillis()
        if (now - last < 10_000) return
        last = now
        runCatching {
            val id = api.folders().map { it.getString("id") }.firstOrNull { it.startsWith(ForkSync.ID_PREFIX) && ForkSync.kindOf(it) == "mar" } ?: return
            var t = System.nanoTime()
            val st = JSONObject(api.call("/rest/db/status?folder=$id"))
            val dbMs = (System.nanoTime() - t) / 1_000_000
            if (st.optLong("needFiles") <= 0) return
            t = System.nanoTime()
            val sys = JSONObject(api.call("/rest/system/status"))
            val sysMs = (System.nanoTime() - t) / 1_000_000
            val conns = JSONObject(api.call("/rest/system/connections")).optJSONObject("connections")
            var inB = 0L; var n = 0
            if (conns != null) for (k in conns.keys()) { val c = conns.getJSONObject(k); if (c.optBoolean("connected")) { inB += c.optLong("inBytesTotal"); n++ } }
            val din = if (lastIn == 0L) 0L else inB - lastIn
            lastIn = inB
            val pm = ctx.getSystemService(PowerManager::class.java)
            val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
            val bucket = if (Build.VERSION.SDK_INT >= 28) ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket else -1
            EmbeddedSync.note(ctx, "진단 state=${st.optString("state")} need=${st.optLong("needFiles")} pullErr=${st.optInt("pullErrors")} " +
                "tick=${tickMs}ms db=${dbMs}ms sys=${sysMs}ms conns=$n in=${din / 1024}KB/10s " +
                "go=${sys.optInt("goroutines")} alloc=${sys.optLong("alloc") / 1_000_000}MB cpu=${sys.optDouble("cpuPercent", -1.0)} " +
                "thermal=$thermal saver=${pm.isPowerSaveMode} idle=${pm.isDeviceIdleMode} bucket=$bucket")
        }
    }
}
