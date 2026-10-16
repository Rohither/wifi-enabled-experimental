package com.nexconnect.app

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class NexPalette(val ink: Color, val muted: Color, val teal: Color, val green: Color, val page: Color, val card: Color)
private val LightPalette = NexPalette(Color(0xFF142A3A), Color(0xFF7D8D99), Color(0xFF18B8C9), Color(0xFF26A879), Color(0xFFF4F7FA), Color.White)
private val DarkPalette = NexPalette(Color(0xFFEAF2F6), Color(0xFFAAB8C1), Color(0xFF39C7D5), Color(0xFF44D19A), Color(0xFF101820), Color(0xFF1C2933))
private val LocalNexPalette = compositionLocalOf { LightPalette }
private val Ink: Color @Composable get() = LocalNexPalette.current.ink
private val Muted: Color @Composable get() = LocalNexPalette.current.muted
private val Teal: Color @Composable get() = LocalNexPalette.current.teal
private val Green: Color @Composable get() = LocalNexPalette.current.green
private val Page: Color @Composable get() = LocalNexPalette.current.page
private val CardSurface: Color @Composable get() = LocalNexPalette.current.card
private val HeroSurface = Color(0xFF173548)

private fun formatPowerReading(watts: Double): String =
    if (kotlin.math.abs(watts) >= 1000.0) "%.2f kW".format(kotlin.math.abs(watts) / 1000.0) else "%.0f W".format(kotlin.math.abs(watts))

private data class BmsModel(
    val vendor: String,
    val model: String,
    val protocol: String,
    val transport: String
)

private val bmsModels = listOf(
    BmsModel("Pylontech", "US2000C", "Pylontech low-voltage", "RS485"),
    BmsModel("Pylontech", "US3000C", "Pylontech low-voltage", "RS485"),
    BmsModel("Pylontech", "US5000", "Pylontech low-voltage", "RS485"),
    BmsModel("Pylontech", "UP5000", "Pylontech low-voltage", "RS485"),
    BmsModel("JK / Jikong", "JK-B1A8S-10P", "JK / Jikong BMS", "Bluetooth / RS485 (model dependent)"),
    BmsModel("JK / Jikong", "JK-B2A8S-20P", "JK / Jikong BMS", "Bluetooth / RS485 (model dependent)"),
    BmsModel("JK / Jikong", "JK-PB2A16S-15P", "JK / Jikong BMS", "Bluetooth / RS485 (model dependent)"),
    BmsModel("JBD / LLT", "SP17S005 Smart BMS", "JBD / LLT Smart BMS · protocol varies", "Bluetooth / UART (model dependent)"),
    BmsModel("JBD / LLT", "SP Series · V4 BLE", "JBD Software-board V4", "Bluetooth LE (FF00 service; read-only polling)"),
    BmsModel("Daly", "Smart BMS · 4S–24S family", "Daly Smart BMS", "Bluetooth / RS485 / CAN (model dependent)"),
    BmsModel("NEEY", "24S8A / 10A / 15A Smart Active Balancer", "NEEY cell-balancer telemetry (not a pack BMS)", "Bluetooth app; TTL serial on select models"),
    BmsModel("JLGW", "Smart BMS · exact model required", "Protocol not publicly verified", "Bluetooth / UART / RS485 varies by model"),
    BmsModel("ARENQ", "LiFePO₄ 100Ah", "ARENQ smart BMS (protocol unverified)", "Bluetooth (module/protocol needs confirmation)"),
    BmsModel("Luminous", "Helios 12.8V 100Ah", "Luminous battery profile", "Manufacturer interface; verify exact model manual"),
    BmsModel("Luminous", "Helios 51.2V 100Ah", "Luminous battery profile", "Manufacturer interface; verify exact model manual"),
    BmsModel("MuscleGrid", "Thunder Pro 48V 120Ah", "Bluetooth LE BMS", "Bluetooth / RS485 / CAN per product listing"),
    BmsModel("Other", "Other / custom model", "Bluetooth LE BMS", "Select the interface documented for your BMS")
)

private val inverterProtocols = listOf(
    "NONE", "PYLON", "GROWATT", "SOFAR", "VOLTRONICPOWER", "GOODWE", "SRNE", "MUST",
    "VICTRONENERGY", "SMA", "DEYE", "AISWEI", "SACOLAR", "SOLARK", "XMT", "SOLIS",
    "LUXPOWERTEK", "STUDER", "SOROTEC"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexApp() }
    }
}

