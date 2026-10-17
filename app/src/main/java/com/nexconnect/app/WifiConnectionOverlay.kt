package com.nexconnect.app

import android.Manifest
import android.annotation.SuppressLint
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class WifiLinkUiState(
    val connecting: Boolean = false,
    val connectedSsid: String? = null,
    val message: String = "Choose the Wi-Fi network used by your inverter or device."
)

private data class SavedWifiCredentials(val ssid: String, val password: String, val secured: Boolean, val wpa3: Boolean)

/** Encrypt Wi-Fi names and passwords with a device-local Android Keystore key. */
private class WifiCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nex_wifi_credentials", Context.MODE_PRIVATE)
    private val alias = "nexconnect_wifi_credentials_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encode(credentials: List<SavedWifiCredentials>) {
        val networks = org.json.JSONArray()
        credentials.forEach { network -> networks.put(JSONObject().put("ssid", network.ssid).put("password", network.password)
            .put("secured", network.secured).put("wpa3", network.wpa3)) }
        val clear = JSONObject().put("networks", networks).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("credential_blob", Base64.encodeToString(cipher.iv + cipher.doFinal(clear), Base64.NO_WRAP)).apply()
    }

    fun loadAll(): List<SavedWifiCredentials> = runCatching {
        val data = Base64.decode(prefs.getString("credential_blob", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        if (json.has("networks")) {
            val networks = json.getJSONArray("networks")
            (0 until networks.length()).map { index -> networks.getJSONObject(index).let { item ->
                SavedWifiCredentials(item.getString("ssid"), item.getString("password"), item.getBoolean("secured"), item.getBoolean("wpa3"))
            } }
        } else listOf(SavedWifiCredentials(json.getString("ssid"), json.getString("password"), json.getBoolean("secured"), json.getBoolean("wpa3")))
    }.getOrDefault(emptyList())

    fun load(ssid: String? = null): SavedWifiCredentials? = if (ssid == null) loadAll().firstOrNull() else loadAll().firstOrNull { it.ssid == ssid }
    fun save(credentials: SavedWifiCredentials) = encode(loadAll().filterNot { it.ssid == credentials.ssid } + credentials)
    fun clear() { prefs.edit().remove("credential_blob").apply() }
}
/** Owns the active Wi-Fi request and encrypted saved-network credentials. */
internal class WifiConnectionController(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val credentials = WifiCredentialStore(appContext)
    var state by mutableStateOf(WifiLinkUiState())
        private set
    private var requestCallback: ConnectivityManager.NetworkCallback? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) markWifiConnected()
            else refreshActiveNetwork()
        }
        override fun onLost(network: Network) {
            refreshActiveNetwork()
        }
        override fun onAvailable(network: Network) { refreshActiveNetwork() }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        refreshActiveNetwork()
    }

    val isConnected: Boolean get() = state.connectedSsid != null
    val savedSsid: String? get() = credentials.load()?.ssid
    val savedNetworkCount: Int get() = credentials.loadAll().size
    fun passwordFor(ssid: String): String? = credentials.load(ssid)?.password

    @SuppressLint("MissingPermission")
    fun connect(ssid: String, password: String, secured: Boolean, wpa3: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            state = state.copy(message = "Direct Wi-Fi connection requires Android 10 or newer. Use Android Wi-Fi settings to connect.")
            return
        }
        if (ssid.isBlank() || (secured && password.isBlank())) {
            state = state.copy(message = "Select a Wi-Fi network and enter its password.")
            return
        }
        val saved = SavedWifiCredentials(ssid, password, secured, wpa3)
        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (secured) {
                if (wpa3) specifierBuilder.setWpa3Passphrase(password) else specifierBuilder.setWpa2Passphrase(password)
            }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifierBuilder.build()).build()
            requestCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            state = WifiLinkUiState(connecting = true, message = "Connecting to $ssid… approve the Android Wi-Fi request if shown.")
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivity.bindProcessToNetwork(network)
                    val autoJoinReady = runCatching { credentials.save(saved); if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) registerAutoJoin(saved) else true }.getOrDefault(false)
                    state = WifiLinkUiState(
                        connectedSsid = ssid,
                        message = if (autoJoinReady) "Connected to $ssid. Credentials are encrypted; Android can reconnect automatically next time."
                        else "Connected to $ssid. Credentials are encrypted and saved on this device; Android auto-join could not be enabled."
                    )
                }
                override fun onUnavailable() {
                    state = WifiLinkUiState(message = "Could not connect to $ssid. Check the password and signal, then retry.")
                    requestCallback = null
                }
                override fun onLost(network: Network) {
                    if (connectivity.boundNetworkForProcess == network) connectivity.bindProcessToNetwork(null)
                    state = WifiLinkUiState(connecting = requestCallback != null, message = "Wi-Fi signal lost. Waiting to reconnect to the saved network…")
                }
            }
            requestCallback = callback
            // Keep the app-scoped local-device request alive; timed requests can drop the BMS link.
            connectivity.requestNetwork(request, callback)
        } catch (_: IllegalArgumentException) {
            state = WifiLinkUiState(message = "This Wi-Fi password or security mode is not supported.")
        } catch (_: SecurityException) {
            state = WifiLinkUiState(message = "Allow nearby Wi-Fi access to connect. No location permission is requested.")
        } catch (_: RuntimeException) {
            state = WifiLinkUiState(message = "Could not start the Wi-Fi connection. Try again.")
        }
    }

    /** Ask Android to save this network in the user's Wi-Fi list (Android 11+). */
    fun savedNetworkIntent(ssid: String, password: String, secured: Boolean, wpa3: Boolean): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val suggestion = buildSuggestion(SavedWifiCredentials(ssid, password, secured, wpa3))
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
            Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion)
        )
    }

    @SuppressLint("MissingPermission")
    private fun buildSuggestion(saved: SavedWifiCredentials): WifiNetworkSuggestion {
        val builder = WifiNetworkSuggestion.Builder().setSsid(saved.ssid)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setIsInitialAutojoinEnabled(true)
        if (saved.secured) {
            if (saved.wpa3) builder.setWpa3Passphrase(saved.password) else builder.setWpa2Passphrase(saved.password)
        }
        return builder.build()
    }
    @SuppressLint("MissingPermission")
    private fun registerAutoJoin(saved: SavedWifiCredentials): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val status = wifiManager.addNetworkSuggestions(listOf(buildSuggestion(saved)))
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS || status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        }.getOrDefault(false)
    }

    fun startDiscovery(onFound: (IntentSender) -> Unit, onFailure: (String) -> Unit) {
        val manager = appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailure("Wi-Fi device discovery is unavailable on this phone. Open Android Wi-Fi settings instead.")
            return
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            onFailure("This phone does not support Android's no-location Wi-Fi picker. Open Android Wi-Fi settings instead.")
            return
        }
        state = state.copy(message = "Searching nearby Wi-Fi networks…")
        val request = AssociationRequest.Builder().addDeviceFilter(WifiDeviceFilter.Builder().build()).build()
        try {
            @Suppress("DEPRECATION")
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) = onFound(chooserLauncher)
                override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "Wi-Fi discovery failed. Try again.")
            }, Handler(Looper.getMainLooper()))
        } catch (error: SecurityException) {
            onFailure("Android could not scan for Wi-Fi. Turn on Location Services for Android's system scan; neXconnect will not ask for location permission.")
        } catch (_: RuntimeException) {
            onFailure("Could not start Wi-Fi discovery. Try again or use Android Wi-Fi settings.")
        }
    }

    private fun markWifiConnected() {
        val ssid = savedSsid ?: "Connected Wi-Fi"
        state = WifiLinkUiState(connectedSsid = ssid, message = "Connected to Wi-Fi. Your phone will remember this network until you disconnect it.")
    }

    fun refreshActiveNetwork() {
        val active = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
        if (active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) markWifiConnected()
        else if (!state.connecting) {
            state = state.copy(connectedSsid = null, message = if (savedSsid != null) "Saved Wi-Fi network: ${savedSsid}. Select it to reconnect." else "Choose an available Wi-Fi network to connect.")
        }
    }

    fun disconnect(clearMessage: Boolean = true, clearSavedNetwork: Boolean = true) {
        requestCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        requestCallback = null
        connectivity.bindProcessToNetwork(null)
        if (clearSavedNetwork) {
            credentials.load()?.let { saved ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
                    val builder = WifiNetworkSuggestion.Builder().setSsid(saved.ssid)
                    if (saved.secured) { if (saved.wpa3) builder.setWpa3Passphrase(saved.password) else builder.setWpa2Passphrase(saved.password) }
                    wifiManager.removeNetworkSuggestions(listOf(builder.build()))
                }
            }
            credentials.clear()
        }
        refreshActiveNetwork()
        if (clearMessage && !isConnected) state = WifiLinkUiState(message = "Wi-Fi connection cancelled.")
    }
}

