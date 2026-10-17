package com.bandspark.huohua

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 抖音网页版 IM 自动化引擎（Android WebView 版）。
 *
 * 移植自 DouYinSparkFlow core/douyin_im.py（MIT License），流程一致：
 *   门禁（登录态 + 列表就绪）→ 回顶 → 边滚边采集虚拟列表窗口 →
 *   按 备注/昵称/抖音号/uid 匹配目标 → 选中校验 conv_id →
 *   Draft.js 逐行合成输入 → 点发送 → HTTP/DOM 双回执确认。
 *
 * 与 Python 版的主要差异：
 *   - Python 用 Playwright 监听 /im/user/info 合并好友资料；
 *     本版用 JS 直接读 React fiber 的 conversation 模型（title 本身就是备注优先）。
 *   - 真实鼠标键盘事件不可用，全部走 JS 合成事件（Python 的 synth 路径）。
 */
class DouyinEngine(
    private val ctx: Context,
    private val webView: WebView,
    private val cb: Callback
) {

    interface Callback {
        fun onLog(line: String)
        fun onDone(result: Result)
    }

    data class Result(
        val ok: Boolean,
        val state: String,
        val sentOk: Int,
        val sentFail: Int,
        val found: List<String>,
        val missing: List<String>,
        val detail: String
    )

    data class Conv(
        val index: Int,
        val convId: String?,
        val uid: String?,
        val secUid: String?,
        val shortId: String?,
        val title: String?,
        val isCurrent: Boolean,
        val dataIndex: Int?,
        val isGroup: Boolean
    )

    companion object {
        const val SCAN_TIMEOUT_MS = 120_000L
        const val MAX_STEPS = 200
        const val RECEIPT_TIMEOUT_MS = 20_000L
        const val READY_TIMEOUT_MS = 90_000L
        const val ROW_HEIGHT = 67

        /** 页面 JS 网络钩子上报通道（JsBridge 写入，主线程读）。 */
        @Volatile
        var netListener: ((status: Int) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val collectBusy = AtomicBoolean(false)

    private var pageLoaded = false
    private var hooked = false
    private var stealthInjected = false
    private var done = false

    // ---- 单次任务状态 ----
    private val seen = mutableSetOf<String>()
    private var pending = mutableSetOf<String>()
    private var pendingRaw = emptyMap<String, String>()
    private var found = mutableListOf<String>()
    private var sentOk = 0
    private var sentFail = 0
    private var failSelect = mutableListOf<String>()
    private var steps = 0
    private var message = ""
    private var startAt = 0L

    /* ================= WebView 准备 ================= */

    @SuppressLint("SetJavaScriptEnabled")
    fun attach() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = DouyinJs.PC_UA
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(JsBridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                if (!stealthInjected) {
                    stealthInjected = true
                    val stealth = loadAsset("js/stealth.js")
                    if (stealth != null) eval(stealth) { }
                }
                if (!hooked) {
                    hooked = true
                    eval(DouyinJs.CALL_HOOK_NET) { }
                }
            }
        }
    }

    fun ensurePage() {
        if (!pageLoaded) {
            cb.onLog("正在打开抖音聊天页…")
            webView.loadUrl(DouyinJs.CHAT_URL)
        }
    }

    fun reloadPage() {
        pageLoaded = false
        hooked = false
        stealthInjected = false
        webView.loadUrl(DouyinJs.CHAT_URL)
    }

    /* ================= 登录态检查（供首页状态显示/扫码登录用） ================= */

    fun checkLogin(timeoutMs: Long = 20_000L, onResult: (String) -> Unit) {
        ensurePage()
        // 以 sessionid Cookie 为准判断登录态（不依赖聊天页 DOM，扫码后页面跳转也能识别）
        handler.postDelayed({
            eval(DouyinJs.CALL_LOGIN_DOM) { raw ->
                val dom = parseObj(raw)
                val hasSession = hasSessionCookie()
                val state = when {
                    !hasSession -> "NOT_LOGGED_IN"
                    dom != null && dom.optBoolean("loginVisible", false) -> "EXPIRED"
                    else -> "LOGGED_IN"
                }
                if (state == "LOGGED_IN") CookieManager.getInstance().flush()
                onResult(state)
            }
        }, 800)
    }

    /* ================= 主任务 ================= */

    fun runTargets(targets: List<String>, msg: String) {
        if (!running.compareAndSet(false, true)) {
            cb.onLog("已有任务正在运行，忽略本次请求")
            return
        }
        // 看门狗：无论 eval 回调是否丢失，最多 5 分钟强制释放 running，
        // 避免 eval 回调丢失导致 running 永久 true、后续任务全被拒
        handler.postDelayed({
            if (running.get() && !done) {
                cb.onLog("⚠️ 任务超时（5min），已强制释放")
                done = true
                running.set(false)
                netListener = null
                cb.onDone(Result(ok = false, state = "timeout", sentOk = sentOk, sentFail = sentFail,
                    found = found.toList(), missing = failSelect.toList(),
                    detail = "任务超时强制结束（已发送 $sentOk）"))
            }
        }, 300_000L)
        done = false
        message = msg
        pendingRaw = targets.associateBy { norm(it) }
        pending = pendingRaw.keys.toMutableSet()
        found.clear()
        sentOk = 0
        sentFail = 0
        failSelect.clear()
        seen.clear()
        steps = 0
        startAt = SystemClock.elapsedRealtime()
        netListener = null

        cb.onLog("任务启动：目标 ${targets.size} 个好友")
        ensurePage()
        waitListReady(READY_TIMEOUT_MS) { ready ->
            if (!ready) {
                finish(Result(false, "TIMEOUT", 0, 0, emptyList(), targets,
                    "页面加载超时，请检查网络后重试"))
                return@waitListReady
            }
            eval(DouyinJs.CALL_LOGIN_DOM) { raw ->
                val dom = parseObj(raw)
                if (dom == null) {
                    finish(Result(false, "UNKNOWN", 0, 0, emptyList(), targets,
                        "无法读取页面状态"))
                    return@eval
                }
                if (dom.optBoolean("loginVisible", false)) {
                    val st = if (hasSessionCookie()) "EXPIRED" else "NOT_LOGGED_IN"
                    finish(Result(false, st, 0, 0, emptyList(), targets,
                        "未登录或登录已失效，请先点击「登录抖音」扫码"))
                    return@eval
                }
                eval(DouyinJs.CALL_SCROLL_TO + "(0)") {
                    handler.postDelayed({ walkRound() }, 600)
                }
            }
        }
    }

    /**
     * 采集全部会话标题（登录后导入好友列表用）。
     * 遍历会话列表，返回非群聊的标题列表（已去重）。回调在主线程。
     */
    fun collectConversations(onDone: (List<String>) -> Unit) {
        // 用独立标志，不与 runTargets 互斥：自动续火跑着也能导入好友
        if (!collectBusy.compareAndSet(false, true)) {
            cb.onLog("正在导入中，请稍候")
            onDone(emptyList())
            return
        }
        val titles = LinkedHashMap<String, String>() // convId -> title
        var steps = 0
        val startAt = SystemClock.elapsedRealtime()
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            collectBusy.set(false)
            onDone(titles.values.filter { it.isNotBlank() })
        }

        // 看门狗：无论 eval 回调是否丢失，最多 60 秒强制收尾并释放标志
        val watchdog = object : Runnable {
            override fun run() {
                if (!finished) {
                    cb.onLog("⚠️ 导入超时（60s），已强制结束")
                    finish()
                }
            }
        }
        handler.postDelayed(watchdog, 60_000L)

        fun walk() {
            if (finished) return
            if (SystemClock.elapsedRealtime() - startAt > SCAN_TIMEOUT_MS || steps >= MAX_STEPS) {
                finish()
                return
            }
            eval(DouyinJs.CALL_COLLECT) { raw ->
                if (finished) return@eval
                cb.onLog("好友采集原始(前200): ${(raw ?: "null").take(200)}")
                val items = parseConvArray(raw)
                if (items == null) {
                    finish()
                    return@eval
                }
                for (item in items) {
                    if (item.convId != null && !item.isGroup && !titles.containsKey(item.convId)) {
                        val t = item.title
                        if (!t.isNullOrBlank()) titles[item.convId] = t
                    }
                }
                eval(DouyinJs.CALL_SCROLL_PROBE) { raw2 ->
                    if (finished) return@eval
                    val probe = parseObj(raw2)
                    if (probe == null || !probe.optBoolean("found", false)) {
                        finish()
                        return@eval
                    }
                    if (probe.optBoolean("atBottom", false)) {
                        handler.postDelayed({
                            eval(DouyinJs.CALL_SCROLL_PROBE) { raw3 ->
                                if (finished) return@eval
                                val after = parseObj(raw3)
                                if (after == null ||
                                    after.optInt("scrollHeight", 0) <= probe.optInt("scrollHeight", 0)
                                ) {
                                    finish()
                                } else {
                                    steps++
                                    val client = probe.optInt("clientHeight", 600)
                                    val step = maxOf(120, (client * 0.4).toInt())
                                    val next = minOf(
                                        probe.optInt("scrollTop", 0) + step,
                                        maxOf(0, probe.optInt("scrollHeight", 0) - client)
                                    )
                                    eval(DouyinJs.CALL_SCROLL_TO + "($next)") {
                                        handler.postDelayed({ walk() }, 400)
                                    }
                                }
                            }
                        }, 1000)
                    } else {
                        steps++
                        val client = probe.optInt("clientHeight", 600)
                        val step = maxOf(120, (client * 0.4).toInt())
                        val next = minOf(
                            probe.optInt("scrollTop", 0) + step,
                            maxOf(0, probe.optInt("scrollHeight", 0) - client)
                        )
                        eval(DouyinJs.CALL_SCROLL_TO + "($next)") {
                            handler.postDelayed({ walk() }, 400)
                        }
                    }
                }
            }
        }

        reloadPage()
        waitListReady(READY_TIMEOUT_MS) { ready ->
            cb.onLog("好友采集：列表就绪=$ready")
            if (!ready) {
                cb.onLog("⚠️ 会话列表未就绪（可能页面没加载出来）")
                finish()
                return@waitListReady
            }
            eval(DouyinJs.CALL_LOGIN_DOM) { raw ->
                if (finished) return@eval
                if (!hasSessionCookie()) {
                    cb.onLog("未登录，无法导入好友")
                    finish()
                    return@eval
                }
                eval(DouyinJs.CALL_SCROLL_TO + "(0)") { handler.postDelayed({ walk() }, 600) }
            }
        }
    }

    /* ================= 滚动扫描 ================= */

    private fun walkRound() {
        if (done || !running.get()) return
        if (pending.isEmpty()) {
            finishScan("all-found")
            return
        }
        if (SystemClock.elapsedRealtime() - startAt > SCAN_TIMEOUT_MS) {
            finishScan("timeout")
            return
        }
        if (steps >= MAX_STEPS) {
            finishScan("max-steps")
            return
        }
        eval(DouyinJs.CALL_COLLECT) { raw ->
            val items = parseConvArray(raw)
            if (items == null) {
                cb.onLog("⚠️ 读取会话窗口失败，稍后重试")
                handler.postDelayed({ walkRound() }, 800)
                return@eval
            }
            val fresh = items.filter { it.convId != null && seen.add(it.convId!!) }
            if (fresh.isNotEmpty()) {
                cb.onLog("扫描中…已见 ${seen.size} 个会话（步数 $steps）")
            }
            processBatch(fresh) { advance() }
        }
    }

    private fun processBatch(list: List<Conv>, onDone: () -> Unit) {
        if (done || !running.get()) return
        if (list.isEmpty() || pending.isEmpty()) {
            onDone()
            return
        }
        val item = list.first()
        val rest = list.drop(1)
        if (item.isGroup) {
            processBatch(rest, onDone)
            return
        }
        val key = match(item)
        if (key == null) {
            processBatch(rest, onDone)
            return
        }
        pending.remove(key)
        found.add(key)
        cb.onLog("命中好友：${item.title ?: "(无标题)"}，准备发送")
        selectAndVerify(item) { ok ->
            if (!ok) {
                failSelect.add(item.title ?: "?")
                cb.onLog("⚠️ 选中失败：${item.title}，继续扫描")
                processBatch(rest, onDone)
            } else {
                typeAndSend(item, message) { sent ->
                    if (sent) {
                        sentOk++
                        cb.onLog("✅ 发送成功：${item.title}")
                    } else {
                        sentFail++
                        cb.onLog("❌ 未确认成功：${item.title}")
                    }
                    handler.postDelayed({
                        if (pending.isEmpty()) finishScan("all-found")
                        else processBatch(rest, onDone)
                    }, 800)
                }
            }
        }
    }

    private fun advance() {
        if (done || !running.get()) return
        eval(DouyinJs.CALL_SCROLL_PROBE) { raw ->
            val probe = parseObj(raw)
            if (probe == null || !probe.optBoolean("found", false)) {
                finishScan("no-container")
                return@eval
            }
            if (probe.optBoolean("atBottom", false)) {
                handler.postDelayed({
                    eval(DouyinJs.CALL_SCROLL_PROBE) { raw2 ->
                        val after = parseObj(raw2)
                        if (after == null ||
                            after.optInt("scrollHeight", 0) <= probe.optInt("scrollHeight", 0)
                        ) {
                            finishScan("reach-bottom")
                        } else {
                            scrollNext(probe)
                        }
                    }
                }, 1000)
            } else {
                scrollNext(probe)
            }
        }
    }

    private fun scrollNext(probe: JSONObject) {
        val client = probe.optInt("clientHeight", 600)
        val step = maxOf(120, (client * 0.4).toInt())
        val next = minOf(
            probe.optInt("scrollTop", 0) + step,
            maxOf(0, probe.optInt("scrollHeight", 0) - client)
        )
        steps++
        eval(DouyinJs.CALL_SCROLL_TO + "($next)") {
            handler.postDelayed({ walkRound() }, 400 + rand(0, 300))
        }
    }

    private fun finishScan(stopped: String) {
        val missing = pending.map { pendingRaw[it] ?: it }
        val foundRaw = found.map { pendingRaw[it] ?: it }
        val ok = found.isNotEmpty() && sentOk > 0 && sentFail == 0 && pending.isEmpty()
        val detail = "扫描结束($stopped)：发送成功 $sentOk，失败 $sentFail，" +
                "未找到 ${missing.ifEmpty { listOf("无") }}"
        finish(Result(ok, "LOGGED_IN", sentOk, sentFail, foundRaw, missing, detail))
    }

    private fun finish(result: Result) {
        if (done) return
        done = true
        running.set(false)
        netListener = null
        cb.onDone(result)
    }

    /* ================= 匹配 ================= */

    private fun match(item: Conv): String? {
        for (f in listOf(item.title, item.uid, item.secUid, item.shortId)) {
            val n = norm(f)
            if (n.isNotEmpty() && pending.contains(n)) return n
        }
        val joined = listOf(item.title, item.uid, item.secUid, item.shortId)
            .joinToString(" ") { norm(it) }
        for (n in pending) {
            if (joined.contains(n)) return n
        }
        return null
    }

    /* ================= 选中与校验 ================= */

    private fun selectAndVerify(item: Conv, onDone: (Boolean) -> Unit) {
        selectAttempt(item, 1, onDone)
    }

    private fun selectAttempt(item: Conv, attempt: Int, onDone: (Boolean) -> Unit) {
        if (done || attempt > 3) {
            onDone(false)
            return
        }
        val js = DouyinJs.CALL_SELECT_INDEX + "(${item.index})"
        eval(js) { r ->
            if (r == "true") {
                verifySelection(item, attempt, onDone)
            } else {
                // 条目可能已滚出窗口：按 dataIndex 估算位置滚回去
                val top = (item.dataIndex ?: 0) * ROW_HEIGHT
                eval(DouyinJs.CALL_SCROLL_TO + "($top)") {
                    handler.postDelayed({ selectAttempt(item, attempt + 1, onDone) }, 900)
                }
            }
        }
    }

    private fun verifySelection(item: Conv, attempt: Int, onDone: (Boolean) -> Unit) {
        if (done || attempt > 3) {
            onDone(false)
            return
        }
        var httpHit = false
        netListener = { status -> if (status == 200) httpHit = true }
        poll(
            js = DouyinJs.CALL_CURRENT_CONV,
            timeoutMs = 5000,
            intervalMs = 250,
            ready = { raw ->
                val cur = parseObj(raw) ?: return@poll false
                val cid = cur.optString("convId", "")
                cid == (item.convId ?: "") ||
                        (cid.isEmpty() && !cur.isNull("title"))
            },
            onOk = {
                netListener = null
                onDone(true)
            },
            onTimeout = {
                netListener = null
                if (httpHit) {
                    onDone(true)
                } else {
                    val js = DouyinJs.CALL_CLICK_SELECT_INDEX + "(${item.index})"
                    eval(js) { r ->
                        if (r == "true") verifySelection(item, attempt + 1, onDone)
                        else onDone(false)
                    }
                }
            }
        )
    }

    /* ================= 输入与发送 ================= */

    private fun typeAndSend(item: Conv, msg: String, onDone: (Boolean) -> Unit) {
        eval(DouyinJs.CALL_EDITOR_EMPTY) { raw ->
            val isClean = raw == "true"
            val thenType = {
                typeLines(item, splitLines(msg), 0, onDone)
            }
            if (isClean) {
                thenType()
            } else {
                // 草稿残留：先清空再输入（与 Python 版一致）
                eval(DouyinJs.CALL_EDITOR_CLEAR) {
                    handler.postDelayed(thenType, 250)
                }
            }
        }
    }

    private fun typeLines(item: Conv, lines: List<String>, idx: Int, onDone: (Boolean) -> Unit) {
        if (done) return
        if (idx >= lines.size) {
            clickSendAndWait(item, onDone)
            return
        }
        val p = JSONObject()
            .put("text", lines[idx])
            .put("enter", idx != lines.size - 1)
        val js = DouyinJs.CALL_TYPE_LINE + "(JSON.parse(" +
                JSONObject.quote(p.toString()) + "))"
        eval(js) {
            // Draft.js 每次 beforeinput 都重渲染，必须逐行、行间留时间（加随机抖动）
            handler.postDelayed({ typeLines(item, lines, idx + 1, onDone) }, 250 + rand(0, 400))
        }
    }

    private fun clickSendAndWait(item: Conv, onDone: (Boolean) -> Unit) {
        eval(DouyinJs.CALL_MSG_STATE) { raw ->
            val before = parseObj(raw)?.optInt("count", 0) ?: 0
            eval(DouyinJs.CALL_CLICK_SEND) { _ ->
                var httpSeen = false
                netListener = { status -> if (status == 200) httpSeen = true }
                poll(
                    js = DouyinJs.CALL_MSG_STATE,
                    timeoutMs = RECEIPT_TIMEOUT_MS,
                    intervalMs = 400,
                    ready = { raw2 ->
                        val after = parseObj(raw2) ?: return@poll false
                        after.optBoolean("lastFromMe", false) &&
                                after.optInt("count", 0) > before
                    },
                    onOk = {
                        netListener = null
                        onDone(true)
                    },
                    onTimeout = {
                        netListener = null
                        cb.onLog("⚠️ ${item.title} 未收到 DOM 回执（可能走了 WebSocket 通道）")
                        onDone(httpSeen)
                    }
                )
            }
        }
    }

    /* ================= 工具方法 ================= */

    private fun waitListReady(timeoutMs: Long, onReady: (Boolean) -> Unit) {
        poll(
            js = DouyinJs.CALL_LIST_READY,
            timeoutMs = timeoutMs,
            intervalMs = 400,
            ready = { raw -> parseObj(raw)?.optBoolean("ready", false) == true },
            onOk = { onReady(true) },
            onTimeout = { onReady(false) }
        )
    }

    private fun poll(
        js: String,
        timeoutMs: Long,
        intervalMs: Long,
        ready: (String) -> Boolean,
        onOk: (String) -> Unit,
        onTimeout: () -> Unit
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val check = object : Runnable {
            override fun run() {
                eval(js) { raw ->
                    val v = raw ?: "null"
                    if (ready(v)) {
                        onOk(v)
                    } else if (SystemClock.elapsedRealtime() >= deadline) {
                        onTimeout()
                    } else {
                        handler.postDelayed(this, intervalMs)
                    }
                }
            }
        }
        handler.postDelayed(check, 300)
    }

    private fun eval(js: String, cb: (String?) -> Unit) {
        try {
            webView.evaluateJavascript(js) { v -> cb(v) }
        } catch (_: Exception) {
            cb(null)
        }
    }

    private fun loadAsset(name: String): String? = try {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    /** 拟人化随机延迟（毫秒），对抗“固定节奏”行为特征。 */
    private fun rand(min: Int, max: Int): Long {
        if (max <= min) return min.toLong()
        return (min + (Math.random() * (max - min)).toInt()).toLong()
    }

    private fun parseObj(raw: String?): JSONObject? = try {
        if (raw.isNullOrEmpty() || raw == "null") null else JSONObject(raw)
    } catch (_: Exception) {
        null
    }

    private fun parseConvArray(raw: String?): List<Conv>? {
        try {
            if (raw.isNullOrEmpty() || raw == "null") return null
            val arr = JSONArray(raw)
            val out = mutableListOf<Conv>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val convId = if (o.isNull("convId")) null else o.optString("convId", null)
                val isGroup = convId != null &&
                        (!convId.contains(":") || convId.startsWith("0:2:"))
                out.add(
                    Conv(
                        index = o.optInt("index", i),
                        convId = convId,
                        uid = if (o.isNull("uid")) null else o.optString("uid", null),
                        secUid = if (o.isNull("secUid")) null else o.optString("secUid", null),
                        shortId = if (o.isNull("shortId")) null else o.optString("shortId", null),
                        title = if (o.isNull("title")) null else o.optString("title", null),
                        isCurrent = o.optBoolean("isCurrent", false),
                        dataIndex = if (o.isNull("dataIndex")) null else o.optInt("dataIndex", -1),
                        isGroup = isGroup
                    )
                )
            }
            return out
        } catch (_: Exception) {
            return null
        }
    }

    private fun hasSessionCookie(): Boolean {
        val cm = CookieManager.getInstance()
        val c = cm.getCookie(DouyinJs.CHAT_URL) ?: return false
        return c.contains("sessionid")
    }

    private fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        var v = Normalizer.normalize(s, Normalizer.Form.NFKC)
        v = v.replace("\u3000", " ").replace("\u00a0", " ")
        v = v.replace("\u200b", "").replace("\ufeff", "")
        return v.replace(Regex("\\s+"), "").lowercase()
    }

    private fun splitLines(text: String): List<String> {
        // 字面 \r\n → 字面 \n；真 CRLF → 真 LF；字面 \n → 真 LF（与 Python 口径一致）
        val t = text.replace("\\r\\n", "\\n")
            .replace("\r\n", "\n")
            .replace("\\n", "\n")
        return t.split("\n")
    }
}
