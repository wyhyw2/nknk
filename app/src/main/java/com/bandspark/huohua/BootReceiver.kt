package com.bandspark.huohua

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机/应用更新后重新排定时任务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.autoEnabled(context)) {
            NotifyHelper.ensureChannel(context)
            SparkScheduler.schedule(context)
        }
    }
}
