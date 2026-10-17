package com.bandspark.huohua

import android.content.Context
import org.json.JSONObject

/**
 * 会员状态管理：微验网络验证 + 本地离线宽限。
 *
 * 设计：
 *   1. 激活时调微验 login，成功后在本地缓存 卡密 + 到期时间 + 最后验证时间；
 *   2. 日常 isVip() 只读本地到期时间（无网也能用，体验好）；
 *   3. 距上次验证超过 12 小时，后台异步 heartbeat 复核一次：
 *      卡密被封/过期 → 立即锁死；网络失败 → 不影响本次（下次再验）。
 *
 * 这样既解决“离线密钥可被提取”的问题（密钥在微验服务器），
 * 又不会因为用户偶尔没网就把会员锁死。
 */
object VipManager {

    private const val HEARTBEAT_INTERVAL_MS = 12 * 3600_000L
    private const val FALLBACK_DAYS = 30L

    /** 激活会员（网络验证）。回调在后台线程，UI 需自行切线程。 */
    fun activate(ctx: Context, key: String, cb: (Boolean, String) -> Unit) {
        val machine = WeiYanClient.machineCode(ctx)
        WeiYanClient.login(key, machine) { r ->
            if (r.code == 0) {
                val expireAt = parseExpire(r.data)
                    ?: (System.currentTimeMillis() + FALLBACK_DAYS * 86_400_000L)
                Prefs.setCardKey(ctx, key.trim())
                Prefs.setExpireAt(ctx, expireAt)
                Prefs.setLastVerifyAt(ctx, System.currentTimeMillis())
                cb(true, "激活成功")
            } else {
                cb(false, r.msg)
            }
        }
    }

    fun isVip(ctx: Context): Boolean {
        val key = Prefs.cardKey(ctx) ?: return false
        val expireAt = Prefs.expireAt(ctx)
        if (System.currentTimeMillis() >= expireAt) return false
        // 定时心跳：封卡/退卡能及时生效
        if (System.currentTimeMillis() - Prefs.lastVerifyAt(ctx) > HEARTBEAT_INTERVAL_MS) {
            heartbeat(ctx, key)
        }
        return true
    }

    fun vipDaysLeft(ctx: Context): Long {
        val expireAt = Prefs.expireAt(ctx)
        if (expireAt <= 0) return -1
        return (expireAt - System.currentTimeMillis()) / 86_400_000L
    }

    fun logout(ctx: Context) {
        Prefs.setCardKey(ctx, null)
        Prefs.setExpireAt(ctx, 0)
        Prefs.setLastVerifyAt(ctx, 0)
    }

    /* ================= 内部 ================= */

    private fun heartbeat(ctx: Context, key: String) {
        val machine = WeiYanClient.machineCode(ctx)
        WeiYanClient.heartbeat(key, machine) { r ->
            when (r.code) {
                0 -> {
                    val expireAt = parseExpire(r.data)
                    if (expireAt != null) Prefs.setExpireAt(ctx, expireAt)
                    Prefs.setLastVerifyAt(ctx, System.currentTimeMillis())
                }
                // 明确失败（卡密被封/过期/解绑）→ 锁死；网络异常(-999)忽略，下次再验
                else -> if (r.code != -999) {
                    Prefs.setExpireAt(ctx, 0)
                }
            }
        }
    }

    /** 兼容微验不同版本返回的到期字段（秒/毫秒都可能）。 */
    private fun parseExpire(data: JSONObject?): Long? {
        if (data == null) return null
        val keys = listOf(
            "endTime", "endtime", "expireTime", "expire_time",
            "expire", "end_time", "到期时间", "endDate"
        )
        for (k in keys) {
            if (data.isNull(k)) continue
            val v = data.opt(k)
            if (v is Number) {
                var t = v.toLong()
                if (t > 0 && t < 10_000_000_000L) t *= 1000L
                if (t > System.currentTimeMillis()) return t
            } else if (v is String && v.isNotBlank()) {
                val t = v.toLongOrNull()
                if (t != null) {
                    var ms = t
                    if (ms > 0 && ms < 10_000_000_000L) ms *= 1000L
                    if (ms > System.currentTimeMillis()) return ms
                }
            }
        }
        return null
    }
}
