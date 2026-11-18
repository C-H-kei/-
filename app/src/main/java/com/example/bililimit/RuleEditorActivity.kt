package com.example.bililimit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class RuleEditorActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var targetPkg: String = ""
    private var targetAppName: String = ""

    // 接收 CaptureActivity 抓取返回的数据
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cls = result.data?.getStringExtra("RESULT_CLASS") ?: ""
            val ids = result.data?.getStringExtra("RESULT_IDS") ?: ""

            // 自动填入输入框
            findViewById<EditText>(R.id.etRuleClass).setText(cls)
            findViewById<EditText>(R.id.etRuleIds).setText(ids)
            Toast.makeText(this, "抓取成功，请填写别名并保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)

        prefs = Prefs(this)
        targetPkg = intent.getStringExtra("EXTRA_TARGET_PKG") ?: ""
        targetAppName = intent.getStringExtra("EXTRA_TARGET_APP_NAME") ?: targetPkg

        findViewById<TextView>(R.id.tvTargetApp).text = "目标应用：$targetAppName ($targetPkg)"

        // 去抓取按钮
        findViewById<Button>(R.id.btnGoCapture).setOnClickListener {
            val intent = Intent(this, CaptureActivity::class.java)
            // 关键：把目标包名传过去，告诉 CaptureActivity 我们是来加规则的
            intent.putExtra("EXTRA_TARGET_PKG", targetPkg)
            captureLauncher.launch(intent)
        }

        // 保存规则按钮
        findViewById<Button>(R.id.btnSaveRule).setOnClickListener {
            val alias = findViewById<EditText>(R.id.etRuleAlias).text.toString().ifBlank { "未命名规则" }
            val cls = findViewById<EditText>(R.id.etRuleClass).text.toString()
            val ids = findViewById<EditText>(R.id.etRuleIds).text.toString()

            android.util.Log.d("BiliLimit", "准备添加规则 -> 目标包名: $targetPkg, 类名: $cls, 视图ID: $ids")

            if (cls.isBlank() && ids.isBlank()) {
                Toast.makeText(this, "类名和 ViewId 至少填一项", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.addRuleItem(targetPkg, RuleItem(alias, cls, ids))

            val enabled = prefs.enabledPackages.toMutableSet()
            enabled.add(targetPkg)
            prefs.enabledPackages = enabled

            Toast.makeText(this, "规则已成功添加给 $targetAppName", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}