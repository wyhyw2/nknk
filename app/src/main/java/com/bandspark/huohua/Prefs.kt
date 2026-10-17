package com.bandspark.huohua

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置与状态存储（SharedPreferences 封装）。
 */
object Prefs {

    private const val NAME = "bandspark"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /* ---------------- 目标好友 ---------------- */

    fun targets(ctx: Context): MutableList<String> = readList(ctx, "targets")
    fun setTargets(ctx: Context, list: List<String>) = writeList(ctx, "targets", list)

    /* ---------------- 好友列表（登录后采集） ---------------- */

    fun conversations(ctx: Context): MutableList<String> = readList(ctx, "conversations")
    fun setConversations(ctx: Context, list: List<String>) = writeList(ctx, "conversations", list)

    /* ---------------- 话术库 ---------------- */

    fun templates(ctx: Context): MutableList<String> {
        val t = readList(ctx, "templates")
        return if (t.isEmpty()) MessagePool.DEFAULTS.toMutableList() else t
    }
    fun setTemplates(ctx: Context, list: List<String>) = writeList(ctx, "templates", list)

    fun loveTemplates(ctx: Context): MutableList<String> {
        val t = readList(ctx, "loveTemplates")
        return if (t.isEmpty()) MessagePool.LOVE_DEFAULTS.toMutableList() else t
    }
    fun setLoveTemplates(ctx: Context, list: List<String>) = writeList(ctx, "loveTemplates", list)

    /** 当前话术类别：random / short / love */
    fun tplCategory(ctx: Context) = sp(ctx).getString("tplCategory", "random") ?: "random"
    fun setTplCategory(ctx: Context, v: String) = sp(ctx).edit().putString("tplCategory", v).apply()

    /** 话术轮播索引（手环“换一句”用到） */
    fun tplIndex(ctx: Context) = sp(ctx).getInt("tplIndex", 0)
    fun setTplIndex(ctx: Context, v: Int) = sp(ctx).edit().putInt("tplIndex", v).apply()

    private fun readList(ctx: Context, key: String): MutableList<String> {
        val s = sp(ctx).getString(key, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { arr.optString(it) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeList(ctx: Context, key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp(ctx).edit().putString(key, arr.toString()).apply()
    }

    /* ---------------- 自动续火 ---------------- */

    fun autoEnabled(ctx: Context) = sp(ctx).getBoolean("auto", false)
    fun setAutoEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto", v).apply()

    fun sendStartHour(ctx: Context) = sp(ctx).getInt("startHour", 8)
    fun setSendStartHour(ctx: Context, v: Int) = sp(ctx).edit().putInt("startHour", v).apply()

    fun sendEndHour(ctx: Context) = sp(ctx).getInt("endHour", 22)
    fun setSendEndHour(ctx: Context, v: Int) = sp(ctx).edit().putInt("endHour", v).apply()

    /* ---------------- 火花状态 ---------------- */

    fun sparkDays(ctx: Context) = sp(ctx).getInt("sparkDays", 0)
    fun setSparkDays(ctx: Context, v: Int) = sp(ctx).edit().putInt("sparkDays", v).apply()

    fun lastSentAt(ctx: Context) = sp(ctx).getLong("lastSentAt", 0)
    fun setLastSentAt(ctx: Context, v: Long) = sp(ctx).edit().putLong("lastSentAt", v).apply()

    fun lastSentText(ctx: Context): String {
        val t = lastSentAt(ctx)
        return if (t > 0) SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(t)) else "无"
    }

    /* ---------------- 登录状态（用于常驻手环面板） ---------------- */

    fun loggedIn(ctx: Context) = sp(ctx).getBoolean("loggedIn", false)
    fun setLoggedIn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("loggedIn", v).apply()

    /* ---------------- 会员（微验） ---------------- */

    fun cardKey(ctx: Context) = sp(ctx).getString("cardKey", null)
    fun setCardKey(ctx: Context, v: String?) = sp(ctx).edit().putString("cardKey", v).apply()

    fun expireAt(ctx: Context) = sp(ctx).getLong("expireAt", 0)
    fun setExpireAt(ctx: Context, v: Long) = sp(ctx).edit().putLong("expireAt", v).apply()

    fun lastVerifyAt(ctx: Context) = sp(ctx).getLong("lastVerifyAt", 0)
    fun setLastVerifyAt(ctx: Context, v: Long) = sp(ctx).edit().putLong("lastVerifyAt", v).apply()

    /* ---------------- 日志 ---------------- */

    fun logs(ctx: Context): String = sp(ctx).getString("logs", "") ?: ""

    fun appendLog(ctx: Context, line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        val old = logs(ctx)
        val new = (old + "\n[$t] $line").takeLast(6000)
        sp(ctx).edit().putString("logs", new).apply()
    }

    fun clearLogs(ctx: Context) = sp(ctx).edit().remove("logs").apply()
}
