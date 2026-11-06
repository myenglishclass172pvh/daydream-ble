package com.example.daydreamble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), DaydreamBleClient.Listener {

    private companion object {
        const val SCAN_MILLIS = 20_000L
        const val UI_REFRESH_MS = 60L     // ~16 fps, đủ mượt mà không nghẽn UI thread
        const val MAX_LOG_CHARS = 20_000
    }

    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var listDevices: ListView

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val found = mutableListOf<BluetoothDevice>()
    private val labels = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private val ui = Handler(Looper.getMainLooper())
    private var scanning = false

    private lateinit var ble: DaydreamBleClient
    private var lastState: DaydreamState? = null
    private var lastRaw: ByteArray? = null
    private var prevButtons = 0
    private var frameCount = 0
    private var lastRateStamp = 0L
    private var fps = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /* ------------------------------------------------------------ */
    /*  Vòng đời                                                     */
    /* ------------------------------------------------------------ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnClearLog = findViewById(R.id.btnClearLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvState = findViewById(R.id.tvState)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        listDevices = findViewById(R.id.listDevices)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listDevices.adapter = listAdapter
        listDevices.setOnItemClickListener { _, _, pos, _ ->
            stopScan()
            ble.connect(found[pos])
        }

        ble = DaydreamBleClient(this, this)

        btnScan.setOnClickListener { if (scanning) stopScan() else ensurePermissionsThenScan() }
        btnDisconnect.setOnClickListener {
            ble.close()
            onLog("Đã yêu cầu ngắt kết nối.")
        }
        btnClearLog.setOnClickListener { tvLog.text = "" }

        tvStatus.text = "Chưa kết nối"
        onLog("Sẵn sàng. Giữ nút Home của tay cầm ~3 giây cho tới khi đèn LED nhấp nháy, rồi bấm Quét thiết bị.")
        startUiLoop()
    }

    override fun onDestroy() {
        stopScan()
        ble.close()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* ------------------------------------------------------------ */
    /*  Quyền                                                        */
    /* ------------------------------------------------------------ */

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                startScan()
            } else {
                onLog("Thiếu quyền Bluetooth/Vị trí nên không quét được. Cấp quyền trong Cài đặt > Ứng dụng.")
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (adapter?.isEnabled == true) startScan() else onLog("Bluetooth vẫn đang tắt.")
        }

    private fun ensurePermissionsThenScan() {
        val ad = adapter
        if (ad == null) {
            onLog("Máy này không có Bluetooth.")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            onLog("Máy này không hỗ trợ Bluetooth Low Energy.")
            return
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!ad.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationOn()) {
            onLog("Android 11 trở xuống bắt buộc bật Dịch vụ vị trí thì mới quét BLE được.")
            Toast.makeText(this, "Hãy bật Vị trí (Location)", Toast.LENGTH_LONG).show()
            return
        }
        startScan()
    }

    private fun isLocationOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /* ------------------------------------------------------------ */
    /*  Quét BLE                                                     */
    /* ------------------------------------------------------------ */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { add(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            btnScan.text = "Quét thiết bị"
            onLog("Quét thất bại, mã lỗi $errorCode")
        }

        private fun add(r: ScanResult) {
            val dev = r.device
            if (found.any { it.address == dev.address }) return

            val name = dev.name ?: r.scanRecord?.deviceName
            val isDaydream = name?.contains("daydream", true) == true ||
                    r.scanRecord?.serviceUuids?.any { it.uuid == DaydreamUuid.SERVICE } == true

            // Bỏ qua thiết bị không tên và không phải Daydream cho danh sách đỡ rối.
            if (name == null && !isDaydream) return

            found.add(dev)
            val mark = if (isDaydream) "★ " else ""
            labels.add("$mark${name ?: "(không tên)"}\n${dev.address}   ${r.rssi} dBm")
            listAdapter.notifyDataSetChanged()
            if (isDaydream) onLog("Thấy tay cầm Daydream: ${dev.address}")
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onLog("Không lấy được bluetoothLeScanner.")
            return
        }
        found.clear(); labels.clear(); listAdapter.notifyDataSetChanged()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Quét không lọc để bạn nhìn thấy mọi thiết bị; tay cầm được đánh dấu ★.
        scanner.startScan(null, settings, scanCallback)
        scanning = true
        btnScan.text = "Dừng quét"
        onLog("Bắt đầu quét (${SCAN_MILLIS / 1000} giây)…")
        ui.postDelayed({ if (scanning) stopScan() }, SCAN_MILLIS)
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        btnScan.text = "Quét thiết bị"
        onLog("Đã dừng quét. Tìm được ${found.size} thiết bị.")
    }

    /* ------------------------------------------------------------ */
    /*  Callback từ DaydreamBleClient                                */
    /* ------------------------------------------------------------ */

    override fun onLog(msg: String) {
        tvLog.append("${timeFmt.format(Date())}  $msg\n")
        if (tvLog.length() > MAX_LOG_CHARS) {
            tvLog.text = tvLog.text.substring(tvLog.length() - MAX_LOG_CHARS / 2)
        }
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onConnectionState(connected: Boolean, deviceName: String?) {
        tvStatus.text = if (connected) "Đã kết nối: ${deviceName ?: "thiết bị"}" else "Chưa kết nối"
        if (!connected) {
            lastState = null
            tvState.text = "—"
        }
    }

    override fun onBattery(percent: Int) = onLog("Pin tay cầm: $percent%")

    override fun onFrame(state: DaydreamState, raw: ByteArray) {
        lastState = state
        lastRaw = raw
        frameCount++

        val now = System.currentTimeMillis()
        if (now - lastRateStamp >= 1000) {
            fps = frameCount; frameCount = 0; lastRateStamp = now
        }

        // Chỉ ghi log khi tổ hợp nút thay đổi → log không bị ngập 60 dòng/giây.
        val mask = (if (state.clickBtn) 1 else 0) or
                (if (state.homeBtn) 2 else 0) or
                (if (state.appBtn) 4 else 0) or
                (if (state.volDownBtn) 8 else 0) or
                (if (state.volUpBtn) 16 else 0)
        if (mask != prevButtons) {
            val names = buildList {
                if (state.clickBtn) add("CLICK")
                if (state.homeBtn) add("HOME")
                if (state.appBtn) add("APP")
                if (state.volDownBtn) add("VOL-")
                if (state.volUpBtn) add("VOL+")
            }
            onLog(
                "Nút: ${if (names.isEmpty()) "(nhả hết)" else names.joinToString("+")}" +
                        "   raw=${DaydreamDecoder.toHex(raw)}"
            )
            prevButtons = mask
        }
    }

    /* ------------------------------------------------------------ */
    /*  Vòng lặp vẽ UI                                               */
    /* ------------------------------------------------------------ */

    private fun startUiLoop() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                lastState?.let { render(it) }
                ui.postDelayed(this, UI_REFRESH_MS)
            }
        }, UI_REFRESH_MS)
    }

    private fun render(s: DaydreamState) {
        fun f(v: Double) = String.format(Locale.US, "%+7.3f", v)
        fun on(b: Boolean) = if (b) "[X]" else "[ ]"

        tvState.text = buildString {
            append("seq ${s.seq}   ${fps} gói/giây\n\n")
            append("${on(s.clickBtn)} Click   ${on(s.homeBtn)} Home   ${on(s.appBtn)} App\n")
            append("${on(s.volDownBtn)} Vol-    ${on(s.volUpBtn)} Vol+\n\n")
            append(
                if (s.touching)
                    "Touchpad  x=${String.format(Locale.US, "%.3f", s.touchX)}" +
                            "  y=${String.format(Locale.US, "%.3f", s.touchY)}\n\n"
                else "Touchpad  (không chạm)\n\n"
            )
            append("Ori (rad)  ${f(s.oriX)} ${f(s.oriY)} ${f(s.oriZ)}\n")
            append("Acc (m/s²) ${f(s.accX)} ${f(s.accY)} ${f(s.accZ)}\n")
            append("Gyr (rad/s)${f(s.gyrX)} ${f(s.gyrY)} ${f(s.gyrZ)}\n\n")
            append("RAW ${lastRaw?.let { DaydreamDecoder.toHex(it) } ?: ""}")
        }
    }
}
