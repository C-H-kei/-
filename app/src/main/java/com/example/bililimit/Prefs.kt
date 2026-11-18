package com.example.bililimit

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(context: Context) {

    // 保存用户自定义的 App 列表
    var customAppsJson: String
        get() = sp.getString("custom_apps_json", "[]") ?: "[]"
        set(value) = sp.edit().putString("custom_apps_json", value).apply()

    var themeMode: Int
        get() = sp.getInt("theme_mode", 0) // 默认跟随系统
        set(value) = sp.edit().putInt("theme_mode", value).apply()
    /** 用户勾选需要限制的应用包名 */
    var enabledPackages: Set<String>
        get() = sp.getStringSet(KEY_ENABLED_PACKAGES, setOf("tv.danmaku.bili")) ?: setOf("tv.danmaku.bili")
        set(value) = sp.edit().putStringSet(KEY_ENABLED_PACKAGES, value).apply()

    private val sp = context.applicationContext
        .getSharedPreferences("bili_limit", Context.MODE_PRIVATE)

    /** 单次连续刷视频的上限 */
    var singleLimitSec: Int
        get() = sp.getInt(KEY_SINGLE, DEFAULT_SINGLE_SEC)
        set(value) = sp.edit().putInt(KEY_SINGLE, value.coerceIn(60, 4 * 3600)).apply()

    /** 每日总量上限 */
    var dailyLimitSec: Int
        get() = sp.getInt(KEY_DAILY, DEFAULT_DAILY_SEC)
        set(value) = sp.edit().putInt(KEY_DAILY, value.coerceIn(60, 12 * 3600)).apply()

    /** 被拦截后，多久之内再进入就直接拦 */
    var cooldownSec: Int
        get() = sp.getInt(KEY_COOLDOWN, DEFAULT_COOLDOWN_SEC)
        set(value) = sp.edit().putInt(KEY_COOLDOWN, value.coerceIn(0, 3600)).apply()

    /** Prefs.ACTION_HOME 或 Prefs.ACTION_LOCK */
    var timeUpAction: String
        get() = sp.getString(KEY_ACTION, ACTION_HOME) ?: ACTION_HOME
        set(value) = sp.edit().putString(KEY_ACTION, value).apply()

    var blockCount: Int
        get() = sp.getInt(KEY_BLOCK_COUNT, 0)
        set(value) = sp.edit().putInt(KEY_BLOCK_COUNT, value).apply()

    /** 匹配 Activity 类名的关键字 */
    /** 匹配 Activity 类名的关键字 */
    // ===== 自定义规则（按包名区分） =====

    fun getCustomClassRules(pkg: String): List<String>? {
        val str = sp.getString("class_$pkg", null)
        if (str.isNullOrBlank()) return null
        return splitRules(str)
    }

    fun setCustomClassRules(pkg: String, rules: String) {
        sp.edit().putString("class_$pkg", rules).apply()
    }

    fun getCustomIdRules(pkg: String): List<String>? {
        val str = sp.getString("id_$pkg", null)
        if (str.isNullOrBlank()) return null
        return splitRules(str)
    }

    fun setCustomIdRules(pkg: String, rules: String) {
        sp.edit().putString("id_$pkg", rules).apply()
    }

    /** 最近一次捕捉到的 B 站页面类名，用于让用户填规则 */
    var lastSeenClass: String
        get() = sp.getString(KEY_LAST_SEEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_SEEN, value).apply()

    /** 节点快照的有效截止时间（毫秒时间戳） */
    var snapshotUntil: Long
        get() = sp.getLong(KEY_SNAPSHOT_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_SNAPSHOT_UNTIL, value).apply()

    /** 最近一次节点快照结果 */
    var lastSnapshot: String
        get() = sp.getString(KEY_LAST_SNAPSHOT, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_SNAPSHOT, value).apply()

    // ---------------- 每日用量 ----------------

    fun loadTodaySeconds(): Int {
        val today = todayKey()
        if (sp.getString(KEY_USAGE_DATE, "") != today) {
            sp.edit().putString(KEY_USAGE_DATE, today).putInt(KEY_USAGE_SEC, 0).apply()
            return 0
        }
        return sp.getInt(KEY_USAGE_SEC, 0)
    }

    fun saveTodaySeconds(sec: Int) {
        sp.edit()
            .putString(KEY_USAGE_DATE, todayKey())
            .putInt(KEY_USAGE_SEC, sec.coerceAtLeast(0))
            .apply()
    }

    fun resetToday() {
        sp.edit().putString(KEY_USAGE_DATE, todayKey()).putInt(KEY_USAGE_SEC, 0).apply()
    }

    fun getAllApps(): List<AppConfig> {
        val list = mutableListOf<AppConfig>()
        list.addAll(AppRules.BUILTIN_APPS)

        try {
            val arr = org.json.JSONArray(customAppsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(AppConfig(
                    packageName = obj.getString("pkg"),
                    appName = obj.optString("name", obj.getString("pkg")),
                    classRules = obj.optString("cls").split(",").filter { it.isNotBlank() },
                    idRules = obj.optString("ids").split(",").filter { it.isNotBlank() },
                    isCustom = true
                ))
            }
        } catch (e: Exception) {}

        // 过滤掉重复的包名（例如用户添加了 B 站，就覆盖内置的）
        return list.distinctBy { it.packageName }
    }

    // 添加一个新的自定义应用
    fun addCustomApp(config: AppConfig) {
        val list = getAllApps().filter { it.isCustom }.toMutableList()
        list.removeAll { it.packageName == config.packageName }
        list.add(config)

        val arr = org.json.JSONArray()
        for (app in list) {
            val obj = org.json.JSONObject()
            obj.put("pkg", app.packageName)
            obj.put("name", app.appName)
            obj.put("cls", app.classRules.joinToString(","))
            obj.put("ids", app.idRules.joinToString(","))
            arr.put(obj)
        }
        customAppsJson = arr.toString()
    }

    // ---------------- 内部工具 ----------------

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun splitRules(raw: String): List<String> =
        raw.split(',', '，', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }


    // ===== 多规则管理 =====

    // 读取某个包名的所有规则
    fun getRuleItems(pkg: String): List<RuleItem> {
        val json = sp.getString("rules_$pkg", "[]")
        val list = mutableListOf<RuleItem>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(RuleItem(
                    alias = obj.optString("alias"),
                    cls = obj.optString("cls"),
                    ids = obj.optString("ids")
                ))
            }
        } catch (e: Exception) {}
        return list
    }

    // 保存某个包名的所有规则
    fun saveRuleItems(pkg: String, items: List<RuleItem>) {
        val arr = org.json.JSONArray()
        for (item in items) {
            val obj = org.json.JSONObject()
            obj.put("alias", item.alias)
            obj.put("cls", item.cls)
            obj.put("ids", item.ids)
            arr.put(obj)
        }
        sp.edit().putString("rules_$pkg", arr.toString()).apply()
    }

    // 添加单条规则
    fun addRuleItem(pkg: String, item: RuleItem) {
        val list = getRuleItems(pkg).toMutableList()
        list.add(item)
        saveRuleItems(pkg, list)
    }

    // 彻底移除自定义应用及其所有规则
    fun removeCustomApp(pkg: String) {
        // 1. 从勾选列表中移除
        val enabled = enabledPackages.toMutableSet()
        enabled.remove(pkg)
        enabledPackages = enabled

        // 2. 清空它的自定义规则
        sp.edit().remove("rules_$pkg").apply()

        // 3. 从 custom_apps_json 中移除
        val currentList = getAllApps().filter { it.isCustom && it.packageName != pkg }
        val arr = org.json.JSONArray()
        for (app in currentList) {
            val obj = org.json.JSONObject()
            obj.put("pkg", app.packageName)
            obj.put("name", app.appName)
            obj.put("cls", app.classRules.joinToString(","))
            obj.put("ids", app.idRules.joinToString(","))
            arr.put(obj)
        }
        customAppsJson = arr.toString()
    }
    companion object {

        private const val KEY_ENABLED_PACKAGES = "enabled_packages"
        const val DEFAULT_SINGLE_SEC = 10 * 60
        const val DEFAULT_DAILY_SEC = 60 * 60
        const val DEFAULT_COOLDOWN_SEC = 5 * 60

        const val ACTION_HOME = "home"
        const val ACTION_LOCK = "lock"

        private const val KEY_SINGLE = "single_limit_sec"
        private const val KEY_DAILY = "daily_limit_sec"
        private const val KEY_COOLDOWN = "cooldown_sec"
        private const val KEY_ACTION = "time_up_action"
        private const val KEY_BLOCK_COUNT = "block_count"
        private const val KEY_CLASS_RULES = "class_rules"
        private const val KEY_ID_RULES = "id_rules"
        private const val KEY_LAST_SEEN = "last_seen_class"
        private const val KEY_SNAPSHOT_UNTIL = "snapshot_until"
        private const val KEY_LAST_SNAPSHOT = "last_snapshot"
        private const val KEY_USAGE_DATE = "usage_date"
        private const val KEY_USAGE_SEC = "usage_sec"
    }
}