package kr.iamtalker.notes

import android.content.Context
import java.io.File

/** 앱이 죽으면 원인(스택)을 파일에 적어 두었다가 다음 실행 때 화면에 보여 준다 — 기기에서 로그를 볼 수 없을 때 원인을 알려고. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun save(ctx: Context, e: Throwable) {
        runCatching { file(ctx).writeText(android.util.Log.getStackTraceString(e)) }
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            save(app, e)
            old?.uncaughtException(t, e)
        }
    }

    /** 저장된 오류를 한 번만 돌려주고 지운다 */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text
    }
}
