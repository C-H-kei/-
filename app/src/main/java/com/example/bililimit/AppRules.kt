package com.example.bililimit

import android.graphics.drawable.Drawable

/**
 * 定义单个 App 的短视频识别规则
 */
data class AppConfig(
    val packageName: String,         // 包名
    val appName: String,             // 应用名称
    val icon: Drawable? = null,      // 图标 (后续可从系统获取)
    val classRules: List<String>,    // Activity 类名关键字
    val idRules: List<String>         // ViewId 关键字
)

object AppRules {
    // 内置的已知短视频规则库
    // 注意：除了 B 站，其他 App 的规则可能随版本更新而失效，需要用户反馈或后续抓包修正
    val KNOWN_APPS = listOf(
        AppConfig(
            packageName = "tv.danmaku.bili",
            appName = "哔哩哔哩",
            classRules = listOf("StoryVideoActivity"),
            idRules = listOf("story_view_pager", "story_activity_root")
        ),
        AppConfig(
            packageName = "com.ss.android.ugc.aweme",
            appName = "抖音",
            classRules = listOf("com.ss.android.ugc.aweme.splash.SplashActivity"), // 仅作占位示例，需实际抓包
            idRules = listOf("douyin_viewpager", "vertical_viewpager")
        ),
        AppConfig(
            packageName = "com.smile.gifmaker",
            appName = "快手",
            classRules = listOf("com.yxcorp.gifshow.HomeActivity"),
            idRules = listOf("root_view") // 占位
        ),
        AppConfig(
            packageName = "com.xingin.xhs",
            appName = "小红书",
            classRules = listOf("com.xingin.xhs.index.v2.IndexActivityV2"),
            idRules = listOf("search_page_recycler_view") // 占位
        )
    )

    /**
     * 根据包名获取对应的规则
     */
    fun getAppConfig(packageName: String): AppConfig? {
        return KNOWN_APPS.find { it.packageName == packageName }
    }
}