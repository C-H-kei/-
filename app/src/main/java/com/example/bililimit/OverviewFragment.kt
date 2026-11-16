package com.example.bililimit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class OverviewFragment : Fragment() {

    private lateinit var prefs: Prefs

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_overview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())

        view.findViewById<Button>(R.id.btnResetToday).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("重置今日用量")
                .setMessage("确定要将今日的已刷时长和拦截次数清零吗？此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定重置") { _, _ ->
                    prefs.resetToday()
                    prefs.blockCount = 0 // 顺手把拦截次数也清了
                    refreshUi(view)
                    android.widget.Toast.makeText(requireContext(), "已重置", android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi(requireView())
    }

    private fun refreshUi(view: View) {
        val used = prefs.loadTodaySeconds()
        val dailyLimitSec = prefs.dailyLimitSec
        val dailyMin = dailyLimitSec / 60
        val blockCount = prefs.blockCount

        view.findViewById<TextView>(R.id.tvTodayUsed).text = "${used / 60} 分 ${used % 60} 秒"
        view.findViewById<TextView>(R.id.tvRemain).text = "今日剩余：${(dailyMin - used / 60).coerceAtLeast(0)} 分钟"
        view.findViewById<TextView>(R.id.tvBlockCount).text = "累计拦截：$blockCount 次"

        // 圆环进度条动画
        val progressRing = view.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.progressRing)
        val percent = if (dailyLimitSec > 0) {
            ((used.toFloat() / dailyLimitSec) * 100).toInt().coerceIn(0, 100)
        } else 0

        // 带动画地更新进度
        progressRing.setProgressCompat(percent, true)
    }
}