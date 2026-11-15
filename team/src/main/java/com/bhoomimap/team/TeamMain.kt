@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bhoomimap.team

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val Green = Color(0xFF1F6F43)
val Red = Color(0xFFDC2626)
val Muted = Color(0xFF4A5147)
val Ground = Color(0xFFF6F7F4)
val Dark = Color(0xFF161A16)

class TeamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White, secondary = Red, background = Ground, surface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = Ground) { TeamApp() }
            }
        }
    }
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
fun errText(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Check internet."

@Composable
fun TeamApp() {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf("loading") }
    var status by remember { mutableStateOf<TeamStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            error = null
            if (!Api.configured) { stage = "noserver"; return@launch }
            if (!Api.loggedIn) { stage = "login"; return@launch }
            try {
                Api.token()
                if (!Api.loggedIn) { stage = "login"; return@launch }
                if (Api.aal() != "aal2") { stage = "mfa"; return@launch }
                val s = Repo.status()
                status = s
                stage = when {
                    !s.member -> "nomember"
                    !s.active -> "blocked"
                    !s.deviceApproved -> "device"
                    !s.codeAccepted -> "code"
                    else -> "home"
                }
            } catch (e: Exception) {
                error = errText(e)
                stage = "error"
            }
        }
    }

    fun logout() {
        Api.logout()
        status = null
        stage = "login"
    }

    LaunchedEffect(Unit) { refresh() }

    when (stage) {
        "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        "noserver" -> InfoScreen("Server not connected", "Add SUPABASE_URL and SUPABASE_ANON_KEY in GitHub Secrets and build again.", null, null)
        "login" -> LoginScreen(onDone = { refresh() })
        "mfa" -> MfaScreen(onDone = { refresh() }, onLogout = { logout() })
        "nomember" -> InfoScreen("No team access", "This login is not added to the BhoomiMap team. Ask the CEO to add you.", { refresh() }, { logout() })
        "blocked" -> InfoScreen("Access blocked", "Your team access is turned off. Contact the CEO.", { refresh() }, { logout() })
        "device" -> InfoScreen(
            "Waiting for CEO approval",
            "This phone is not approved yet.\n\nDevice code: ${Api.shortDevice}\n${Api.deviceLabel}\n\nAsk the CEO to approve this code, then tap Refresh.",
            { refresh() }, { logout() },
        )
        "code" -> StaffCodeScreen(onAgree = { scope.launch { try { Api.rpc("accept_code"); refresh() } catch (e: Exception) { error = errText(e); stage = "error" } } })
        "error" -> InfoScreen("Could not connect", error ?: "", { refresh() }, { logout() })
        else -> status?.let { HomeScreen(it, onLogout = { logout() }) }
    }
}

// ---------------- Gate screens ----------------

