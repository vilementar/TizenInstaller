package com.vilementar.watchinstaller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════
//  THEME COLORS (Modern One UI Dark Palette)
// ═══════════════════════════════════════════════════════════════
val ThemeBg = Color(0xFF0C0D10)
val ThemeCard = Color(0xFF171920)
val ThemeCardAlt = Color(0xFF1E212A)
val ThemeBorder = Color(0xFF282B37)
val ThemePrimary = Color(0xFF0C66E4)
val ThemeTextPrimary = Color(0xFFF5F6FA)
val ThemeTextSecondary = Color(0xFF8A90A2)
val ThemeGreen = Color(0xFF22C55E)
val ThemeGreenBg = Color(0xFF122A1E)
val ThemeRed = Color(0xFFF87171)
val ThemeRedBg = Color(0xFF33161A)
val ThemeYellow = Color(0xFFFBBF24)
val ThemeConsoleBg = Color(0xFF08090C)

data class LogItem(
    val timestamp: String,
    val message: String,
    val type: LogType = LogType.INFO
)

enum class LogType {
    INFO, SUCCESS, ERROR, METHOD, WARNING
}

enum class ConnectionMode {
    BLUETOOTH, WIFI
}

class MainActivity : ComponentActivity() {

    private var selectedAppInfo by mutableStateOf<TizenAppInfo?>(null)
    private var isInstalling by mutableStateOf(false)
    private var installProgress by mutableStateOf(0f)

    private var connectionMode by mutableStateOf(ConnectionMode.BLUETOOTH)

    // Bluetooth State
    private val bluetoothDevices = mutableStateListOf<BluetoothDevice>()
    private var selectedBtDevice by mutableStateOf<BluetoothDevice?>(null)
    private var isBtTesting by mutableStateOf(false)

