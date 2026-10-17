package com.bandspark.huohua

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * 页面 JS → Android 的桥。
 * core.js 的 hookNet() 会拦截页面 XHR/fetch，把 /v1/message/send 的
 * HTTP 状态码报回来（HTTP 回执，与 Python 版 ImMonitor 的 send 路由对应）。
 *
 * 注意：@JavascriptInterface 方法运行在 WebView 的 JavaBridge 线程，
 * 不是主线程，这里只做线程安全的静态回调分发。
 */
class JsBridge {

    @JavascriptInterface
    fun onNet(payload: String) {
        try {
            val o = JSONObject(payload)
            if (o.optBoolean("send", false)) {
                DouyinEngine.netListener?.invoke(o.optInt("status", -1))
            }
        } catch (_: Exception) {
            // 页面传来的内容不可控，静默忽略
        }
    }
}