@Composable
fun DarkPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Dark).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun DarkTitle(small: String, big: String) {
    Text(small, color = Color(0xFFB8C0B4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Text(big, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFF2E9A5C), unfocusedBorderColor = Color(0xFF3A4238),
    focusedLabelColor = Color(0xFFB8C0B4), unfocusedLabelColor = Color(0xFFB8C0B4), cursorColor = Color.White,
)

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    DarkPage {
        DarkTitle("TEAM MEMBERS ONLY", "BhoomiMap Team")
        Text("Accounts are created by the CEO. No sign up.", color = Color(0xFFB8C0B4), fontSize = 14.sp)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, colors = darkFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, colors = darkFieldColors(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (email.isBlank() || pass.isBlank()) { toast(ctx, "Enter email and password"); return@Button }
                busy = true
                scope.launch {
                    try { Api.signIn(email, pass); onDone() } catch (e: Exception) {
                        toast(ctx, if (e is ApiException && e.code == 400) "Wrong email or password" else errText(e))
                    }
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
        ) { Text(if (busy) "Please wait…" else "Login", fontSize = 16.sp) }
        Text("Login works only on phones approved by the CEO.", color = Color(0xFFB8C0B4), fontSize = 12.sp)
    }
}

@Composable
fun MfaScreen(onDone: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var factorId by remember { mutableStateOf<String?>(null) }
    var secret by remember { mutableStateOf<String?>(null) }
    var uri by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            factorId = Api.verifiedFactorId()
            if (factorId == null) {
                val (id, s, u) = Api.enrollTotp()
                factorId = id; secret = s; uri = u
            }
        } catch (e: Exception) { toast(ctx, errText(e)) }
        busy = false
    }

    DarkPage {
        DarkTitle("STEP 2 OF LOGIN", "Verification Code")
        if (busy) CircularProgressIndicator(color = Color.White)
        if (secret != null) {
            Text("First time on this account: set up an authenticator app (Google Authenticator or Microsoft Authenticator).",
                color = Color(0xFFB8C0B4), fontSize = 14.sp)
            Button(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } catch (_: Exception) {
                    toast(ctx, "Install Google Authenticator, then use the setup key below.")
                }
            }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Open Authenticator App") }
            Text("Setup key:", color = Color(0xFFB8C0B4), fontSize = 13.sp)
            Text(secret ?: "", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("key", secret))
                toast(ctx, "Setup key copied")
            }) { Text("Copy Setup Key", color = Color(0xFF9FE0B8)) }
        } else if (!busy) {
            Text("Enter the 6-digit code from your authenticator app.", color = Color(0xFFB8C0B4), fontSize = 14.sp)
        }
        OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, label = { Text("6-digit code") }, singleLine = true,
            colors = darkFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                val id = factorId ?: return@Button
                if (code.length != 6) { toast(ctx, "Enter the 6-digit code"); return@Button }
                busy = true
                scope.launch {
                    try { Api.verifyTotp(id, code); onDone() } catch (e: Exception) { toast(ctx, "Wrong or expired code. Try the new code.") }
                    busy = false
                }
            },
            enabled = !busy && factorId != null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
        ) { Text("Verify", fontSize = 16.sp) }
        TextButton(onClick = onLogout) { Text("Logout", color = Color(0xFFB8C0B4)) }
    }
}

@Composable
fun InfoScreen(title: String, body: String, onRefresh: (() -> Unit)?, onLogout: (() -> Unit)?) {
    DarkPage {
        DarkTitle("BHOOMIMAP TEAM", title)
        Text(body, color = Color(0xFFDDE3D9), fontSize = 15.sp, lineHeight = 22.sp)
        if (onRefresh != null) Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("Refresh") }
        if (onLogout != null) TextButton(onClick = onLogout) { Text("Logout", color = Color(0xFFB8C0B4)) }
    }
}

val STAFF_CODE = listOf(
    "Never ask anyone for money, token or booking fee before the deal is done.",
    "Never take a buyer's or owner's number out of the app (no saving, no WhatsApp groups).",
    "Call a buyer only about the plot they chose. Maximum 2 calls. Respect Stop Calls.",
    "No false promises: never say \"100% clear title\" or \"price will surely go up\".",
    "Use only the official BhoomiMap number for calls.",
    "Everything you open is recorded in the audit log. Breaking these rules = access removed.",
)

@Composable
fun StaffCodeScreen(onAgree: () -> Unit) {
    var ok by remember { mutableStateOf(false) }
    DarkPage {
        DarkTitle("BEFORE YOU START", "Staff Code")
        STAFF_CODE.forEachIndexed { i, r -> Text("${i + 1}. $r", color = Color(0xFFDDE3D9), fontSize = 15.sp, lineHeight = 22.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ok, onCheckedChange = { ok = it })
            Text("I have read and I agree to follow the Staff Code.", color = Color.White, fontSize = 14.sp)
        }
        Button(onClick = onAgree, enabled = ok, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Text("I Agree") }
    }
}

