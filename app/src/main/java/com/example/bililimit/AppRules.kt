package com.example.bililimit

data class AppConfig(
    val packageName: String,
    val appName: String,
    val classRules: List<String>,
    val idRules: List<String>,
    val isCustom: Boolean = false // 标识是否为用户手动添加
)

object AppRules {
    // 内置的默认规则
    val BUILTIN_APPS = listOf(
        AppConfig(
            packageName = "tv.danmaku.bili",
            appName = "哔哩哔哩",
            // B站竖屏视频的类名
            classRules = listOf("StoryVideoActivity", "DetailActivity"),
            // ViewId 包含：竖屏翻页容器 + 视频卡片 + 评论区弹出视频的节点
            idRules = listOf(
                "story_view_pager",
                "story_activity_root",
                "video_view",
                "player_container"
            )
        ),
        AppConfig("com.ss.android.ugc.aweme", "抖音", listOf(), listOf()),
        AppConfig("com.smile.gifmaker", "快手", listOf(), listOf()),
        AppConfig("com.xingin.xhs", "小红书", listOf(), listOf())
    )
}