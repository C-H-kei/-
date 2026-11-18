package com.example.bililimit

data class RuleItem(
    val alias: String,  // 别名，如 "短视频页"、"评论区视频"
    val cls: String,    // 类名关键字
    val ids: String     // ViewId关键字（逗号分隔）
)