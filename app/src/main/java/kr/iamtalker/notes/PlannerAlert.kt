package kr.iamtalker.notes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.LocalDate

/** 그날이 되면 알림: 할일 날짜가 오늘 이하이고 안 끝낸 것을 하루 한 번 알린다(PlannerCalc.due, PC 와 같은 규칙).
 *  1시간마다 깨는 부정확 반복 알람 + 앱을 열 때 확인. 알린 기록은 `.planner/data.json` 의 notified 를 PC 와 같이 쓴다. */
object PlannerAlert {
    private const val CHANNEL = "planner"

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, PlannerAlertReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 60_000, AlarmManager.INTERVAL_HOUR, pi)
    }

    /** 오늘 알릴 것이 있으면 알림을 올리고 알린 기록을 남긴다 → 알린 개수 */
    fun check(ctx: Context): Int {
        val tree = ctx.getSharedPreferences("settings", 0).getString("tree", null)?.let(Uri::parse) ?: return 0
        val vault = ForkSync.treePath(tree) ?: return 0
        val store = PlannerStore(vault)
        val today = LocalDate.now().toString()
        val due = PlannerCalc.due(store.todos, today, store.notified)
        if (due.isEmpty()) return 0
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "달력·할일", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java).putExtra("screen", "planner"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = due.joinToString("\n") { (it.date?.substring(5) ?: "") + " " + it.text }
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("오늘 할일 ${due.size}개").setContentText(due.first().text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(4100, n) }
        store.markNotified(due.map { it.id }, today)
        return due.size
    }
}

class PlannerAlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        runCatching { PlannerAlert.check(ctx) }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { PlannerAlert.schedule(ctx) }
    }
}
