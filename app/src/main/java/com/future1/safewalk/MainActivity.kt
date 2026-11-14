package com.future1.safewalk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 보호자용 설정 화면: 권한 허용 → 보호 시작/중지 → PIN·옵션. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        fun add(v: android.view.View) = col.addView(
            v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 24 }
        )

        add(TextView(this).apply {
            text = "🚸 세이프워크"
            textSize = 28f
        })
        add(TextView(this).apply {
            text = "걸으면서 폰을 보면 화면이 잠기고, 횡단보도 앞에서는 바로 잠겨요.\n멈춰 서면 다시 쓸 수 있어요."
            textSize = 15f
        })
        status = TextView(this).apply { textSize = 15f }
        add(status)

        add(Button(this).apply {
            text = "① 위치·걸음 감지 권한 허용"
            setOnClickListener { requestBasicPermissions() }
        })
        add(Button(this).apply {
            text = "② 위치 '항상 허용' (화면이 꺼져 있어도 동작)"
            setOnClickListener { requestBackgroundLocation() }
        })
        add(Button(this).apply {
            text = "③ 다른 앱 위에 표시 허용 (잠금 화면용)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        add(CheckBox(this).apply {
            text = "신호 기다리며 서 있을 때도 횡단보도 앞에서는 잠그기"
            isChecked = prefs.lockWhileWaiting
            var reverting = false
            setOnCheckedChangeListener { box, checked ->
                if (reverting) return@setOnCheckedChangeListener
                withPin(onCancel = { reverting = true; box.isChecked = !checked; reverting = false }) {
                    prefs.lockWhileWaiting = checked
                    if (prefs.protectionOn) SafeWalkService.reload(this@MainActivity)
                }
            }
        })
        add(Button(this).apply {
            text = "보호자 PIN 설정"
            setOnClickListener { withPin { askNewPin() } }
        })

        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener { onToggle() }
        }
        add(toggle)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun onToggle() {
        if (prefs.protectionOn) {
            withPin {
                prefs.protectionOn = false
                SafeWalkService.stop(this)
                refresh()
            }
            return
        }
        val missing = missingRequirements()
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "먼저 허용해 주세요: ${missing.joinToString()}", Toast.LENGTH_LONG).show()
            return
        }
        prefs.protectionOn = true
        SafeWalkService.start(this)
        refresh()
    }

    private fun refresh() {
        val missing = missingRequirements()
        status.text = buildString {
            append(if (prefs.protectionOn) "상태: 보호 중 ✅" else "상태: 꺼짐")
            if (missing.isNotEmpty()) append("\n필요한 권한: ${missing.joinToString()}")
            if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                append("\n(권장) 위치를 '항상 허용'으로 바꾸면 재부팅 후에도 자동으로 켜져요.")
            }
            if (!prefs.hasPin) append("\n(권장) 아이가 끄지 못하게 보호자 PIN을 설정하세요.")
        }
        toggle.text = if (prefs.protectionOn) "보호 중지 (PIN 필요)" else "보호 시작"
    }

    private fun missingRequirements(): List<String> = buildList {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add("정확한 위치")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            add("신체 활동")
        }
        if (!Settings.canDrawOverlays(this@MainActivity)) add("다른 앱 위에 표시")
    }

    private fun requestBasicPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_BASIC)
    }

    private fun requestBackgroundLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "① 권한을 먼저 허용해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 11+에서는 시스템 설정 화면으로 이동해 '항상 허용'을 고른다
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---- PIN ----

    private fun withPin(onCancel: () -> Unit = {}, action: () -> Unit) {
        if (!prefs.hasPin) { action(); return }
        val input = pinField()
        AlertDialog.Builder(this)
            .setTitle("보호자 PIN 입력")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                if (prefs.checkPin(input.text.toString())) action()
                else {
                    Toast.makeText(this, "PIN이 틀렸어요", Toast.LENGTH_SHORT).show()
                    onCancel()
                }
            }
            .setNegativeButton("취소") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun askNewPin() {
        val input = pinField()
        AlertDialog.Builder(this)
            .setTitle("새 PIN (숫자 4자리 이상)")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val pin = input.text.toString()
                if (pin.length >= 4) {
                    prefs.setPin(pin)
                    Toast.makeText(this, "PIN을 저장했어요", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(this, "4자리 이상 입력해 주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun pinField() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    companion object {
        private const val REQ_BASIC = 1
        private const val REQ_BG = 2
    }
}