@Composable
private fun NexApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var showSplash by remember { mutableStateOf(true) }
    var showConnectionInfo by remember { mutableStateOf(false) }
    var darkTheme by remember(context) { mutableStateOf(AppSettingsStore.darkTheme(context)) }
    var selectedBleSlot by remember { mutableIntStateOf(0) }
    val slotConnected = remember { mutableStateListOf(false, false, false) }
    val slotActivity = remember { mutableStateListOf(false, false, false) }
    val slotSearchCancelSignal = remember { mutableStateListOf(0, 0, 0) }
    val slotReadStatus = remember { mutableStateListOf("Waiting for BMS data", "Waiting for BMS data", "Waiting for BMS data") }
    val slotBatteryNames = remember(context) {
        mutableStateListOf(*(0..2).map { slot ->
            val prefsName = if (slot == 0) "nex_ble_connection" else "nex_ble_connection_$slot"
            context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).getString("device_name", "").orEmpty()
        }.toTypedArray())
    }
    val slotTelemetry = remember { mutableStateListOf<BmsTelemetry?>(null, null, null) }
    val bleConnected = slotConnected[selectedBleSlot]
    val bleActivity = slotActivity[selectedBleSlot]
    val bleReadStatus = slotReadStatus[selectedBleSlot]
    val connectedBatteryName = slotBatteryNames[selectedBleSlot]
    val liveTelemetry = slotTelemetry[selectedBleSlot]
    var selectedModel by remember(context) {
        val savedKey = AppSettingsStore.modelKey(context)
        mutableStateOf(bmsModels.firstOrNull { "${it.vendor}|${it.model}" == savedKey } ?: bmsModels.first { it.vendor == "Daly" })
    }
    var inverterProtocol by remember(context) { mutableStateOf(AppSettingsStore.protocol(context)) }
    val lastHistoryAt = remember { mutableStateListOf(0L, 0L, 0L) }
    val historyRevision = remember { mutableStateListOf(0L, 0L, 0L) }
    fun onTelemetry(slot: Int, sample: BmsTelemetry) {
        slotTelemetry[slot] = sample
        val now = sample.timestampMillis
        if (sample.packVoltageV != null && sample.currentA != null && sample.socPercent != null && now - lastHistoryAt[slot] >= 60_000L) {
            val point = BatteryHistoryPoint(now, sample.packVoltageV, sample.currentA, sample.socPercent, sample.operatingMode ?: "Unknown")
            BatteryHistoryStore.append(context, point, slot)
            lastHistoryAt[slot] = now
            historyRevision[slot]++
        }
    }
    CriticalBatteryAlarm(bleConnected && liveTelemetry?.socPercent?.let { it <= 2.0 } == true)
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalNexPalette provides palette) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        window?.statusBarColor = palette.page.toArgb()
        window?.navigationBarColor = palette.page.toArgb()
        window?.let {
            WindowInsetsControllerCompat(it, view).isAppearanceLightStatusBars = !darkTheme
            WindowInsetsControllerCompat(it, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme(primary = palette.teal, background = palette.page, surface = palette.card) else lightColorScheme(primary = palette.teal, background = palette.page, surface = palette.card)) {
        Scaffold(
            containerColor = Page,
            bottomBar = {
                NavigationBar(containerColor = CardSurface) {
                    listOf("Monitor", "History", "Settings", "About").forEachIndexed { index, title ->
                        val icon = when (index) { 0 -> Icons.Default.Home; 1 -> Icons.Default.History; 2 -> Icons.Default.Settings; else -> Icons.Default.Info }
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(title) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().pointerInput(tab) {
                var horizontalTravel = 0f
                detectHorizontalDragGestures(
                    onDragEnd = { horizontalTravel = 0f },
                    onDragCancel = { horizontalTravel = 0f },
                    onHorizontalDrag = { change, amount ->
                        horizontalTravel += amount
                        if (abs(horizontalTravel) >= 96f) {
                            val nextTab = if (horizontalTravel < 0f) (tab + 1).coerceAtMost(3) else (tab - 1).coerceAtLeast(0)
                            if (nextTab != tab) {
                                tab = nextTab
                                change.consume()
                            }
                            horizontalTravel = 0f
                        }
                    }
                )
            }) {
                // Keep the BLE owner composed while users switch to Monitor and History.
                SettingsPage(
                    padding, selectedModel,
                    { selectedModel = it; AppSettingsStore.saveModelKey(context, "${it.vendor}|${it.model}") },
                    inverterProtocol,
                    { inverterProtocol = it; AppSettingsStore.saveProtocol(context, it) },
                    selectedBleSlot,
                    slotSearchCancelSignal,
                    { slot, sample -> onTelemetry(slot, sample) },
                    darkTheme,
                    { darkTheme = it; AppSettingsStore.saveDarkTheme(context, it) },
                    { slot, connected, name -> slotConnected[slot] = connected; if (name.isNotBlank()) slotBatteryNames[slot] = name },
                    { slot -> slotTelemetry[slot] = null; lastHistoryAt[slot] = 0L },
                    { slot, active -> slotActivity[slot] = active },
                    { slot, status -> slotReadStatus[slot] = status }
                ) { showConnectionInfo = true }
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { direction * it / 5 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -direction * it / 5 } + fadeOut(tween(200)))
                    },
                    label = "Tab navigation"
                ) { page ->
                    Box(Modifier.fillMaxSize().then(if (page == 2) Modifier else Modifier.background(Page))) {
                        when (page) {
                            0 -> MonitorScreen(padding, liveTelemetry, bleConnected, connectedBatteryName, bleActivity, bleReadStatus,
                                slotBatteryNames, slotConnected, selectedBleSlot, { selectedBleSlot = it }) { tab = 2 }
                            1 -> HistoryPage(padding, context, historyRevision[selectedBleSlot], selectedBleSlot) { BatteryHistoryStore.clear(context, selectedBleSlot); historyRevision[selectedBleSlot]++ }
                            2 -> Spacer(Modifier.fillMaxSize()) // SettingsPage remains composed beneath this layer to own all BLE links.
                            else -> AboutPage(padding)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = bleActivity && !bleConnected,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(800))
        ) {
            BluetoothSearchOverlay { slotSearchCancelSignal[selectedBleSlot]++ }
        }
        if (showConnectionInfo) AlertDialog(
            onDismissRequest = { showConnectionInfo = false },
            title = { Text("Connection setup") },
            text = { Text("Bluetooth supports experimental read-only DALY and JBD polling when the exact BMS protocol matches. Other brands can be discovered and connected, but live telemetry requires a verified model-specific driver. Settings edits stay on the phone and are not sent to the battery.") },
            confirmButton = { TextButton(onClick = { showConnectionInfo = false }) { Text("Got it") } }
        )
        if (showSplash) PowerNexSplash { showSplash = false }
    } }
}

@Composable
private fun PowerNexSplash(onFinished: () -> Unit) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    var splashVisible by remember { mutableStateOf(true) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val logoScale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.78f,
        animationSpec = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
        label = "Power Nex logo scale"
    )
    val logoAlpha by animateFloatAsState(if (revealed) 1f else 0f, animationSpec = tween(1350, easing = FastOutSlowInEasing), label = "Power Nex logo fade")
    val taglineAlpha by animateFloatAsState(if (revealed) 1f else 0f, animationSpec = tween(1450, delayMillis = 600, easing = FastOutSlowInEasing), label = "Power Nex tagline fade")

    LaunchedEffect(Unit) {
        revealed = true
        player = runCatching { MediaPlayer.create(context, R.raw.powernex_chime) }.getOrNull()
        runCatching { player?.setVolume(0.65f, 0.65f) }
        runCatching { player?.start() }
        delay(4450)
        splashVisible = false
        delay(650)
        onFinished()
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.let { sound ->
                runCatching { sound.setOnCompletionListener(null); if (sound.isPlaying) sound.stop(); sound.release() }
            }
        }
    }

    AnimatedVisibility(visible = splashVisible, enter = fadeIn(tween(650)), exit = fadeOut(tween(650))) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF27677C), Color(0xFF173548), Color(0xFF0B1722))))
                .clickable(onClick = {}),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(
                    painterResource(R.drawable.power_nex_about_logo),
                    contentDescription = "Power Nex",
                    modifier = Modifier.width(270.dp).height(195.dp).scale(logoScale).alpha(logoAlpha),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(16.dp))
                Text("Powering a Better Tomorrow", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.alpha(taglineAlpha))
                Spacer(Modifier.height(11.dp))
                Text("neXconnect", color = Color(0xFFC4D8E1), fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp, modifier = Modifier.alpha(taglineAlpha))
            }
        }
    }
}

