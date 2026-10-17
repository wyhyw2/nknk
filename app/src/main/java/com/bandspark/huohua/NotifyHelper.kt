package com.bandspark.huohua

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * 通知中心。
 *
 * 两个核心：
 *   1. 常驻手环快捷面板（NOTIF_QUICK，ongoing）——登录后手环通知列表里永远可见，
 *      随时快捷回复：发/换/情话/简短/随机/任意文字，无需会员、无需定时、无需开自动。
 *      这就是 9 Pro 上能做到的最接近“手环端 App”的形态（系统限制，无法真装 App）。
 *   2. 一次性「火花待续」提醒（NOTIF_BAND）+ 结果通知。
 */
object NotifyHelper {

    const val CHANNEL_RUN = "bandspark_run"
    const val CHANNEL_BAND = "bandspark_band"
    const val NOTIF_RUN = 1001
    const val NOTIF_BAND = 1002
    const val NOTIF_QUICK = 1003
    const val KEY_BAND_REPLY = "bandspark_reply"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUN, "续火任务", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_BAND, "手环续火提醒", NotificationManager.IMPORTANCE_HIGH)
                    .apply { enableVibration(true) }
            )
        }
    }

    private fun openPi(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            2002,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun replyPi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            2001,
            Intent(ctx, BandReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /* ---------------- 常驻手环快捷面板（核心） ---------------- */

    fun quickPanelNotification(ctx: Context): Notification {
        val remoteInput = RemoteInput.Builder(KEY_BAND_REPLY)
            .setLabel("发/换/情话/简短/随机")
            .setChoices(arrayOf("发", "换", "情话", "简短", "随机"))
            .build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "快捷操作", replyPi(ctx)
        ).addRemoteInput(remoteInput).build()

        val phrase = MessagePool.current(ctx)
        val big = "今日话术：$phrase\n" +
                "手环回复：发=发送  换=换一句  情话/简短/随机=切换库\n" +
                "手输任意文字=自定义话术并发送"
        return NotificationCompat.Builder(ctx, CHANNEL_BAND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🔥 火花管家 · 手环面板")
            .setContentText("今日话术：$phrase")
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPi(ctx))
            .addAction(action)
            .build()
    }

    fun showQuickPanel(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_QUICK, quickPanelNotification(ctx))
        } catch (_: Exception) {
        }
    }

    fun hideQuickPanel(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_QUICK)
        } catch (_: Exception) {
        }
    }

    /* ---------------- 运行中 / 提醒 / 结果 ---------------- */

    fun runningNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("火花管家运行中")
            .setContentText("正在自动续火…")
            .setOngoing(true)
            .setContentIntent(openPi(ctx))
            .build()

    fun bandRemindNotification(ctx: Context): Notification {
        val remoteInput = RemoteInput.Builder(KEY_BAND_REPLY)
            .setLabel("发/换/情话/简短/随机")
            .setChoices(arrayOf("发", "换", "情话", "简短", "随机"))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "快捷操作", replyPi(ctx)
        ).addRemoteInput(remoteInput).build()

        val me = Person.Builder().setName("火花管家").build()
        val phrase = MessagePool.current(ctx)
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("火花管家 · 手表快捷编辑")
            .addMessage("今日话术：$phrase", System.currentTimeMillis(), me)
            .addMessage("回复「发」发送，「换」换一句，「情话/简短/随机」切换库",
                System.currentTimeMillis() - 1000, me)

        return NotificationCompat.Builder(ctx, CHANNEL_BAND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🔥 火花待续（第 ${Prefs.sparkDays(ctx)} 天）")
            .setContentText("今日话术：$phrase")
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi(ctx))
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(replyAction)
            .build()
    }

    fun resultNotification(ctx: Context, ok: Boolean, text: String, sentCount: Int) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_BAND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (ok) "🔥 续火成功（$sentCount 位好友）" else "⚠️ 续火异常")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (ok) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_BAND, n)
        } catch (_: Exception) {
        }
    }

    fun remindBand(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_BAND, bandRemindNotification(ctx))
        } catch (_: Exception) {
        }
    }

    fun cancelBand(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_BAND)
        } catch (_: Exception) {
        }
    }
}
