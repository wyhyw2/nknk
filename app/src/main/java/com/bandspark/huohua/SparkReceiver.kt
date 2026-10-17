package com.bandspark.huohua

import android.content.Context
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * 手环火花面板 接收器（基于 xms-wearable-lib，非正式 SDK）。
 *
 * 参考 BandQQ 的 InterconnectBridge，连接流程：
 *   1. nodeApi.connectedNodes 发现已通过小米运动健康连接的手环
 *   2. authApi 申请 DEVICE_MANAGER 权限（触发运动健康授权）
 *   3. messageApi.addListener 注册接收手环帧
 *   4. messageApi.sendMessage 向手环下发帧
 *
 * 协议（JSON 字符串帧）：
 *   手环 → 手机：{"type":"spark-command","action":"syncStatus|send|changePhrase","payload":{...}}
 *   手机 → 手环：spark-status / spark-not-activated / spark-result
 *
 * 激活门禁：手机端只在用户已激活会员（VipManager.isVip）时下发 spark-status，
 * 否则下发 spark-not-activated —— 手环单独安装无法使用。
 */
object SparkReceiver {

    private var context: Context? = null
    private var nodeApi: NodeApi? = null
    private var authApi: AuthApi? = null
    private var messageApi: MessageApi? = null
    private var currentNode: Node? = null
    private var registered = false

    /** 手环端 manifest 入口路由，用于 launchWearApp 拉起火花面板 */
    private const val WEAR_ENTRY_ROUTE = "/pages/index"

    fun init(ctx: Context) {
        this.context = ctx.applicationContext
        if (available()) return
        try {
            nodeApi = Wearable.getNodeApi(ctx)
            authApi = Wearable.getAuthApi(ctx)
            messageApi = Wearable.getMessageApi(ctx)
            Prefs.appendLog(ctx, "手环互联 SDK 初始化成功")
        } catch (e: Throwable) {
            Prefs.appendLog(ctx, "手环互联 SDK 初始化失败：$e")
        }
    }

    fun available(): Boolean = nodeApi != null && authApi != null && messageApi != null

    fun connect() {
        val ctx = context ?: return
        if (!available()) {
            init(ctx)
        }
        val nodeApi = nodeApi ?: return
        nodeApi.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Prefs.appendLog(ctx, "未发现已连接的手环，请确认小米运动健康已连接手环")
                    return@addOnSuccessListener
                }
                currentNode = nodes[0]
                auth(nodes[0])
            }
            .addOnFailureListener { e ->
                Prefs.appendLog(ctx, "发现手环失败：${e.message}")
            }
    }

    private fun auth(node: Node) {
        val ctx = context ?: return
        val authApi = authApi ?: return
        authApi.checkPermissions(node.id, arrayOf(Permission.DEVICE_MANAGER))
            .addOnSuccessListener { results ->
                var needRequest = false
                for (granted in results) {
                    if (!granted) {
                        authApi.requestPermission(node.id, Permission.DEVICE_MANAGER)
                        needRequest = true
                    }
                }
                if (needRequest) {
                    Prefs.appendLog(ctx, "已请求 DEVICE_MANAGER 权限，请在小米运动健康/手环上授权")
                }
                launchWearApp(node)
                registerListener(node)
            }
            .addOnFailureListener {
                registerListener(node)
            }
    }

    private fun launchWearApp(node: Node) {
        val nodeApi = nodeApi ?: return
        nodeApi.launchWearApp(node.id, WEAR_ENTRY_ROUTE)
            .addOnSuccessListener {
                Prefs.appendLog(context ?: return@addOnSuccessListener, "已拉起手环火花面板")
            }
            .addOnFailureListener {
                // 手环端未安装或未前台，忽略（用户可手动打开）
            }
    }

    private fun registerListener(node: Node) {
        val ctx = context ?: return
        val messageApi = messageApi ?: return
        if (registered) messageApi.removeListener(node.id)
        messageApi.addListener(node.id, OnMessageReceivedListener { _, data ->
            try {
                val json = String(data, StandardCharsets.UTF_8)
                handleFrame(json)
            } catch (e: Exception) {
                Prefs.appendLog(ctx, "手环消息解析失败：$e")
            }
        }).addOnSuccessListener {
            registered = true
            Prefs.appendLog(ctx, "手环监听注册成功")
        }.addOnFailureListener { e ->
            Prefs.appendLog(ctx, "手环监听注册失败：${e.message}")
        }
    }

    private fun handleFrame(json: String) {
        val ctx = context ?: return
        try {
            val msg = JSONObject(json)
            if (msg.optString("type") != "spark-command") return
            when (msg.optString("action")) {
                "syncStatus" -> sendStatus(ctx)
                "send" -> doSend(ctx)
                "changePhrase" -> doChangePhrase(ctx)
            }
        } catch (e: Exception) {
            Prefs.appendLog(ctx, "手环指令处理失败：$e")
        }
    }

    private fun sendStatus(ctx: Context) {
        if (VipManager.isVip(ctx)) {
            sendJson(JSONObject().apply {
                put("type", "spark-status")
                put("payload", JSONObject().apply {
                    put("sparkDays", Prefs.sparkDays(ctx))
                    put("phrase", MessagePool.current(ctx))
                })
            })
        } else {
            sendNotActivated()
        }
    }

    private fun doSend(ctx: Context) {
        if (!VipManager.isVip(ctx)) {
            sendNotActivated()
            return
        }
        val targets = Prefs.targets(ctx)
        if (targets.isEmpty()) {
            sendSendResult(false, Prefs.sparkDays(ctx))
            return
        }
        // 触发后台服务执行发送，结果在 AutoSendService.onDone 里经 sendSendResult 回传
        AutoSendService.start(ctx, AutoSendService.ACTION_BAND)
    }

    private fun doChangePhrase(ctx: Context) {
        if (!VipManager.isVip(ctx)) {
            sendNotActivated()
            return
        }
        val phrase = MessagePool.next(ctx)
        sendJson(JSONObject().apply {
            put("type", "spark-result")
            put("payload", JSONObject().apply {
                put("action", "changePhrase")
                put("ok", true)
                put("phrase", phrase)
            })
        })
    }

    private fun sendNotActivated() {
        sendJson(JSONObject().put("type", "spark-not-activated"))
    }

    /** 由 AutoSendService 发送完成后调用，把结果回传手环。 */
    fun sendSendResult(ok: Boolean, sparkDays: Int) {
        sendJson(JSONObject().apply {
            put("type", "spark-result")
            put("payload", JSONObject().apply {
                put("action", "send")
                put("ok", ok)
                put("sparkDays", sparkDays)
            })
        })
    }

    private fun sendJson(json: JSONObject) {
        val ctx = context ?: return
        val node = currentNode ?: run {
            Prefs.appendLog(ctx, "手环下发跳过：无连接节点")
            return
        }
        val messageApi = messageApi ?: return
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            messageApi.sendMessage(node.id, bytes)
        } catch (e: Throwable) {
            Prefs.appendLog(ctx, "手环下发失败：$e")
        }
    }
}