@Composable
private fun MonitorBatteryIcon(socPercent: Float?, charging: Boolean, currentFlowing: Boolean, fillColor: Color) {
    val transition = rememberInfiniteTransition(label = "battery charge wave")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "battery wave phase"
    )
    val progress = (socPercent?.div(100f) ?: if (charging) 0.08f else 0f).coerceIn(0f, 1f)
    Canvas(Modifier.size(width = 50.dp, height = 74.dp)) {
        val stroke = 1.5.dp.toPx()
        val left = size.width * 0.09f
        val right = size.width * 0.91f
        val top = size.height * 0.17f
        val bottom = size.height * 0.96f
        val bodyWidth = right - left
        val bodyHeight = bottom - top
        val radius = 6.dp.toPx()
        val innerLeft = left + stroke
        val innerRight = right - stroke
        val innerTop = top + stroke
        val innerBottom = bottom - stroke
        val innerHeight = innerBottom - innerTop
        val glass = Path().apply {
            addRoundRect(RoundRect(left, top, right, bottom, CornerRadius(radius)))
        }
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF78818A), Color(0xFFE8EDF0), Color(0xFF59636C)), top, bottom),
            topLeft = Offset(left, top), size = Size(bodyWidth, bodyHeight), cornerRadius = CornerRadius(radius)
        )
        drawRoundRect(
            color = if (socPercent == null) HeroSurface else Color(0xFF10202A),
            topLeft = Offset(left + stroke, top + stroke),
            size = Size(bodyWidth - 2 * stroke, bodyHeight - 2 * stroke), cornerRadius = CornerRadius(radius * .8f)
        )
        if (socPercent == null) clipPath(glass) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.White.copy(alpha = .18f), Color.Transparent, Color.White.copy(alpha = .06f))),
                topLeft = Offset(innerLeft, innerTop), size = Size(innerRight - innerLeft, innerHeight)
            )
        }
        if (progress > 0f) {
            val fillTop = innerBottom - innerHeight * progress
            val amplitude = if (currentFlowing) 2.2.dp.toPx() else 0.35.dp.toPx()
            val liquid = Path().apply {
                moveTo(innerLeft, fillTop)
                val step = 1.5.dp.toPx().coerceAtLeast(1f)
                var x = innerLeft
                while (x <= innerRight) {
                    val angle = wavePhase + (x - innerLeft) / (innerRight - innerLeft) * (2f * PI).toFloat()
                    lineTo(x, (fillTop + sin(angle.toDouble()).toFloat() * amplitude).coerceIn(innerTop, innerBottom))
                    x += step
                }
                lineTo(innerRight, innerBottom); lineTo(innerLeft, innerBottom); close()
            }
            clipPath(glass) {
                drawPath(liquid, Brush.verticalGradient(
                    listOf(Color(0xFF52E346), Color(0xFFB6E52E), Color(0xFFFFB42C), Color(0xFFEA3939)),
                    startY = innerTop, endY = innerBottom
                ))
                // A shallow elliptical meniscus gives the fill a visible 3D liquid surface.
                val surfaceY = (fillTop - 1.1.dp.toPx()).coerceAtLeast(innerTop)
                drawOval(
                    color = Color(0xFF79FF6B).copy(alpha = .22f),
                    topLeft = Offset(innerLeft + 1.dp.toPx(), surfaceY),
                    size = Size(innerRight - innerLeft - 2.dp.toPx(), 3.dp.toPx())
                )
                val surface = Path().apply {
                    moveTo(innerLeft, fillTop)
                    var x = innerLeft
                    while (x <= innerRight) {
                        val angle = wavePhase + (x - innerLeft) / (innerRight - innerLeft) * (2f * PI).toFloat()
                        lineTo(x, (fillTop + sin(angle.toDouble()).toFloat() * amplitude).coerceIn(innerTop, innerBottom))
                        x += 1.5.dp.toPx().coerceAtLeast(1f)
                    }
                }
                drawPath(surface, fillColor.copy(alpha = .9f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
                drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = .42f), Color.Transparent, Color.White.copy(alpha = .18f))),
                    topLeft = Offset(innerLeft, innerTop), size = Size(innerRight - innerLeft, innerHeight))
                // Darkened glass edges and a bright central reflection make the cell read as curved glass.
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF06131C).copy(alpha = .36f), Color.Transparent, Color(0xFF06131C).copy(alpha = .3f))),
                    topLeft = Offset(innerLeft, innerTop), size = Size(innerRight - innerLeft, innerHeight))
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .2f), Color.Transparent)),
                    topLeft = Offset(innerLeft + bodyWidth * .24f, innerTop), size = Size(bodyWidth * .22f, innerHeight))
            }
        }
        // Glass reflections and a dark fine outline give the liquid depth of the reference model.
        drawRoundRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = .62f), Color.Transparent, Color.Black.copy(alpha = .5f))),
            topLeft = Offset(left, top), size = Size(bodyWidth, bodyHeight), cornerRadius = CornerRadius(radius), style = Stroke(stroke))
        drawRoundRect(Color.White.copy(alpha = .2f), Offset(left + stroke * 1.2f, top + 2.dp.toPx()),
            Size(2.dp.toPx(), bodyHeight - 4.dp.toPx()), CornerRadius(1.dp.toPx()))
        // Wide metal shoulder, inset terminal, and rolled bottom rim follow the reference's cell silhouette.
        val shoulderTop = size.height * .115f
        val shoulderHeight = top - shoulderTop + 2.dp.toPx()
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF171B1F), Color(0xFFCDD1D3), Color(0xFF3B4146), Color(0xFF171B1F))),
            Offset(left - 1.dp.toPx(), shoulderTop), Size(bodyWidth + 2.dp.toPx(), shoulderHeight), CornerRadius(5.dp.toPx()))
        drawOval(Brush.verticalGradient(listOf(Color(0xFF171B1F), Color(0xFFE5E7E8), Color(0xFF464B50))),
            Offset(left - 1.dp.toPx(), shoulderTop - 1.dp.toPx()), Size(bodyWidth + 2.dp.toPx(), 4.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF25292D), Color(0xFFEFF1F2), Color(0xFF686E73))),
            Offset(left + bodyWidth * .22f, top - 2.dp.toPx()), Size(bodyWidth * .56f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        val capWidth = bodyWidth * .38f
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF16191C), Color(0xFFBFC3C5), Color(0xFF3D4247), Color(0xFF15181B))),
            Offset((size.width - capWidth) / 2f, size.height * .025f), Size(capWidth, shoulderTop - size.height * .025f + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawOval(Brush.horizontalGradient(listOf(Color(0xFF171A1D), Color(0xFFE1E4E5), Color(0xFF25292D))),
            Offset((size.width - capWidth) / 2f, size.height * .018f), Size(capWidth, 3.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF22272B), Color(0xFFE1E5E7), Color(0xFF555B60))),
            Offset(left - .5.dp.toPx(), bottom - 3.dp.toPx()), Size(bodyWidth + 1.dp.toPx(), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        drawOval(Brush.horizontalGradient(listOf(Color(0xFF181C20), Color(0xFFD7DADD), Color(0xFF202428))),
            Offset(left - .5.dp.toPx(), bottom - 1.dp.toPx()), Size(bodyWidth + 1.dp.toPx(), 4.dp.toPx()))
        drawRoundRect(Color(0xFF25292D), Offset(left + bodyWidth * .12f, bottom + 1.dp.toPx()),
            Size(bodyWidth * .76f, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
    }
}

@Composable
private fun BluetoothActivityIndicator(connected: Boolean, active: Boolean) {
    val bluetoothColor = if (connected) Green else Teal
    val transition = rememberInfiniteTransition(label = "Bluetooth activity")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "Bluetooth pulse"
    )
    val successAlpha = remember { Animatable(1f) }
    LaunchedEffect(connected) {
        if (connected) {
            repeat(2) {
                successAlpha.animateTo(0.2f, tween(180))
                successAlpha.animateTo(1f, tween(220))
            }
        } else successAlpha.snapTo(1f)
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF081727), Color(0xFF0D2D46), Color(0xFF071522)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val radius = size.minDimension * .39f
            val ringColor = Color(0xFF50D9FF)
            drawCircle(ringColor.copy(alpha = .18f), radius = radius * 1.12f, style = Stroke(1.dp.toPx()))
            drawCircle(ringColor.copy(alpha = .34f), radius = radius * .88f, style = Stroke(.7.dp.toPx()))
            val rotation = if (active) pulse * 2f * PI.toFloat() else 0f
            repeat(32) { index ->
                val angle = rotation + index * (2f * PI.toFloat() / 32f)
                val x1 = cx + cos(angle) * radius * .91f
                val y1 = cy + sin(angle) * radius * .91f
                val x2 = cx + cos(angle) * radius
                val y2 = cy + sin(angle) * radius
                val lit = !active || ((index - (pulse * 32f).toInt() + 64) % 32) < 12
                drawLine(ringColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = if (index % 4 == 0) 1.6.dp.toPx() else 1.dp.toPx(), alpha = if (lit) .95f else .28f, cap = StrokeCap.Round)
            }
            // Subtle circuit traces echo the reference's blue technology backdrop.
            val traceAlpha = if (active) .5f else .25f
            repeat(2) { side ->
                val direction = if (side == 0) -1f else 1f
                val trace = Path().apply {
                    moveTo(cx + direction * radius * .65f, cy - 2.dp.toPx())
                    lineTo(cx + direction * radius * 1.05f, cy - 2.dp.toPx())
                    lineTo(cx + direction * radius * 1.18f, cy - 5.dp.toPx())
                    lineTo(cx + direction * radius * 1.35f, cy - 5.dp.toPx())
                }
                drawPath(trace, ringColor.copy(alpha = traceAlpha), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Icon(
            Icons.Default.Bluetooth,
            contentDescription = if (connected) "Bluetooth connected" else if (active) "Bluetooth connecting" else "Bluetooth disconnected",
            tint = if (connected) Color(0xFF72F3C0) else Color(0xFF8AE9FF),
            modifier = Modifier.size(27.dp).graphicsLayer {
                alpha = (if (active) 0.62f + 0.38f * kotlin.math.abs(sin((pulse * PI).toDouble())).toFloat() else successAlpha.value)
            }
        )
    }
}

@Composable
private fun BluetoothSearchOverlay(onCancel: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "full screen BLE search")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "search ring rotation"
    )
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF123E5B), Color(0xFF091B2C), Color(0xFF050D18))))
            .clickable { },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * .40f
            val base = size.minDimension * .22f
            val cyan = Color(0xFF50D9FF)
            drawCircle(cyan.copy(alpha = .07f), radius = base * 1.48f, center = Offset(cx, cy))
            drawCircle(cyan.copy(alpha = .1f), radius = base * 1.25f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            drawCircle(cyan.copy(alpha = .22f), radius = base * .88f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            // Rotating segmented halo and orbiting dots mimic the supplied Bluetooth search animation.
            repeat(64) { index ->
                val angle = phase * 2f * PI.toFloat() + index * (2f * PI.toFloat() / 64f)
                val innerRadius = base * 1.03f
                val outerRadius = base * if (index % 4 == 0) 1.22f else 1.15f
                val x1 = cx + cos(angle) * innerRadius
                val y1 = cy + sin(angle) * innerRadius
                val x2 = cx + cos(angle) * outerRadius
                val y2 = cy + sin(angle) * outerRadius
                val distance = ((index - (phase * 64f).toInt() + 64) % 64)
                val alpha = if (distance < 18) .98f else .3f
                drawLine(cyan, Offset(x1, y1), Offset(x2, y2), 2.dp.toPx(), alpha = alpha, cap = StrokeCap.Round)
            }
            repeat(3) { orbit ->
                val angle = phase * 2f * PI.toFloat() * (if (orbit == 1) -1f else 1f) + orbit * 2.1f
                val radius = base * (1.35f + orbit * .13f)
                drawCircle(Color(0xFF9CEEFF).copy(alpha = .82f - orbit * .15f), 2.dp.toPx(), Offset(cx + cos(angle) * radius, cy + sin(angle) * radius))
            }
            // Circuit-like traces softly travel out from both sides of the halo.
            repeat(3) { row ->
                val y = cy + (row - 1) * 24.dp.toPx()
                val drift = sin((phase * 2f * PI.toFloat() + row).toDouble()).toFloat() * 7.dp.toPx()
                listOf(-1f, 1f).forEach { side ->
                    val trace = Path().apply {
                        moveTo(cx + side * base * 1.25f, y)
                        lineTo(cx + side * base * 1.55f, y)
                        lineTo(cx + side * base * 1.7f, y + drift)
                        lineTo(cx + side * base * 2.1f, y + drift)
                    }
                    drawPath(trace, cyan.copy(alpha = .16f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(cyan.copy(alpha = .38f), 1.5.dp.toPx(), Offset(cx + side * base * 2.1f, y + drift))
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset(y = maxHeight * .40f - 64.dp)
                .size(128.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF153D58).copy(alpha = .65f), Color.Transparent))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = "Searching for Bluetooth devices",
                tint = Color(0xFF8AE9FF),
                modifier = Modifier.size(88.dp).graphicsLayer {
                    alpha = .72f + .28f * kotlin.math.abs(sin((phase * PI).toDouble())).toFloat()
                }
            )
        }
        Column(
            Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .40f + 145.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { dot ->
                    val distance = (phase * 3f + dot) % 3f
                    Box(Modifier.size(6.dp).alpha(if (distance < 1f) .95f else .3f).clip(CircleShape).background(Color(0xFF50D9FF)))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Looking for your BMS nearby…", color = Color(0xFF9BBACC), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp)) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF50D9FF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 11.dp)
            ) {
                Text("Cancel search", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MonitorScreen(padding: PaddingValues, telemetry: BmsTelemetry?, connected: Boolean, batteryName: String, bluetoothActivity: Boolean, readingStatus: String, slotNames: List<String>, slotConnections: List<Boolean>, selectedSlot: Int, onSelectSlot: (Int) -> Unit, onConnect: () -> Unit) {
    val soc = telemetry?.socPercent
    val progressColor = when {
        soc == null -> Teal
        soc <= 5.0 -> Color(0xFFD32F2F)
        soc <= 25.0 -> Color(0xFFFFC928)
        soc <= 50.0 -> Color(0xFF9DBB45)
        soc >= 90.0 -> Color(0xFF087A38)
        else -> Color(0xFF27A66A)
    }
    val charging = telemetry?.currentA?.let { it > 0.2 } == true
    val currentFlowing = telemetry?.currentA?.let { kotlin.math.abs(it) > 0.2 } == true
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.nex_power_logo), contentDescription = "neX Power", modifier = Modifier.width(104.dp).height(64.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        if (connected) batteryName.ifBlank { "CONNECTED BMS" } else "Waiting to Connect",
                        fontSize = if (connected) 15.sp else 11.sp,
                        fontWeight = if (connected) FontWeight.Bold else FontWeight.Normal,
                        color = if (connected) Ink else Muted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(if (connected && telemetry != null) "Connected · ${telemetry.protocol}" else if (connected) readingStatus else "Disconnected · 4S LiFePO₄", fontSize = 11.sp, color = if (connected) Green else Muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = HeroSurface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MonitorBatteryIcon(telemetry?.socPercent?.toFloat(), charging, currentFlowing, progressColor)
                            Column {
                                Text("STATE OF CHARGE", color = Color(0xFFB5C5CE), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                Text(telemetry?.socPercent?.let { "${it.roundToInt()}%" } ?: "—", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                Text("Current  ${telemetry?.currentA?.let { "%.1f A".format(it) } ?: "—"}", color = Color(0xFFB5C5CE), fontSize = 11.sp)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(telemetry?.packVoltageV?.let { "%.1f V".format(it) } ?: "— V", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("Pack voltage", color = Color(0xFFB5C5CE), fontSize = 11.sp)
                        }
                    }
                    LinearProgressIndicator(progress = { ((soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape), color = progressColor, trackColor = Color(0xFF36505F))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Mode  ${telemetry?.operatingMode ?: "—"}", color = Color.White, fontSize = 14.sp)
                        Text("Remaining  ${telemetry?.remainingCapacityAh?.let { "%.1f Ah".format(it) } ?: "—"}", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("POWER", telemetry?.powerW?.let(::formatPowerReading) ?: "—", "", Icons.Default.Bolt, Modifier.weight(1f))
                MetricCard("CYCLES", telemetry?.cycleCount?.toString() ?: "—", "", Icons.Default.History, Modifier.weight(1f))
                MetricCard("TEMP", telemetry?.temperaturesC?.firstOrNull()?.toString() ?: "—", if (telemetry?.temperaturesC?.firstOrNull() != null) "°C" else "", Icons.Default.BatteryChargingFull, Modifier.weight(1f))
            }
        }
        item { RuntimeEstimateCard(telemetry, connected) }
        item { CellsCard(telemetry) }
        item {
            DataSection("Protection status") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatePill("Charge MOS", telemetry?.chargeMosEnabled)
                    StatePill("Discharge MOS", telemetry?.dischargeMosEnabled)
                    StatePill("Balance", telemetry?.balanceEnabled)
                }
                Text("Status information: ${telemetry?.alarmCode?.let { if (it == 0L) "0 alarms" else "alarm 0x%X".format(it) } ?: "—"}", color = Ink, fontSize = 14.sp)
                Text("${telemetry?.temperaturesC?.size?.toString() ?: "—"} temp sensor(s) · MOS temperature ${telemetry?.temperaturesC?.lastOrNull()?.let { "$it °C" } ?: "—"}", color = Ink, fontSize = 14.sp)
            }
        }
        item {
            DataSection("BMS details") {
                DetailLine("Device name", if (connected) batteryName.ifBlank { "CONNECTED BMS" } else "Waiting for connection")
                DetailLine("Connection", if (connected) "Connected" else "Disconnected")
                DetailLine("Protocol", if (connected) telemetry?.protocol ?: "Reading…" else "—")
                DetailLine("Pack voltage", telemetry?.packVoltageV?.let { "%.2f V".format(it) } ?: "—")
                DetailLine("Current", telemetry?.currentA?.let { "%.1f A".format(it) } ?: "—")
                DetailLine("Power", telemetry?.powerW?.let(::formatPowerReading) ?: "—")
                DetailLine("State of charge", telemetry?.socPercent?.let { "%.1f%%".format(it) } ?: "—")
                DetailLine("Remaining capacity", telemetry?.remainingCapacityAh?.let { "%.1f Ah".format(it) } ?: "—")
                DetailLine("Cells", telemetry?.cellVoltagesV?.size?.takeIf { it > 0 }?.toString() ?: "—")
                DetailLine("Cycle count", telemetry?.cycleCount?.toString() ?: "—")
                DetailLine("Temperatures", telemetry?.temperaturesC?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "$it °C" } ?: "—")
                DetailLine("Serial / firmware", telemetry?.firmwareVersion ?: "Not reported by the connected BLE protocol")
            }
        }
        item {
                    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BluetoothActivityIndicator(connected, bluetoothActivity)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Bluetooth connection", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text(if (connected) "${batteryName.ifBlank { "ARENQ BMS" }} · live" else "Find ARENQ BMS", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Button(onClick = onConnect, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text(if (connected) "Connected ✓" else "Connect") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { slot ->
                    val name = slotNames.getOrNull(slot).orEmpty().ifBlank { "BMS ${slot + 1}" }
                    FilterChip(
                        selected = selectedSlot == slot,
                        onClick = { onSelectSlot(slot) },
                        label = {
                            Column {
                                Text(name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (slotConnections.getOrNull(slot) == true) "Connected" else "Saved / idle", maxLines = 1, fontSize = 9.sp)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item { Text(if (connected && telemetry == null) readingStatus else if (!connected) "Not connected. Tap Connect to scan for your battery." else "Live data updates from the connected BMS. Disconnecting clears current readings; saved history remains on this phone.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 2.dp)) }
    }
}

@Composable
private fun CriticalBatteryAlarm(active: Boolean) {
    LaunchedEffect(active) {
        if (active) {
            val alarm = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 85) }.getOrNull()
            try {
                while (true) {
                    alarm?.startTone(ToneGenerator.TONE_PROP_BEEP, 700)
                    delay(1000)
                }
            } finally {
                alarm?.stopTone()
                alarm?.release()
            }
        }
    }
}

@Composable
private fun RuntimeEstimateCard(telemetry: BmsTelemetry?, connected: Boolean) {
    val current = telemetry?.currentA
    val remainingAh = telemetry?.remainingCapacityAh
    val charging = current != null && current > 0.2
    val discharging = current != null && current < -0.2
    val soc = telemetry?.socPercent
    val chargingComplete = current != null && kotlin.math.abs(current) <= 0.2 && soc != null && soc >= 99.0
    val figureColor = when {
        soc == null -> Teal
        soc <= 5.0 -> Color(0xFFD32F2F)
        soc <= 25.0 -> Color(0xFFFFC928)
        soc <= 50.0 -> Color(0xFF9DBB45)
        soc >= 90.0 -> Color(0xFF087A38)
        else -> Color(0xFF27A66A)
    }
    val estimateMinutes = if (current != null && current < -0.2 && remainingAh != null && remainingAh > 0.0) {
        (remainingAh / kotlin.math.abs(current) * 60.0).toInt().coerceAtLeast(1)
    } else null
    val estimateText = if (estimateMinutes != null) {
            val days = estimateMinutes / (24 * 60)
            val hours = estimateMinutes % (24 * 60) / 60
            val minutes = estimateMinutes % 60
            if (days > 0) "${days}d ${hours}h ${minutes}m" else if (hours > 0) "${hours}h ${minutes}m" else "$minutes min"
    } else "—"
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    !connected -> Text("BACKUP TIME", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    discharging -> {
                        Text("Estimated\nBattery Backup\nTime", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 18.sp)
                    }
                    charging -> {
                        Text("Charging", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        ChargingFlowAnimation()
                    }
                    chargingComplete -> Text("Battery Charged", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    telemetry == null -> Text("Waiting for BMS data", color = Muted, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    else -> Text("Battery idle", color = Muted, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    !connected -> Text("—", color = Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    discharging -> {
                        Text(digitColoredText(estimateText, figureColor, Teal), fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End)
                        Text("Connected Load", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        Text(digitColoredText(telemetry?.powerW?.let(::formatPowerReading) ?: "—", figureColor, Ink), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    charging -> {
                        Text("Charging", color = Green, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.End) {
                            Text(digitColoredText("%.1f".format(current), figureColor, Ink), fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("A", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp, bottom = 4.dp))
                        }
                        Text("Charging current", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                    }
                    chargingComplete -> Text("No Load", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    telemetry == null -> Text("—", color = Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    else -> Text("—", color = Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun digitColoredText(value: String, digitColor: Color, textColor: Color) = buildAnnotatedString {
    value.forEach { character ->
        withStyle(SpanStyle(color = if (character.isDigit()) digitColor else textColor)) { append(character) }
    }
}

@Composable
private fun ChargingFlowAnimation() {
    val transition = rememberInfiniteTransition(label = "charging flow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing), RepeatMode.Restart),
        label = "charging flow phase"
    )
    Canvas(Modifier.width(126.dp).height(24.dp)) {
        val mid = size.height / 2f
        val amplitude = size.height * .24f
        val path = Path().apply {
            for (i in 0..48) {
                val x = size.width * i / 48f
                val y = mid + sin((x / size.width * 2.5 * PI + phase * 2 * PI)).toFloat() * amplitude
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, Color(0xFF4BD4E4).copy(alpha = .42f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        for (i in 0..3) {
            val progress = (phase + i * .25f) % 1f
            val x = size.width * progress
            val y = mid + sin((x / size.width * 2.5 * PI + phase * 2 * PI)).toFloat() * amplitude
            drawCircle(Color(0xFF8AF6A2).copy(alpha = 1f - progress * .42f), radius = 2.5.dp.toPx(), center = Offset(x, y))
        }
        val bolt = Path().apply {
            moveTo(size.width - 9.dp.toPx(), 2.dp.toPx())
            lineTo(size.width - 16.dp.toPx(), mid)
            lineTo(size.width - 11.dp.toPx(), mid)
            lineTo(size.width - 15.dp.toPx(), size.height - 2.dp.toPx())
            lineTo(size.width - 5.dp.toPx(), mid - 2.dp.toPx())
            lineTo(size.width - 10.dp.toPx(), mid - 2.dp.toPx())
            close()
        }
        drawPath(bolt, Color(0xFF8AF6A2))
    }
}

@Composable
private fun DataSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun StatePill(label: String, enabled: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (enabled == true) Green else Muted))
        Text(if (enabled == null) "$label —" else label, fontSize = 11.sp, color = Ink)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private data class ChargeDischargeSession(
    val mode: String,
    val startMillis: Long,
    val endMillis: Long,
    val ampHours: Double,
    val energyKwh: Double,
    val averageCurrentA: Double,
    val averagePowerW: Double,
    val startSoc: Double,
    val endSoc: Double
)

private fun buildSessions(points: List<BatteryHistoryPoint>): List<ChargeDischargeSession> {
    val result = mutableListOf<ChargeDischargeSession>()
    val activeModes = setOf("Charging", "Discharging")
    var mode: String? = null
    var start = 0L
    var end = 0L
    var ampHours = 0.0
    var energy = 0.0
    var startSoc = 0.0
    var endSoc = 0.0
    fun finish() {
        val savedMode = mode ?: return
        if (end > start) {
            val hours = (end - start).toDouble() / 3_600_000.0
            result += ChargeDischargeSession(savedMode, start, end, ampHours, energy, if (hours > 0) ampHours / hours else 0.0, if (hours > 0) energy * 1000.0 / hours else 0.0, startSoc, endSoc)
        }
        mode = null
    }
    points.sortedBy { it.timestampMillis }.zipWithNext().forEach { (a, b) ->
        val nextMode = if (a.mode in activeModes && a.mode == b.mode) a.mode else null
        if (nextMode != mode) {
            finish()
            if (nextMode != null) { mode = nextMode; start = a.timestampMillis; end = b.timestampMillis; startSoc = a.socPercent; endSoc = b.socPercent; ampHours = 0.0; energy = 0.0 }
        }
        if (nextMode != null && nextMode == mode) {
            val hours = ((b.timestampMillis - a.timestampMillis).coerceIn(0L, 60_000L)).toDouble() / 3_600_000.0
            ampHours += (kotlin.math.abs(a.currentA) + kotlin.math.abs(b.currentA)) / 2.0 * hours
            energy += (kotlin.math.abs(a.currentA * a.voltageV) + kotlin.math.abs(b.currentA * b.voltageV)) / 2.0 * hours / 1000.0
            end = b.timestampMillis
            endSoc = b.socPercent
        }
    }
    finish()
    return result.asReversed()
}

@Composable
private fun AboutPage(padding: PaddingValues) {
    val context = LocalContext.current
    fun dial(number: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
    }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("About neXconnect", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(R.drawable.power_nex_about_logo),
                    contentDescription = "Power Nex logo",
                    modifier = Modifier.fillMaxWidth().height(108.dp),
                    contentScale = ContentScale.Fit
                )
                Text("POWER NEX", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text("Power solution", color = Teal, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("NEXORA SOLUTIONS", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Near Bypass Junction", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Chembra Road - Perambra", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Kozhikkode Dt - Kerala State", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Pin Code: 673525", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                HorizontalDivider(Modifier.padding(vertical = 3.dp), color = Page)
                Text("Contact", color = Ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Touch the numbers to Call", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                listOf("+919562060602", "+919745008687", "+918907606090").forEach { number ->
                    Text(
                        number,
                        color = Teal,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable { dial(number) }
                    )
                }
            }
        }
        Text("Designed by", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Image(
            painterResource(R.drawable.chanakya_logo),
            contentDescription = "Chanakya · The Rhythm of Multimedia",
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun HistoryPage(padding: PaddingValues, context: android.content.Context, revision: Long, deviceSlot: Int, onClear: () -> Unit) {
    val bounds = remember(context, revision, deviceSlot) { BatteryHistoryStore.bounds(context, deviceSlot) }
    val initialDate = remember(context) {
        bounds.second?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()
    }
    var selectedYear by remember(context) { mutableIntStateOf(initialDate.year) }
    var selectedMonth by remember(context) { mutableIntStateOf(initialDate.monthValue) }
    var selectedDay by remember(context) { mutableIntStateOf(initialDate.dayOfMonth) }
    var resolution by remember { mutableStateOf("Days") }
    val monthLength = YearMonth.of(selectedYear, selectedMonth).lengthOfMonth()
    val day = selectedDay.coerceIn(1, monthLength)
    val zone = ZoneId.systemDefault()
    val monthStart = YearMonth.of(selectedYear, selectedMonth).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = YearMonth.of(selectedYear, selectedMonth).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val points = remember(context, monthStart, monthEnd, revision, deviceSlot) { BatteryHistoryStore.loadBetween(context, monthStart, monthEnd, deviceSlot) }
    val totalCount = remember(context, revision, deviceSlot) { BatteryHistoryStore.count(context, deviceSlot) }
    val sessions = remember(points) { buildSessions(points) }
    val years = if (bounds.first != null && bounds.second != null) {
        val firstYear = Instant.ofEpochMilli(bounds.first!!).atZone(zone).year
        val lastYear = Instant.ofEpochMilli(bounds.second!!).atZone(zone).year
        (firstYear..lastYear).toList().ifEmpty { listOf(initialDate.year) }
    } else listOf(LocalDate.now().year)
    val energyBars = remember(points, resolution, selectedYear, selectedMonth, day) { buildEnergyBars(points, resolution, selectedYear, selectedMonth, day) }
    val selectedDayPoints = remember(points, selectedYear, selectedMonth, day) {
        val selectedDate = LocalDate.of(selectedYear, selectedMonth, day)
        points.filter { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() == selectedDate }
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Charge & discharge history", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Saved on this phone · $totalCount samples", color = Muted, fontSize = 12.sp)
            }
            if (totalCount > 0) TextButton(onClick = onClear) { Text("Clear") }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose time range", color = Ink, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistorySelector("Month", Month.of(selectedMonth).getDisplayName(TextStyle.SHORT, Locale.getDefault()), (1..12).map { it to Month.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }, Modifier.weight(1f)) { selectedMonth = it; selectedDay = selectedDay.coerceAtMost(YearMonth.of(selectedYear, it).lengthOfMonth()) }
                    HistorySelector("Year", selectedYear.toString(), years.map { it to it.toString() }, Modifier.weight(1f)) { selectedYear = it; selectedDay = selectedDay.coerceAtMost(YearMonth.of(it, selectedMonth).lengthOfMonth()) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = resolution == "Days", onClick = { resolution = "Days" }, label = { Text("Days") })
                    FilterChip(selected = resolution == "Hours", onClick = { resolution = "Hours" }, label = { Text("Hours") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistorySelector("Day", day.toString(), (1..monthLength).map { it to it.toString() }, Modifier.weight(1f)) { selectedDay = it }
                }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (resolution == "Hours") "Hourly charge & discharge" else "Daily charge & discharge", color = Ink, fontWeight = FontWeight.SemiBold)
                Text(if (resolution == "Hours") "Bar height: energy · labels: SOC change for each hour" else "Energy from BMS battery current", color = Muted, fontSize = 12.sp)
                if (points.size < 2) {
                    Text("No readings for this month yet. History is recorded while the BMS is connected.", color = Muted, fontSize = 13.sp)
                } else EnergyBarChart(energyBars, zoomable = resolution == "Hours")
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HistoryLegend("Discharge", Teal)
                    HistoryLegend("Charge", Green)
                }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("State of charge · selected day", color = Ink, fontWeight = FontWeight.SemiBold)
                if (selectedDayPoints.size >= 2) {
                    val stride = ((selectedDayPoints.size + 179) / 180).coerceAtLeast(1)
                    val visible = selectedDayPoints.filterIndexed { index, _ -> index % stride == 0 }.let { sampled ->
                        if (sampled.lastOrNull() == selectedDayPoints.last()) sampled else sampled + selectedDayPoints.last()
                    }
                    val chartTeal = Teal
                    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                        val low = 0.0
                        val high = 100.0
                        val path = Path()
                        val xStep = size.width / (visible.size - 1).coerceAtLeast(1)
                        visible.forEachIndexed { index, point ->
                            val x = index * xStep
                            val y = size.height - (point.socPercent.coerceIn(low, high) / high * size.height).toFloat()
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        for (i in 0..4) {
                            val y = size.height * i / 4f
                            drawLine(Color(0xFFE7EDF1), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                        }
                        drawPath(path, chartTeal, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    }
                    val last = selectedDayPoints.last()
                    Text("Latest: ${"%.1f".format(last.socPercent)}% SOC · ${"%.1f".format(last.voltageV)} V", color = Muted, fontSize = 12.sp)
                } else {
                    Text("Not enough readings on this day to draw the SOC trend.", color = Muted, fontSize = 13.sp)
                }
            }
        }
        Text("Sessions", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (sessions.isEmpty()) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
                Text("No charge or discharge sessions recorded for this month.", Modifier.padding(16.dp), color = Muted, fontSize = 13.sp)
            }
        } else sessions.take(30).forEach { session ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(session.mode, color = if (session.mode == "Charging") Green else Teal, fontWeight = FontWeight.SemiBold)
                        Text(formatPowerReading(session.averagePowerW), color = Ink, fontWeight = FontWeight.SemiBold)
                    }
                    Text("${formatTime(session.startMillis)} – ${formatTime(session.endMillis)} · ${"%.2f".format(session.ampHours)} Ah", color = Muted, fontSize = 12.sp)
                    Text("Average current ${"%.1f".format(session.averageCurrentA)} A", color = Muted, fontSize = 12.sp)
                    Text("SOC ${"%.1f".format(session.startSoc)}% → ${"%.1f".format(session.endSoc)}%", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

private data class EnergyBar(
    val label: String,
    var chargeKwh: Double = 0.0,
    var dischargeKwh: Double = 0.0,
    var chargePercent: Double = 0.0,
    var dischargePercent: Double = 0.0
)

private fun buildEnergyBars(points: List<BatteryHistoryPoint>, resolution: String, year: Int, month: Int, day: Int): List<EnergyBar> {
    val count = if (resolution == "Days") YearMonth.of(year, month).lengthOfMonth() else 24
    val bars = MutableList(count) { index ->
        EnergyBar(if (resolution == "Days") "%02d".format(index + 1) else "%02d:00".format(index))
    }
    val zone = ZoneId.systemDefault()
    points.sortedBy { it.timestampMillis }.zipWithNext().forEach { (first, next) ->
        val elapsed = next.timestampMillis - first.timestampMillis
        if (elapsed <= 0 || elapsed > 3 * 60 * 1000L) return@forEach
        val midpoint = Instant.ofEpochMilli(first.timestampMillis + elapsed / 2).atZone(zone)
        val index = when (resolution) {
            "Days" -> if (midpoint.year == year && midpoint.monthValue == month) midpoint.dayOfMonth - 1 else -1
            "Hours" -> if (midpoint.toLocalDate() == LocalDate.of(year, month, day)) midpoint.hour else -1
            else -> if (midpoint.toLocalDate() == LocalDate.of(year, month, day)) midpoint.hour else -1
        }
        if (index !in bars.indices) return@forEach
        val averageCurrent = (first.currentA + next.currentA) / 2.0
        if (kotlin.math.abs(averageCurrent) < 0.05) return@forEach
        val averageVoltage = (first.voltageV + next.voltageV) / 2.0
        val energy = kotlin.math.abs(averageCurrent) * averageVoltage * elapsed / 3_600_000.0 / 1000.0
        val socChange = next.socPercent - first.socPercent
        if (averageCurrent < 0.0) {
            bars[index].dischargeKwh += energy
            bars[index].dischargePercent += (-socChange).coerceAtLeast(0.0)
        } else {
            bars[index].chargeKwh += energy
            bars[index].chargePercent += socChange.coerceAtLeast(0.0)
        }
    }
    return bars
}

@Composable
private fun HistorySelector(label: String, selected: String, options: List<Pair<Int, String>>, modifier: Modifier = Modifier, onSelected: (Int) -> Unit) {
    var expanded by remember(label) { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, color = Muted, fontSize = 10.sp, maxLines = 1)
                Text("$selected  ▾", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelected(value); expanded = false })
            }
        }
    }
}

@Composable
private fun EnergyBarChart(bars: List<EnergyBar>, zoomable: Boolean = false) {
    val chargeColor = Green
    val dischargeColor = Teal
    val peak = bars.maxOfOrNull { maxOf(it.chargeKwh, it.dischargeKwh) }?.coerceAtLeast(0.0001) ?: 0.0001
    var zoom by remember(zoomable) { mutableFloatStateOf(1f) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    if (zoomable) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("Pinch to zoom · drag to scroll", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }) { Text("−") }
            Text("${(zoom * 100).roundToInt()}%", color = Ink, fontSize = 12.sp)
            TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) { Text("+") }
            TextButton(onClick = { zoom = 1f; scope.launch { scrollState.scrollTo(0) } }) { Text("Reset") }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().then(if (zoomable) Modifier.pointerInput(zoomable) {
            detectTransformGestures { _, pan, zoomChange, _ ->
                zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                if (pan.x != 0f) scope.launch { scrollState.scrollBy(-pan.x) }
            }
        } else Modifier)
    ) {
        // A minimum of 44 dp per hour keeps all 24 HH:00 labels legible at the base zoom.
        val baseWidth = if (zoomable) maxWidth.coerceAtLeast(44.dp * bars.size) else maxWidth
        val chartWidth = if (zoomable) baseWidth * zoom else maxWidth
        Column(Modifier.then(if (zoomable) Modifier.horizontalScroll(scrollState) else Modifier).width(chartWidth)) {
            if (zoomable) {
                Row(Modifier.width(chartWidth), horizontalArrangement = Arrangement.Start) {
                    bars.forEach { bar ->
                        Column(Modifier.width(chartWidth / bars.size.coerceAtLeast(1)), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("+%.1f%%".format(bar.chargePercent), color = chargeColor, fontSize = 8.sp, maxLines = 1)
                            Text("−%.1f%%".format(bar.dischargePercent), color = dischargeColor, fontSize = 8.sp, maxLines = 1)
                        }
                    }
                }
            }
            Canvas(Modifier.width(chartWidth).height(160.dp)) {
                val slot = size.width / bars.size.coerceAtLeast(1)
                val barWidth = (slot * 0.34f).coerceAtMost(10.dp.toPx()).coerceAtLeast(1.dp.toPx())
                bars.forEachIndexed { index, bar ->
                    val center = slot * index + slot / 2f
                    val chargeHeight = (bar.chargeKwh / peak * size.height).toFloat()
                    val dischargeHeight = (bar.dischargeKwh / peak * size.height).toFloat()
                    if (chargeHeight > 0f) drawRoundRect(chargeColor, androidx.compose.ui.geometry.Offset(center - barWidth - 1.dp.toPx(), size.height - chargeHeight), androidx.compose.ui.geometry.Size(barWidth, chargeHeight), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    if (dischargeHeight > 0f) drawRoundRect(dischargeColor, androidx.compose.ui.geometry.Offset(center + 1.dp.toPx(), size.height - dischargeHeight), androidx.compose.ui.geometry.Size(barWidth, dischargeHeight), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                }
            }
            val tickStep = (bars.size / 6).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                bars.forEachIndexed { index, bar ->
                    if (zoomable || index % tickStep == 0 || index == bars.lastIndex) Text(bar.label, color = Muted, fontSize = if (zoomable) 10.sp else 9.sp)
                }
            }
        }
    }
}

@Composable
private fun HistoryLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun MetricCard(label: String, value: String, unit: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier.height(112.dp), colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Teal, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Start) {
                Text(value, color = Ink, fontSize = if (label == "POWER") 19.sp else 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, modifier = Modifier.weight(1f, fill = false))
                if (unit.isNotBlank()) Text(unit, color = Muted, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
            }
        }
    }
}

@Composable
private fun CellsCard(telemetry: BmsTelemetry? = null) {
    val cells = telemetry?.cellVoltagesV.orEmpty()
    val min = cells.minOrNull()
    val max = cells.maxOrNull()
    val average = cells.takeIf { it.isNotEmpty() }?.average()
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Cell voltage", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(if (cells.isEmpty()) "— cells" else "${cells.size} cells", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Cell("Min", min?.let { "%.3f V".format(it) } ?: "—", min?.let { "Cell ${cells.indexOf(it) + 1}" } ?: "")
                Cell("Average", average?.let { "%.3f V".format(it) } ?: "—", "Pack")
                Cell("Max", max?.let { "%.3f V".format(it) } ?: "—", max?.let { "Cell ${cells.indexOf(it) + 1}" } ?: "")
            }
            Spacer(Modifier.height(13.dp))
            Text(max?.let { "Voltage difference  %.3f V".format(it - (min ?: it)) } ?: "No cell readings received", fontSize = 12.sp, color = Muted)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Page)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (cells.isEmpty()) Text("Cell values appear here while connected.", color = Muted, fontSize = 12.sp)
                else cells.take(4).forEachIndexed { index, value -> Cell("Cell ${index + 1}", "%.3f V".format(value), "") }
            }
        }
    }
}

@Composable
private fun Cell(label: String, value: String, detail: String) {
    Column {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SettingsPage(
    padding: PaddingValues,
    selectedModel: BmsModel,
    onModelSelected: (BmsModel) -> Unit,
    inverterProtocol: String,
    onInverterProtocolChange: (String) -> Unit,
    selectedBleSlot: Int,
    searchCancelSignals: List<Int>,
    onTelemetry: (Int, BmsTelemetry) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onConnectionChanged: (Int, Boolean, String) -> Unit,
    onDisconnected: (Int) -> Unit,
    onBluetoothActivity: (Int, Boolean) -> Unit,
    onReadStatusChange: (Int, String) -> Unit,
    onConnect: () -> Unit
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Power Nex BMS setup", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Choose a supported BMS profile, connection, and inverter communication protocol.", color = Muted)
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Appearance", color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(if (darkTheme) "Dark theme" else "Light theme", color = Muted, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !darkTheme, onClick = { onDarkThemeChange(false) }, label = { Text("Light") })
                    FilterChip(selected = darkTheme, onClick = { onDarkThemeChange(true) }, label = { Text("Dark") })
                }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text("VENDOR & MODEL", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Box {
                    OutlinedButton(onClick = { modelMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                        Text("${selectedModel.vendor} · ${selectedModel.model}", modifier = Modifier.weight(1f), maxLines = 1)
                        Text("⌄", fontSize = 18.sp)
                    }
                    DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                        bmsModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text("${model.vendor} · ${model.model}") },
                                onClick = { onModelSelected(model); modelMenuExpanded = false }
                            )
                        }
                    }
                }
                SettingRow("Protocol family", selectedModel.protocol)
                SettingRow("Connection options", selectedModel.transport)
                HorizontalDivider(color = Page)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFF3AA42)))
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        selectedModel.vendor.startsWith("Daly", true) -> "Experimental read-only DALY driver · verify firmware"
                        selectedModel.vendor.startsWith("JBD", true) -> "Read-only JBD V4 driver · verify software-board firmware"
                        selectedModel.vendor == "NEEY" -> "Balancer profile only · not a pack BMS telemetry driver"
                        selectedModel.vendor == "JLGW" -> "Protocol needs exact model and vendor documentation"
                        else -> "Profile details depend on the exact model and communication interface"
                    }, color = Color(0xFF9A6B1A), fontSize = 12.sp)
                }
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Text(if (selectedModel.transport.contains("Bluetooth")) "Set up Bluetooth BMS" else "Set up BMS connection")
                }
            }
        }
        (0..2).forEach { slot ->
            key(slot) {
                Box(
                    Modifier.fillMaxWidth()
                        .then(if (slot == selectedBleSlot) Modifier else Modifier.height(0.dp))
                        .clipToBounds()
                ) {
                    BleDiscoveryCard(
                        selectedVendor = selectedModel.vendor,
                        selectedProtocol = selectedModel.protocol,
                        onTelemetry = { sample -> onTelemetry(slot, sample) },
                        onConnectionChanged = { connected, name -> onConnectionChanged(slot, connected, name) },
                        onDisconnected = { onDisconnected(slot) },
                        onConnectionActivityChanged = { active -> onBluetoothActivity(slot, active) },
                        onReadStatusChanged = { status -> onReadStatusChange(slot, status) },
                        cancelSearchSignal = searchCancelSignals[slot],
                        connectionSlot = slot
                    )
                }
            }
        }
        InverterProtocolCard(inverterProtocol, onInverterProtocolChange)
        Text("BMS protection and charging parameters are read-only in this app build; no generic LiFePO₄ values are shown or written.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun InverterProtocolCard(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("INVERTER COMMUNICATION PROFILE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Text("Select the protocol used between the battery and inverter. This setting is separate from the BMS Bluetooth connection.", color = Muted, fontSize = 12.sp)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Text(selected, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text("⌄", fontSize = 18.sp)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    inverterProtocols.forEach { protocol ->
                        DropdownMenuItem(text = { Text(protocol) }, onClick = { onSelected(protocol); expanded = false })
                    }
                }
            }
            Text("Profile selection is saved only in this preview; it does not configure the battery or inverter.", color = Color(0xFF9A6B1A), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 12.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun SimplePage(padding: PaddingValues, title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = Teal, modifier = Modifier.size(34.dp))
                Text(description, color = Muted)
            }
        }
    }
}
