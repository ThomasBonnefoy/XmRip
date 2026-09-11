package com.xmrip.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.xmrip.app.bt.HeadphoneClient
import com.xmrip.app.protocol.BatteryReport
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_BLUETOOTH_CONNECT = 1001
        private const val UUID_SCAN_TIMEOUT_MS = 4_000L
    }

    private lateinit var statusText: TextView
    private lateinit var batteryBig: TextView
    private lateinit var detailText: TextView
    private lateinit var deviceList: ListView
    private lateinit var refreshButton: Button

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var selectedDevice: BluetoothDevice? = null
    private var busy = false

    private val pendingDevices = mutableListOf<BluetoothDevice>()
    private val discoveredUuids = HashMap<String, List<ParcelUuid>>()
    private val uuidScanRunnable = Runnable { finishUuidScan() }

    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val rawUuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            val uuids = rawUuids?.filterIsInstance<ParcelUuid>() ?: return
            discoveredUuids[device.address] = uuids
            if (pendingDevices.isNotEmpty() &&
                pendingDevices.all { discoveredUuids.containsKey(it.address) }
            ) {
                finishUuidScan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(uuidReceiver, IntentFilter(BluetoothDevice.ACTION_UUID))
        requestPermissionIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        runOnUiThread { deviceList.removeCallbacks(uuidScanRunnable) }
        runCatching { unregisterReceiver(uuidReceiver) }
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (18 * resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "XmRip"
        title.textSize = 22f
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        root.addView(title)

        statusText = TextView(this).apply { textSize = 14f }
        val lpStatus = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lpStatus.topMargin = (8 * resources.displayMetrics.density).toInt()
        root.addView(statusText, lpStatus)

        batteryBig = TextView(this).apply {
            textSize = 64f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(batteryBig)

        detailText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(detailText)

        deviceList = ListView(this)
        val lpList = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
        )
        lpList.weight = 1f
        lpList.topMargin = (12 * resources.displayMetrics.density).toInt()
        root.addView(deviceList, lpList)

        refreshButton = Button(this).apply {
            text = "Actualiser"
            isEnabled = true
        }
        root.addView(refreshButton)
        refreshButton.setOnClickListener {
            if (selectedDevice != null) refreshBattery() else listDevices()
        }

        setContentView(root)
        setStatus("Recherche du casque…")
    }

    @Suppress("MissingPermission")
    private fun refreshBattery() {
        val device = selectedDevice ?: return
        if (busy) return
        busy = true
        setStatus("Connexion à ${device.name}…")
        Thread {
            try {
                val report = HeadphoneClient.connectAndReadBattery(device)
                runOnUiThread {
                    busy = false
                    showBattery(device.name ?: "Casque", report)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Échec : ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "Vérifie que Sound Connect est fermé (un seul contrôleur à la fois).")
                }
            }
        }.start()
    }

    private fun showBattery(deviceLabel: String, report: BatteryReport) {
        batteryBig.text = "${report.levelPercent}%"
        val modelLine = report.modelName?.let { "Modèle : $it" }
        detailText.text = listOfNotNull(
            "$deviceLabel — batterie ${report.levelPercent}% (${report.chargingLabel})",
            modelLine,
            report.firmwareVersion?.let { "Firmware : $it" },
            report.modelName?.let {
                if (it.uppercase().contains("XM6")) null
                else "⚠️ Ce modèle n'est pas le WH-1000XM6 — support non garanti."
            },
        ).joinToString("\n")
        setStatus("Connecté à $deviceLabel")
    }

    private fun requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH_CONNECT)
                return
            }
        }
        listDevices()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLUETOOTH_CONNECT && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) listDevices()
            else setStatus("Permission Bluetooth requise pour se connecter au casque.")
        }
    }

    @Suppress("MissingPermission")
    private fun listDevices() {
        val btAdapter = adapter
        if (btAdapter == null) {
            setStatus("Bluetooth non disponible sur cet appareil.")
            return
        }
        val bonded = btAdapter.bondedDevices
        if (bonded == null || bonded.isEmpty()) {
            setStatus("Aucun appareil appairé. Appaire ton casque dans les réglages Bluetooth.")
            return
        }
        val pre = bonded.filter { HeadphoneClient.isCandidate(it) }
        if (pre.isEmpty()) {
            setStatus("Aucun appareil Bluetooth classique trouvé.")
            return
        }

        pendingDevices.clear()
        pendingDevices.addAll(pre)
        discoveredUuids.clear()
        deviceList.adapter = null
        batteryBig.text = ""
        detailText.text = ""
        setStatus("Détection des casques Sony…")
        var needScan = false
        for (device in pre) {
            val cached = runCatching { device.uuids?.toList() }.getOrNull()
            if (cached != null) {
                discoveredUuids[device.address] = cached
            } else {
                needScan = true
                runCatching { device.fetchUuidsWithSdp() }
            }
        }
        if (needScan) {
            finishUuidScan()
        } else {
            deviceList.postDelayed(uuidScanRunnable, UUID_SCAN_TIMEOUT_MS)
        }
    }

    @Suppress("MissingPermission")
    private fun finishUuidScan() {
        deviceList.removeCallbacks(uuidScanRunnable)
        val sony = pendingDevices.filter { device ->
            discoveredUuids[device.address]
                ?.any { it.uuid == HeadphoneClient.SERVICE_UUID } == true
        }
        if (sony.isEmpty()) {
            listAllFallback()
            return
        }
        showDeviceList(sony)
    }

    @Suppress("MissingPermission")
    private fun listAllFallback() {
        setStatus(
            "Détection SDP sans résultat — liste des appareils appairés.\n" +
                "Seul le WH-1000XM6 est pris en charge : la connexion le confirme.",
        )
        showDeviceList(pendingDevices)
    }

    @Suppress("MissingPermission")
    private fun showDeviceList(devices: List<BluetoothDevice>) {
        val candidates = HeadphoneClient.sortSonyFirst(devices)
        if (candidates.size == 1) {
            autoConnect(candidates[0])
            return
        }
        val names = candidates.map { "${it.name}\n${it.address.uppercase(Locale.ROOT)}" }
        deviceList.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text2, names)
        deviceList.setOnItemClickListener { _, _, position, _ ->
            val device = candidates[position]
            autoConnect(device)
        }
        setStatus("Choisis ton casque :")
    }

    @Suppress("MissingPermission")
    private fun autoConnect(device: BluetoothDevice) {
        selectedDevice = device
        refreshButton.isEnabled = true
        batteryBig.text = "—"
        detailText.text = ""
        setStatus("Connexion automatique à ${device.name}…")
        refreshBattery()
    }

    private fun setStatus(msg: String) {
        runOnUiThread {
            if (::statusText.isInitialized) statusText.text = msg
            else Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}