    // Wi-Fi State
    private var watchIp by mutableStateOf("192.168.1.49")
    private var watchPort by mutableStateOf("26101")
    private var isConnected by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)
    private var scanProgress by mutableStateOf(0f)

    private val logs = mutableStateListOf<LogItem>()

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processSelectedFile(it) }
    }

    private val requestBtPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            loadBluetoothDevices()
        } else {
            addLog("Bluetooth permission denied. Cannot list paired Galaxy Watch.", LogType.ERROR)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addLog("TizenInstaller initialized. SDB over Bluetooth and Wi-Fi ready.", LogType.INFO)
        loadBluetoothDevices()

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ThemeBg
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BtBridgeManager.stopBridge()
    }

    @SuppressLint("MissingPermission")
    private fun loadBluetoothDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect) {
                requestBtPermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
                return
            }
        }

        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                addLog("Bluetooth hardware not available on this device.", LogType.ERROR)
                return
            }
            if (!adapter.isEnabled) {
                addLog("Bluetooth is currently turned OFF. Enable Bluetooth to connect to Galaxy Watch.", LogType.WARNING)
                return
            }

            val bonded = adapter.bondedDevices ?: emptySet()
            bluetoothDevices.clear()
            bluetoothDevices.addAll(bonded)

            if (selectedBtDevice == null || !bonded.contains(selectedBtDevice)) {
                // Auto-detect Galaxy Watch or Gear
                val watch = bonded.find { dev ->
                    val name = dev.name ?: ""
                    name.contains("Watch", ignoreCase = true) ||
                    name.contains("Gear", ignoreCase = true) ||
                    name.contains("Galaxy", ignoreCase = true)
                }
                selectedBtDevice = watch ?: bonded.firstOrNull()
            }

            if (selectedBtDevice != null) {
                addLog("Detected paired watch: ${selectedBtDevice?.name} (${selectedBtDevice?.address})", LogType.SUCCESS)
            } else {
                addLog("No paired Galaxy Watch found. Please pair your watch in Android Bluetooth settings.", LogType.WARNING)
            }
        } catch (e: Exception) {
            addLog("Bluetooth scan error: ${e.message}", LogType.ERROR)
        }
    }

    private fun addLog(msg: String, type: LogType = LogType.INFO) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logType = when {
            msg.contains("✔") || msg.contains("🎉") || msg.contains("SUCCESS") -> LogType.SUCCESS
            msg.contains("❌") || msg.contains("failed") || msg.contains("error") -> LogType.ERROR
            msg.contains("[1/4]") || msg.contains("[2/4]") || msg.contains("[3/4]") || msg.contains("[4/4]") -> LogType.METHOD
            msg.contains("👉") || msg.contains("CHECK YOUR WATCH") || msg.contains("Wakeup") -> LogType.WARNING
            else -> type
        }
        logs.add(LogItem(time, msg, logType))
    }

    @Composable
    fun AppTheme(content: @Composable () -> Unit) {
        val colorScheme = darkColorScheme(
            primary = ThemePrimary,
            background = ThemeBg,
            surface = ThemeCard,
            onPrimary = Color.White,
            onBackground = ThemeTextPrimary,
            onSurface = ThemeTextPrimary
        )
        MaterialTheme(colorScheme = colorScheme, content = content)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("MissingPermission")
    @Composable
    fun MainScreen() {
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // ─── 1. TOP HEADER ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TizenInstaller",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = ThemeTextPrimary
                    )
                    Text(
                        text = "by Vilementar • SDB over BT & Wi-Fi",
                        fontSize = 13.sp,
                        color = ThemeTextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Status Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isConnected) ThemeGreenBg else ThemeRedBg)
                        .border(1.dp, if (isConnected) ThemeGreen else ThemeRed, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isConnected) "● Connected" else "● Offline",
                        color = if (isConnected) ThemeGreen else ThemeRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ─── 2. CONNECTION MODE SELECTOR TABS ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ThemeCard)
                    .border(1.dp, ThemeBorder, RoundedCornerShape(14.dp))
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (connectionMode == ConnectionMode.BLUETOOTH) ThemePrimary else Color.Transparent)
                        .clickable { connectionMode = ConnectionMode.BLUETOOTH }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🔵 Bluetooth (BT Bridge)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (connectionMode == ConnectionMode.BLUETOOTH) Color.White else ThemeTextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (connectionMode == ConnectionMode.WIFI) ThemePrimary else Color.Transparent)
                        .clickable { connectionMode = ConnectionMode.WIFI }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📶 Wi-Fi (IP)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (connectionMode == ConnectionMode.WIFI) Color.White else ThemeTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ─── 3. CONNECTION CARD (BLUETOOTH OR WI-FI) ───
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ThemeCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(ThemeBorder))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    if (connectionMode == ConnectionMode.BLUETOOTH) {
                        // ── BLUETOOTH MODE ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SDB over Bluetooth",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ThemeTextPrimary
                            )
                            Text(
                                text = "Samsung RFCOMM",
                                fontSize = 12.sp,
                                color = ThemePrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Bypasses router/LTE routing. Communicates directly with watch over Bluetooth RFCOMM.",
                            fontSize = 12.sp,
                            color = ThemeTextSecondary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Device selector dropdown
                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = selectedBtDevice?.name ?: (selectedBtDevice?.address ?: "No device selected"),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Paired Galaxy Watch", color = ThemeTextSecondary) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ThemePrimary,
                                    unfocusedBorderColor = ThemeBorder,
                                    focusedTextColor = ThemeTextPrimary,
                                    unfocusedTextColor = ThemeTextPrimary
                                )
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.background(ThemeCardAlt)
                            ) {
                                bluetoothDevices.forEach { dev ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(dev.name ?: "Unknown Device", color = ThemeTextPrimary, fontWeight = FontWeight.SemiBold)
                                                Text(dev.address, color = ThemeTextSecondary, fontSize = 11.sp)
                                            }
                                        },
                                        onClick = {
                                            selectedBtDevice = dev
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { loadBluetoothDevices() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemeCardAlt)
                            ) {
                                Text("🔄 Refresh", color = ThemeTextPrimary)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    val dev = selectedBtDevice
                                    if (dev == null) {
                                        Toast.makeText(this@MainActivity, "Please select a watch", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    coroutineScope.launch {
                                        isBtTesting = true
                                        addLog("Testing Bluetooth RFCOMM bridge to ${dev.name}...")
                                        try {
                                            val p = BtBridgeManager.startBridge(dev) { msg -> addLog(msg) }
                                            addLog("✔ Bluetooth bridge active on 127.0.0.1:$p", LogType.SUCCESS)
                                            val session = SdbClient.connectAndAuthorize(this@MainActivity, "127.0.0.1", p) { msg -> addLog(msg) }
                                            val banner = session.execShell("uname -a")
                                            session.close()
                                            isConnected = true
                                            addLog("✔ Watch SDB response: $banner", LogType.SUCCESS)
                                            Toast.makeText(this@MainActivity, "Connected to watch via Bluetooth!", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            addLog("❌ Bluetooth connection failed: ${e.message}", LogType.ERROR)
                                            Toast.makeText(this@MainActivity, "BT connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        } finally {
                                            isBtTesting = false
                                        }
                                    }
                                },
                                enabled = selectedBtDevice != null && !isBtTesting && !isInstalling,
                                modifier = Modifier.weight(1.3f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemePrimary)
                            ) {
                                Text(if (isBtTesting) "Connecting..." else "Test BT Connection", fontWeight = FontWeight.Bold)
                            }
                        }

                    } else {
                        // ── WI-FI MODE ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Watch Wi-Fi Connection",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ThemeTextPrimary
                            )
                            Text(
                                text = "Port 26101",
                                fontSize = 12.sp,
                                color = ThemePrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = watchIp,
                                onValueChange = { watchIp = it },
                                label = { Text("Watch IP Address", color = ThemeTextSecondary) },
                                singleLine = true,
                                modifier = Modifier.weight(2.5f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ThemePrimary,
                                    unfocusedBorderColor = ThemeBorder,
                                    focusedTextColor = ThemeTextPrimary,
                                    unfocusedTextColor = ThemeTextPrimary
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            OutlinedTextField(
                                value = watchPort,
                                onValueChange = { watchPort = it },
                                label = { Text("Port", color = ThemeTextSecondary) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ThemePrimary,
                                    unfocusedBorderColor = ThemeBorder,
                                    focusedTextColor = ThemeTextPrimary,
                                    unfocusedTextColor = ThemeTextPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isScanning) {
                            Column {
                                LinearProgressIndicator(
                                    progress = { scanProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = ThemePrimary,
                                    trackColor = ThemeBorder
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Scanning subnet: ${(scanProgress * 100).toInt()}%...",
                                    fontSize = 11.sp,
                                    color = ThemeTextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isScanning = true
                                        scanProgress = 0f
                                        val prefix = SdbClient.getLocalSubnet(this@MainActivity)
                                        addLog("Scanning subnet $prefix.0/24 for Galaxy Watch on port 26101...")
                                        val found = SdbClient.scanSubnet(this@MainActivity, prefix) { p -> scanProgress = p }
                                        isScanning = false
                                        if (found.isNotEmpty()) {
                                            watchIp = found[0]
                                            isConnected = true
                                            addLog("✔ Found watch at: ${found[0]}", LogType.SUCCESS)
                                            Toast.makeText(this@MainActivity, "Detected watch: ${found[0]}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            addLog("No Tizen devices found on subnet $prefix.0/24", LogType.ERROR)
                                            Toast.makeText(this@MainActivity, "No devices found", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isScanning && !isInstalling,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemeCardAlt)
                            ) {
                                Text(if (isScanning) "Scanning..." else "🔍 Scan LAN", color = ThemeTextPrimary)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val p = watchPort.toIntOrNull() ?: 26101
                                        addLog("Probing $watchIp:$p over Wi-Fi...")
                                        val ok = withContext(Dispatchers.IO) { SdbClient.probePort(this@MainActivity, watchIp, p) }
                                        isConnected = ok
                                        if (ok) {
                                            addLog("✔ Watch is responsive on port $p!", LogType.SUCCESS)
                                            Toast.makeText(this@MainActivity, "Watch is responsive on port $p!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            addLog("❌ No response from $watchIp:$p. Ensure Debugging is ON & screen is awake.", LogType.ERROR)
                                            Toast.makeText(this@MainActivity, "No response from $watchIp:$p", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isInstalling,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemePrimary)
                            ) {
                                Text(if (isConnected) "Verify Status" else "Connect", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ─── 4. CARD: PACKAGE SELECTION (.TPK / .WGT) ───
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ThemeCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(ThemeBorder))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Target Package (.tpk / .wgt)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ThemeTextPrimary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (selectedAppInfo == null) {
                        Button(
                            onClick = { pickFileLauncher.launch("*/*") },
                            enabled = !isInstalling,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeCardAlt)
                        ) {
                            Text("📁 Select .tpk or .wgt watch package...", color = ThemePrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        val app = selectedAppInfo!!
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ThemeCardAlt)
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            app.iconBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Icon",
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                            } ?: Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ThemeBorder)
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ThemeTextPrimary
                                )
                                Text(
                                    text = "ID: ${app.packageId}",
                                    fontSize = 12.sp,
                                    color = ThemeTextSecondary
                                )
                                Text(
                                    text = "Version: ${app.version} • ${app.rawBytes.size / 1024} KB",
                                    fontSize = 12.sp,
                                    color = ThemeTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { selectedAppInfo = null },
                            enabled = !isInstalling,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Select another package", color = ThemeTextSecondary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ─── 5. CARD: INSTALLATION BUTTON & LIVE CONSOLE ───
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ThemeCard),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(ThemeBorder))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Installation & Execution",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ThemeTextPrimary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isInstalling) {
                        Column {
                            LinearProgressIndicator(
                                progress = { installProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = ThemeGreen,
                                trackColor = ThemeBorder
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Transferring / Installing... ${(installProgress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = ThemeTextSecondary
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                val app = selectedAppInfo ?: return@Button

                                coroutineScope.launch {
                                    isInstalling = true
                                    installProgress = 0f

                                    try {
                                        val (targetIp, targetPort) = if (connectionMode == ConnectionMode.BLUETOOTH) {
                                            val dev = selectedBtDevice ?: throw IOException("No Bluetooth watch selected. Pair watch in settings.")
                                            addLog("=== Starting SDB over Bluetooth installation for ${app.name} ===", LogType.INFO)
                                            val p = BtBridgeManager.startBridge(dev) { msg -> addLog(msg) }
                                            Pair("127.0.0.1", p)
                                        } else {
                                            val p = watchPort.toIntOrNull() ?: 26101
                                            addLog("=== Starting SDB Wi-Fi installation for ${app.name} ($watchIp:$p) ===", LogType.INFO)
                                            Pair(watchIp, p)
                                        }

                                        val (ok, method) = SdbClient.installPackage(
                                            context = this@MainActivity,
                                            ip = targetIp,
                                            port = targetPort,
                                            appInfo = app,
                                            onLog = { msg -> addLog(msg) },
                                            onProgress = { prg -> installProgress = prg }
                                        )

                                        if (ok) {
                                            isConnected = true
                                            Toast.makeText(this@MainActivity, "Installed successfully via $method!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "Installation failed", Toast.LENGTH_LONG).show()
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        addLog("❌ Error: ${e.message}", LogType.ERROR)
                                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isInstalling = false
                                        installProgress = 0f
                                    }
                                }
                            },
                            enabled = selectedAppInfo != null && !isInstalling,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemePrimary)
                        ) {
                            val buttonTitle = if (connectionMode == ConnectionMode.BLUETOOTH) {
                                "🚀 INSTALL OVER BLUETOOTH (RECOMMENDED)"
                            } else {
                                "🚀 INSTALL OVER SDB WI-FI"
                            }
                            Text(
                                text = buttonTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Log Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live Log Console",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ThemeTextSecondary
                        )
                        TextButton(
                            onClick = { logs.clear() },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp, color = ThemeTextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Terminal Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ThemeConsoleBg)
                            .border(1.dp, ThemeBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        val listState = rememberLazyListState()
                        LaunchedEffect(logs.size) {
                            if (logs.isNotEmpty()) {
                                listState.animateScrollToItem(logs.size - 1)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(logs) { item ->
                                val color = when (item.type) {
                                    LogType.SUCCESS -> ThemeGreen
                                    LogType.ERROR -> ThemeRed
                                    LogType.METHOD -> ThemeYellow
                                    LogType.WARNING -> ThemeYellow
                                    LogType.INFO -> ThemeTextSecondary
                                }
                                Text(
                                    text = "[${item.timestamp}] ${item.message}",
                                    color = color,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun processSelectedFile(uri: Uri) {
        val fileName = getFileName(uri) ?: "unknown"
        if (!fileName.endsWith(".wgt", ignoreCase = true) && !fileName.endsWith(".tpk", ignoreCase = true)) {
            Toast.makeText(this, "Please select a .tpk or .wgt file", Toast.LENGTH_SHORT).show()
            return
        }

        val coroutineScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        coroutineScope.launch {
            addLog("Parsing package: $fileName...")
            contentResolver.openInputStream(uri)?.use { stream ->
                selectedAppInfo = TizenParser.parsePackage(stream, fileName)
            }
            selectedAppInfo?.let {
                addLog("Loaded package: ${it.name} (${it.packageId}, v${it.version})", LogType.SUCCESS)
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        return result ?: uri.path?.substringAfterLast('/')
    }
}