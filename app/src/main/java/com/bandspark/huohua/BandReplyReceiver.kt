package com.bandspark.huohua

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * 手环快捷操作入口（手环“快捷回复面板”的处理逻辑）。
 *
 * 用户在小米手环上对「火花管家 · 手环面板」或「火花待续」通知快捷回复：
 *   「发 / 续 / 续火 / 1 / 🔥」 → 立即用当前话术发送
 *   「换 / 换一句」          → 换一句随机话术（面板同步刷新，手环上可见新话术）
 *   「情话 / 简短 / 随机」    → 切换话术库并刷新
 *   其它任意文字            → 当作自定义话术存入话术库并立即发送
 *   按钮（无文字）           → 立即发送当前话术
 *
 * 常驻面板不消失：每次操作后自动刷新（同 ID 更新）。
 */
class BandReplyReceiver : BroadcastReceiver() {

    private val sendWords = setOf("发", "发送", "续", "续火", "1", "🔥", "ok", "OK", "好", "发消息")
    private val nextWords = setOf("换", "换一句", "下一句", "换话术", "2")
    private val loveWords = setOf("情话", "3")
    private val shortWords = setOf("简短", "4")
    private val randomWords = setOf("随机", "5")

    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotifyHelper.KEY_BAND_REPLY)
            ?.toString()
            ?.trim()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NotifyHelper.NOTIF_BAND)

        when {
            reply.isNullOrEmpty() -> triggerSend(context, "按钮触发")
            reply in sendWords -> triggerSend(context, "手环指令：发送")
            reply in nextWords -> {
                val phrase = MessagePool.next(context)
                Prefs.appendLog(context, "手环换一句 → $phrase")
            }
            reply in loveWords -> switchCategory(context, "love")
            reply in shortWords -> switchCategory(context, "short")
            reply in randomWords -> switchCategory(context, "random")
            else -> {
                val list = Prefs.templates(context)
                if (!list.contains(reply) && list.size < 50) {
                    list.add(0, reply)
                    Prefs.setTemplates(context, list)
                }
                Prefs.setTplIndex(context, 0)
                Prefs.appendLog(context, "手环自定义话术 → $reply")
                triggerSend(context, "手环自定义话术")
            }
        }
        // 常驻面板始终刷新（同 ID 更新，手环上立刻看到新状态）
        NotifyHelper.showQuickPanel(context)
    }

    private fun switchCategory(context: Context, cat: String) {
        Prefs.setTplCategory(context, cat)
        Prefs.setTplIndex(context, 0)
        Prefs.appendLog(context, "手环切换话术类别 → $cat，当前：${MessagePool.current(context)}")
    }

    private fun triggerSend(context: Context, label: String) {
        Prefs.appendLog(context, "$label：立即续火")
        AutoSendService.start(context, AutoSendService.ACTION_BAND)
    }
}
