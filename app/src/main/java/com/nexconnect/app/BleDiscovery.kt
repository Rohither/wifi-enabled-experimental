package com.nexconnect.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors

private data class DiscoveredBleDevice(val address: String, val name: String, val rssi: Int, val connectable: Boolean)

@Composable
fun BleDiscoveryCard(
    selectedVendor: String = "Daly",
    selectedProtocol: String = "Daly Smart BMS",
    onTelemetry: (BmsTelemetry) -> Unit = {},
    onConnectionChanged: (Boolean, String) -> Unit = { _, _ -> },
    onDisconnected: () -> Unit = {},
    onConnectionActivityChanged: (Boolean) -> Unit = {},
    onReadStatusChanged: (String) -> Unit = {},
    onKnownDeviceName: (String) -> Unit = {},
    cancelSearchSignal: Int = 0,
    connectionSlot: Int = 0,
) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(BluetoothManager::class.java) }
    val adapter = manager?.adapter
    var bluetoothEnabled by remember(adapter) { mutableStateOf(runCatching { adapter?.isEnabled == true }.getOrDefault(false)) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val devices = remember { mutableStateListOf<DiscoveredBleDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Scan nearby Bluetooth LE devices") }
    var timeoutRunnable by remember { mutableStateOf<Runnable?>(null) }
    var activeGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var connectedName by remember { mutableStateOf("") }
    val reconnectPrefs = remember(context, connectionSlot) {
        context.getSharedPreferences(if (connectionSlot == 0) "nex_ble_connection" else "nex_ble_connection_$connectionSlot", android.content.Context.MODE_PRIVATE)
    }
    var autoReconnect by remember(context) { mutableStateOf(reconnectPrefs.getBoolean("auto_reconnect", false)) }
    var savedAddress by remember(context) { mutableStateOf(reconnectPrefs.getString("device_address", "").orEmpty()) }
    var savedName by remember(context) { mutableStateOf(reconnectPrefs.getString("device_name", "BMS").orEmpty()) }
    var reconnectRunnable by remember { mutableStateOf<Runnable?>(null) }
    var reconnectAttempts by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var retryConnection by remember { mutableStateOf<(() -> Unit)?>(null) }
    var ownerDisposed by remember { mutableStateOf(false) }
    var txCharacteristic by remember { mutableStateOf<BluetoothGattCharacteristic?>(null) }
    var pollRunnable by remember { mutableStateOf<Runnable?>(null) }
    var connectionTimeoutRunnable by remember { mutableStateOf<Runnable?>(null) }
    var searchingSavedDevice by remember { mutableStateOf(false) }
    var userCancelledSearch by remember { mutableStateOf(false) }
    var startSavedDeviceScan by remember { mutableStateOf<(() -> Unit)?>(null) }
    var stopActiveScan by remember { mutableStateOf<(() -> Unit)?>(null) }
    val telemetryCallback by rememberUpdatedState(onTelemetry)
    val connectionCallback by rememberUpdatedState(onConnectionChanged)
    val disconnectedCallback by rememberUpdatedState(onDisconnected)
    val connectionActivityCallback by rememberUpdatedState(onConnectionActivityChanged)
    val readStatusCallback by rememberUpdatedState(onReadStatusChanged)
    val knownNameCallback by rememberUpdatedState(onKnownDeviceName)
    val currentSelectedVendor by rememberUpdatedState(selectedVendor)
    val currentSelectedProtocol by rememberUpdatedState(selectedProtocol)

    DisposableEffect(context, adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: android.content.Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    bluetoothEnabled = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        } catch (_: SecurityException) { }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { } }
    }
    val decoder = remember { DalyA5Decoder() }
    val modbusDecoder = remember { DalyModbusBleDecoder() }
    val jbdDecoder = remember { JbdV4BleDecoder() }
    var activeDriverProtocol by remember { mutableStateOf(selectedProtocol) }
    val decoderExecutor = remember { Executors.newSingleThreadExecutor { task -> Thread(task, "nex-ble-decoder").apply { isDaemon = true } } }
    var receivedModbus by remember { mutableStateOf(false) }
    var receivedAnyTelemetry by remember { mutableStateOf(false) }

    LaunchedEffect(savedName) { knownNameCallback(savedName) }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasScanPermissions(): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun scheduleReconnect() {
        if (userCancelledSearch || !autoReconnect || savedAddress.isBlank() || !hasScanPermissions()) return
        // One initial connection plus two retries; stop the full-screen search once all fail.
        if (reconnectAttempts >= 2) {
            reconnectRunnable?.let(handler::removeCallbacks)
            reconnectRunnable = null
            connectionActivityCallback(false)
            status = "Searching nearby for saved BMS…"
            reconnectAttempts = 0
            startSavedDeviceScan?.invoke()
            return
        }
        reconnectRunnable?.let(handler::removeCallbacks)
        val delayMs = 1_000L * (reconnectAttempts + 1)
        reconnectAttempts += 1
        val retry = Runnable { if (autoReconnect && activeGatt == null) retryConnection?.invoke() }
        reconnectRunnable = retry
        connectionActivityCallback(true)
        status = "BMS out of range · retrying automatically"
        handler.postDelayed(retry, delayMs)
    }

    fun output(line: String) { android.util.Log.d("NexBle", line) }
    fun clearConnectionTimeout() {
        connectionTimeoutRunnable?.let(handler::removeCallbacks)
        connectionTimeoutRunnable = null
    }
    fun armConnectionTimeout(gatt: BluetoothGatt) {
        clearConnectionTimeout()
        val timeout = Runnable {
            if (activeGatt == gatt) {
                output("Connection timed out; closing GATT and retrying")
                activeGatt = null
                pollRunnable?.let(handler::removeCallbacks)
                txCharacteristic = null
                try { gatt.disconnect(); gatt.close() } catch (_: SecurityException) { }
                connectionCallback(false, "")
                connectionActivityCallback(false)
                readStatusCallback("Connection timed out · retrying")
                status = "Connection timed out · retrying"
                scheduleReconnect()
            }
        }
        connectionTimeoutRunnable = timeout
        handler.postDelayed(timeout, 12_000L)
    }
    fun resetDecoders() { runCatching { decoderExecutor.execute { decoder.reset(); modbusDecoder.reset(); jbdDecoder.reset() } } }
    fun decodeInBackground(bytes: ByteArray) {
        runCatching {
            decoderExecutor.execute {
                val autoDetect = activeDriverProtocol.contains("auto-detect", ignoreCase = true)
                val sample = when {
                    autoDetect -> modbusDecoder.accept(bytes) ?: decoder.accept(bytes) ?: jbdDecoder.accept(bytes)
                    activeDriverProtocol.contains("JBD Software-board V4", ignoreCase = true) -> jbdDecoder.accept(bytes)
                    activeDriverProtocol.contains("Daly Smart BMS", ignoreCase = true) -> {
                        modbusDecoder.accept(bytes)?.also { handler.post { receivedModbus = true } } ?: decoder.accept(bytes)
                    }
                    else -> null
                }
                sample?.let { found ->
                    handler.post {
                        receivedAnyTelemetry = true
                        if (autoDetect) {
                            activeDriverProtocol = if (found.protocol.startsWith("JBD")) "JBD Software-board V4" else "Daly Smart BMS"
                        }
                        readStatusCallback("Receiving telemetry · ${found.protocol}")
                        telemetryCallback(found)
                    }
                }
            }
        }
    }
    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    output("Connected; discovering services…")
                    handler.post {
                        if (activeGatt == gatt) {
                            clearConnectionTimeout()
                            reconnectRunnable?.let(handler::removeCallbacks)
                            reconnectRunnable = null
                            reconnectAttempts = 0
                            status = "Connected to $connectedName · discovering services"
                            readStatusCallback("Discovering BMS services…")
                            connectionCallback(true, connectedName)
                            connectionActivityCallback(false)
                            try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: SecurityException) { }
                        }
                    }
                    try { gatt.discoverServices() } catch (_: SecurityException) { output("Bluetooth permission denied") }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    output("Disconnected (status $statusCode)")
                    handler.post {
                        if (activeGatt == gatt) {
                            clearConnectionTimeout()
                            pollRunnable?.let(handler::removeCallbacks)
                            activeGatt = null
                            try { gatt.close() } catch (_: SecurityException) { }
                            txCharacteristic = null
                            connectedName = ""
                            receivedModbus = false
                            status = "Disconnected from BMS"
                            connectionCallback(false, "")
                            connectionActivityCallback(false)
                            disconnectedCallback()
                            readStatusCallback("Disconnected")
                            if (!ownerDisposed) scheduleReconnect()
                        }
                    }
                } else {
                    output("Connection error $statusCode")
                    handler.post { connectionActivityCallback(false) }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    output("Service discovery failed ($statusCode)")
                    handler.post {
                        if (activeGatt == gatt) {
                            activeGatt = null
                            try { gatt.disconnect(); gatt.close() } catch (_: SecurityException) { }
                            connectionCallback(false, "")
                            readStatusCallback("Could not discover BMS services · reconnecting")
                            scheduleReconnect()
                        }
                    }
                    return
                }
                activeDriverProtocol = currentSelectedProtocol
                var rxCharacteristic: BluetoothGattCharacteristic? = null
                var txChar: BluetoothGattCharacteristic? = null
                var genericPair: Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? = null
                gatt.services.forEach { service ->
                    output("SERVICE ${service.uuid}")
                    var serviceRx: BluetoothGattCharacteristic? = null
                    var serviceTx: BluetoothGattCharacteristic? = null
                    service.characteristics.forEach { characteristic ->
                        val props = characteristic.properties
                        val labels = buildList {
                            if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                            if (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("WRITE")
                            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                        }.joinToString(", ").ifBlank { "other" }
                        output("  CHAR ${characteristic.uuid} [$labels]")
                        val shortUuid = characteristic.uuid.toString().substring(4, 8).lowercase()
                        if ((shortUuid == "fff1" || shortUuid == "ff01") && props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) rxCharacteristic = characteristic
                        if ((shortUuid == "fff2" || shortUuid == "ff02") && props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) txChar = characteristic
                        if (serviceRx == null && props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) serviceRx = characteristic
                        if (serviceTx == null && props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) serviceTx = characteristic
                        if (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            try {
                                gatt.setCharacteristicNotification(characteristic, true)
                                characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { descriptor ->
                                    descriptor.value = if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(descriptor)
                                }
                            } catch (_: SecurityException) { output("  Notification permission denied") }
                        }
                    }
                    if (genericPair == null && serviceRx != null && serviceTx != null) genericPair = serviceRx to serviceTx
                }
                val jbdSelected = currentSelectedProtocol.contains("JBD Software-board V4", ignoreCase = true)
                val dalySelected = currentSelectedProtocol.contains("Daly Smart BMS", ignoreCase = true)
                val autoDetectKnownProtocols = !jbdSelected && !dalySelected
                if (autoDetectKnownProtocols && genericPair != null) {
                    if (rxCharacteristic == null) rxCharacteristic = genericPair.first
                    if (txChar == null) txChar = genericPair.second
                }
                txCharacteristic = txChar
                if (rxCharacteristic != null && txChar != null) {
                    val autoDetect = autoDetectKnownProtocols
                    receivedAnyTelemetry = false
                    output(when { jbdSelected -> "BLE channels found. Starting read-only JBD V4 commands 03/04/05."; autoDetect -> "Profile is not a verified BLE driver; probing only known read-only DALY and JBD status requests."; else -> "BLE channels found. Trying DALY D2 Modbus live read first; A5 fallback follows if no valid reply." })
                    if (autoDetect) handler.post {
                        activeDriverProtocol = "auto-detect known read-only protocols"
                        status = "Connected · identifying BMS protocol"
                        readStatusCallback("Connected · identifying BMS protocol…")
                    }
                    pollRunnable?.let(handler::removeCallbacks)
                    val commands = listOf(0x90, 0x93, 0x94, 0x95, 0x96)
                    val jbdCommands = listOf(0x03, 0x04, 0x05)
                    var index = 0
                    var attempts = 0
                    lateinit var poll: Runnable
                    poll = Runnable {
                        val tx = txCharacteristic
                        if (tx != null && activeGatt == gatt) {
                            if (autoDetect && attempts >= 26 && !receivedAnyTelemetry) {
                                status = "Connected · no supported telemetry protocol detected"
                                readStatusCallback("No supported DALY/JBD telemetry reply · confirm BMS model and BLE protocol")
                                output("Read-only protocol detection completed without a valid telemetry response.")
                                return@Runnable
                            }
                            try {
                                tx.writeType = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                val driverNow = activeDriverProtocol
                                val jbdNow = driverNow.contains("JBD Software-board V4", ignoreCase = true)
                                val dalyNow = driverNow.contains("Daly Smart BMS", ignoreCase = true)
                                val autoProbe = autoDetect && !jbdNow && !dalyNow
                                val useA5Fallback = dalyNow && attempts >= 5 && !receivedModbus
                                tx.value = when {
                                    jbdNow -> jbdDecoder.request(jbdCommands[index % jbdCommands.size])
                                    autoProbe && index % 13 < 5 -> modbusDecoder.requestReadBlock()
                                    autoProbe && index % 13 < 10 -> decoder.request(commands[index % 13 - 5])
                                    autoProbe -> jbdDecoder.request(jbdCommands[index % 13 - 10])
                                    useA5Fallback -> decoder.request(commands[index % commands.size])
                                    else -> modbusDecoder.requestReadBlock()
                                }
                                gatt.writeCharacteristic(tx)
                                if (jbdNow || autoProbe || useA5Fallback) index++
                                attempts++
                                handler.postDelayed(poll, if (autoProbe || jbdNow || useA5Fallback) 900L else 1_500L)
                            } catch (_: SecurityException) { output("Bluetooth permission denied while polling") }
                        }
                    }
                    pollRunnable = poll
                    handler.postDelayed(poll, 1_000L)
                } else {
                    output("GATT services found. No compatible read/write BLE characteristic pair was detected.")
                    handler.post {
                        status = "Connected · no compatible BLE data channels"
                        readStatusCallback("Connected, but no compatible BLE data channels were found")
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                decodeInBackground(characteristic.value?.copyOf() ?: byteArrayOf())
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) decodeInBackground(characteristic.value?.copyOf() ?: byteArrayOf())
            }
        }
    }

    retryConnection = {
        if (!ownerDisposed && autoReconnect && activeGatt == null && savedAddress.isNotBlank() && hasScanPermissions()) {
            try {
                connectedName = savedName.ifBlank { "BMS" }
                status = "Reconnecting to $connectedName…"
                resetDecoders()
                receivedModbus = false
                connectionActivityCallback(true)
                activeGatt = adapter?.getRemoteDevice(savedAddress)?.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (activeGatt == null) { connectionActivityCallback(false); scheduleReconnect() }
                else armConnectionTimeout(activeGatt!!)
            } catch (_: SecurityException) {
                status = "Bluetooth permission is needed to reconnect"
            } catch (_: IllegalArgumentException) {
                status = "Saved address unavailable · searching nearby"
                reconnectAttempts = 2
                scheduleReconnect()
            }
        }
    }

    LaunchedEffect(adapter, savedAddress, autoReconnect) {
        if (!ownerDisposed && autoReconnect && activeGatt == null && hasScanPermissions()) retryConnection?.invoke()
    }

    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = try { device.address } catch (_: SecurityException) { "Unknown device" }
                val name = result.scanRecord?.deviceName ?: (try { device.name } catch (_: SecurityException) { null }) ?: "Unnamed BLE device"
                val discovered = DiscoveredBleDevice(address, name, result.rssi, result.isConnectable)
                handler.post {
                    val index = devices.indexOfFirst { it.address == address }
                    if (index >= 0) devices[index] = discovered else devices.add(discovered)
                    val savedNameMatches = savedName.isNotBlank() && (
                        name.equals(savedName, ignoreCase = true) ||
                            (name != "Unnamed BLE device" && savedName.length >= 4 && name.contains(savedName, ignoreCase = true))
                        )
                    val savedDeviceFound = searchingSavedDevice &&
                        (address.equals(savedAddress, ignoreCase = true) || savedNameMatches)
                    if (savedDeviceFound) {
                        searchingSavedDevice = false
                        timeoutRunnable?.let(handler::removeCallbacks)
                        timeoutRunnable = null
                        stopActiveScan?.invoke()
                        scanning = false
                        connectionActivityCallback(false)
                        savedAddress = address
                        reconnectPrefs.edit().putString("device_address", address).putString("device_name", name).apply()
                        savedName = name
                        reconnectAttempts = 0
                        status = "Saved BMS found · reconnecting…"
                        retryConnection?.invoke()
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    val wasSearchingSaved = searchingSavedDevice
                    scanning = false
                    searchingSavedDevice = false
                    connectionActivityCallback(false)
                    status = "Bluetooth scan failed (code $errorCode)"
                    if (wasSearchingSaved && autoReconnect && !ownerDisposed) {
                        connectionActivityCallback(true)
                        reconnectRunnable?.let(handler::removeCallbacks)
                        val retrySearch = Runnable { startSavedDeviceScan?.invoke() }
                        reconnectRunnable = retrySearch
                        handler.postDelayed(retrySearch, 1_000L)
                    }
                }
            }
        }
    }

    stopActiveScan = {
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) { }
    }

    fun stopScan(message: String) {
        timeoutRunnable?.let(handler::removeCallbacks)
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) { }
        scanning = false
        searchingSavedDevice = false
        connectionActivityCallback(false)
        status = message
    }

    fun cancelSearch() {
        userCancelledSearch = true
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) { }
        scanning = false
        searchingSavedDevice = false
        activeGatt?.let { gatt ->
            try { gatt.disconnect(); gatt.close() } catch (_: SecurityException) { }
        }
        activeGatt = null
        txCharacteristic = null
        connectionActivityCallback(false)
        status = "Search cancelled"
        connectionCallback(false, "")
        disconnectedCallback()
        readStatusCallback("Search cancelled")
    }

    var lastCancelSignal by remember { mutableIntStateOf(cancelSearchSignal) }
    LaunchedEffect(cancelSearchSignal) {
        if (cancelSearchSignal != lastCancelSignal) {
            lastCancelSignal = cancelSearchSignal
            cancelSearch()
        }
    }

    fun startScan() {
        userCancelledSearch = false
        val currentScanner = try { adapter?.bluetoothLeScanner } catch (_: SecurityException) { null }
        if (adapter == null || currentScanner == null) {
            status = "Bluetooth LE is not available on this phone"
            return
        }
        if (!adapter.isEnabled) {
            bluetoothEnabled = false
            status = "Turn on your mobile Bluetooth"
            return
        }
        bluetoothEnabled = true
        devices.clear()
        try {
            searchingSavedDevice = false
            timeoutRunnable?.let(handler::removeCallbacks)
            if (scanning) try { currentScanner.stopScan(callback) } catch (_: SecurityException) { }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            currentScanner.startScan(null, settings, callback)
            scanning = true
            connectionActivityCallback(true)
            status = "Scanning for 15 seconds…"
            val timeout = Runnable { stopScan("Scan complete") }
            timeoutRunnable = timeout
            handler.postDelayed(timeout, 15_000L)
        } catch (_: SecurityException) {
            status = "Bluetooth permission is required to scan"
        } catch (_: IllegalStateException) {
            status = "Bluetooth is not ready; try again"
        }
    }

    startSavedDeviceScan = {
        val currentScanner = try { adapter?.bluetoothLeScanner } catch (_: SecurityException) { null }
        if (!ownerDisposed && autoReconnect && savedAddress.isNotBlank() && hasScanPermissions() && currentScanner != null && adapter?.isEnabled == true) try {
            timeoutRunnable?.let(handler::removeCallbacks)
            if (scanning) try { currentScanner.stopScan(callback) } catch (_: SecurityException) { }
            searchingSavedDevice = true
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            currentScanner.startScan(null, settings, callback)
            scanning = true
            connectionActivityCallback(true)
            status = "Searching nearby for $savedName…"
            readStatusCallback("Searching nearby for saved BMS…")
            val timeout = Runnable {
                if (searchingSavedDevice) {
                    try { currentScanner.stopScan(callback) } catch (_: SecurityException) { }
                    searchingSavedDevice = false
                    scanning = false
                    timeoutRunnable = null
                    status = "Saved BMS not found yet · will retry"
                    readStatusCallback("Saved BMS not in range yet · retrying discovery")
                    if (autoReconnect && activeGatt == null && !ownerDisposed) {
                        reconnectRunnable?.let(handler::removeCallbacks)
                        val again = Runnable { startSavedDeviceScan?.invoke() }
                        reconnectRunnable = again
                        handler.postDelayed(again, 2_000L)
                    }
                }
            }
            timeoutRunnable?.let(handler::removeCallbacks)
            timeoutRunnable = timeout
            handler.postDelayed(timeout, 15_000L)
        } catch (_: SecurityException) {
            searchingSavedDevice = false
            scanning = false
            status = "Bluetooth permission is needed to find the saved BMS"
        } catch (_: IllegalStateException) {
            searchingSavedDevice = false
            scanning = false
            status = "Bluetooth scan could not start · retrying"
            if (autoReconnect && !ownerDisposed) handler.postDelayed({ startSavedDeviceScan?.invoke() }, 2_000L)
        } else if (!ownerDisposed && autoReconnect && savedAddress.isNotBlank() && hasScanPermissions()) {
            status = if (adapter?.isEnabled == false) "Turn on Bluetooth to reconnect to $savedName" else "Waiting for Bluetooth LE to become available…"
            handler.postDelayed({ startSavedDeviceScan?.invoke() }, 5_000L)
        }
    }

    var pendingBluetoothEnable by remember { mutableStateOf(false) }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        if (bluetoothEnabled) startScan()
        else status = "Bluetooth is still off"
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            if (pendingBluetoothEnable) {
                pendingBluetoothEnable = false
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else if (autoReconnect && savedAddress.isNotBlank()) retryConnection?.invoke() else startScan()
        } else {
            pendingBluetoothEnable = false
            status = "Bluetooth permission was not granted"
        }
    }

    fun connectTo(device: DiscoveredBleDevice) {
        userCancelledSearch = false
        if (adapter?.isEnabled != true) {
            bluetoothEnabled = false
            status = "Turn on your mobile Bluetooth"
            return
        }
        if (!hasScanPermissions()) { status = "Grant Bluetooth permission before connecting"; permissionLauncher.launch(permissions); return }
        try {
            reconnectRunnable?.let(handler::removeCallbacks)
            autoReconnect = true
            savedAddress = device.address
            savedName = device.name
            reconnectPrefs.edit().putString("device_address", device.address).putString("device_name", device.name).putBoolean("auto_reconnect", true).apply()
            reconnectAttempts = 0
            activeGatt?.disconnect()
            activeGatt?.close()
            clearConnectionTimeout()
            stopScan("Connecting to ${device.name}…")
            pollRunnable?.let(handler::removeCallbacks)
            resetDecoders()
            receivedModbus = false
            connectedName = device.name
            status = "Connecting to ${device.name}…"
            connectionActivityCallback(true)
            connectionCallback(false, device.name)
            readStatusCallback("Connecting to BMS…")
            disconnectedCallback()
            output("Connecting to ${device.name} (${device.address})…")
            activeGatt = adapter?.getRemoteDevice(device.address)?.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (activeGatt == null) { output("Could not start the BLE connection"); scheduleReconnect() }
            else armConnectionTimeout(activeGatt!!)
        } catch (_: SecurityException) { connectionActivityCallback(false); output("Bluetooth permission denied") }
        catch (_: IllegalArgumentException) { connectionActivityCallback(false); output("Invalid Bluetooth device address") }
    }

    DisposableEffect(adapter, callback) {
        onDispose {
            ownerDisposed = true
            timeoutRunnable?.let(handler::removeCallbacks)
            pollRunnable?.let(handler::removeCallbacks)
            reconnectRunnable?.let(handler::removeCallbacks)
            clearConnectionTimeout()
            try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) { }
            try { activeGatt?.disconnect(); activeGatt?.close() } catch (_: SecurityException) { }
            decoderExecutor.shutdownNow()
            connectionActivityCallback(false)
        }
    }

    fun disconnectFromDevice() {
        autoReconnect = false
        reconnectPrefs.edit().putBoolean("auto_reconnect", false).apply()
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        clearConnectionTimeout()
        pollRunnable?.let(handler::removeCallbacks)
        val gatt = activeGatt
        activeGatt = null
        connectionActivityCallback(false)
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) { }
        txCharacteristic = null
        connectedName = ""
        receivedModbus = false
        status = "Disconnected from BMS"
        connectionCallback(false, "")
        disconnectedCallback()
        readStatusCallback("Disconnected")
        output("Connection closed")
    }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Bluetooth connection", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("BMS ${connectionSlot + 1} discovery", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!bluetoothEnabled) {
                OutlinedButton(
                    onClick = {
                        if (!hasScanPermissions()) {
                            pendingBluetoothEnable = true
                            permissionLauncher.launch(permissions)
                        }
                        else runCatching {
                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }.onFailure { status = "Allow Bluetooth to be enabled, then scan again" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF57DDF1)),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0x2237B8D0),
                        contentColor = Color(0xFFB9F5FF)
                    )
                ) {
                    Text("Turn on your mobile Bluetooth", fontWeight = FontWeight.SemiBold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-reconnect to saved BMS", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(if (savedAddress.isBlank()) "Connect once to remember this BMS" else savedName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                androidx.compose.material3.Switch(checked = autoReconnect, onCheckedChange = { enabled ->
                    autoReconnect = enabled
                    reconnectPrefs.edit().putBoolean("auto_reconnect", enabled).apply()
                    if (enabled && savedAddress.isNotBlank()) retryConnection?.invoke()
                    if (!enabled) { reconnectRunnable?.let(handler::removeCallbacks); reconnectRunnable = null }
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (activeGatt != null) {
                    Button(onClick = { disconnectFromDevice() }) { Text("Disconnect · ${connectedName.ifBlank { "BMS" }}") }
                } else Button(onClick = {
                    if (scanning) stopScan("Scan stopped")
                    else if (hasScanPermissions()) startScan()
                    else permissionLauncher.launch(permissions)
                }) { Text(if (scanning) "Stop scan" else "Scan BLE devices") }
                if (devices.isNotEmpty()) OutlinedButton(onClick = { devices.clear(); status = "Scan nearby Bluetooth LE devices" }) { Text("Clear") }
            }
            devices.take(12).forEach { device ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${device.address} · ${device.rssi} dBm · ${if (device.connectable) "connectable" else "advertiser"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    if (device.connectable) OutlinedButton(onClick = { connectTo(device) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 3.dp)) { Text(if (activeGatt != null && connectedName == device.name) "Connected" else "Connect") }
                }
            }
            if (devices.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(when {
                    selectedProtocol.contains("Daly Smart BMS", true) -> "Experimental read-only DALY polling · verify exact firmware. No setup writes are sent."
                    selectedProtocol.contains("JBD Software-board V4", true) -> "Read-only JBD V4 polling · protocol support depends on the connected software-board firmware. No setup writes are sent."
                    else -> "Generic BLE discovery only. This model has no verified driver in this build, so no vendor polling commands are sent."
                }, color = Color(0xFF9A6B1A), fontSize = 11.sp)
            }
        }
    }
}

