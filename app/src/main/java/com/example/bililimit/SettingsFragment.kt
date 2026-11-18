package com.example.bililimit

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private lateinit var prefs: Prefs

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())

        setupAppList(view)
        setupThemeClick(view)
        loadSettings(view)

        // 保存设置按钮
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave).setOnClickListener {
            saveSettings(view)
            saveEnabledApps(view)
            Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
        }

        // ====== 新增：智能识别新应用按钮 ======
        view.findViewById<Button>(R.id.btnAddCustomApp).setOnClickListener {
            // 跳转到 CaptureActivity
            startActivity(Intent(requireContext(), CaptureActivity::class.java))
        }
        // ======================================
    }

    override fun onResume() {
        super.onResume()
        // 每次回到该页面（包括从抓取页面返回），都重新刷新应用列表
        view?.let { setupAppList(it) }
    }

    // ================= 限制应用列表 =================
    private fun setupAppList(view: View) {
        val llAppList = view.findViewById<LinearLayout>(R.id.llAppList)
        llAppList.removeAllViews()
        val enabledPackages = prefs.enabledPackages

        // 获取所有应用（内置 + 自定义）
        val allApps = prefs.getAllApps()

        for (app in allApps) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val checkBox = MaterialCheckBox(requireContext()).apply {
                text = app.appName
                textSize = 16f
                isChecked = enabledPackages.contains(app.packageName)
                tag = app.packageName
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                setOnCheckedChangeListener { _, isChecked ->
                    val currentEnabled = prefs.enabledPackages.toMutableSet()
                    if (isChecked) {
                        currentEnabled.add(app.packageName)
                    } else {
                        currentEnabled.remove(app.packageName)
                    }
                    prefs.enabledPackages = currentEnabled
                }
            }

            val btnEdit = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "修改规则"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#FF6B8B"))
                background = null
                setOnClickListener {
                    showEditDialog(app)
                }
            }

            row.addView(checkBox)
            row.addView(btnEdit)

            // 【新增】如果是自定义应用，额外显示一个删除按钮
            if (app.isCustom) {
                val btnDelete = com.google.android.material.button.MaterialButton(requireContext()).apply {
                    text = "删除"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#FF4444")) // 红色
                    background = null
                    setOnClickListener {
                        // 二次确认
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle("移除应用")
                            .setMessage("确定要彻底移除「${app.appName}」及其所有规则吗？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("确定移除") { _, _ ->
                                prefs.removeCustomApp(app.packageName)
                                setupAppList(view) // 刷新列表
                                Toast.makeText(requireContext(), "已移除", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                }
                row.addView(btnDelete)
            }

            llAppList.addView(row)
        }
    }

    private fun saveEnabledApps(view: View) {
        val llAppList = view.findViewById<LinearLayout>(R.id.llAppList)
        val selectedPackages = mutableSetOf<String>()
        for (i in 0 until llAppList.childCount) {
            val child = llAppList.getChildAt(i)
            if (child is LinearLayout) {
                for (j in 0 until child.childCount) {
                    val innerChild = child.getChildAt(j)
                    if (innerChild is MaterialCheckBox && innerChild.isChecked) {
                        selectedPackages.add(innerChild.tag.toString())
                    }
                }
            }
        }
        prefs.enabledPackages = selectedPackages
    }

    // ================= 修改规则弹窗 =================
    // ================= 多规则弹窗管理 =================
    private fun showEditDialog(app: AppConfig) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_rules, null)
        val llContainer = dialogView.findViewById<LinearLayout>(R.id.llRuleContainer)
        val btnAddNewRule = dialogView.findViewById<Button>(R.id.btnAddNewRule)

        // 刷新弹窗内列表的函数
        fun refreshList() {
            llContainer.removeAllViews()
            val rules = prefs.getRuleItems(app.packageName)

            if (rules.isEmpty()) {
                val tvEmpty = TextView(requireContext()).apply {
                    text = "暂无规则，请点击下方按钮去抓取"
                    textSize = 14f
                    setTextColor(android.graphics.Color.GRAY)
                    setPadding(0, 20, 0, 20)
                }
                llContainer.addView(tvEmpty)
            } else {
                rules.forEach { rule ->
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 12, 0, 12)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }

                    val info = TextView(requireContext()).apply {
                        text = "别名：${rule.alias}\n类: ${rule.cls.ifBlank { "无" }} \nID: ${rule.ids.take(30)}..."
                        textSize = 14f
                        setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_main))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val btnDelete = com.google.android.material.button.MaterialButton(requireContext()).apply {
                        text = "删除"
                        textSize = 13f
                        setTextColor(android.graphics.Color.RED)
                        background = null
                        setOnClickListener {
                            val current = prefs.getRuleItems(app.packageName).toMutableList()
                            current.remove(rule)
                            prefs.saveRuleItems(app.packageName, current)
                            refreshList()
                            Toast.makeText(requireContext(), "已删除该规则", Toast.LENGTH_SHORT).show()
                        }
                    }

                    val divider = View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(android.graphics.Color.LTGRAY)
                        alpha = 0.3f
                    }

                    row.addView(info)
                    row.addView(btnDelete)
                    llContainer.addView(row)
                    llContainer.addView(divider)
                }
            }
        }

        // 初始化列表
        refreshList()

        // 创建 Dialog 对象（这次我们把它存下来，方便后面关闭）
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("「${app.appName}」规则管理")
            .setView(dialogView)
            .setNegativeButton("关闭", null)
            .create()

        // 点击添加新规则 -> 跳转到独立的新页面
        btnAddNewRule.setOnClickListener {
            dialog.dismiss() // 关闭当前弹窗

            // 跳转到独立的规则编辑页，并带上目标 App 信息
            val intent = android.content.Intent(requireContext(), RuleEditorActivity::class.java)
            intent.putExtra("EXTRA_TARGET_PKG", app.packageName)
            intent.putExtra("EXTRA_TARGET_APP_NAME", app.appName)
            startActivity(intent)
        }

        dialog.show()
    }

    // ================= 外观设置 =================
    private fun setupThemeClick(view: View) {
        val cardTheme = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTheme)
        val tvThemeStatus = view.findViewById<TextView>(R.id.tvThemeStatus)

        updateThemeStatusText(tvThemeStatus, prefs.themeMode)

        cardTheme.setOnClickListener {
            val themes = arrayOf("跟随系统", "浅色模式", "深色模式")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("外观设置")
                .setSingleChoiceItems(themes, prefs.themeMode) { dialog, which ->
                    prefs.themeMode = which
                    updateThemeStatusText(tvThemeStatus, which)

                    val mode = when (which) {
                        1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun updateThemeStatusText(textView: TextView, mode: Int) {
        textView.text = when (mode) {
            1 -> "浅色模式"
            2 -> "深色模式"
            else -> "跟随系统"
        }
    }

    // ================= 读取与保存设置 =================
    private fun loadSettings(view: View) {
        view.findViewById<EditText>(R.id.etSingle).setText((prefs.singleLimitSec / 60).toString())
        view.findViewById<EditText>(R.id.etDaily).setText((prefs.dailyLimitSec / 60).toString())
        view.findViewById<EditText>(R.id.etCooldown).setText((prefs.cooldownSec / 60).toString())

        val group = view.findViewById<RadioGroup>(R.id.rgAction)
        if (prefs.timeUpAction == Prefs.ACTION_LOCK) group.check(R.id.rbLock) else group.check(R.id.rbHome)
    }

    private fun saveSettings(view: View) {
        prefs.singleLimitSec = (view.findViewById<EditText>(R.id.etSingle).text.toString().toIntOrNull() ?: 10) * 60
        prefs.dailyLimitSec = (view.findViewById<EditText>(R.id.etDaily).text.toString().toIntOrNull() ?: 60) * 60
        prefs.cooldownSec = (view.findViewById<EditText>(R.id.etCooldown).text.toString().toIntOrNull() ?: 5) * 60

        prefs.timeUpAction = if (view.findViewById<RadioGroup>(R.id.rgAction).checkedRadioButtonId == R.id.rbLock) {
            Prefs.ACTION_LOCK
        } else {
            Prefs.ACTION_HOME
        }
    }
}