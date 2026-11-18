package com.example.bililimit

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StoryLimitService : AccessibilityService() {

    companion object {
        private const val TAG = "BiliLimit"

        private const val CHANNEL_ID = "bili_limit_status"
        private const val NOTIFICATION_ID = 1001

        private const val CHANNEL_ID_ALERT = "bili_limit_alert"
        private const val NOTIFICATION_ID_ALERT = 1002

        // ===== 广播 Action =====
        const val ACTION_START_CAPTURE = "com.example.bililimit.START_CAPTURE"
        const val ACTION_APP_CAPTURED = "com.example.bililimit.APP_CAPTURED"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_CLASS = "class"
        const val EXTRA_IDS = "ids"

        const val ACTION_RESET_TODAY = "com.example.bililimit.RESET_TODAY"
        // =======================

        private const val TICK_FAR_MS = 2000L
        private const val TICK_NEAR_MS = 500L
        private const val NEAR_THRESHOLD_MS = 30_000L
        private const val WARNING_THRESHOLD_MS = 30_000L
        private const val SESSION_GRACE_MS = 60_000L
        private const val THROTTLE_IDLE_MS = 500L
        private const val THROTTLE_ACTIVE_MS = 1000L
        private const val PERSIST_INTERVAL_MS = 15_000L
        private const val MAX_SCAN_NODES = 400
        private const val NOTIFY_THROTTLE_MS = 1000L
    }

    private val prefs: Prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ===== 状态机 =====
    private var foregroundInStory = false
    private var sessionActive = false
    private var sessionAccumulatedMs = 0L
    private var segmentStartAt = 0L
    private var lastWindowClass: String? = null
    private var blockedUntil = 0L

    // ===== 用量 =====
    private var todaySec = 0.0
    private var lastTickAt = 0L
    private var lastPersistAt = 0L
    private var lastContentEventAt = 0L
    private var lastNotifyAt = 0L

    // ===== 新增：预警 + 跨天 =====
    private var warningSent = false
    private var lastDateKey: String = ""

    // ===== 定时任务 =====
    private val tickRunnable = Runnable { onTick() }

    private val sessionTimeoutRunnable = Runnable {
        if (!foregroundInStory && sessionActive) {
            Log.d(TAG, "离开超过 ${SESSION_GRACE_MS / 1000} 秒，结束本次会话")
            endSession()
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "屏幕熄灭，结束会话")
                foregroundInStory = false
                endSession()
            }
        }
    }

    // 接收 UI 发来的抓取指令
    private val captureCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_START_CAPTURE) {
                Log.d(TAG, "接收到抓取指令，开始抓取")
                performCapture()
            }
        }
    }

    // 接收重置指令
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESET_TODAY) {
                todaySec = 0.0
                sessionAccumulatedMs = 0L
                segmentStartAt = 0L
                foregroundInStory = false // 👈 新增：强制退出前台判定
                blockedUntil = 0L         // 👈 新增：强制解除冷却时间！
                endSession()
                persistUsage()
                Log.d(TAG, "收到重置指令，内存数据及冷却状态已清零")
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        todaySec = prefs.loadTodaySeconds().toDouble()
        checkDateRollover()
        ensureChannel()
        registerScreenReceiver()

        // 注册重置指令接收器 (使用 ContextCompat，最稳)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            resetReceiver,
            IntentFilter(ACTION_RESET_TODAY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 注册抓取指令接收器 (使用 ContextCompat)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            captureCommandReceiver,
            IntentFilter(ACTION_START_CAPTURE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.d(TAG, "服务已连接，今日已刷 ${todaySec.toInt()} 秒")
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        persistUsage()
        cancelNotification()

        // 注销熄屏广播
        try { unregisterReceiver(screenOffReceiver) } catch (t: Throwable) {}

        // 注销抓取指令广播
        try { unregisterReceiver(captureCommandReceiver) } catch (t: Throwable) {}

        // 【新增】注销重置指令广播
        try { unregisterReceiver(resetReceiver) } catch (t: Throwable) {}
    }
    private fun registerScreenReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "注册熄屏广播失败", t)
        }
    }

    // ==================== 事件入口 ====================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return

        // 👈 加上这行，看看到底浏览器/设置/桌面哪个包名在做动作
        android.util.Log.d("BiliLimitCheck", "收到事件 -> 包名: $pkg, 启用列表: ${prefs.enabledPackages}")

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in prefs.enabledPackages) {
                    val cls = e.className?.toString()
                    if (!cls.isNullOrBlank()) lastWindowClass = cls
                    recordLastSeen(cls)
                    handlePageChange(pkg, cls)
                } else {
                    if (foregroundInStory) {
                        leaveStory()
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg !in prefs.enabledPackages) {
                    // 被这里拦截了！
                    return
                }

                val now = SystemClock.elapsedRealtime()
                val throttle = if (foregroundInStory) THROTTLE_ACTIVE_MS else THROTTLE_IDLE_MS
                if (now - lastContentEventAt < throttle) return
                lastContentEventAt = now

                handlePageChange(pkg, null)
            }
        }
    }

    private fun handlePageChange(pkg: String, eventClassName: String?) {
        val nowStory = isStoryMode(pkg, eventClassName)
        if (nowStory == foregroundInStory) return

        if (nowStory) enterStory() else leaveStory()
    }

    // ==================== 页面判定 ====================

    private fun isStoryMode(pkg: String, eventClassName: String?): Boolean {
        if (!prefs.enabledPackages.contains(pkg)) {
            android.util.Log.d("BiliLimitCheck", "未勾选应用: $pkg")
            return false
        }

        val cls = eventClassName ?: lastWindowClass
        android.util.Log.d("BiliLimitCheck", "正在判断 $pkg，类名: $cls")

        val customRules = prefs.getRuleItems(pkg)
        if (customRules.isNotEmpty()) {
            android.util.Log.d("BiliLimitCheck", "应用 $pkg 有 ${customRules.size} 条自定义规则")
            for (rule in customRules) {
                if (rule.cls.isNotBlank() && !cls.isNullOrBlank()) {
                    if (cls.contains(rule.cls, ignoreCase = true)) {
                        android.util.Log.d("BiliLimitCheck", "匹配成功：类名命中 ${rule.cls}")
                        return true
                    }
                }
                if (rule.ids.isNotBlank()) {
                    val idList = rule.ids.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (idList.isNotEmpty()) {
                        val root = safeRoot() ?: continue
                        if (root.packageName?.toString() == pkg && scanForIds(root, idList, MAX_SCAN_NODES) { it.isNotEmpty() }) {
                            android.util.Log.d("BiliLimitCheck", "匹配成功：ViewId 命中")
                            return true
                        }
                    }
                }
            }
            android.util.Log.d("BiliLimitCheck", "自定义规则未命中，准备进入兜底逻辑")
        }

        // 兜底逻辑
        val config = prefs.getAllApps().find { it.packageName == pkg } ?: return false
        if (config.classRules.isNotEmpty() && !cls.isNullOrBlank()) {
            if (config.classRules.any { cls.contains(it, ignoreCase = true) }) {
                android.util.Log.d("BiliLimitCheck", "内置类名命中")
                return true
            }
        }

        if (config.idRules.isNotEmpty()) {
            val root = safeRoot() ?: return false
            if (root.packageName?.toString() != pkg) return false
            if (scanForIds(root, config.idRules, MAX_SCAN_NODES) { it.isNotEmpty() }) {
                android.util.Log.d("BiliLimitCheck", "内置 ViewId 命中")
                return true
            }
        }

        android.util.Log.d("BiliLimitCheck", "所有规则均未命中")
        return false
    }
    private fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        null
    }

    private fun scanForIds(
        root: AccessibilityNodeInfo,
        rules: List<String>,
        maxNodes: Int,
        stopWhen: (List<String>) -> Boolean
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val hits = ArrayList<String>()

        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++

            val vid = try { node.viewIdResourceName } catch (t: Throwable) { null }
            if (vid != null && rules.any { vid.contains(it, ignoreCase = true) }) {
                hits.add(vid)
                if (stopWhen(hits)) return true
            }

            val childCount = try { node.childCount } catch (t: Throwable) { 0 }
            for (i in 0 until childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (t: Throwable) {}
            }
        }
        return false
    }

    // ==================== 状态机切换 ====================

    private fun enterStory() {
        checkDateRollover()
        val now = SystemClock.elapsedRealtime()
        foregroundInStory = true

        if (now < blockedUntil) {
            endSession()
            doBlock()
            return
        }

        handler.removeCallbacks(sessionTimeoutRunnable)

        if (!sessionActive) {
            sessionActive = true
            sessionAccumulatedMs = 0L
            warningSent = false
        }

        segmentStartAt = now
        lastTickAt = now

        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_FAR_MS)
    }

    private fun leaveStory() {
        if (!foregroundInStory) return
        foregroundInStory = false

        if (segmentStartAt > 0L) {
            sessionAccumulatedMs += SystemClock.elapsedRealtime() - segmentStartAt
            segmentStartAt = 0L
        }
        lastTickAt = 0L

        handler.removeCallbacks(tickRunnable)
        cancelNotification()

        if (sessionActive) {
            handler.removeCallbacks(sessionTimeoutRunnable)
            handler.postDelayed(sessionTimeoutRunnable, SESSION_GRACE_MS)
        }
    }

    private fun endSession() {
        sessionActive = false
        sessionAccumulatedMs = 0L
        segmentStartAt = 0L
        lastTickAt = 0L
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(sessionTimeoutRunnable)
        cancelNotification()
        persistUsage()
    }

    // ==================== 计时循环 ====================

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()

        if (!foregroundInStory || segmentStartAt <= 0L) {
            handler.removeCallbacks(tickRunnable)
            return
        }

        if (lastTickAt > 0L) {
            todaySec += (now - lastTickAt) / 1000.0
        }
        lastTickAt = now

        if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
            lastPersistAt = now
            persistUsage()
        }

        val currentSessionMs = sessionAccumulatedMs + (now - segmentStartAt)
        val singleRemainMs = prefs.singleLimitSec * 1000L - currentSessionMs
        val dailyRemainMs = prefs.dailyLimitSec * 1000L - (todaySec * 1000.0).toLong()
        val remainMs = minOf(singleRemainMs, dailyRemainMs)

        updateNotification(currentSessionMs, remainMs, now)

        if (remainMs in 1L..WARNING_THRESHOLD_MS && !warningSent) {
            warningSent = true
            sendAlert("还有 ${remainMs / 1000} 秒", "短视频额度即将用完")
        }

        if (remainMs <= 0L) {
            onTimeout()
            return
        }

        val interval = if (remainMs <= NEAR_THRESHOLD_MS) TICK_NEAR_MS else TICK_FAR_MS
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, interval)
    }

    // ==================== 超时处理 ====================

    private fun onTimeout() {
        val cooldownMs = prefs.cooldownSec * 1000L
        blockedUntil = if (cooldownMs > 0L) SystemClock.elapsedRealtime() + cooldownMs else 0L

        foregroundInStory = false
        endSession()

        persistUsage()
        prefs.blockCount = prefs.blockCount + 1

        doBlock()
    }

    private fun doBlock() {
        val now = SystemClock.elapsedRealtime()
        val cooldownRemain = ((blockedUntil - now) / 1000).coerceAtLeast(0L)

        val reason = if (todaySec * 1000.0 >= prefs.dailyLimitSec * 1000L) "今日总时长已用完" else "本次时长已用完"
        val detail = if (cooldownRemain > 0L) "$reason，冷却 ${cooldownRemain / 60} 分 ${cooldownRemain % 60} 秒" else "$reason（可直接重进）"

        sendAlert("已退出目标应用", detail)

        when (prefs.timeUpAction) {
            Prefs.ACTION_LOCK -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            else -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
        cancelNotification()
    }

    // ==================== 抓取逻辑 ====================

    private fun performCapture() {
        val root = safeRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        val cls = lastWindowClass ?: root.className?.toString() ?: ""

        val ids = mutableSetOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 800) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let {
                if (!it.startsWith("android:id")) {
                    // 【核心修改】自动截取 :id/ 后面的部分，去掉前缀
                    val cleanId = it.substringAfter(":id/", it)
                    ids.add(cleanId)
                }
            }
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Throwable) {}
            }
        }

        val resultIntent = Intent(ACTION_APP_CAPTURED).apply {
            setPackage(packageName)
            putExtra(EXTRA_PKG, pkg)
            putExtra(EXTRA_CLASS, cls)
            putExtra(EXTRA_IDS, ids.joinToString(","))
        }
        sendBroadcast(resultIntent)
    }

    // ==================== 跨天重置与持久化 ====================

    private fun checkDateRollover() {
        val today = dateFormat.format(Date())
        if (lastDateKey.isEmpty()) {
            lastDateKey = today
            return
        }
        if (today != lastDateKey) {
            todaySec = 0.0
            lastDateKey = today
            prefs.resetToday()
        }
    }

    private fun recordLastSeen(className: String?) {
        if (className.isNullOrBlank()) return
        prefs.lastSeenClass = className
    }

    private fun persistUsage() {
        prefs.saveTodaySeconds(todaySec.toInt())
    }

    // ==================== 通知 ====================

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "短视频计时", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示当前已刷时长和剩余额度"
                    setShowBadge(false)
                }
            )
        }

        if (nm.getNotificationChannel(CHANNEL_ID_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_ALERT, "额度提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "额度即将用完与超限通知"
                    enableVibration(true)
                }
            )
        }
    }

    private fun updateNotification(sessionMs: Long, remainMs: Long, now: Long) {
        if (now - lastNotifyAt < NOTIFY_THROTTLE_MS) return
        lastNotifyAt = now

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("流年 · 专注计时")
            .setContentText("本轮已刷 ${fmt(sessionMs)} · 剩余 ${fmt(remainMs)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {}
    }

    private fun sendAlert(title: String, text: String) {
        val builder = Notification.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)

        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID_ALERT, builder.build())
        } catch (t: Throwable) {}
    }

    private fun cancelNotification() {
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {}
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }
}