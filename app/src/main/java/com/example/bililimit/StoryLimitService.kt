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

        private const val TICK_FAR_MS = 2000L
        private const val TICK_NEAR_MS = 500L
        private const val NEAR_THRESHOLD_MS = 30_000L

        /** 剩余不足这个时间时发一次预警 */
        private const val WARNING_THRESHOLD_MS = 30_000L

        /** 离开竖屏页后的宽限期，期间回来不清零 */
        private const val SESSION_GRACE_MS = 60_000L

        private const val THROTTLE_IDLE_MS = 500L
        private const val THROTTLE_ACTIVE_MS = 1000L
        private const val PERSIST_INTERVAL_MS = 15_000L
        private const val MAX_SCAN_NODES = 400
        private const val NOTIFY_THROTTLE_MS = 1000L
        private const val EXTRA_MAX_SNAPSHOT_IDS = 60
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

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        todaySec = prefs.loadTodaySeconds().toDouble()
        checkDateRollover()
        ensureChannel()
        registerScreenReceiver()
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
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (t: Throwable) {
        }
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

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in prefs.enabledPackages) {
                    val cls = e.className?.toString()
                    if (!cls.isNullOrBlank()) lastWindowClass = cls
                    recordLastSeen(cls)
                    handlePageChange(pkg, cls)
                } else {
                    if (foregroundInStory) {
                        Log.d(TAG, "离开 B站前台（$pkg），暂停计时但保留会话")
                        leaveStory()
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg !in prefs.enabledPackages) return

                val now = SystemClock.elapsedRealtime()
                val throttle = if (foregroundInStory) THROTTLE_ACTIVE_MS else THROTTLE_IDLE_MS
                if (now - lastContentEventAt < throttle) return
                lastContentEventAt = now

                handlePageChange(pkg, null)
            }
        }
    }

    private fun handlePageChange(pkg: String, eventClassName: String?) {
        maybeTakeSnapshot()

        val nowStory = isStoryMode(pkg, eventClassName)
        if (nowStory == foregroundInStory) return

        if (nowStory) enterStory() else leaveStory()
    }

    // ==================== 页面判定 ====================

    private fun isStoryMode(pkg: String, eventClassName: String?): Boolean {
        if (!prefs.enabledPackages.contains(pkg)) return false

        val config = AppRules.getAppConfig(pkg) ?: return false

        // 1. 读取类名规则：优先用户自定义，其次默认
        val classRules = prefs.getCustomClassRules(pkg) ?: config.classRules
        val cls = eventClassName ?: lastWindowClass
        if (classRules.isNotEmpty() && !cls.isNullOrBlank()) {
            if (classRules.any { cls.contains(it, ignoreCase = true) }) return true
        }

        // 2. 读取 ViewId 规则：优先用户自定义，其次默认
        val idRules = prefs.getCustomIdRules(pkg) ?: config.idRules
        if (idRules.isEmpty()) return false

        val root = safeRoot() ?: return false
        if (root.packageName?.toString() != pkg) return false

        return scanForIds(root, idRules, MAX_SCAN_NODES) { it.isNotEmpty() }
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

            val vid = try {
                node.viewIdResourceName
            } catch (t: Throwable) {
                null
            }

            if (vid != null && rules.any { vid.contains(it, ignoreCase = true) }) {
                hits.add(vid)
                if (stopWhen(hits)) return true
            }

            val childCount = try {
                node.childCount
            } catch (t: Throwable) {
                0
            }

            for (i in 0 until childCount) {
                try {
                    node.getChild(i)?.let { queue.add(it) }
                } catch (t: Throwable) {
                }
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
            Log.d(TAG, "处于冷却期内，直接拦截（剩余 ${(blockedUntil - now) / 1000} 秒）")
            endSession()
            doBlock()
            return
        }

        handler.removeCallbacks(sessionTimeoutRunnable)

        if (!sessionActive) {
            sessionActive = true
            sessionAccumulatedMs = 0L
            warningSent = false      // 新会话才重置预警标记
            Log.d(TAG, "开启新会话")
        } else {
            Log.d(TAG, "继续会话，已累计 ${sessionAccumulatedMs / 1000} 秒")
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

        Log.d(TAG, "离开竖屏页，本轮累计 ${sessionAccumulatedMs / 1000} 秒")
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

        // 剩余不足 30 秒时预警一次
        if (remainMs in 1L..WARNING_THRESHOLD_MS && !warningSent) {
            warningSent = true
            sendAlert(
                "还有 ${remainMs / 1000} 秒",
                "B 站短视频额度即将用完"
            )
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
        Log.d(TAG, "达到限额，执行拦截")

        val cooldownMs = prefs.cooldownSec * 1000L
        blockedUntil = if (cooldownMs > 0L) {
            SystemClock.elapsedRealtime() + cooldownMs
        } else {
            0L
        }

        foregroundInStory = false   // 强制复位，防止 WSC 事件丢失导致卡死
        endSession()

        persistUsage()
        prefs.blockCount = prefs.blockCount + 1

        doBlock()
    }

    private fun doBlock() {
        val now = SystemClock.elapsedRealtime()
        val cooldownRemain = ((blockedUntil - now) / 1000).coerceAtLeast(0L)

        val reason = if (todaySec * 1000.0 >= prefs.dailyLimitSec * 1000L)
            "今日总时长已用完" else "本次时长已用完"

        val detail = if (cooldownRemain > 0L)
            "$reason，冷却 ${cooldownRemain / 60} 分 ${cooldownRemain % 60} 秒"
        else
            "$reason（可直接重进）"

        val big = buildString {
            append(detail).append("\n\n")
            append("单次上限 ").append(prefs.singleLimitSec / 60).append(" 分钟\n")
            append("今日已刷 ").append((todaySec / 60).toInt())
            append(" 分钟 / ").append(prefs.dailyLimitSec / 60).append(" 分钟\n")
            append("累计拦截 ").append(prefs.blockCount).append(" 次")
        }

        sendAlert("已退出目标应用", detail, big)

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

    // ==================== 跨天重置 ====================

    private fun checkDateRollover() {
        val today = dateFormat.format(Date())
        if (lastDateKey.isEmpty()) {
            lastDateKey = today
            return
        }
        if (today != lastDateKey) {
            Log.d(TAG, "跨天：$lastDateKey → $today，重置用量")
            todaySec = 0.0
            lastDateKey = today
            prefs.resetToday()
        }
    }

    // ==================== 节点快照 ====================

    private fun maybeTakeSnapshot() {
        val until = prefs.snapshotUntil
        if (until <= 0L) return
        if (System.currentTimeMillis() > until) {
            prefs.snapshotUntil = 0L
            return
        }
        prefs.snapshotUntil = 0L

        handler.postDelayed({
            val pkg = safeRoot()?.packageName?.toString()
            if (pkg in prefs.enabledPackages) {
                takeSnapshot()
            } else {
                prefs.snapshotUntil = System.currentTimeMillis() + 30_000L
            }
        }, 800L)
    }

    private fun takeSnapshot() {
        val root = safeRoot() ?: return
        val ids = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() &&
            visited < MAX_SCAN_NODES &&
            ids.size < EXTRA_MAX_SNAPSHOT_IDS
        ) {
            val node = queue.removeFirst()
            visited++

            try {
                node.viewIdResourceName?.let { ids.add(it) }
            } catch (t: Throwable) {
            }

            val childCount = try {
                node.childCount
            } catch (t: Throwable) {
                0
            }

            for (i in 0 until childCount) {
                try {
                    node.getChild(i)?.let { queue.add(it) }
                } catch (t: Throwable) {
                }
            }
        }

        prefs.lastSnapshot = ids.joinToString("\n")
        Log.d(TAG, "节点快照完成，共 ${ids.size} 个 viewId")
    }

    private fun recordLastSeen(className: String?) {
        if (className.isNullOrBlank()) return
        prefs.lastSeenClass = className
    }

    // ==================== 通知 ====================

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "专注计时", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示当前已刷时长和剩余额度"
                    setShowBadge(false)
                }
            )
        }

        if (nm.getNotificationChannel(CHANNEL_ID_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ALERT, "额度提醒", NotificationManager.IMPORTANCE_HIGH
                ).apply {
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
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "通知发送失败", t)
        }
    }

    private fun sendAlert(title: String, text: String, big: String? = null) {
        val builder = Notification.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)

        if (big != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(big))
        }

        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID_ALERT, builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "预警通知发送失败", t)
        }
    }

    private fun cancelNotification() {
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
        }
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    // ==================== 持久化 ====================

    private fun persistUsage() {
        prefs.saveTodaySeconds(todaySec.toInt())
    }
}