@Composable
internal fun WifiConnectionOverlay(controller: WifiConnectionController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selectedNetwork by remember { mutableStateOf<ScanResult?>(null) }
    var password by remember { mutableStateOf("") }
    var discoveryMessage by remember { mutableStateOf("Scan to see nearby Wi-Fi networks.") }
    var pendingConnect by remember { mutableStateOf<(() -> Unit)?>(null) }
    val wifiPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val scan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE) as? ScanResult
        }
        if (scan != null) {
            selectedNetwork = scan
            password = controller.passwordFor(scan.SSID).orEmpty()
            discoveryMessage = if (password.isNotEmpty()) "Saved login loaded for ${scan.SSID}. No need to enter the password again." else "Selected ${scan.SSID}. Enter its password to connect."
        } else {
            discoveryMessage = "No network selected. Tap Scan Wi-Fi to try again."
        }
    }
    val saveNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingConnect?.invoke()
            discoveryMessage = "Network saved by Android. Starting connection…"
        } else {
            pendingConnect = null
            discoveryMessage = "Android did not save this network. Accept the system prompt to enable automatic reconnection."
        }
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingConnect?.invoke()
        else discoveryMessage = "Allow Nearby Wi-Fi access to connect. neXconnect does not request location permission."
        pendingConnect = null
    }
    fun connectSelected() {
        val scan = selectedNetwork ?: return
        val caps = scan.capabilities.orEmpty().uppercase()
        val enterpriseOrWep = "EAP" in caps || "WEP" in caps
        if (enterpriseOrWep) {
            discoveryMessage = "This network uses enterprise or legacy security, which is not supported yet."
            return
        }
        val secured = "PSK" in caps || "SAE" in caps
        val wpa3 = "SAE" in caps && "PSK" !in caps
        val rememberedPassword = controller.passwordFor(scan.SSID)
        val loginPassword = rememberedPassword ?: password
        val action: () -> Unit = {
            if (rememberedPassword != null) controller.connect(scan.SSID, loginPassword, secured, wpa3)
            else {
                val saveIntent = controller.savedNetworkIntent(scan.SSID, loginPassword, secured, wpa3)
                if (saveIntent == null) controller.connect(scan.SSID, loginPassword, secured, wpa3)
                else {
                    pendingConnect = { controller.connect(scan.SSID, loginPassword, secured, wpa3) }
                    runCatching { saveNetworkLauncher.launch(saveIntent) }
                        .onFailure { discoveryMessage = "Could not open Android save-network prompt. Try again."; pendingConnect = null }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnect = { action(); Unit }
            nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else action()
    }
    fun scanWifi() {
        selectedNetwork = null
        password = ""
        discoveryMessage = "Searching nearby Wi-Fi networks…"
        controller.startDiscovery(
            onFound = { sender -> runCatching { wifiPickerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                .onFailure { discoveryMessage = "Could not open Wi-Fi selection. Please try again." } },
            onFailure = { discoveryMessage = it }
        )
    }
    BackHandler {
        if (controller.state.connecting) controller.disconnect(clearSavedNetwork = false)
        onDismiss()
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
            Text("Choose a nearby network and enter its password", color = Color(0xFF9BADE1), fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF52DDF3).copy(alpha = .30f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC111735))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Wifi, null, tint = Color(0xFF54DDF5), modifier = Modifier.size(28.dp))
                    Text("Available Wi-Fi networks", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(discoveryMessage, color = Color(0xFFA9B6D5), fontSize = 12.sp)
                    Button(
                        onClick = { scanWifi() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF208CB8), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Wi-Fi")
                    }
                    selectedNetwork?.let { scan ->
                        Text("Selected: ${scan.SSID}", color = Color(0xFF71F0CB), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val secured = scan.capabilities.orEmpty().let { "PSK" in it || "SAE" in it || "WEP" in it || "EAP" in it }
                        if (secured) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Wi-Fi password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                colors = androidx.compose.material3.TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedContainerColor = Color(0xFF0C1430), unfocusedContainerColor = Color(0xFF0C1430),
                                    focusedIndicatorColor = Color(0xFF56DDF5), unfocusedIndicatorColor = Color(0xFF526180),
                                    focusedLabelColor = Color(0xFF73E5F4), unfocusedLabelColor = Color(0xFF9BAED0), cursorColor = Color(0xFF73E5F4)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Button(
                            onClick = { connectSelected() },
                            enabled = !secured || password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF208CB8), contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect to ${scan.SSID}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (controller.savedNetworkCount > 0) Text("${controller.savedNetworkCount} saved Wi-Fi network(s) · passwords encrypted on this phone", color = Color(0xFF9CB7CA), fontSize = 11.sp)
                    Text("2.4 GHz and 5 GHz supported · phone selects the best available band", color = Color(0xFF9CB7CA), fontSize = 11.sp)
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
                    if (controller.isConnected) { controller.disconnect(); onDismiss() }
                    else if (controller.state.connecting) { controller.disconnect(clearSavedNetwork = false); onDismiss() }
                    else { controller.disconnect(); onDismiss() }
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
