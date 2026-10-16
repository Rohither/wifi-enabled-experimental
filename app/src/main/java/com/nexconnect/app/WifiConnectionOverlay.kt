package com.nexconnect.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class WifiAccessPoint(
    val ssid: String,
    val signalLevel: Int,
    val secure: Boolean,
    val wpa3Only: Boolean,
    val unsupportedSecurity: Boolean
)

internal data class WifiLinkUiState(
    val connecting: Boolean = false,
    val connectedSsid: String? = null,
    val message: String = "Choose the Wi-Fi network used by your inverter or device."
)

/** Holds the selected local Wi-Fi network while the screen is dismissed. */
internal class WifiConnectionController(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val preferences = appContext.getSharedPreferences("nex_wifi_connection", Context.MODE_PRIVATE)
    var state by mutableStateOf(WifiLinkUiState())
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    val isConnected: Boolean get() = state.connectedSsid != null
    val savedSsid: String? get() = preferences.getString("saved_ssid", null)

    fun connect(accessPoint: WifiAccessPoint, password: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            state = state.copy(message = "Android 10 or newer is needed to connect to a device network here. Use Android Wi-Fi settings on this phone.")
            return
        }
        if (accessPoint.unsupportedSecurity) {
            state = state.copy(message = "This network uses enterprise or legacy security that is not supported yet.")
            return
        }
        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(accessPoint.ssid)
        if (accessPoint.secure) {
            if (password.isBlank()) {
                state = state.copy(message = "Enter the Wi-Fi password to continue.")
                return
            }
            try {
                if (accessPoint.wpa3Only) specifierBuilder.setWpa3Passphrase(password)
                else specifierBuilder.setWpa2Passphrase(password)
            } catch (_: IllegalArgumentException) {
                state = state.copy(message = "Check the password and try again.")
                return
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()
        disconnect(clearMessage = false, clearSavedNetwork = false)
        preferences.edit().putString("saved_ssid", accessPoint.ssid).apply()
        state = WifiLinkUiState(connecting = true, message = "Connecting to ${accessPoint.ssid}… approve the Android network prompt if it appears.")
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.bindProcessToNetwork(network)
                state = WifiLinkUiState(connectedSsid = accessPoint.ssid, message = "Connected to ${accessPoint.ssid}. This link is ready for local device communication.")
            }

            override fun onUnavailable() {
                state = WifiLinkUiState(message = "Could not connect. Check the password and make sure the device is nearby.")
                callback = null
            }

            override fun onLost(network: Network) {
                if (connectivity.boundNetworkForProcess == network) connectivity.bindProcessToNetwork(null)
                state = WifiLinkUiState(message = "The Wi-Fi connection was lost. Select the network to reconnect.")
                callback = null
            }
        }
        callback = networkCallback
        try {
            connectivity.requestNetwork(request, networkCallback, 30_000)
        } catch (_: SecurityException) {
            callback = null
            state = WifiLinkUiState(message = "Wi-Fi permission is needed to connect to this network.")
        } catch (_: RuntimeException) {
            callback = null
            state = WifiLinkUiState(message = "Could not start the Wi-Fi connection. Try again.")
        }
    }

    fun disconnect(clearMessage: Boolean = true, clearSavedNetwork: Boolean = true) {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        connectivity.bindProcessToNetwork(null)
        if (clearSavedNetwork) preferences.edit().remove("saved_ssid").apply()
        state = WifiLinkUiState(message = if (clearMessage) "Wi-Fi connection cancelled." else state.message)
    }
}

