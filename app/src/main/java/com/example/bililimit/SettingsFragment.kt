package com.example.bililimit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave).setOnClickListener {
            saveSettings(view)
            saveEnabledApps(view)
            Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= 限制应用列表 =================
    private fun setupAppList(view: View) {
        val llAppList = view.findViewById<LinearLayout>(R.id.llAppList)
        llAppList.removeAllViews()
        val enabledPackages = prefs.enabledPackages

        for (app in AppRules.KNOWN_APPS) {
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
            }

            val btnEdit = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "修改规则"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#FF6B8B"))
                background = null // 去掉背景，看起来像纯文本
                setOnClickListener {
                    showEditDialog(app)
                }
            }

            row.addView(checkBox)
            row.addView(btnEdit)
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
    private fun showEditDialog(app: AppConfig) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_rules, null)
        val etClass = dialogView.findViewById<EditText>(R.id.etEditClass)
        val etId = dialogView.findViewById<EditText>(R.id.etEditId)

        val currentClass = prefs.getCustomClassRules(app.packageName) ?: app.classRules
        val currentId = prefs.getCustomIdRules(app.packageName) ?: app.idRules
        etClass.setText(currentClass.joinToString(","))
        etId.setText(currentId.joinToString(","))

        // 第一层弹窗：输入规则
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("修改「${app.appName}」识别规则")
            .setView(dialogView)
            .setPositiveButton("下一步") { _, _ ->
                val newClass = etClass.text.toString()
                val newId = etId.text.toString()

                // 第二层弹窗：确认修改
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("确认修改")
                    .setMessage("规则被修改后，如果填错可能会导致拦截失效。\n\n确定要保存这些新规则吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认保存") { _, _ ->
                        prefs.setCustomClassRules(app.packageName, newClass)
                        prefs.setCustomIdRules(app.packageName, newId)
                        Toast.makeText(requireContext(), "已保存自定义规则", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton("恢复默认") { _, _ ->
                // 恢复默认最好也加个二次确认
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("恢复默认规则")
                    .setMessage("确定要清空自定义规则，恢复内置的默认规则吗？")
                    .setPositiveButton("确认恢复") { _, _ ->
                        prefs.setCustomClassRules(app.packageName, "")
                        prefs.setCustomIdRules(app.packageName, "")
                        Toast.makeText(requireContext(), "已恢复出厂规则", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("取消", null)
            .show()
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