package com.nexconnect.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) markWifiConnected()
            else refreshActiveNetwork()
        }
        override fun onLost(network: Network) {
            val wasConnected = isConnected
            refreshActiveNetwork()
            if (wasConnected && !isConnected) preferences.edit().remove("saved_ssid").apply()
        }
        override fun onAvailable(network: Network) { refreshActiveNetwork() }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        refreshActiveNetwork()
    }

    val isConnected: Boolean get() = state.connectedSsid != null
    val savedSsid: String? get() = preferences.getString("saved_ssid", null)

    fun beginSystemPicker() {
        state = state.copy(connecting = true, message = "Choose a nearby Wi-Fi network and enter its password in Android settings.")
    }

    fun finishSystemPicker() {
        refreshActiveNetwork()
        if (isConnected) {
            state = state.copy(connecting = false, message = "Connected to Wi-Fi. Your phone will remember this network until you disconnect it.")
        } else {
            preferences.edit().remove("saved_ssid").apply()
            state = state.copy(connecting = false, message = "No Wi-Fi connection detected. Select a network and enter its password to connect.")
        }
    }

    private fun markWifiConnected() {
        val ssid = savedSsid ?: "Connected Wi-Fi"
        if (savedSsid == null) preferences.edit().putString("saved_ssid", ssid).apply()
        state = WifiLinkUiState(connectedSsid = ssid, message = "Connected to Wi-Fi. Your phone will remember this network until you disconnect it.")
    }

    fun refreshActiveNetwork() {
        val active = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
        if (active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) markWifiConnected()
        else if (!state.connecting) {
            state = state.copy(connectedSsid = null, message = if (savedSsid != null) "Saved Wi-Fi network: ${savedSsid}. Select it in Android Wi-Fi settings to reconnect." else "Choose an available Wi-Fi network to connect.")
        }
    }

    fun disconnect(clearMessage: Boolean = true, clearSavedNetwork: Boolean = true) {
        if (clearSavedNetwork) preferences.edit().remove("saved_ssid").apply()
        refreshActiveNetwork()
        if (clearMessage && !isConnected) state = WifiLinkUiState(message = "Wi-Fi connection cancelled.")
    }
}

@Composable
internal fun WifiConnectionOverlay(controller: WifiConnectionController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.finishSystemPicker()
    }
    fun openSystemWifiPicker() {
        controller.beginSystemPicker()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        runCatching { settingsLauncher.launch(intent) }.onFailure {
            controller.finishSystemPicker()
        }
    }
    BackHandler {
        controller.finishSystemPicker()
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
                    Text("Use your phone’s Wi-Fi picker", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Available networks and password entry are handled by Android. neXconnect will not request location access.", color = Color(0xFFA9B6D5), fontSize = 12.sp)
                    Button(
                        onClick = { openSystemWifiPicker() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF208CB8), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (controller.isConnected) "Change Wi-Fi network" else "Choose Wi-Fi network")
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
                    if (controller.isConnected) openSystemWifiPicker()
                    else { controller.disconnect(); onDismiss() }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.4.dp, Color(0xFF5EE1F5)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(if (controller.isConnected) "Manage or disconnect Wi-Fi" else if (controller.state.connecting) "Cancel connection" else "Cancel", fontWeight = FontWeight.SemiBold)
            }
        }
        }
    }
}