@Composable
internal fun WifiConnectionOverlay(controller: WifiConnectionController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val wifiManager = remember(context) { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var accessPoints by remember { mutableStateOf(emptyList<WifiAccessPoint>()) }
    var selectedSsid by remember(controller) { mutableStateOf(controller.savedSsid) }
    var password by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf("Searching for nearby Wi-Fi networks…") }
    val selected = accessPoints.firstOrNull { it.ssid == selectedSsid }

    @SuppressLint("MissingPermission")
    fun readScanResults() {
        accessPoints = try {
            wifiManager.scanResults
                .mapNotNull { result ->
                    val ssid = result.SSID?.trim().orEmpty()
                    if (ssid.isBlank()) return@mapNotNull null
                    val capabilities = result.capabilities.orEmpty().uppercase()
                    val sae = "SAE" in capabilities
                    val psk = "PSK" in capabilities
                    val wep = "WEP" in capabilities
                    val enterprise = "EAP" in capabilities
                    WifiAccessPoint(ssid, result.level, sae || psk || wep || enterprise, sae && !psk, wep || enterprise)
                }
                .groupBy { it.ssid }
                .map { (_, entries) -> entries.maxBy { it.signalLevel } }
                .sortedByDescending { it.signalLevel }
        } catch (_: SecurityException) {
            emptyList()
        }
        if (selectedSsid == null) selectedSsid = controller.savedSsid
    }

    @SuppressLint("MissingPermission")
    fun scanNow() {
        if (!wifiManager.isWifiEnabled) {
            isScanning = false
            scanMessage = "Turn on Wi-Fi to find nearby networks."
            return
        }
        isScanning = true
        scanMessage = "Searching for nearby Wi-Fi networks…"
        val scanStarted = try { wifiManager.startScan() } catch (_: SecurityException) { false }
        readScanResults()
        if (!scanStarted) {
            isScanning = false
            scanMessage = if (accessPoints.isEmpty()) "Wi-Fi scan is unavailable right now. Tap Refresh to try again." else "Showing recently found networks."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) scanNow()
        else {
            isScanning = false
            scanMessage = "Allow nearby-device and location access to list Wi-Fi networks."
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scanNow()
    }
    fun requestScan() {
        val required = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) required += Manifest.permission.NEARBY_WIFI_DEVICES
        if (required.isEmpty()) scanNow() else permissionLauncher.launch(required.toTypedArray())
    }

    DisposableEffect(context, wifiManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    readScanResults()
                    isScanning = false
                    scanMessage = if (accessPoints.isEmpty()) "No Wi-Fi networks found nearby." else "Select a Wi-Fi network to continue."
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(Unit) { requestScan() }
    BackHandler {
        if (controller.isConnected) onDismiss() else {
            controller.disconnect()
            onDismiss()
        }
    }

    val transition = rememberInfiniteTransition(label = "Wi-Fi connection animation")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "Wi-Fi pulse")
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF090C25), Color(0xFF10143B), Color(0xFF190B35))))
    ) {
        val artworkHeight = maxHeight * .31f
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * if (controller.state.connecting) .40f else .23f
            val maxRadius = size.minDimension * if (controller.state.connecting) .22f else .34f
            repeat(4) { ring ->
                val progress = (phase + ring * .25f) % 1f
                val radius = maxRadius * (.38f + progress * .68f)
                drawCircle(Color(0xFF42DDF5).copy(alpha = (1f - progress) * .20f), radius, androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
            repeat(16) { point ->
                val angle = phase * 2f * PI.toFloat() + point * (2f * PI.toFloat() / 16f)
                val radius = maxRadius * (.72f + (point % 3) * .13f)
                val twinkle = .22f + .42f * kotlin.math.abs(sin((phase * PI + point).toDouble())).toFloat()
                drawCircle(Color(0xFF65DFF8).copy(alpha = twinkle), 1.7.dp.toPx(), androidx.compose.ui.geometry.Offset(cx + cos(angle) * radius, cy + sin(angle) * radius))
            }
            if (controller.state.connecting) {
                repeat(64) { index ->
                    val angle = phase * 2f * PI.toFloat() + index * (2f * PI.toFloat() / 64f)
                    val inner = maxRadius * 1.03f
                    val outer = maxRadius * if (index % 4 == 0) 1.22f else 1.15f
                    val x1 = cx + cos(angle) * inner
                    val y1 = cy + sin(angle) * inner
                    val x2 = cx + cos(angle) * outer
                    val y2 = cy + sin(angle) * outer
                    val distance = ((index - (phase * 64f).toInt() + 64) % 64)
                    drawLine(Color(0xFF50D9FF), androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), 2.dp.toPx(), alpha = if (distance < 18) .98f else .3f, cap = StrokeCap.Round)
                }
            }
        }
        if (controller.state.connecting) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    TextureView(viewContext).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                val player = MediaPlayer()
                                val surface = Surface(texture)
                                setTag(player)
                                player.setSurface(surface)
                                player.setDataSource(viewContext, Uri.parse("android.resource://${viewContext.packageName}/${R.raw.wifi_connecting_animation}"))
                                player.isLooping = true
                                player.setVolume(0f, 0f)
                                player.setOnPreparedListener { it.start() }
                                player.setOnErrorListener { mediaPlayer, _, _ -> mediaPlayer.release(); setTag(null); true }
                                player.prepareAsync()
                            }
                            override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                            override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                                (getTag() as? MediaPlayer)?.let { runCatching { it.release() } }
                                setTag(null)
                                return true
                            }
                        }
                    }
                },
                onRelease = { view ->
                    (view.getTag() as? MediaPlayer)?.let { runCatching { it.release() } }
                    view.setTag(null)
                }
            )
            Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 20.dp)) {
                OutlinedButton(
                    onClick = { controller.disconnect(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.4.dp, Color(0xFF5EE1F5)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Cancel connection", fontWeight = FontWeight.SemiBold) }
            }
        } else {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 92.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.wifi_connecting_art),
                contentDescription = "Animated Wi-Fi connection artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(.9f).height(artworkHeight).graphicsLayer {
                    val breathe = .98f + .025f * kotlin.math.abs(sin((phase * PI).toDouble())).toFloat()
                    scaleX = breathe
                    scaleY = breathe
                    alpha = .93f
                }
            )
            Text("Connect to Wi-Fi", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Select the network used by your inverter or device", color = Color(0xFF9BADE1), fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF52DDF3).copy(alpha = .30f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC111735))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, null, tint = Color(0xFF54DDF5), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Available networks", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        TextButton(onClick = { requestScan() }) {
                            if (isScanning) CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF57DFFC), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, "Refresh networks", tint = Color(0xFF57DFFC), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Refresh", color = Color(0xFF80E8F5), fontSize = 12.sp)
                        }
                    }
                    Text(scanMessage, color = Color(0xFFA9B6D5), fontSize = 11.sp)
                    if (accessPoints.isEmpty()) {
                        OutlinedButton(onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                            settingsLauncher.launch(intent)
                        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (wifiManager.isWifiEnabled) "Open Wi-Fi settings" else "Turn on Wi-Fi")
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().height(148.dp).verticalScroll(rememberScrollState())) {
                            accessPoints.take(8).forEach { point ->
                                val chosen = point.ssid == selectedSsid
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (chosen) Color(0xFF123F58).copy(alpha = .82f) else Color.Transparent)
                                        .clickable { selectedSsid = point.ssid; password = "" }.padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Wifi, null, tint = if (chosen) Color(0xFF5BE5F8) else Color(0xFF9BB3D0), modifier = Modifier.size(19.dp))
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(point.ssid, color = Color.White, fontSize = 13.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(when { point.unsupportedSecurity -> "Unsupported security"; point.secure -> if (point.wpa3Only) "WPA3 secured" else "Password protected"; else -> "Open network" }, color = Color(0xFFAAB8D2), fontSize = 10.sp)
                                    }
                                    Text("●".repeat((WifiManager.calculateSignalLevel(point.signalLevel, 4) + 1).coerceIn(1, 4)), color = Color(0xFF5BDCF4), fontSize = 9.sp)
                                }
                            }
                        }
                    }
                    if (selected != null && selected.secure) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Wi-Fi password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !controller.state.connecting,
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0C1430),
                                unfocusedContainerColor = Color(0xFF0C1430),
                                focusedIndicatorColor = Color(0xFF56DDF5),
                                unfocusedIndicatorColor = Color(0xFF526180),
                                focusedLabelColor = Color(0xFF73E5F4),
                                unfocusedLabelColor = Color(0xFF9BAED0),
                                cursorColor = Color(0xFF73E5F4)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                    if (selected != null && !selected.unsupportedSecurity && !controller.isConnected) {
                        Button(
                            onClick = { controller.connect(selected, password) },
                            enabled = !controller.state.connecting && (!selected.secure || password.isNotBlank()),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF208CB8), contentColor = Color.White)
                        ) {
                            if (controller.state.connecting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (controller.state.connecting) "Connecting…" else "Connect to ${selected.ssid}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val connected = controller.state.connectedSsid != null
            Text(
                controller.state.message,
                color = if (connected) Color(0xFF71F0CB) else Color(0xFFB6C1DA),
                fontSize = 12.sp,
                fontWeight = if (connected || controller.state.connecting) FontWeight.Medium else FontWeight.Normal
            )
            if (connected) TextButton(onClick = onDismiss) { Text("Done", color = Color(0xFF7DEAF6), fontWeight = FontWeight.SemiBold) }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 20.dp)) {
            OutlinedButton(
                onClick = {
                    controller.disconnect()
                    password = ""
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.4.dp, Color(0xFF5EE1F5)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(if (controller.isConnected) "Disconnect Wi-Fi" else if (controller.state.connecting) "Cancel connection" else "Cancel", fontWeight = FontWeight.SemiBold)
            }
        }
        }
    }
}
