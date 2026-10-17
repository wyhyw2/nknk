package com.bandspark.huohua

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.Locale
import java.util.Random
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 每日随机时间调度：在 [sendStartHour, sendEndHour) 窗口内随机取一分钟，
 * 用精确闹钟（可休眠唤醒）触发 BandAlarmReceiver。
 */
object SparkScheduler {

    private const val REQ = 3001

    fun schedule(ctx: Context) {
        if (!Prefs.autoEnabled(ctx)) {
            cancel(ctx)
            return
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(ctx)
        val trigger = nextTrigger(ctx)
        try {
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            Prefs.appendLog(ctx, "已安排下次自动续火：${fmt.format(Date(trigger))}")
        } catch (_: Exception) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.cancel(pendingIntent(ctx))
        } catch (_: Exception) {
        }
    }

    private fun pendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            REQ,
            Intent(ctx, BandAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 下一次触发时间：今天窗口内随机；今天窗口已过则随机到明天。 */
    fun nextTrigger(ctx: Context): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.sendStartHour(ctx))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val rangeMinutes = ((Prefs.sendEndHour(ctx) - Prefs.sendStartHour(ctx) + 24) % 24) * 60
        val offset = if (rangeMinutes > 0) Random().nextInt(rangeMinutes) else 0
        cal.add(Calendar.MINUTE, offset)
        return cal.timeInMillis
    }
}
