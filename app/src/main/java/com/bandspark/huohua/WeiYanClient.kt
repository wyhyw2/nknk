package com.bandspark.huohua

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * 微验（wy.llua.cn）网络验证客户端 —— RC4 加密模式。
 *
 * 协议来源：微验官方 Android Studio 模板（Rc4Util.java / MainActivity.java）
 *           + 你的 main.sh Shell 模板（kmlogin 混淆字段版）。
 *
 * 关键点（对照模板修正过）：
 *   - RC4 加密后是 HEX 十六进制字符串（不是 Base64！Rc4Util.encryRC4String=bytesToHex）
 *   - 解密：先把 HEX 转字节，再 RC4，再按 UTF-8 还原字符串
 *   - 接口：id=kmlogin&app=APPID&data=<hex>
 *   - sign = md5("kami=..&markcode=..&t=..&APPKEY")
 *   - data 明文 = "kami=..&markcode=..&t=..&sign=..&value=..&APPKEY"（末尾带 APPKEY）
 *   - 成功码 52135；响应字段名是混淆的（p929010b.. 等）
 *   - check = md5(timec + APPKEY + value)
 */
object WeiYanClient {

    // ===== 真实配置（来自 main.sh，已填入） =====
    const val APPID = "38055"
    const val APPKEY = "kPDLCZk6yWKxmMm"
    const val RC4_KEY = "8BG19UPOOUfHKgY"
    const val BASE_URL = "http://wy.llua.cn/api/"

    data class WeiYanResult(val code: Int, val msg: String, val data: JSONObject? = null)

    /** 机器码：md5(android_id.fingerprint)，与 main.sh 的 imei 一致。 */
    fun machineCode(ctx: Context): String {
        val aid = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val fingerprint = Build.FINGERPRINT
        return md5("$aid.$fingerprint")
    }

    /** 卡密登录验证。 */
    fun login(cardKey: String, machine: String, cb: (WeiYanResult) -> Unit) {
        thread {
            try {
                val timer = (System.currentTimeMillis() / 1000).toString()
                val value = (100000..999999).random().toString() + timer

                val sign = md5("kami=$cardKey&markcode=$machine&t=$timer&$APPKEY")
                val rawData =
                    "kami=$cardKey&markcode=$machine&t=$timer&sign=$sign&value=$value&$APPKEY"

                // RC4 加密 → HEX
                val encrypted = rc4Encrypt(rawData, RC4_KEY)
                val url = "${BASE_URL}?id=kmlogin&app=$APPID&data=$encrypted"
                val response = get(url)

                // HEX → RC4 解密
                val decrypted = rc4Decrypt(response, RC4_KEY)
                val json = JSONObject(decrypted)

                val code = json.optInt("p929010b195eb2095d692420d5294160e", 0)
                if (code != 52135) {
                    val msg = json.optString("wa11a83a638a6a9631e816a1a07ea7cd4", "登录失败")
                    cb(WeiYanResult(code, msg))
                    return@thread
                }

                val kamid = json.optString("lbb288188dcd40302aa6b12b61c3816ff")
                val timec = json.optString("v3b4b3071cd1c966a2c5eb1cba8450f4d")
                val checks = json.optString("dea84a0bc135da2b62280881097ccbcb0")
                val vip = json.optLong("k372ba1484e0b0c47d0e81875bce9a59a")

                val myCheck = md5("$timec$APPKEY$value")
                if (myCheck != checks) {
                    cb(WeiYanResult(-1, "数据校验失败"))
                    return@thread
                }

                val data = JSONObject().put("expire_time", vip).put("kamid", kamid)
                cb(WeiYanResult(0, "登录成功", data))
            } catch (e: Exception) {
                cb(WeiYanResult(-999, "网络异常：${e.message}"))
            }
        }
    }

    /** 心跳/复核：复用登录接口。 */
    fun heartbeat(cardKey: String, machine: String, cb: (WeiYanResult) -> Unit) =
        login(cardKey, machine, cb)

    /* ================= 工具方法 ================= */

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val raw = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in raw) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun get(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    /** RC4 加密：返回 HEX 字符串（与 Rc4Util.encryRC4String 一致）。 */
    private fun rc4Encrypt(data: String, key: String): String =
        bytesToHex(rc4(data.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8)))

    /** RC4 解密：输入 HEX 字符串。 */
    private fun rc4Decrypt(hex: String, key: String): String =
        String(rc4(hexToByte(hex), key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    private fun rc4(input: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256)
        for (i in 0..255) s[i] = i
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) % 256
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        var i = 0
        j = 0
        val out = ByteArray(input.size)
        for (x in input.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            val t = (s[i] + s[j]) % 256
            out[x] = (input[x].toInt() xor s[t]).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val hex = Integer.toHexString(b.toInt() and 0xff)
            if (hex.length < 2) sb.append('0')
            sb.append(hex)
        }
        return sb.toString()
    }

    private fun hexToByte(hex: String): ByteArray {
        var h = hex
        if (h.length % 2 == 1) h = "0$h"
        val out = ByteArray(h.length / 2)
        var j = 0
        var i = 0
        while (i < h.length) {
            out[j++] = h.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}
