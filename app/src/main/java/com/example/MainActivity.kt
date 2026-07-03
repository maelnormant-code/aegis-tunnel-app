package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit

import com.example.data.db.AppDatabase
import com.example.data.db.ChatMessage
import com.example.data.db.RoutingRule
import com.example.data.api.HeimdallRepository
import com.example.data.vpn.WireGuardManager
import com.example.data.local.PasswordGenerator
import com.example.service.RoutingVpnService

// Aegis network service is located in com.example.data.api.HeimdallApiService


enum class RoutingProtocol(val title: String, val icon: ImageVector) {
    SYS_VPN("sys-vpn (WireGuard)", Icons.Default.VpnKey),
    SYS_WHONIX("sys-whonix", Icons.Default.NetworkWifi),
    SYS_TOR("sys-tor", Icons.Default.NetworkWifi),
    SYS_I2P("sys-i2p", Icons.Default.Security)
}

data class Message(
    val id: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: String,
    val isEncrypted: Boolean = true,
    val protocolName: String = "sys-vpn (WireGuard)"
)

data class TotpToken(
    val id: String,
    val label: String,
    val issuer: String,
    val secret: String
)

object TotpGenerator {
    private fun decodeBase32(secret: String): ByteArray {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleanSecret = secret.replace("-", "").replace(" ", "").uppercase()
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in cleanSecret) {
            val idx = base32Chars.indexOf(c)
            if (idx < 0) continue
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer shr (bitsLeft - 8)) and 0xFF)
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    fun generateTotp(secret: String, timeSeconds: Long = System.currentTimeMillis() / 1000): String {
        return try {
            val key = decodeBase32(secret)
            if (key.isEmpty()) return "000000"
            
            val timeStep = timeSeconds / 30
            val data = ByteArray(8)
            var t = timeStep
            for (i in 7 downTo 0) {
                data[i] = (t and 0xFF).toByte()
                t = t ushr 8
            }

            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            val keySpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA1")
            mac.init(keySpec)
            val hash = mac.doFinal(data)

            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val otp = binary % 1000000
            String.format("%06d", otp)
        } catch (e: Exception) {
            "000000"
        }
    }
}

sealed class ParseResult {
    data class GatewayConfig(val url: String, val nodeName: String, val wireguardEndpoint: String? = null) : ParseResult()
    data class Totp(val uri: String) : ParseResult()
    data class Unknown(val content: String) : ParseResult()
}

