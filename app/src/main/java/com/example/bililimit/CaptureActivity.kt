package com.example.bililimit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CaptureActivity : AppCompatActivity() {

    private var isCapturing = false

    private var extraTargetPkg: String? = null

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == StoryLimitService.ACTION_APP_CAPTURED) {
                val pkg = intent.getStringExtra(StoryLimitService.EXTRA_PKG) ?: return
                val cls = intent.getStringExtra(StoryLimitService.EXTRA_CLASS) ?: ""
                val ids = intent.getStringExtra(StoryLimitService.EXTRA_IDS) ?: ""

                val bringToFront = Intent(this@CaptureActivity, CaptureActivity::class.java)
                bringToFront.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(bringToFront)

                runOnUiThread {
                    if (!extraTargetPkg.isNullOrBlank()) {
                        val resultIntent = Intent().apply {
                            putExtra("RESULT_CLASS", cls.substringAfterLast("."))
                            putExtra("RESULT_IDS", ids)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                        return@runOnUiThread
                    }

                    // 下面是原有的“添加新应用”逻辑，保持不变
                    findViewById<LinearLayout>(R.id.llResult).visibility = android.view.View.VISIBLE
                    findViewById<EditText>(R.id.etPkg).setText(pkg)
                    findViewById<EditText>(R.id.etAppName).setText(pkg.substringAfterLast("."))
                    findViewById<EditText>(R.id.etCls).setText(cls.substringAfterLast("."))
                    findViewById<EditText>(R.id.etIds).setText(ids)

                    isCapturing = false
                    val btnStartCapture = findViewById<Button>(R.id.btnStartCapture)
                    btnStartCapture.isEnabled = true
                    btnStartCapture.text = "重新抓取"

                    Toast.makeText(this@CaptureActivity, "抓取成功！请确认并保存", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private var targetPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        targetPkg = intent.getStringExtra("EXTRA_TARGET_PKG")
        extraTargetPkg = intent.getStringExtra("EXTRA_TARGET_PKG")

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            captureReceiver,
            IntentFilter(StoryLimitService.ACTION_APP_CAPTURED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val btnStartCapture = findViewById<Button>(R.id.btnStartCapture)

        btnStartCapture.setOnClickListener {
            if (isCapturing) return@setOnClickListener

            isCapturing = true
            btnStartCapture.isEnabled = false

            val timer = object : CountDownTimer(10000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    btnStartCapture.text = "请切换目标页面 (${millisUntilFinished / 1000} 秒)"
                }

                override fun onFinish() {
                    btnStartCapture.text = "正在抓取当前页面..."
                    val intent = Intent(StoryLimitService.ACTION_START_CAPTURE).apply {
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
            }
            timer.start()

            btnStartCapture.postDelayed({
                moveTaskToBack(true)
            }, 1000)
        }

        findViewById<Button>(R.id.btnSaveApp).setOnClickListener {
            val pkg = findViewById<EditText>(R.id.etPkg).text.toString()
            val appName = findViewById<EditText>(R.id.etAppName).text.toString().ifBlank { pkg.substringAfterLast(".") }
            val cls = findViewById<EditText>(R.id.etCls).text.toString()
            val ids = findViewById<EditText>(R.id.etIds).text.toString()

            if (pkg.isBlank()) return@setOnClickListener

            val prefs = Prefs(this)

            if (targetPkg != null && targetPkg == pkg) {
                // 模式：为已有应用追加一条规则
                prefs.addRuleItem(pkg, RuleItem("新抓取规则 ${System.currentTimeMillis() % 1000}", cls, ids))
                Toast.makeText(this, "已为该应用追加新规则", Toast.LENGTH_SHORT).show()
            } else {
                // 模式：添加全新应用
                prefs.addCustomApp(AppConfig(pkg, appName, emptyList(), emptyList(), true))
                prefs.saveRuleItems(pkg, listOf(RuleItem("默认抓取规则", cls, ids)))
                val enabled = prefs.enabledPackages.toMutableSet()
                enabled.add(pkg)
                prefs.enabledPackages = enabled
                Toast.makeText(this, "应用「$appName」已成功添加", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(captureReceiver)
    }
}