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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.xmrip.app.bt.HeadphoneClient
import com.xmrip.app.bt.LastDevice
import com.xmrip.app.protocol.BatteryReport
import com.xmrip.app.protocol.MdrConstants
import com.xmrip.app.protocol.MdrSession
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_BLUETOOTH_CONNECT = 1001
        private const val UUID_SCAN_TIMEOUT_MS = 4_000L
        private const val ACTION_SET_NC_ASM = "com.xmrip.app.action.SET_NC_ASM"
        private const val EXTRA_NC_ASM_MODE = "com.xmrip.app.extra.NC_ASM_MODE"
    }

    private lateinit var statusText: TextView
    private lateinit var batteryBig: TextView
    private lateinit var detailText: TextView
    private lateinit var ncButton: Button
    private lateinit var ambButton: Button
    private lateinit var offButton: Button
    private lateinit var ambientSeekBar: SeekBar
    private lateinit var ambientLevelText: TextView
    private lateinit var autoAmbientCheck: CheckBox
    private lateinit var deviceList: ListView
    private lateinit var refreshButton: Button

    private var ncAsmState: com.xmrip.app.protocol.NcAsmState? = null
    private var lastAmbientLevel: Int = MdrConstants.AMBIENT_LEVEL_DEFAULT
    private var suppressSeekBar = false
    private var suppressAutoCheck = false

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var selectedDevice: BluetoothDevice? = null
    private var requestedMode: Int? = null
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
        requestedMode = parseModeFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val mode = parseModeFromIntent(intent)
        if (mode != null) {
            requestedMode = mode
            val device = selectedDevice
            if (device != null && !busy) {
                setNcAsmMode(device, mode)
            } else {
                listDevices()
            }
        }
    }

    private fun parseModeFromIntent(intent: Intent?): Int? {
        if (intent?.action != ACTION_SET_NC_ASM) return null
        return when (intent.getStringExtra(EXTRA_NC_ASM_MODE)) {
            "noise_cancelling" -> MdrSession.MODE_NC
            "ambient" -> MdrSession.MODE_AMBIENT
            "off" -> MdrSession.MODE_OFF
            else -> null
        }
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

        val ncRow = LinearLayout(this)
        ncRow.orientation = LinearLayout.HORIZONTAL
        val btnLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btnLp.topMargin = (12 * resources.displayMetrics.density).toInt()
        ncButton = Button(this).apply { text = "Réduction de bruit" }
        ambButton = Button(this).apply { text = "Son ambiant" }
        offButton = Button(this).apply { text = "Désactivé" }
        listOf(ncButton, ambButton, offButton).forEach {
            it.isEnabled = false
            ncRow.addView(it, btnLp)
        }
        root.addView(ncRow)
        ncButton.setOnClickListener { selectedDevice?.let { setNcAsmMode(it, MdrSession.MODE_NC) } }
        ambButton.setOnClickListener { selectedDevice?.let { setNcAsmMode(it, MdrSession.MODE_AMBIENT) } }
        offButton.setOnClickListener { selectedDevice?.let { setNcAsmMode(it, MdrSession.MODE_OFF) } }

        val ambientControls = LinearLayout(this)
        ambientControls.orientation = LinearLayout.VERTICAL

        val ambLevelRow = LinearLayout(this)
        ambLevelRow.orientation = LinearLayout.HORIZONTAL
        ambLevelRow.gravity = Gravity.CENTER_VERTICAL
        ambientLevelText = TextView(this).apply {
            textSize = 13f
            text = "Niveau ambiant : ${MdrConstants.AMBIENT_LEVEL_DEFAULT}"
        }
        val lpTxt = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        ambientLevelText.layoutParams = lpTxt
        ambientSeekBar = SeekBar(this)
        ambientSeekBar.max = MdrConstants.AMBIENT_LEVEL_MAX - 1
        ambientSeekBar.progress = MdrConstants.AMBIENT_LEVEL_DEFAULT - 1
        val lpSb = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpSb.leftMargin = (10 * resources.displayMetrics.density).toInt()
        ambientSeekBar.layoutParams = lpSb
        ambLevelRow.addView(ambientLevelText)
        ambLevelRow.addView(ambientSeekBar)
        ambientControls.addView(ambLevelRow)

        autoAmbientCheck = CheckBox(this).apply {
            text = "Son ambiant automatique"
            textSize = 14f
        }
        ambientControls.addView(autoAmbientCheck)

        val lpAmbient = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lpAmbient.topMargin = (12 * resources.displayMetrics.density).toInt()
        ambientControls.layoutParams = lpAmbient
        root.addView(ambientControls)

        ambientSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!suppressSeekBar) ambientLevelText.text = "Niveau ambiant : ${progress + 1}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (suppressSeekBar) return
                val level = seekBar?.progress?.plus(1) ?: return
                selectedDevice?.let { setAmbientLevel(it, level) }
            }
        })

        autoAmbientCheck.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoCheck) return@setOnCheckedChangeListener
            val device = selectedDevice
            if (device != null && ncAsmState?.enabled == true && ncAsmState?.mode == MdrConstants.NCASM_MODE_ASM) {
                setAutoAmbient(device, isChecked)
            } else {
                suppressAutoCheck = true
                autoAmbientCheck.isChecked = ncAsmState?.autoEnabled == true
                suppressAutoCheck = false
            }
        }

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
                LastDevice.save(this, device)
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
            report.ncAsm?.let { "Mode : ${it.label}" },
            modelLine,
            report.firmwareVersion?.let { "Firmware : $it" },
            report.modelName?.let {
                if (it.uppercase().contains("XM6")) null
                else "⚠️ Ce modèle n'est pas le WH-1000XM6 — support non garanti."
            },
        ).joinToString("\n")
        updateNcAsmButtons(report)
        setStatus("Connecté à $deviceLabel")
    }

    private fun updateNcAsmButtons(report: BatteryReport) {
        val nc = report.ncAsm
        ncAsmState = nc
        ncButton.isEnabled = nc != null
        ambButton.isEnabled = nc != null
        offButton.isEnabled = nc != null
        val active = when {
            nc != null && !nc.enabled -> MdrSession.MODE_OFF
            nc != null && nc.mode == MdrConstants.NCASM_MODE_ASM -> MdrSession.MODE_AMBIENT
            nc != null -> MdrSession.MODE_NC
            else -> -1
        }
        ncButton.isSelected = active == MdrSession.MODE_NC
        ambButton.isSelected = active == MdrSession.MODE_AMBIENT
        offButton.isSelected = active == MdrSession.MODE_OFF

        val ambientActive = nc != null && nc.enabled && nc.mode == MdrConstants.NCASM_MODE_ASM
        val manualLevel = ambientActive && !nc.autoEnabled
        if (manualLevel) lastAmbientLevel = nc.ambientLevel
        ambientSeekBar.isEnabled = manualLevel
        autoAmbientCheck.isEnabled = ambientActive
        suppressSeekBar = true
        if (manualLevel) {
            ambientSeekBar.progress = (nc.ambientLevel - 1).coerceIn(0, MdrConstants.AMBIENT_LEVEL_MAX - 1)
            ambientLevelText.text = "Niveau ambiant : ${nc.ambientLevel}"
        }
        suppressSeekBar = false
        suppressAutoCheck = true
        autoAmbientCheck.isChecked = nc?.autoEnabled == true
        suppressAutoCheck = false
    }

    @Suppress("MissingPermission")
    private fun setNcAsmMode(device: BluetoothDevice, mode: Int) {
        if (busy) return
        busy = true
        setStatus("Application du mode…")
        Thread {
            try {
                val autoEnabled = ncAsmState?.autoEnabled == true && mode == MdrSession.MODE_AMBIENT
                val report = HeadphoneClient.connectAndSetNcAsm(
                    device,
                    mode,
                    ambientLevel = if (mode == MdrSession.MODE_AMBIENT) lastAmbientLevel else MdrConstants.AMBIENT_LEVEL_DEFAULT,
                    autoEnabled = autoEnabled,
                )
                LastDevice.save(this, device)
                runOnUiThread {
                    busy = false
                    requestedMode = null
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

    @Suppress("MissingPermission")
    private fun setAmbientLevel(device: BluetoothDevice, level: Int) {
        if (busy) return
        busy = true
        setStatus("Réglage du niveau ambiant…")
        Thread {
            try {
                val autoEnabled = ncAsmState?.autoEnabled == true
                val report = HeadphoneClient.connectAndSetAmbientLevel(device, level, autoEnabled)
                LastDevice.save(this, device)
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

    @Suppress("MissingPermission")
    private fun setAutoAmbient(device: BluetoothDevice, enabled: Boolean) {
        if (busy) return
        busy = true
        setStatus(if (enabled) "Activation du son ambiant automatique…" else "Désactivation du son ambiant automatique…")
        Thread {
            try {
                val report = HeadphoneClient.connectAndSetAutoAmbient(device, enabled, lastAmbientLevel)
                LastDevice.save(this, device)
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
        val mode = requestedMode
        if (mode != null) {
            setStatus("Connexion à ${device.name} et application du mode…")
            setNcAsmMode(device, mode)
        } else {
            setStatus("Connexion automatique à ${device.name}…")
            refreshBattery()
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread {
            if (::statusText.isInitialized) statusText.text = msg
            else Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}