fun parseAegisQrCode(scannedContent: String): ParseResult {
    val trimmed = scannedContent.trim()
    
    if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
        return ParseResult.Totp(uri = trimmed)
    }
    
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            val json = org.json.JSONObject(trimmed)
            val url = json.optString("url").ifEmpty { json.optString("gateway") } ?: ""
            if (url.isNotEmpty()) {
                return ParseResult.GatewayConfig(url = url, nodeName = json.optString("node_name", "sys-vpn-peer"))
            }
        } catch (e: Exception) {}
    }
    
    if (trimmed.contains("[Interface]", ignoreCase = true) || trimmed.contains("[Peer]", ignoreCase = true)) {
        var endpoint = ""
        var serverTunnelIp = "10.137.0.1"
        
        val lines = trimmed.split("\n")
        for (line in lines) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim()
                if (key == "endpoint") {
                    endpoint = value
                } else if (key == "allowedips") {
                    val ip = value.split("/")[0].trim()
                    if (ip.isNotEmpty() && ip != "0.0.0.0") {
                        serverTunnelIp = ip
                    }
                }
            }
        }
        val gatewayUrl = "http://$serverTunnelIp:5000/"
        return ParseResult.GatewayConfig(
            url = gatewayUrl, 
            nodeName = "sys-vpn (WireGuard)",
            wireguardEndpoint = endpoint
        )
    }
    
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return ParseResult.GatewayConfig(url = trimmed, nodeName = "Aegis Peer")
    }
    
    if (trimmed.matches(Regex("^[a-zA-Z0-9.-]+(:\\d+)?$"))) {
        val url = if (trimmed.contains(":")) "http://$trimmed/" else "http://$trimmed:5000/"
        return ParseResult.GatewayConfig(url = url, nodeName = "Aegis Peer")
    }
    
    return ParseResult.Unknown(content = trimmed)
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val chatDao = db.chatMessageDao()
    private val routingDao = db.routingRuleDao()
    private val repository = HeimdallRepository()

    val messages: StateFlow<List<Message>> = chatDao.getAllMessages().map { list ->
        list.map { entity ->
            Message(
                id = entity.id,
                text = entity.text,
                isFromMe = entity.isFromMe,
                timestamp = entity.timestamp,
                isEncrypted = entity.isEncrypted,
                protocolName = entity.protocolName
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val routingRules: StateFlow<List<RoutingRule>> = routingDao.getAllRules().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _currentProtocol = MutableStateFlow(RoutingProtocol.SYS_VPN)
    val currentProtocol: StateFlow<RoutingProtocol> = _currentProtocol.asStateFlow()

    private val _mullvadEnabled = MutableStateFlow(false)
    val mullvadEnabled: StateFlow<Boolean> = _mullvadEnabled.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Offline (Simulated)")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    val serverUrl = MutableStateFlow("http://10.137.0.1:5000/")
    val isSimulationMode = MutableStateFlow(true)

    private val _totpTokens = MutableStateFlow<List<TotpToken>>(emptyList())
    val totpTokens: StateFlow<List<TotpToken>> = _totpTokens.asStateFlow()

    init {
        _totpTokens.value = listOf(
            TotpToken("1", "sys-copilot Admin", "Qubes OS", "JBSWY3DPEHPK3PXP"),
            TotpToken("2", "Aegis Peer Gateway", "Aegis", "KVKVEV2VJVGVG666")
        )

        viewModelScope.launch {
            val initialMsgs = chatDao.getAllMessages().first()
            if (initialMsgs.isEmpty()) {
                chatDao.insertMessage(ChatMessage("1", "Aegis Node handshake initialized.", false, getCurrentTime(), true, "sys-vpn (WireGuard)"))
                chatDao.insertMessage(ChatMessage("2", "Secure channel established via ${_currentProtocol.value.title}.", false, getCurrentTime(), true, _currentProtocol.value.title))
            }

            val initialRules = routingDao.getAllRules().first()
            if (initialRules.isEmpty()) {
                routingDao.insertRule(RoutingRule("org.torproject.torbrowser", "DuckDuckGo Browser", "sys-whonix", "Use Mullvad VPN first", true))
                routingDao.insertRule(RoutingRule("org.fennec.browser", "Fennec Browser", "sys-i2p", "Access .i2p hidden sites", true))
                routingDao.insertRule(RoutingRule("com.bank", "Bank App", "sys-firewall", "Direct to App VM", true))
                routingDao.insertRule(RoutingRule("org.thoughtcrime.securesms", "Signal", "sys-tor", "Direct to Tor VM", true))
            }
        }

        testConnection()
    }

    fun addTotpToken(label: String, issuer: String, secret: String): Boolean {
        if (secret.isEmpty()) return false
        val newToken = TotpToken(
            id = System.currentTimeMillis().toString(),
            label = label.ifEmpty { "Scanned Secret" },
            issuer = issuer.ifEmpty { "Aegis" },
            secret = secret.uppercase().replace(" ", "")
        )
        _totpTokens.value = _totpTokens.value + newToken
        return true
    }

    fun addTotpFromUri(uri: String): Boolean {
        try {
            val cleanUri = java.net.URLDecoder.decode(uri, "UTF-8")
            if (!cleanUri.startsWith("otpauth://totp/", ignoreCase = true)) return false
            
            val queryStart = cleanUri.indexOf('?')
            val path = if (queryStart > 0) cleanUri.substring(15, queryStart) else cleanUri.substring(15)
            
            var label = path.trim()
            var issuer = ""
            if (label.contains(":")) {
                val parts = label.split(":", limit = 2)
                issuer = parts[0].trim()
                label = parts[1].trim()
            }
            
            var secret = ""
            if (queryStart > 0) {
                val query = cleanUri.substring(queryStart + 1)
                val params = query.split("&")
                for (param in params) {
                    val pair = param.split("=", limit = 2)
                    if (pair.size == 2) {
                        val key = pair[0].trim().lowercase()
                        val value = pair[1].trim()
                        if (key == "secret") {
                            secret = value
                        } else if (key == "issuer" && issuer.isEmpty()) {
                            issuer = value
                        }
                    }
                }
            }
            
            if (secret.isNotEmpty()) {
                return addTotpToken(label, issuer, secret)
            }
        } catch (e: Exception) {}
        return false
    }

    fun updateServerUrl(newUrl: String) {
        serverUrl.value = newUrl
        testConnection()
    }

    fun toggleSimulationMode(enabled: Boolean) {
        isSimulationMode.value = enabled
        if (!enabled) {
            testConnection()
        } else {
            _connectionStatus.value = "Offline (Simulated)"
            viewModelScope.launch {
                chatDao.insertMessage(
                    ChatMessage(
                        id = System.currentTimeMillis().toString(),
                        text = "Switched to secure offline simulation mode.",
                        isFromMe = false,
                        timestamp = getCurrentTime(),
                        isEncrypted = true,
                        protocolName = _currentProtocol.value.title
                    )
                )
            }
        }
    }

    fun testConnection(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _connectionStatus.value = "Connecting to Aegis Node..."
            val result = repository.checkStatus(serverUrl.value)
            result.onSuccess { status ->
                if (status.status == "ok") {
                    isSimulationMode.value = false
                    _connectionStatus.value = "Connected to ${status.aegis_node ?: "sys-vpn"}"
                    onResult(true, "Successfully connected to Aegis Node!")
                } else {
                    _connectionStatus.value = "Offline (Simulated)"
                    isSimulationMode.value = true
                    onResult(false, "Gateway responded with invalid status.")
                }
            }.onFailure { e ->
                _connectionStatus.value = "Offline (Simulated)"
                isSimulationMode.value = true
                onResult(false, "Could not reach gateway: ${e.localizedMessage ?: "Timeout"}")
            }
        }
    }

    fun setProtocol(protocol: RoutingProtocol) {
        _currentProtocol.value = protocol
        val stateStr = if (_mullvadEnabled.value) "Mullvad -> ${protocol.title}" else protocol.title
        viewModelScope.launch {
            _connectionStatus.value = "Reconnecting via $stateStr..."
            delay(1000)
            _connectionStatus.value = if (isSimulationMode.value) "Offline (Simulated)" else "Connected"
            chatDao.insertMessage(
                ChatMessage(
                    id = System.currentTimeMillis().toString(),
                    text = "Routing switched to ${protocol.title}. Tunnel secured.",
                    isFromMe = false,
                    timestamp = getCurrentTime(),
                    isEncrypted = true,
                    protocolName = protocol.title
                )
            )
        }
    }

    fun toggleMullvad() {
        _mullvadEnabled.value = !_mullvadEnabled.value
        val stateStr = if (_mullvadEnabled.value) "Mullvad -> ${_currentProtocol.value.title}" else _currentProtocol.value.title
        viewModelScope.launch {
            _connectionStatus.value = "Reconnecting via $stateStr..."
            delay(1000)
            _connectionStatus.value = if (isSimulationMode.value) "Offline (Simulated)" else "Connected"
            chatDao.insertMessage(
                ChatMessage(
                    id = System.currentTimeMillis().toString(),
                    text = if (_mullvadEnabled.value) "Traffic now routed through Mullvad VPN prior to Aegis node." else "Mullvad VPN disconnected. Direct to Aegis node.",
                    isFromMe = false,
                    timestamp = getCurrentTime(),
                    isEncrypted = true,
                    protocolName = _currentProtocol.value.title
                )
            )
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        val currentProto = _currentProtocol.value.title
        val userMsgId = System.currentTimeMillis().toString()
        
        viewModelScope.launch {
            chatDao.insertMessage(
                ChatMessage(
                    id = userMsgId,
                    text = text,
                    isFromMe = true,
                    timestamp = getCurrentTime(),
                    isEncrypted = true,
                    protocolName = currentProto
                )
            )

            if (isSimulationMode.value) {
                delay(1000)
                chatDao.insertMessage(
                    ChatMessage(
                        id = (System.currentTimeMillis() + 1).toString(),
                        text = "[Encrypted Payload Acknowledged (Simulated)]\n\nHeimdall: Received query over simulated gateway tunnel. If you have setup aegis-tunnel-proxy in your sys-vpn VM, tap the Connection Settings icon in the top right to configure your live endpoint and test the active integration.",
                        isFromMe = false,
                        timestamp = getCurrentTime(),
                        isEncrypted = true,
                        protocolName = currentProto
                    )
                )
            } else {
                _connectionStatus.value = "Aegis Node processing query..."
                val responseResult = if (text.startsWith("/goal ")) {
                    val goalQuery = text.substring(6).trim()
                    repository.sendGoal(serverUrl.value, goalQuery)
                } else {
                    repository.sendChat(serverUrl.value, text)
                }

                responseResult.onSuccess { responseText ->
                    chatDao.insertMessage(
                        ChatMessage(
                            id = (System.currentTimeMillis() + 1).toString(),
                            text = responseText,
                            isFromMe = false,
                            timestamp = getCurrentTime(),
                            isEncrypted = true,
                            protocolName = currentProto
                        )
                    )
                    _connectionStatus.value = "Connected to sys-vpn"
                }.onFailure { e ->
                    _connectionStatus.value = "Connection Lost"
                    chatDao.insertMessage(
                        ChatMessage(
                            id = (System.currentTimeMillis() + 1).toString(),
                            text = "Transmission error: Unable to contact Aegis Gateway Peer at ${serverUrl.value}. ${e.localizedMessage ?: "Endpoint connection refused."}\n\nFalling back to offline simulation mode.",
                            isFromMe = false,
                            timestamp = getCurrentTime(),
                            isEncrypted = true,
                            protocolName = currentProto
                        )
                    )
                    isSimulationMode.value = true
                }
            }
        }
    }

    fun toggleRoutingRule(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            routingDao.updateRuleEnabled(packageName, enabled)
        }
    }

    fun addRoutingRule(packageName: String, appName: String, destination: String, notes: String = "") {
        viewModelScope.launch {
            routingDao.insertRule(RoutingRule(packageName, appName, destination, notes, true))
        }
    }

    fun deleteRoutingRule(packageName: String) {
        viewModelScope.launch {
            routingDao.deleteRule(packageName)
        }
    }

    fun getInstalledApps(): List<Pair<String, String>> {
        val pm = getApplication<Application>().packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.map { app ->
            val name = pm.getApplicationLabel(app).toString()
            val pkg = app.packageName
            Pair(name, pkg)
        }.filter { it.second != getApplication<Application>().packageName }
         .sortedBy { it.first }
    }

    private fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    }
}

