package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AppShortcut
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


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

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _currentProtocol = MutableStateFlow(RoutingProtocol.SYS_VPN)
    val currentProtocol: StateFlow<RoutingProtocol> = _currentProtocol.asStateFlow()

    private val _mullvadEnabled = MutableStateFlow(false)
    val mullvadEnabled: StateFlow<Boolean> = _mullvadEnabled.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Connected to Heimdall")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    init {
        _messages.value = listOf(
            Message("1", "Aegis Node handshake initialized.", false, getCurrentTime(), true, "sys-vpn (WireGuard)"),
            Message("2", "Secure channel established via ${_currentProtocol.value.title}.", false, getCurrentTime(), true, _currentProtocol.value.title)
        )
    }

    fun setProtocol(protocol: RoutingProtocol) {
        _currentProtocol.value = protocol
        val stateStr = if (_mullvadEnabled.value) "Mullvad -> ${protocol.title}" else protocol.title
        viewModelScope.launch {
            _connectionStatus.value = "Reconnecting via $stateStr..."
            delay(1500)
            _connectionStatus.value = "Connected to Heimdall"
            _messages.value = _messages.value + Message(
                id = System.currentTimeMillis().toString(),
                text = "Routing switched to ${protocol.title}. Tunnel secured.",
                isFromMe = false,
                timestamp = getCurrentTime(),
                protocolName = protocol.title
            )
        }
    }

    fun toggleMullvad() {
        _mullvadEnabled.value = !_mullvadEnabled.value
        val stateStr = if (_mullvadEnabled.value) "Mullvad -> ${_currentProtocol.value.title}" else _currentProtocol.value.title
        viewModelScope.launch {
            _connectionStatus.value = "Reconnecting via $stateStr..."
            delay(1500)
            _connectionStatus.value = "Connected to Heimdall"
            _messages.value = _messages.value + Message(
                id = System.currentTimeMillis().toString(),
                text = if (_mullvadEnabled.value) "Traffic now routed through Mullvad VPN prior to Aegis node." else "Mullvad VPN disconnected. Direct to Aegis node.",
                isFromMe = false,
                timestamp = getCurrentTime(),
                protocolName = _currentProtocol.value.title
            )
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        val currentProto = _currentProtocol.value.title
        val userMsg = Message(
            id = System.currentTimeMillis().toString(),
            text = text,
            isFromMe = true,
            timestamp = getCurrentTime(),
            protocolName = currentProto
        )
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            delay(1000)
            val replyMsg = Message(
                id = (System.currentTimeMillis() + 1).toString(),
                text = "[Encrypted Payload Acknowledged]",
                isFromMe = false,
                timestamp = getCurrentTime(),
                protocolName = currentProto
            )
            _messages.value = _messages.value + replyMsg
        }
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
                                .padding(end = 8.dp)
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
            KeePassVaultScreen(paddingValues)
        } else if (currentTab == "Services") {
            ServicesScreen(paddingValues)
        } else {
            RoutingScreen(paddingValues)
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
fun KeePassVaultScreen(paddingValues: PaddingValues) {
    var isUnlocked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var autofillEnabled by remember { mutableStateOf(false) }

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
                    Text("Synced via Syncthing • 3 mins ago", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                modifier = Modifier.fillMaxWidth(),
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
            
            Button(
                onClick = { isUnlocked = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Unlock Database", fontWeight = FontWeight.Bold)
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

            Text("Categories", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

            val categories = listOf(
                Pair("Logins", Icons.Default.AccountCircle),
                Pair("Credit & Debit Cards", Icons.Default.CreditCard),
                Pair("SSH Keys", Icons.Default.Terminal),
                Pair("Secure Notes", Icons.AutoMirrored.Filled.Notes)
            )

            categories.forEach { (title, icon) ->
                VaultCategoryCard(title, icon)
            }
            
            // Authenticator (TOTP)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Authenticator", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Authenticator (TOTP)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "View", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                            Text("Scan QR Code", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                            Text("Enter Manually", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
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
                    IconButton(onClick = {}, modifier = Modifier.size(24.dp)) {
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
                onClick = { /* Generate logic */ }, 
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
fun RoutingScreen(paddingValues: PaddingValues) {
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
        
        RoutingRuleCard("DuckDuckGo Browser", "sys-whonix", "Use Mullvad VPN first", Icons.Default.Search)
        RoutingRuleCard("Fennec Browser", "sys-i2p", "Access .i2p hidden sites", Icons.Default.Public)
        RoutingRuleCard("Bank App", "sys-firewall", "Direct to App VM", Icons.Default.AccountBalanceWallet)
        RoutingRuleCard("Signal", "sys-tor", "Direct to Tor VM", Icons.AutoMirrored.Filled.Chat)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        ) {
            Icon(Icons.Default.Tune, contentDescription = "Add Rule")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Routing Rule", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun RoutingRuleCard(appName: String, destination: String, notes: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    var enabled by remember { mutableStateOf(true) }
    
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = appName, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("App Traffic Rule", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primaryContainer)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutingStep("Android App", appName, Icons.Default.AppShortcut)
                    RoutingPath()
                    RoutingStep("Optional Outward VPN", "Mullvad", Icons.Default.Security)
                    RoutingPath()
                    RoutingStep("Internet -> sys-net -> sys-firewall", "Standard Routing", Icons.Default.NetworkWifi)
                    RoutingPath()
                    RoutingStep("Private VPN", "app-wireguard-server", Icons.Default.VpnKey)
                    RoutingPath()
                    RoutingStep("Destination Qube", destination, Icons.Default.Dns)
                    
                    if (notes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp))
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

