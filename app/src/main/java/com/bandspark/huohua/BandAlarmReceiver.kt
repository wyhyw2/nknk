package com.bandspark.huohua

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时闹钟入口：先发手环提醒，给用户 10 分钟在手环上一键续火；
 * 无人响应则由 AutoSendService 自动续火。同时排下一次任务。
 */
class BandAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.autoEnabled(context)) return
        SparkScheduler.schedule(context)
        NotifyHelper.ensureChannel(context)
        NotifyHelper.remindBand(context)
        AutoSendService.start(context, AutoSendService.ACTION_AUTO_DELAYED)
    }
}
