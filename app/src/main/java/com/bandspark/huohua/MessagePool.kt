package com.bandspark.huohua

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 话术库：分 随机/简短/情话 三类，支持手环“换一句”轮播与 [API] 一言占位。
 */
object MessagePool {

    val DEFAULTS: List<String> = listOf(
        "今日火花续上啦 🔥",
        "滴，续火卡 ✓",
        "在忙也要记得互相惦记哦",
        "今天的火花也不能断",
        "续个火，顺便想你一下",
        "打卡，明天也要一起哦",
        "火花+1 又是想你的一天",
        "晚安前先把火续上",
        "嘿嘿，今日份火花已送达",
        "不管多忙，火花不能断"
    )

    val LOVE_DEFAULTS: List<String> = listOf(
        "今天的火花，是我想你的证明 🔥",
        "续火成功，顺便说声想你",
        "忙归忙，想你这件事从没断过",
        "火花要续，你也要一直在我身边",
        "每天的坚持，都是因为是你"
    )

    val SHORT_DEFAULTS: List<String> = listOf(
        "续火 🔥",
        "滴，打卡",
        "火花+1",
        "在呢",
        "晚安，火花不断"
    )

    /** 取当前类别的话术池。 */
    fun poolFor(ctx: Context): List<String> {
        return when (Prefs.tplCategory(ctx)) {
            "love" -> Prefs.loveTemplates(ctx)
            "short" -> SHORT_DEFAULTS
            else -> Prefs.templates(ctx)
        }
    }

    /** 取当前轮播位置的话术（不推进）。 */
    fun current(ctx: Context): String {
        val pool = poolFor(ctx)
        if (pool.isEmpty()) return "续火花"
        return pool[Prefs.tplIndex(ctx) % pool.size]
    }

    /** 手环“换一句”：推进轮播并返回新话术。 */
    fun next(ctx: Context): String {
        val pool = poolFor(ctx)
        if (pool.isEmpty()) return "续火花"
        val next = (Prefs.tplIndex(ctx) + 1) % pool.size
        Prefs.setTplIndex(ctx, next)
        return pool[next]
    }

    /** 最终要发送的内容（[API] 占位替换为一言）。 */
    fun build(ctx: Context): String {
        val base = current(ctx)
        return if (base.contains("[API]")) {
            val api = fetchHitokoto() ?: "今日份小确幸：火花还在，你也在。"
            base.replace("[API]", api)
        } else {
            base
        }
    }

    /** 一言 API：返回一句话，失败返回 null（不阻塞主流程）。 */
    fun fetchHitokoto(): String? {
        return try {
            val conn = URL("https://v1.hitokoto.cn/?c=i&c=k&c=d").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val hitokoto = JSONObject(text).optString("hitokoto")
            hitokoto.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
