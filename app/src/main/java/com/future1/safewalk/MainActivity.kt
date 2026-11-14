package com.future1.safewalk

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import com.future1.safewalk.core.CrosswalkIndex
import com.future1.safewalk.core.GeoPoint
import com.future1.safewalk.core.Level
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
    private lateinit var dataStatus: TextView
    private lateinit var downloadButton: Button
    private lateinit var testResult: TextView
    private lateinit var liveStatus: TextView
    private lateinit var simulateButton: Button
    private lateinit var repo: CrosswalkRepository
    private var lastChecked: GeoPoint? = null
    private val ui = Handler(Looper.getMainLooper())
    private val liveTick = object : Runnable {
        override fun run() {
            refreshLive()
            ui.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = CrosswalkRepository.get(this)

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

        // ---- 공공 횡단보도 데이터 ----
        add(section("공공 횡단보도 데이터"))
        dataStatus = TextView(this).apply { textSize = 14f }
        add(dataStatus)
        add(Button(this).apply {
            text = "공공데이터포털 인증키 입력"
            setOnClickListener { withPin { askServiceKey() } }
        })
        downloadButton = Button(this).apply {
            text = "전국 횡단보도 데이터 받기 (Wi-Fi 권장)"
            setOnClickListener { downloadPublicData() }
        }
        add(downloadButton)

        // ---- 테스트 ----
        add(section("테스트"))
        add(Button(this).apply {
            text = "📍 지금 위치에서 가장 가까운 횡단보도 찾기"
            setOnClickListener { checkHere() }
        })
        testResult = TextView(this).apply { textSize = 14f }
        add(testResult)
        add(Button(this).apply {
            text = "🗺 그 횡단보도를 지도에서 보기"
            setOnClickListener { openNearestOnMap() }
        })
        simulateButton = Button(this).apply { setOnClickListener { toggleSimulation() } }
        add(simulateButton)
        liveStatus = TextView(this).apply { textSize = 14f }
        add(liveStatus)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.post(liveTick)
    }

    override fun onPause() {
        ui.removeCallbacks(liveTick)
        super.onPause()
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 20f
        setPadding(0, 32, 0, 0)
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
        refreshDataStatus()
    }

    // ---- 공공데이터 ----

    private fun refreshDataStatus() {
        if (repo.downloading) return
        val count = repo.publicIndex.size
        val updated = prefs.publicUpdatedAt
        dataStatus.text = buildString {
            append("인증키: ").append(if (prefs.serviceKey.isBlank()) "없음 ❌" else "입력됨 ✅")
            append("\n저장된 공공 횡단보도: ")
            if (count > 0) {
                append("%,d개".format(count))
                if (updated > 0) append(" (").append(DateFormat.format("yyyy-MM-dd", updated)).append(" 받음)")
            } else if (updated > 0) {
                append("불러오는 중…")
            } else {
                append("아직 없음 — 인증키 입력 후 '받기'를 눌러 주세요")
            }
        }
    }

    private fun askServiceKey() {
        val input = EditText(this).apply {
            hint = "일반 인증키 (Decoding 또는 Encoding)"
            setText(prefs.serviceKey)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("공공데이터포털 인증키")
            .setMessage("data.go.kr에서 「전국횡단보도표준데이터」 오픈API 활용신청 후 받은 일반 인증키를 붙여넣어 주세요.")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                prefs.serviceKey = input.text.toString()
                refreshDataStatus()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun downloadPublicData() {
        val key = prefs.serviceKey
        if (key.isBlank()) {
            Toast.makeText(this, "인증키를 먼저 입력해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        downloadButton.isEnabled = false
        dataStatus.text = "공공데이터 서버에 연결 중…"
        repo.downloadPublic(
            key,
            onProgress = { fetched, total ->
                val pct = if (total > 0) fetched * 100 / total else 0
                dataStatus.text = "받는 중… %,d / %,d (%d%%)".format(fetched, total, pct)
            },
            onDone = { result ->
                downloadButton.isEnabled = true
                result.onSuccess {
                    Toast.makeText(this, "횡단보도 %,d개를 저장했어요".format(it), Toast.LENGTH_LONG).show()
                    refreshDataStatus()
                }.onFailure {
                    refreshDataStatus()
                    AlertDialog.Builder(this)
                        .setTitle("받기 실패")
                        .setMessage(it.message ?: it.toString())
                        .setPositiveButton("확인", null)
                        .show()
                }
            },
        )
    }

    // ---- 테스트 ----

    @SuppressLint("MissingPermission")
    private fun checkHere() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "① 위치 권한을 먼저 허용해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        testResult.text = "위치 찾는 중… (밖에서 하면 더 정확해요)"
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
        else LocationManager.NETWORK_PROVIDER
        lm.getLastKnownLocation(provider)?.let { showTestResult(it, approximate = true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                if (loc != null) showTestResult(loc, approximate = false)
                else testResult.text = "위치를 못 찾았어요. 위치(GPS)가 켜져 있는지 확인해 주세요."
            }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { loc -> showTestResult(loc, approximate = false) }, Looper.getMainLooper())
        }
    }

    private fun showTestResult(loc: Location, approximate: Boolean) {
        val here = GeoPoint(loc.latitude, loc.longitude)
        lastChecked = here
        repo.maybeRefreshOsm(here)
        val radius = prefs.config().crosswalkRadiusM
        fun line(label: String, idx: CrosswalkIndex): String {
            if (idx.size == 0) return "$label: 데이터 없음"
            val n = idx.nearest(here) ?: return "$label: 1km 안에 없음"
            val mark = if (n.distanceM <= radius) " ← 횡단보도 앞으로 판정" else ""
            return "$label: %.0fm$mark".format(n.distanceM)
        }
        testResult.text = buildString {
            if (approximate) append("(마지막으로 알던 위치, 새 위치 찾는 중…)\n")
            append("내 위치: %.5f, %.5f".format(here.lat, here.lon))
            if (loc.hasAccuracy()) append("  (오차 약 %.0fm)".format(loc.accuracy))
            append("\n").append(line("공공데이터", repo.publicIndex))
            append("\n").append(line("OpenStreetMap", repo.osmIndex))
            if (repo.osmIndex.size == 0) append(" (몇 초 뒤 다시 누르면 보여요)")
            append("\n'횡단보도 앞' 기준: %.0fm 이내".format(radius))
        }
    }

    private fun openNearestOnMap() {
        val here = lastChecked
        val n = here?.let { repo.nearest(it) }
        if (n == null) {
            Toast.makeText(this, "먼저 '가장 가까운 횡단보도 찾기'를 눌러 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val p = n.nearest.point
        val uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode("횡단보도 (" + n.source.label + ")")})")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "지도 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSimulation() {
        val now = SystemClock.elapsedRealtime()
        prefs.simulateWalkingUntil = if (now < prefs.simulateWalkingUntil) 0L else now + SIMULATE_MS
        if (prefs.simulateWalkingUntil > 0 && !prefs.protectionOn) {
            Toast.makeText(this, "'보호 시작'을 눌러야 잠금이 동작해요", Toast.LENGTH_LONG).show()
        }
        refreshLive()
    }

    private fun refreshLive() {
        val now = SystemClock.elapsedRealtime()
        val left = (prefs.simulateWalkingUntil - now) / 1000
        simulateButton.text = if (left > 0) "🚶 걷는 중으로 가정 끄기 (${left}초 남음)"
        else "🚶 걷는 중으로 가정하기 (5분, 앉아서 잠금 테스트)"

        val st = SafeWalkService.status
        liveStatus.text = when {
            !prefs.protectionOn -> "실시간 상태: 보호가 꺼져 있어요"
            st == null -> "실시간 상태: 서비스 시작 중…"
            else -> buildString {
                append("실시간 상태\n")
                append("걷는 중: ").append(if (st.snapshot.walking) "예" else "아니오")
                if (st.simulatedWalking) append(" (가정)")
                append("\n위치: ").append(if (st.hasLocation) "수신 중" else "아직 없음 (실외로 나가 보세요)")
                append("\n가장 가까운 횡단보도: ")
                val n = st.nearest
                if (n == null) append("1km 안에 없음")
                else append("%.0fm (%s)".format(n.nearest.distanceM, n.source.label))
                append("\n판정: ").append(
                    when (st.decision.level) {
                        Level.SAFE -> "안전"
                        Level.CAUTION -> "주의 (걸으며 폰 사용)"
                        Level.DANGER -> "위험 (횡단보도 앞)"
                    }
                )
                append(if (st.decision.locked) " — 잠금 중" else "")
            }
        }
        if (!repo.downloading) refreshDataStatus()
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
        private const val SIMULATE_MS = 5 * 60_000L
        private const val REQ_BASIC = 1
        private const val REQ_BG = 2
    }
}
