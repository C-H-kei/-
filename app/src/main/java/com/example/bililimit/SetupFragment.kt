package com.example.bililimit

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class SetupFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请在列表中找到「流年 · 短视频限制」并打开开关")
        }

        view.findViewById<Button>(R.id.btnIgnoreBattery).setOnClickListener {
            val pm = requireContext().getSystemService(PowerManager::class.java)
            if (pm != null && pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                toast("已经关闭电池优化，无需重复操作")
                return@setOnClickListener
            }
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${requireContext().packageName}"))
                startActivity(intent)
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        view.findViewById<Button>(R.id.btnAppSettings).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
            toast("进入「应用启动管理」，关闭自动管理")
        }
    }

    /**
     * 每次页面回到前台（用户从系统设置退回来）时触发
     */
    override fun onResume() {
        super.onResume()
        val isEnabled = isAccessibilityEnabled()
        updateStatusUi(requireView(), isEnabled)
    }

    /**
     * 动态更新状态栏的 UI
     */
    private fun updateStatusUi(view: View, isEnabled: Boolean) {
        val tvStatus = view.findViewById<TextView>(R.id.tvServiceStatus)

        if (isEnabled) {
            tvStatus.text = "无障碍服务：已开启 ✓"
            tvStatus.setTextColor(Color.parseColor("#4CAF50")) // 绿色文字

            // 动态修改背景为浅绿色圆角 + 绿色边框
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f // 12dp 转 px 差不多 24f
                setColor(Color.parseColor("#E8F5E9")) // 浅绿底
                setStroke(2, Color.parseColor("#81C784")) // 绿边框
            }
            tvStatus.background = bg

        } else {
            tvStatus.text = "无障碍服务：未开启 ✗（必须开启）"
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.banner_text_normal))

            // 动态修改背景为浅红色圆角 + 红色边框
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(ContextCompat.getColor(requireContext(), R.color.banner_bg_normal))
                setStroke(2, ContextCompat.getColor(requireContext(), R.color.banner_stroke_normal))
            }
            tvStatus.background = bg
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val raw = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceName = StoryLimitService::class.java.simpleName
        return raw.split(':').any {
            it.contains(requireContext().packageName) && it.contains(serviceName)
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}