val AegisColorScheme = lightColorScheme(
    primary = Color(0xFF4359A9),
    onPrimary = Color.White,
    secondary = Color(0xFF10B981), // Emerald 500 for status
    onSecondary = Color.White,
    background = Color(0xFFFDFBFF),
    surface = Color.White,
    onBackground = Color(0xFF1B1B1F),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFF3F0F5),
    onSurfaceVariant = Color(0xFF1B1B1F),
    tertiary = Color(0xFFD3E2FF),
    onTertiary = Color(0xFF001D49)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AegisColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AegisApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AegisApp() {
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsState()
    val currentProtocol by viewModel.currentProtocol.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val mullvadEnabled by viewModel.mullvadEnabled.collectAsState()
    
    var messageText by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf("Messages") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showScannerDialog by remember { mutableStateOf(false) }
    var scannerPurpose by remember { mutableStateOf("Gateway") } // "Gateway" or "TOTP"
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == "Messages",
                    onClick = { currentTab = "Messages" },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Messages") },
                    label = { Text("Messages", fontWeight = if (currentTab == "Messages") FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == "Vault",
                    onClick = { currentTab = "Vault" },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Vault") },
                    label = { Text("Vault", fontWeight = if (currentTab == "Vault") FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == "Services",
                    onClick = { currentTab = "Services" },
                    icon = { Icon(Icons.Default.Dns, contentDescription = "Services") },
                    label = { Text("Services", fontWeight = if (currentTab == "Services") FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == "Routing",
                    onClick = { currentTab = "Routing" },
                    icon = { Icon(Icons.Default.Route, contentDescription = "Routing") },
                    label = { Text("Routing", fontWeight = if (currentTab == "Routing") FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFE1E2EC), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Shield",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Heimdall", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(50))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    connectionStatus.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = Color(0xFF059669)
                                )
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { viewModel.toggleMullvad() },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(40.dp)
                                .background(if (mullvadEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(50))
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = "Toggle Mullvad VPN",
                                tint = if (mullvadEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(40.dp)
                        ) {
                            Icon(currentProtocol.icon, contentDescription = "Routing Protocol", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            RoutingProtocol.entries.forEach { protocol ->
                                DropdownMenuItem(
                                    text = { Text(protocol.title) },
                                    onClick = {
                                        viewModel.setProtocol(protocol)
                                        menuExpanded = false
                                    },
                                    leadingIcon = { Icon(protocol.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Connection Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.shadow(elevation = 1.dp, spotColor = Color(0xFFE2E8F0))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (showSettingsDialog) {
            val serverUrl by viewModel.serverUrl.collectAsState()
            val isSimulationMode by viewModel.isSimulationMode.collectAsState()
            
            var urlText by remember { mutableStateOf(serverUrl) }
            var isSimMode by remember { mutableStateOf(isSimulationMode) }
            var testStatus by remember { mutableStateOf("") }
            var isTesting by remember { mutableStateOf(false) }
            
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Aegis Node Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Configure connection parameters to interface the mobile Aegis Tunnel App with your Qubes OS gateway peer (sys-vpn).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = { Text("Gateway API URL") },
                            placeholder = { Text("http://10.137.0.1:5000/") },
                            modifier = Modifier.fillMaxWidth().testTag("gateway_url_input"),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        scannerPurpose = "Gateway"
                                        showScannerDialog = true
                                        showSettingsDialog = false
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = "Scan Peer QR Code",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Offline Simulation Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Use fully secure, mock offline messages loop.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isSimMode,
                                onCheckedChange = { isSimMode = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primaryContainer)
                            )
                        }
                        
                        if (testStatus.isNotEmpty()) {
                            Text(
                                text = testStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (testStatus.contains("Success", ignoreCase = true)) Color(0xFF059669) else Color(0xFFDC2626)
                            )
                        }
                        
                        Button(
                            onClick = {
                                isTesting = true
                                testStatus = "Probing gateway status..."
                                
                                val formattedUrl = if (urlText.endsWith("/")) urlText else "$urlText/"
                                val tempService = Retrofit.Builder()
                                    .baseUrl(formattedUrl)
                                    .client(OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build())
                                    .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
                                    .build()
                                    .create(com.example.data.api.HeimdallApiService::class.java)
                                    
                                viewModel.viewModelScope.launch {
                                    try {
                                        val resp = tempService.checkStatus()
                                        if (resp.status == "ok") {
                                            testStatus = "Success! Connected to Qubes Aegis: ${resp.aegis_node ?: "sys-vpn"}"
                                            isSimMode = false
                                        } else {
                                            testStatus = "Failed: Invalid response"
                                        }
                                    } catch (e: Exception) {
                                        testStatus = "Failed to connect: ${e.localizedMessage ?: "Timeout"}"
                                    } finally {
                                        isTesting = false
                                    }
                                }
                            },
                            enabled = !isTesting && urlText.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Text(if (isTesting) "Probing..." else "Test Connection", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.updateServerUrl(urlText)
                            viewModel.toggleSimulationMode(isSimMode)
                            showSettingsDialog = false
                        }
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        if (showScannerDialog) {
            AegisScannerDialog(
                onDismiss = { showScannerDialog = false },
                onResultScanned = { result ->
                    showScannerDialog = false
                    when (result) {
                        is ParseResult.GatewayConfig -> {
                            viewModel.updateServerUrl(result.url)
                            viewModel.toggleSimulationMode(false)
                            showSettingsDialog = true
                        }
                        is ParseResult.Totp -> {
                            viewModel.addTotpFromUri(result.uri)
                            currentTab = "Vault"
                        }
                        is ParseResult.Unknown -> {
                            if (scannerPurpose == "TOTP") {
                                viewModel.addTotpToken("Scanned Secret", "Aegis", result.content)
                                currentTab = "Vault"
                            } else {
                                viewModel.updateServerUrl("http://${result.content}:5000/")
                                viewModel.toggleSimulationMode(false)
                                showSettingsDialog = true
                            }
                        }
                    }
                }
            )
        }
        
        if (currentTab == "Messages") {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages) { msg ->
                        MessageBubble(msg)
                    }
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFFF3F4F9), RoundedCornerShape(24.dp))
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            OutlinedTextField(
                                value = messageText,
                                onValueChange = { messageText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("message_input"),
                                placeholder = { Text("Encrypted message...", color = Color(0xFF64748B)) },
                                shape = RoundedCornerShape(24.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    viewModel.sendMessage(messageText)
                                    messageText = ""
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        FloatingActionButton(
                            onClick = {
                                viewModel.sendMessage(messageText)
                                messageText = ""
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            elevation = FloatingActionButtonDefaults.elevation(2.dp),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("send_button"),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        } else if (currentTab == "Vault") {
            KeePassVaultScreen(
                paddingValues = paddingValues,
                viewModel = viewModel,
                onScanQrClick = {
                    scannerPurpose = "TOTP"
                    showScannerDialog = true
                }
            )
        } else if (currentTab == "Services") {
            ServicesScreen(paddingValues)
        } else {
            RoutingScreen(paddingValues, viewModel)
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
    val bgColor = if (message.isFromMe) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isFromMe) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (message.isFromMe) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .shadow(1.dp, shape)
                .clip(shape)
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                text = message.timestamp,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = Color(0xFF94A3B8)
            )
            if (message.isEncrypted) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "OpenPGP",
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "OpenPGP",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (message.isFromMe) "Sent via ${message.protocolName}" else "Received via ${message.protocolName}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
fun KeePassVaultScreen(
    paddingValues: PaddingValues,
    viewModel: ChatViewModel,
    onScanQrClick: () -> Unit
) {
    var isUnlocked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var autofillEnabled by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    
    val defaultEntries = remember {
        listOf(
            com.example.data.model.VaultEntry(
                id = "1",
                title = "sys-vpn WireGuard Certification Client",
                username = "qubes-wg-client",
                password = "aegis_secure_client_token_99812",
                url = "10.137.0.1",
                notes = "Credentials to connect sys-copilot client to the desktop server",
                category = "Logins"
            ),
            com.example.data.model.VaultEntry(
                id = "2",
                title = "Heimdall API Authorization Key",
                username = "admin",
                password = "copilot_heimdall_token_b5327a",
                url = "http://10.137.0.1:5000",
                notes = "Primary API authorization token for sys-copilot control calls",
                category = "Logins"
            ),
            com.example.data.model.VaultEntry(
                id = "3",
                title = "sys-tor SSH Identity Private Key",
                username = "user",
                password = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDQg6...",
                url = "sys-tor.local",
                notes = "Onion gateway SSH authentication key",
                category = "SSH Keys"
            ),
            com.example.data.model.VaultEntry(
                id = "4",
                title = "Aegis Backup Cold Wallet Wallet-Seed",
                username = "monero-cold",
                password = "seed: alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima",
                url = "XMR Wallet",
                notes = "Recovery seed for native monero node wallet storage",
                category = "Secure Notes"
            ),
            com.example.data.model.VaultEntry(
                id = "5",
                title = "Aegis Primary Crypto Debit Card",
                username = "Aegis Holder",
                password = "4532 9982 1102 7731",
                url = "",
                notes = "Exp: 12/30, CVV: 432",
                category = "Credit & Debit Cards"
            )
        )
    }

    var decryptedEntries by remember { mutableStateOf<List<com.example.data.model.VaultEntry>>(defaultEntries) }
    
    val totpTokens by viewModel.totpTokens.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Storage access framework launcher to pick KDBX files
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) {
                    val entries = com.example.data.local.KeePassManager.decryptVault(inputStream, password)
                    decryptedEntries = entries
                    isUnlocked = true
                    android.widget.Toast.makeText(context, "Loaded ${entries.size} entries from vault!", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "Could not open vault file stream.", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Decryption failed: ${e.localizedMessage ?: "Invalid password"}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Live ticking timer for TOTP regeneration and progress bars
    var currentTimeSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTimeSeconds = System.currentTimeMillis() / 1000
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = "Database",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("aegis_vault.kdbx", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Synced via Syncthing • Just now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = if (isUnlocked) "Unlocked" else "Locked",
                    tint = if (isUnlocked) Color(0xFF10B981) else MaterialTheme.colorScheme.secondary
                )
            }
        }
        
        if (!isUnlocked) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().testTag("vault_password_input"),
                placeholder = { Text("Master Password...") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                trailingIcon = {
                    Icon(Icons.Default.Key, contentDescription = "Unlock")
                }
            )
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { 
                        isUnlocked = true 
                        decryptedEntries = defaultEntries
                    },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("unlock_vault_button"),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Unlock Default", fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = { 
                        if (password.isBlank()) {
                            android.widget.Toast.makeText(context, "Enter master password first", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            filePickerLauncher.launch("*/*")
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("import_vault_button"),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open File", fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Recent Entries (Locked)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
            
            listOf("Sys-VPN Certs", "Onion Routing Nodes", "Heimdall Admin Token").forEach { entry ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = "Entry",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(entry, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
        } else {
            // Unlocked State
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Native Autofill", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Fill passwords in other apps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = autofillEnabled,
                        onCheckedChange = { autofillEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primaryContainer)
                    )
                }
            }

            PasswordGeneratorCard()

            if (selectedCategory == null) {
                Text("Categories", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

                val categories = listOf(
                    Pair("Logins", Icons.Default.AccountCircle),
                    Pair("Credit & Debit Cards", Icons.Default.CreditCard),
                    Pair("SSH Keys", Icons.Default.Terminal),
                    Pair("Secure Notes", Icons.AutoMirrored.Filled.Notes)
                )

                categories.forEach { (title, icon) ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selectedCategory = title },
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                val count = decryptedEntries.count { it.category == title }
                                Text("$count entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "View", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // Showing Category Detail list
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedCategory = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(selectedCategory ?: "Entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }

                val filtered = decryptedEntries.filter { it.category == selectedCategory }
                if (filtered.isEmpty()) {
                    Text("No credentials saved in this category.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(16.dp))
                } else {
                    filtered.forEach { entry ->
                        var showDetails by remember { mutableStateOf(false) }
                        var showPassword by remember { mutableStateOf(false) }

                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { showDetails = !showDetails },
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        if (entry.username.isNotEmpty()) {
                                            Text(entry.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Icon(
                                        if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null
                                    )
                                }

                                if (showDetails) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                                    if (entry.password.isNotEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Column {
                                                Text("Secret / Password", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    if (showPassword) entry.password else "••••••••••••",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                                                )
                                            }
                                            Row {
                                                IconButton(onClick = { showPassword = !showPassword }) {
                                                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                                                }
                                                IconButton(onClick = {
                                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText("Vault Password", entry.password)
                                                    clipboard.setPrimaryClip(clip)
                                                    android.widget.Toast.makeText(context, "Password copied!", android.widget.Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }

                                    if (entry.url.isNotEmpty()) {
                                        Column {
                                            Text("URL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(entry.url, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }

                                    if (entry.notes.isNotEmpty()) {
                                        Column {
                                            Text("Notes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Authenticator (TOTP) Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Authenticator", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Authenticator (TOTP)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Real-Time Qubes Verification", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Display active codes
                    if (totpTokens.isEmpty()) {
                        Text("No keys added yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    } else {
                        totpTokens.forEach { token ->
                            val rawCode = TotpGenerator.generateTotp(token.secret, currentTimeSeconds)
                            val formattedCode = if (rawCode.length == 6) {
                                "${rawCode.substring(0, 3)} ${rawCode.substring(3)}"
                            } else rawCode
                            
                            val remainingSeconds = 30 - (currentTimeSeconds % 30)
                            val progress = remainingSeconds.toFloat() / 30f

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(token.issuer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        Text(token.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            formattedCode, 
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 24.sp,
                                                letterSpacing = 1.sp
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Visual timer progress circle
                                        Box(
                                            modifier = Modifier.size(28.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Canvas(modifier = Modifier.fillMaxSize()) {
                                                drawCircle(
                                                    color = Color.LightGray.copy(alpha = 0.3f),
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                                                )
                                                drawArc(
                                                    color = if (remainingSeconds > 5) Color(0xFF10B981) else Color(0xFFEF4444),
                                                    startAngle = -90f,
                                                    sweepAngle = 360f * progress,
                                                    useCenter = false,
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                                                )
                                            }
                                            Text(
                                                text = remainingSeconds.toString(),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("TOTP Code", rawCode)
                                                clipboard.setPrimaryClip(clip)
                                                android.widget.Toast.makeText(context, "Code copied: $formattedCode", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onScanQrClick, 
                            modifier = Modifier.weight(1f).testTag("scan_totp_button")
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Scan QR Code", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { showManualDialog = true }, 
                            modifier = Modifier.weight(1f).testTag("enter_manual_totp_button")
                        ) {
                            Text("Enter Manually", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showManualDialog) {
        var manualLabel by remember { mutableStateOf("") }
        var manualIssuer by remember { mutableStateOf("") }
        var manualSecret by remember { mutableStateOf("") }
        var manualError by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("Add Authenticator Key", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = manualLabel,
                        onValueChange = { manualLabel = it },
                        label = { Text("Account Label / Name") },
                        placeholder = { Text("sys-copilot Admin") },
                        modifier = Modifier.fillMaxWidth().testTag("manual_label_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualIssuer,
                        onValueChange = { manualIssuer = it },
                        label = { Text("Issuer") },
                        placeholder = { Text("Qubes OS") },
                        modifier = Modifier.fillMaxWidth().testTag("manual_issuer_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualSecret,
                        onValueChange = { manualSecret = it; manualError = "" },
                        label = { Text("Base32 Secret Key") },
                        placeholder = { Text("JBSWY3DPEHPK3PXP") },
                        modifier = Modifier.fillMaxWidth().testTag("manual_secret_input"),
                        singleLine = true
                    )
                    if (manualError.isNotEmpty()) {
                        Text(manualError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualSecret.isBlank()) {
                            manualError = "Secret key cannot be empty"
                        } else {
                            val success = viewModel.addTotpToken(manualLabel, manualIssuer, manualSecret)
                            if (success) {
                                showManualDialog = false
                            } else {
                                manualError = "Invalid key format."
                            }
                        }
                    },
                    modifier = Modifier.testTag("save_manual_token_button")
                ) {
                    Text("Save Key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun VaultCategoryCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "View", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PasswordGeneratorCard() {
    var length by remember { mutableStateOf(16f) }
    var useUpper by remember { mutableStateOf(true) }
    var useLower by remember { mutableStateOf(true) }
    var useNumbers by remember { mutableStateOf(true) }
    var useSpecials by remember { mutableStateOf(true) }
    
    var generatedPassword by remember { mutableStateOf("A8#mK9!vL2\$pQ5*x") }
    val context = androidx.compose.ui.platform.LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Password, contentDescription = "Generator", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Password Generator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(generatedPassword, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Generated Password", generatedPassword)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "Password copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Length", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("${length.toInt()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = length, 
                    onValueChange = { length = it }, 
                    valueRange = 8f..64f,
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                )
            }
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useUpper, onCheckedChange = { useUpper = it }, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("A-Z", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useLower, onCheckedChange = { useLower = it }, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("a-z", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useNumbers, onCheckedChange = { useNumbers = it }, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("0-9", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useSpecials, onCheckedChange = { useSpecials = it }, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("!@#$", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Button(
                onClick = {
                    generatedPassword = PasswordGenerator.generate(
                        length = length.toInt(),
                        useUpper = useUpper,
                        useLower = useLower,
                        useNumbers = useNumbers,
                        useSpecials = useSpecials
                    )
                }, 
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
            ) {
                Text("Generate New Password", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ServicesScreen(paddingValues: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Active Daemons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        ServiceCard(
            title = "SearXNG", 
            desc = "Privacy-respecting metasearch", 
            icon = Icons.Default.Search, 
            protocol = "sys-whonix",
            actionIcon = Icons.Default.Link,
            onAction = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Search Engine URL", "http://searxng.sys-whonix.local/search?q=%s")
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(context, "OpenSearch URL copied! Set in browser settings.", android.widget.Toast.LENGTH_LONG).show()
            }
        )
        ServiceCard("Immich", "Self-hosted photo backup", Icons.Default.Image, "sys-vpn (WireGuard)")
        ServiceCard("Nextcloud", "Secure decentralized storage", Icons.Default.Cloud, "sys-vpn (WireGuard)")
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("Native Wallets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        ServiceCard("Monero (XMR)", "Untraceable cryptocurrency", Icons.Default.AccountBalanceWallet, "sys-tor", isWallet = true)
    }
}

@Composable
fun ServiceCard(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, protocol: String, isWallet: Boolean = false, actionIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Filled.Send, onAction: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(if (isWallet) Color(0xFFF97316).copy(alpha = 0.1f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = if (isWallet) Color(0xFFF97316) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF10B981), RoundedCornerShape(50)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Routed via $protocol", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                }
            }
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = "Action", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun RoutingScreen(paddingValues: PaddingValues, viewModel: ChatViewModel) {
    val rules by viewModel.routingRules.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Traffic Routing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        Text("Route specific Android apps traffic to specific qubes over the private VPN (WireGuard).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        rules.forEach { rule ->
            RoutingRuleCard(
                rule = rule,
                onToggle = { isEnabled ->
                    viewModel.toggleRoutingRule(rule.packageName, isEnabled)
                },
                onDelete = {
                    viewModel.deleteRoutingRule(rule.packageName)
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("add_routing_rule_button"),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        ) {
            Icon(Icons.Default.Tune, contentDescription = "Add Rule")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Routing Rule", fontWeight = FontWeight.SemiBold)
        }
    }

    if (showAddDialog) {
        val installedApps = remember { viewModel.getInstalledApps() }
        var selectedApp by remember { mutableStateOf<Pair<String, String>?>(null) }
        var dropdownExpanded by remember { mutableStateOf(false) }
        var destination by remember { mutableStateOf("sys-whonix") }
        var notes by remember { mutableStateOf("") }
        var customPackageName by remember { mutableStateOf("") }
        var customAppName by remember { mutableStateOf("") }
        var isCustom by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Routing Rule", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { isCustom = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (!isCustom) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Installed Apps", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { isCustom = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isCustom) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Custom Package", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (!isCustom) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedApp?.first ?: "Select Android App...",
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth().clickable { dropdownExpanded = true }.testTag("app_select_input"),
                                label = { Text("App") },
                                trailingIcon = {
                                    IconButton(onClick = { dropdownExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                }
                            )
                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 240.dp)
                            ) {
                                installedApps.forEach { app ->
                                    DropdownMenuItem(
                                        text = { Text("${app.first} (${app.second})") },
                                        onClick = {
                                            selectedApp = app
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = customAppName,
                            onValueChange = { customAppName = it },
                            label = { Text("App Name") },
                            placeholder = { Text("My Custom App") },
                            modifier = Modifier.fillMaxWidth().testTag("custom_app_name_input")
                        )
                        OutlinedTextField(
                            value = customPackageName,
                            onValueChange = { customPackageName = it },
                            label = { Text("Package Name") },
                            placeholder = { Text("com.custom.app") },
                            modifier = Modifier.fillMaxWidth().testTag("custom_package_name_input")
                        )
                    }

                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        label = { Text("Destination Qube") },
                        placeholder = { Text("sys-whonix") },
                        modifier = Modifier.fillMaxWidth().testTag("destination_qube_input")
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes / Description") },
                        placeholder = { Text("Direct routing through Whonix") },
                        modifier = Modifier.fillMaxWidth().testTag("rule_notes_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalPkg = if (isCustom) customPackageName else selectedApp?.second
                        val finalName = if (isCustom) customAppName else selectedApp?.first
                        if (!finalPkg.isNullOrBlank() && !finalName.isNullOrBlank()) {
                            viewModel.addRoutingRule(finalPkg, finalName, destination, notes)
                            showAddDialog = false
                        }
                    },
                    modifier = Modifier.testTag("save_rule_button")
                ) {
                    Text("Save Rule")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RoutingRuleCard(
    rule: RoutingRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var enabled by remember(rule.isEnabled) { mutableStateOf(rule.isEnabled) }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when {
                            rule.destination.contains("whonix") -> Icons.Default.Search
                            rule.destination.contains("tor") -> Icons.Default.Public
                            rule.destination.contains("i2p") -> Icons.Default.Security
                            else -> Icons.Default.NetworkWifi
                        }
                        Icon(icon, contentDescription = rule.appName, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(rule.appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(rule.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { 
                            enabled = it
                            onToggle(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primaryContainer)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp).testTag("delete_rule_${rule.packageName}")) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Rule", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutingStep("Android App", rule.appName, Icons.Default.AppShortcut)
                    RoutingPath()
                    RoutingStep("Optional Outward VPN", "Mullvad", Icons.Default.Security)
                    RoutingPath()
                    RoutingStep("Internet -> sys-net -> sys-firewall", "Standard Routing", Icons.Default.NetworkWifi)
                    RoutingPath()
                    RoutingStep("Private VPN", "app-wireguard-server", Icons.Default.VpnKey)
                    RoutingPath()
                    RoutingStep("Destination Qube", rule.destination, Icons.Default.Dns)
                    
                    if (rule.notes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(rule.notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun RoutingStep(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun RoutingPath() {
    Row(modifier = Modifier.padding(start = 7.dp, top = 2.dp, bottom = 2.dp)) {
        Box(modifier = Modifier.width(2.dp).height(16.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
    }
}

@Composable
fun AegisScannerDialog(
    onDismiss: () -> Unit,
    onResultScanned: (ParseResult) -> Unit
) {
    var activeTab by remember { mutableStateOf("Simulator") }
    var inputPayload by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Aegis Config/TOTP QR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(2.dp)
                ) {
                    listOf("Camera Feed", "Simulator").forEach { tab ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                                .clickable { activeTab = tab }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tab,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (activeTab == "Camera Feed") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color.Black, RoundedCornerShape(12.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Camera Frame",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(Color(0xFF10B981))
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Camera Active (Simulation Fallback)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray
                        )
                        Text(
                            "Switch to 'Simulator' tab to scan presets",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = Color.Gray
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Paste scanned text/payload or select a preset below to instantly simulate scanning:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = inputPayload,
                            onValueChange = { 
                                inputPayload = it
                                errorMessage = ""
                            },
                            label = { Text("Scanned Payload text") },
                            placeholder = { Text("http://... or [Interface] or otpauth://...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .testTag("qr_payload_input"),
                            maxLines = 4
                        )

                        if (errorMessage.isNotEmpty()) {
                            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }

                        Text("Select a Peer / Key Preset to test:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)

                        val presetWg = """
                        [Interface]
                        Address = 10.137.0.2/24
                        PrivateKey = kPvK_client_key_placeholder

                        [Peer]
                        PublicKey = sPkS_server_key_placeholder
                        Endpoint = 192.168.10.12:51820
                        AllowedIPs = 10.137.0.1/32
                        """.trimIndent()

                        val presetJson = """{"url": "http://10.137.0.1:5000/", "node_name": "sys-vpn-tunnel"}"""
                        val presetTotp = "otpauth://totp/Qubes:sys-copilot?secret=JBSWY3DPEHPK3PXP&issuer=Qubes"

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { inputPayload = presetWg },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                            ) {
                                Text("WireGuard Peer", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                            }
                            Button(
                                onClick = { inputPayload = presetJson },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                            ) {
                                Text("Aegis JSON", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                            }
                            Button(
                                onClick = { inputPayload = presetTotp },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                            ) {
                                Text("TOTP Secret", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inputPayload.isBlank()) {
                        errorMessage = "Please enter or select a payload."
                    } else {
                        val result = parseAegisQrCode(inputPayload)
                        onResultScanned(result)
                    }
                },
                modifier = Modifier.testTag("confirm_scan_button")
            ) {
                Text("Process Scan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

