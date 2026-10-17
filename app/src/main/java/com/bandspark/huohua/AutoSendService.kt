package com.bandspark.huohua

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebView
import androidx.core.app.ServiceCompat

/**
 * 后台续火服务：承载隐藏 WebView 运行自动化引擎。
 *
 * 触发方式：
 *   ACTION_AUTO          —— 定时任务直接执行
 *   ACTION_AUTO_DELAYED  —— 定时任务先发手环提醒，等 10 分钟无人响应再自动执行
 *   ACTION_BAND          —— 用户在手环上快捷回复，立即执行
 *
 * 会员门禁：服务的所有入口（自动/手环）都要求会员已开通。
 */
class AutoSendService : Service() {

    companion object {
        const val ACTION_AUTO = "com.bandspark.huohua.AUTO"
        const val ACTION_AUTO_DELAYED = "com.bandspark.huohua.AUTO_DELAYED"
        const val ACTION_BAND = "com.bandspark.huohua.BAND"

        private const val BAND_WAIT_MS = 10 * 60 * 1000L

        fun start(ctx: Context, action: String = ACTION_AUTO) {
            val i = Intent(ctx, AutoSendService::class.java).setAction(action)
            try {
                ctx.startForegroundService(i)
            } catch (_: Exception) {
                try {
                    ctx.startService(i)
                } catch (_: Exception) {
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var delayedRunnable: Runnable? = null
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        NotifyHelper.ensureChannel(this)
        try {
            ServiceCompat.startForeground(
                this,
                NotifyHelper.NOTIF_RUN,
                NotifyHelper.runningNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (_: Exception) {
            try {
                startForeground(NotifyHelper.NOTIF_RUN, NotifyHelper.runningNotification(this))
            } catch (_: Exception) {
            }
        }
        // 锁屏时保持 CPU 唤醒，防止 Doze 把 WebView 掐断（也是反检测：避免执行到一半卡死）
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bandspark:send")
            wakeLock?.acquire(5 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_AUTO
        when (action) {
            ACTION_BAND -> {
                delayedRunnable?.let { handler.removeCallbacks(it) }
                delayedRunnable = null
                Prefs.appendLog(this, "手环触发：立即续火")
                startRun("手环触发", false)
            }

            ACTION_AUTO_DELAYED -> {
                if (!Prefs.autoEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                delayedRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    if (Prefs.autoEnabled(this)) {
                        Prefs.appendLog(this, "手环无人响应，执行自动续火")
                        startRun("自动", true)
                    } else {
                        stopSelf()
                    }
                }
                delayedRunnable = r
                handler.postDelayed(r, BAND_WAIT_MS)
            }

            else -> {
                delayedRunnable?.let { handler.removeCallbacks(it) }
                delayedRunnable = null
                Prefs.appendLog(this, "定时任务触发：自动续火")
                startRun("自动", true)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRun(label: String, requireVip: Boolean) {
        if (started) return
        started = true
        if (requireVip && !VipManager.isVip(this)) {
            Prefs.appendLog(this, "未开通会员，跳过$label")
            NotifyHelper.resultNotification(this, false, "自动续火需开通会员（手环快捷面板免费）", 0)
            stopSelf()
            return
        }
        val targets = Prefs.targets(this)
        if (targets.isEmpty()) {
            Prefs.appendLog(this, "未配置续火好友")
            NotifyHelper.resultNotification(this, false, "未配置续火好友，请在 App 中添加", 0)
            stopSelf()
            return
        }
        val message = MessagePool.build(this)
        if (webView == null) webView = WebView(applicationContext)
        val engine = DouyinEngine(this, webView!!, object : DouyinEngine.Callback {
            override fun onLog(line: String) {
                Prefs.appendLog(this@AutoSendService, line)
            }

            override fun onDone(result: DouyinEngine.Result) {
                Prefs.appendLog(this@AutoSendService, result.detail)
                if (result.sentOk > 0) {
                    val days = Prefs.sparkDays(this@AutoSendService) + 1
                    Prefs.setSparkDays(this@AutoSendService, days)
                }
                Prefs.setLastSentAt(this@AutoSendService, System.currentTimeMillis())
                // 把续火结果回传手环火花面板
                SparkReceiver.sendSendResult(result.sentOk > 0, Prefs.sparkDays(this@AutoSendService))
                val total = result.sentOk + result.sentFail
                val text = "$label：成功 ${result.sentOk}/$total，" +
                        "火花第 ${Prefs.sparkDays(this@AutoSendService)} 天" +
                        if (result.missing.isNotEmpty()) "。未找到：${result.missing}" else ""
                NotifyHelper.resultNotification(
                    this@AutoSendService, result.sentOk > 0, text, result.sentOk
                )
                stopSelf()
            }
        })
        engine.runTargets(targets, message)
    }

    override fun onDestroy() {
        try {
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
