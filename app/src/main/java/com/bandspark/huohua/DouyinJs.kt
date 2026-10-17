package com.bandspark.huohua

/**
 * 抖音网页版 IM 自动化 JS 调用入口。
 * 全部 JS 实现在 assets/js/core.js（window.__spark 对象），
 * 这里只声明调用点，保证 Kotlin 侧不散落 JS 字符串。
 */
object DouyinJs {

    const val CHAT_URL = "https://www.douyin.com/chat"

    // 桌面端 Chrome UA：抖音网页版按 UA 判断是否渲染 PC 聊天页
    const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val CALL_HOOK_NET = "window.__spark.hookNet()"
    const val CALL_LOGIN_DOM = "window.__spark.loginDom()"
    const val CALL_LIST_READY = "window.__spark.listReady()"
    const val CALL_COLLECT = "window.__spark.collect()"
    const val CALL_SCROLL_PROBE = "window.__spark.scrollProbe()"
    const val CALL_SCROLL_TO = "window.__spark.scrollTo"
    const val CALL_SELECT_INDEX = "window.__spark.selectIndex"
    const val CALL_CLICK_SELECT_INDEX = "window.__spark.clickSelectIndex"
    const val CALL_CURRENT_CONV = "window.__spark.currentConv()"
    const val CALL_EDITOR_EMPTY = "window.__spark.editorEmpty()"
    const val CALL_EDITOR_CLEAR = "window.__spark.editorClear()"
    const val CALL_CLICK_SEND = "window.__spark.clickSend()"
    const val CALL_MSG_STATE = "window.__spark.msgState()"
    const val CALL_TYPE_LINE = "window.__spark.typeLine"
}
