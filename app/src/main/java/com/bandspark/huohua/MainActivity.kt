package com.bandspark.huohua

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webLogin: WebView
    private lateinit var webContainer: FrameLayout
    private lateinit var btnCloseWeb: Button
    private lateinit var tvStatus: TextView
    private lateinit var lvTargets: ListView
    private lateinit var btnRefreshFriends: Button
    private lateinit var tvFriendHint: TextView
    private lateinit var etTemplate: EditText
    private lateinit var swAuto: Switch
    private lateinit var tvVip: TextView
    private lateinit var etKey: EditText
    private lateinit var tvLog: TextView

    private var adapter: ArrayAdapter<String>? = null
    private var engine: DouyinEngine? = null
    private var loginWatching = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webLogin = findViewById(R.id.webLogin)
        webContainer = findViewById(R.id.webContainer)
        btnCloseWeb = findViewById(R.id.btnCloseWeb)
        tvStatus = findViewById(R.id.tvStatus)
        lvTargets = findViewById(R.id.lvTargets)
        btnRefreshFriends = findViewById(R.id.btnRefreshFriends)
        tvFriendHint = findViewById(R.id.tvFriendHint)
        etTemplate = findViewById(R.id.etTemplate)
        swAuto = findViewById(R.id.swAuto)
        tvVip = findViewById(R.id.tvVip)
        etKey = findViewById(R.id.etKey)
        tvLog = findViewById(R.id.tvLog)

        NotifyHelper.ensureChannel(this)
        // 初始化并连接手环互联通道（接收手环火花面板的 spark-command）
        SparkReceiver.init(this)
        SparkReceiver.connect()
        requestPerms()
        showDisclaimerIfNeeded()

        engine = DouyinEngine(this, webLogin, object : DouyinEngine.Callback {
            override fun onLog(line: String) {
                Prefs.appendLog(this@MainActivity, line)
                refreshLog()
            }

            override fun onDone(result: DouyinEngine.Result) {
                if (result.sentOk > 0) {
                    Prefs.setSparkDays(this@MainActivity, Prefs.sparkDays(this@MainActivity) + 1)
                }
                Prefs.setLastSentAt(this@MainActivity, System.currentTimeMillis())
                Prefs.appendLog(this@MainActivity, result.detail)
                refreshLog()
                refreshStatus()
                Toast.makeText(
                    this@MainActivity,
                    if (result.sentOk > 0) "续火成功 🔥" else result.detail,
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        engine?.attach()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, Prefs.conversations(this))
        lvTargets.adapter = adapter
        lvTargets.setOnItemClickListener { _, _, _, _ ->
            saveSelectedFriends()
        }

        btnRefreshFriends.setOnClickListener {
            refreshFriends()
        }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            if (webContainer.visibility == View.GONE) {
                webContainer.visibility = View.VISIBLE
                engine?.ensurePage()
                startLoginWatch()
                toast("网页已打开：扫码登录或验证码登录")
            } else {
                closeWeb()
            }
        }

        findViewById<Button>(R.id.btnCloseWeb).setOnClickListener {
            closeWeb()
        }

        findViewById<Button>(R.id.btnSendNow).setOnClickListener { manualSend() }

        findViewById<Button>(R.id.btnAddTemplate).setOnClickListener {
            val t = etTemplate.text.toString().trim()
            if (t.isEmpty()) {
                toast("请输入消息内容")
                return@setOnClickListener
            }
            val list = Prefs.templates(this)
            if (!list.contains(t) && list.size < 50) {
                list.add(t)
                Prefs.setTemplates(this, list)
                etTemplate.text.clear()
                toast("已加入随机话术库（${list.size} 条）")
            }
        }

        swAuto.setOnCheckedChangeListener { _, checked ->
            if (checked && !VipManager.isVip(this)) {
                swAuto.isChecked = false
                toast("自动续火/手环一键续火需开通会员")
                return@setOnCheckedChangeListener
            }
            Prefs.setAutoEnabled(this, checked)
            if (checked) SparkScheduler.schedule(this) else SparkScheduler.cancel(this)
            refreshStatus()
        }

        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isEmpty()) {
                toast("请输入卡密")
                return@setOnClickListener
            }
            toast("正在验证卡密…")
            VipManager.activate(this, key) { ok, msg ->
                runOnUiThread {
                    if (ok) {
                        toast("激活成功，欢迎加入会员 🔥")
                        etKey.text.clear()
                        Prefs.appendLog(this@MainActivity, "会员激活成功")
                        refreshLog()
                    } else {
                        toast(msg)
                    }
                    refreshStatus()
                }
            }
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Prefs.clearLogs(this)
            refreshLog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshTargets()
        refreshLog()
        // 登录过就保持手环常驻面板在通知栏
        if (Prefs.loggedIn(this)) NotifyHelper.showQuickPanel(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopLoginWatch()
            try {
                webLogin.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun showDisclaimerIfNeeded() {
        val sp = getSharedPreferences("bandspark", Context.MODE_PRIVATE)
        if (sp.getBoolean("disclaimerShown", false)) return
        sp.edit().putBoolean("disclaimerShown", true).apply()
        AlertDialog.Builder(this)
            .setTitle("风险提示")
            .setMessage(
                "本工具通过自动化操作抖音网页版发送消息以维持火花。\n\n" +
                        "使用可能触发抖音平台风控，存在账号被限制/封禁的风险。" +
                        "请合理控制频率（每天最多 1 次、随机时间、随机话术），" +
                        "并自行承担使用风险，遵守《抖音用户服务协议》。"
            )
            .setPositiveButton("我已了解，继续使用", null)
            .setCancelable(false)
            .show()
    }

    private fun requestPerms() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    private fun manualSend() {
        val targets = Prefs.targets(this)
        if (targets.isEmpty()) {
            toast("请先添加续火好友")
            return
        }
        if (!VipManager.isVip(this) && targets.size > 1) {
            toast("免费版仅支持 1 位好友，多好友请开通会员")
            return
        }
        val msg = MessagePool.build(this)
        toast("开始续火，请保持 App 前台/后台运行…")
        engine?.runTargets(targets, msg)
    }

    private fun startLoginWatch() {
        if (loginWatching) return
        loginWatching = true
        val watch = object : Runnable {
            var tries = 0
            override fun run() {
                if (!loginWatching) return
                tries++
                if (tries > 80) {
                    loginWatching = false
                    return
                }
                engine?.checkLogin(8000) { state ->
                    when (state) {
                        "LOGGED_IN" -> {
                            loginWatching = false
                            webContainer.visibility = View.GONE
                            toast("登录成功！手环面板已开启")
                            Prefs.setLoggedIn(this@MainActivity, true)
                            NotifyHelper.showQuickPanel(this@MainActivity)
                            Prefs.appendLog(this@MainActivity, "抖音登录成功，手环快捷面板已开启")
                            refreshLog()
                            refreshStatus()
                            refreshFriends()
                        }
                        else -> handler.postDelayed(this, 3000)
                    }
                }
            }
        }
        handler.postDelayed(watch, 2000)
    }

    private fun stopLoginWatch() {
        loginWatching = false
    }

    private fun refreshTargets() {
        val conversations = Prefs.conversations(this)
        val selected = Prefs.targets(this).toSet()
        adapter?.clear()
        adapter?.addAll(conversations)
        adapter?.notifyDataSetChanged()
        for (i in conversations.indices) {
            lvTargets.setItemChecked(i, conversations[i] in selected)
        }
        tvFriendHint.text = if (conversations.isEmpty())
            "登录抖音后点「刷新」导入好友列表"
        else
            "已导入 ${conversations.size} 位好友，点选要续火的好友"
        refreshStatus()
    }

    /** 从勾选状态同步选中好友到 targets */
    private fun saveSelectedFriends() {
        val conversations = Prefs.conversations(this)
        val checked = lvTargets.checkedItemPositions
        val selected = mutableListOf<String>()
        for (i in conversations.indices) {
            if (checked.get(i)) selected.add(conversations[i])
        }
        Prefs.setTargets(this, selected)
        refreshStatus()
    }

    /** 登录后采集好友列表并显示 */
    private fun refreshFriends() {
        toast("正在导入好友列表…")
        engine?.collectConversations { list ->
            Prefs.setConversations(this, list)
            Prefs.appendLog(this, "好友导入完成：${list.size} 位")
            refreshTargets()
            toast("已导入 ${list.size} 位好友，请点选要续火的好友")
        }
    }

    private fun refreshStatus() {
        val vip = VipManager.isVip(this)
        val days = Prefs.sparkDays(this)
        val auto = Prefs.autoEnabled(this)
        val h0 = Prefs.sendStartHour(this)
        val h1 = Prefs.sendEndHour(this)
        tvStatus.text = "🔥 火花天数：$days 天\n" +
                "最近续火：${Prefs.lastSentText(this)}\n" +
                "自动续火：${if (auto) "开（每天 $h0:00-$h1:00 随机 + 手环提醒）" else "关"}\n" +
                "目标好友：${Prefs.targets(this).size} 个"
        val left = VipManager.vipDaysLeft(this)
        tvVip.text = if (vip) "✅ 会员已开通（剩余 $left 天）" else "❌ 未开通：自动续火/手环一键续火需会员"
        swAuto.isChecked = auto
    }

    private fun refreshLog() {
        tvLog.text = Prefs.logs(this)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 关闭网页登录浮层 */
    private fun closeWeb() {
        webContainer.visibility = View.GONE
        stopLoginWatch()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webContainer.visibility == View.VISIBLE) {
            closeWeb()
        } else {
            super.onBackPressed()
        }
    }
}