// ---------------- Home ----------------

data class TabItem(val key: String, val label: String, val icon: ImageVector)

@Composable
fun HomeScreen(s: TeamStatus, onLogout: () -> Unit) {
    val tabs = when (s.role) {
        "field" -> listOf(TabItem("tasks", "My Tasks", Icons.Default.Place), TabItem("me", "Me", Icons.Default.Person))
        "senior" -> listOf(
            TabItem("leads", "Leads", Icons.Default.Phone), TabItem("listings", "Listings", Icons.Default.Place),
            TabItem("checks", "Site Checks", Icons.Default.CheckCircle), TabItem("tickets", "Tickets", Icons.Default.Email),
            TabItem("me", "Me", Icons.Default.Person),
        )
        else -> listOf(
            TabItem("dash", "Dashboard", Icons.Default.Home), TabItem("leads", "Leads", Icons.Default.Phone),
            TabItem("listings", "Listings", Icons.Default.Place), TabItem("tickets", "Tickets", Icons.Default.Email),
            TabItem("team", "Team", Icons.Default.Person),
        )
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first().key) }
    Scaffold(
        containerColor = Ground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.role.uppercase() + " · BHOOMIMAP TEAM", fontSize = 11.sp, color = Color(0xFFC8D0C4), fontWeight = FontWeight.SemiBold)
                        Text(s.name, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Dark),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                tabs.forEach { t ->
                    NavigationBarItem(selected = tab == t.key, onClick = { tab = t.key },
                        icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label, fontSize = 11.sp, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                "tasks" -> FieldTasksScreen()
                "leads" -> LeadsScreen(isCeo = s.role == "ceo")
                "listings" -> ListingsScreen()
                "checks" -> SiteChecksScreen()
                "tickets" -> TicketsScreen()
                "dash" -> DashboardScreen()
                "team" -> TeamManageScreen(onLogout)
                else -> MeScreen(s, onLogout)
            }
        }
    }
}

@Composable
fun MeScreen(s: TeamStatus, onLogout: () -> Unit) {
    Page {
        Section("My Account") {
            Row2("Name", s.name)
            Row2("Role", s.role.replaceFirstChar { it.uppercase() })
            Row2("Email", Api.email ?: "")
            Row2("Device code", Api.shortDevice)
        }
        Section("Staff Code") { STAFF_CODE.forEachIndexed { i, r -> Text("${i + 1}. $r", fontSize = 13.sp, color = Muted) } }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Logout") }
    }
}

// ---------------- Shared UI ----------------

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun Row2(label: String, value: String, color: Color = Color.Black) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
fun Pill(text: String, bg: Color = Color(0xFFEEF0EB), fg: Color = Color(0xFF3A4038)) {
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
fun statusPill(status: String) = when (status) {
    "new", "open" -> Pill(status.replaceFirstChar { it.uppercase() }, Color(0xFFFFF4E5), Color(0xFF8A4B00))
    "live", "approved", "solved", "unlocked", "closed" -> Pill(status.replaceFirstChar { it.uppercase() }, Color(0xFFE8F3EC), Color(0xFF14502F))
    "submitted", "called", "visit" -> Pill(status.replaceFirstChar { it.uppercase() }, Color(0xFFE6F0FB), Color(0xFF1D4F91))
    "rejected", "removed", "sold" -> Pill(status.replaceFirstChar { it.uppercase() }, Color(0xFFFDECEC), Color(0xFF991B1B))
    else -> Pill(status.replaceFirstChar { it.uppercase() })
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, number: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false, outlined: Boolean = false) {
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text(text) }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
            colors = if (danger) ButtonDefaults.buttonColors(containerColor = Red) else ButtonDefaults.buttonColors()) { Text(text) }
    }
}

@Composable
fun Loading(loading: Boolean, empty: Boolean, emptyText: String) {
    if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else if (empty) Text(emptyText, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
}
