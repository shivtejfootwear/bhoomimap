@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bhoomimap.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import coil.compose.AsyncImage
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

val Green = Color(0xFF1F6F43)
val Red = Color(0xFFDC2626)
val Muted = Color(0xFF4A5147)
val Ground = Color(0xFFF6F7F4)
val PlotFill = Color(0x59DC2626)
val PlotLine = Color(0xFFFF3B3B)
val SOLAPUR = LatLng(17.6599, 75.9064)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        val activity = this
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green, onPrimary = Color.White, secondary = Red,
                    background = Ground, surface = Color.White,
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Ground) { App(activity) }
            }
        }
    }
}

fun loginError(e: Exception): String = when (e) {
    is GetCredentialCancellationException -> "Login cancelled."
    is NoCredentialException -> "No Google account found on this phone."
    is IllegalArgumentException -> e.message ?: "Login is not available yet."
    else -> "Login failed. Please try again."
}

@Composable
fun App(activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var loggedIn by remember { mutableStateOf(Session.loggedIn) }
    var skipped by remember { mutableStateOf(Session.skippedLogin) }
    var listings by remember { mutableStateOf<List<Listing>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Set<String>>(emptySet()) }
    var leads by remember { mutableStateOf<List<Lead>>(emptyList()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var interestFor by remember { mutableStateOf<Listing?>(null) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var consentFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var agents by remember { mutableStateOf<List<Agent>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<LandAlert>>(emptyList()) }
    var agentPick by remember { mutableStateOf<Agent?>(null) }
    var reportFor by remember { mutableStateOf<Listing?>(null) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun reload() {
        scope.launch {
            loading = true
            loadError = null
            if (!Supa.configured) {
                loadError = "Server not connected yet."
                loading = false
                return@launch
            }
            try {
                listings = Repo.listings()
            } catch (e: Exception) {
                loadError = "Could not load plots. Check your internet."
            }
            try {
                saved = Repo.savedIds()
                leads = Repo.myLeads()
            } catch (_: Exception) {
            }
            try {
                agents = Repo.agents()
            } catch (_: Exception) {
            }
            try {
                alerts = Repo.alerts()
            } catch (_: Exception) {
            }
            loggedIn = Session.loggedIn
            loading = false
        }
    }

    fun doLogin(after: () -> Unit) {
        scope.launch {
            try {
                Session.googleSignIn(activity)
                Session.skippedLogin = true
                skipped = true
                loggedIn = true
                reload()
                after()
            } catch (e: Exception) {
                toast(loginError(e))
            }
        }
    }

    fun login(after: () -> Unit = {}) {
        if (Session.termsAccepted) doLogin(after) else consentFor = after
    }

    val askInterest: (Listing) -> Unit = { l ->
        if (loggedIn) {
            interestFor = l
        } else {
            login { interestFor = l }
        }
    }
    val toggleSave: (Listing) -> Unit = { l ->
        if (!loggedIn) {
            login()
        } else {
            scope.launch {
                val makeSaved = l.id !in saved
                try {
                    Repo.setSaved(l.id, makeSaved)
                    saved = if (makeSaved) saved + l.id else saved - l.id
                } catch (e: Exception) {
                    toast("Could not save. Please try again.")
                }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val openPage: (String) -> Unit = { page = it }

    if (page != null) {
        BackHandler { page = null }
        when (page) {
            "terms" -> LegalScreen("Terms of Use", LegalText.terms) { page = null }
            "privacy" -> LegalScreen("Privacy Policy", LegalText.privacy) { page = null }
            "converter" -> ConverterScreen { page = null }
            "guides" -> GuidesScreen { page = null }
            "rules" -> RulesScreen { page = null }
            else -> {
                val p = page ?: ""
                val enquiry = if (p.startsWith("enquiry:")) leads.firstOrNull { it.id == p.removePrefix("enquiry:") } else null
                if (enquiry != null) {
                    EnquiryScreen(
                        lead = enquiry,
                        listing = listings.firstOrNull { it.id == enquiry.listingId },
                        onBack = { page = null },
                        onChanged = { scope.launch { try { leads = Repo.myLeads() } catch (_: Exception) {} } },
                        onRules = { page = "rules" },
                    )
                } else {
                    SafetyScreen(loggedIn, onBack = { page = null }, onOpen = openPage)
                }
            }
        }
    } else if (!loggedIn && !skipped) {
        LoginScreen(
            onGoogle = {
                Session.termsAccepted = true
                doLogin {}
            },
            onOpen = openPage,
            onSkip = {
                Session.skippedLogin = true
                skipped = true
            },
        )
    } else {
        val open = listings.firstOrNull { it.id == openId }
        if (open != null) {
            BackHandler { openId = null }
            key(open.id) { DetailsScreen(
                l = open,
                lead = leads.firstOrNull { it.listingId == open.id },
                isSaved = open.id in saved,
                loggedIn = loggedIn,
                onBack = { openId = null },
                onInterested = { askInterest(open) },
                onSave = { toggleSave(open) },
                onSafety = { page = "safety" },
                onEnquiry = { page = "enquiry:$it" },
                nearby = listings.filter { it.id != open.id && distanceKm(it.center, open.center) <= 5.0 }
                    .sortedBy { distanceKm(it.center, open.center) }.take(5),
                onOpenOther = { openId = it.id },
                onShare = { shareListing(ctx, open) },
                onReport = { if (loggedIn) { reportFor = open } else { login { reportFor = open } } },
            ) }
        } else {
            Scaffold(
                containerColor = Ground,
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Place, contentDescription = null) },
                            label = { Text("Map") },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                            label = { Text("Saved") },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.AddCircle, contentDescription = null) },
                            label = { Text("Sell") },
                        )
                        NavigationBarItem(
                            selected = tab == 3, onClick = { tab = 3 },
                            icon = {
                                val newCount = alerts.sumOf { a -> listings.count { a.matches(it) && a.isNew(it) } }
                                BadgedBox(badge = { if (newCount > 0) Badge { Text(if (newCount > 9) "9+" else "$newCount") } }) {
                                    Icon(Icons.Default.Notifications, contentDescription = null)
                                }
                            },
                            label = { Text("Alerts") },
                        )
                        NavigationBarItem(
                            selected = tab == 4, onClick = { tab = 4 },
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            label = { Text("Profile") },
                        )
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (tab) {
                        0 -> MapScreen(
                            listings = listings, loading = loading, error = loadError,
                            onRetry = { reload() },
                            onOpen = { openId = it.id },
                            onInterested = askInterest,
                            agents = agents,
                            onAgent = { agentPick = it },
                        )
                        1 -> SavedScreen(
                            plots = listings.filter { it.id in saved },
                            loggedIn = loggedIn,
                            onLogin = { login() },
                            onOpen = { openId = it.id },
                        )
                        2 -> SellScreen(
                            loggedIn = loggedIn,
                            onLogin = { login() },
                            onSend = { n, ph, pl, k, ar, nt, done ->
                                scope.launch {
                                    try {
                                        Repo.sendSellRequest(n, ph, pl, k, ar, nt)
                                        toast("Thank you! Our team will call you to check your land.")
                                    } catch (e: Exception) {
                                        toast("Could not send. Please try again.")
                                    }
                                    done()
                                }
                            },
                        )
                        3 -> AlertsScreen(
                            loggedIn = loggedIn,
                            alerts = alerts,
                            listings = listings,
                            onLogin = { login() },
                            onAdd = { pl, k, sz ->
                                scope.launch {
                                    try {
                                        Repo.addAlert(pl, k, sz)
                                        alerts = Repo.alerts()
                                        toast("Alert created.")
                                    } catch (e: Exception) {
                                        toast("Could not create alert. Please try again.")
                                    }
                                }
                            },
                            onDelete = { a ->
                                scope.launch {
                                    try {
                                        Repo.deleteAlert(a.id)
                                        alerts = alerts - a
                                    } catch (e: Exception) {
                                        toast("Could not delete. Please try again.")
                                    }
                                }
                            },
                            onOpen = { openId = it.id },
                        )
                        else -> ProfileScreen(
                            loggedIn = loggedIn, leads = leads, listings = listings,
                            onLogin = { login() },
                            onLogout = {
                                Session.logout()
                                loggedIn = false
                                saved = emptySet()
                                leads = emptyList()
                                alerts = emptyList()
                            },
                            onDelete = {
                                scope.launch {
                                    try {
                                        Repo.requestAccountDeletion()
                                        toast("Delete request sent. Your account will be deleted within 7 days.")
                                    } catch (e: Exception) {
                                        toast("Could not send request. Please try again.")
                                        return@launch
                                    }
                                    Session.logout()
                                    loggedIn = false
                                    saved = emptySet()
                                    leads = emptyList()
                                    alerts = emptyList()
                                }
                            },
                            onOpen = { openId = it.id },
                            onPage = openPage,
                        )
                    }
                }
            }
        }
    }

    consentFor?.let { after ->
        ConsentDialog(
            onAccept = {
                Session.termsAccepted = true
                consentFor = null
                doLogin(after)
            },
            onDismiss = { consentFor = null },
            onOpen = { consentFor = null; page = it },
        )
    }

    agentPick?.let { a ->
        AgentDialog(
            a = a,
            onDismiss = { agentPick = null },
            onConnect = {
                agentPick = null
                val send = {
                    scope.launch {
                        try {
                            Repo.report("Agent connect request | ${a.id} | ${a.name} | ${a.area}")
                            toast("Our team will call you to connect with ${a.name}.")
                        } catch (e: Exception) {
                            toast("Could not send. Please try again.")
                        }
                    }
                    Unit
                }
                if (loggedIn) send() else login { send() }
            },
        )
    }

    reportFor?.let { l ->
        ReportDialog(
            l = l,
            onDismiss = { reportFor = null },
            onSend = { msg ->
                scope.launch {
                    try {
                        Repo.report(msg, "report_listing", l.id)
                        toast("Thank you. Ticket created – our team will check this land within 48 hours.")
                        reportFor = null
                    } catch (e: Exception) {
                        toast("Could not send. Please try again.")
                    }
                }
            },
        )
    }

    interestFor?.let { l ->
        InterestDialog(
            l = l,
            onDismiss = { interestFor = null },
            onSend = { name, phone ->
                scope.launch {
                    try {
                        Repo.sendInterest(l.id, name, phone)
                        toast("Thank you! Our team will call you within 24 hours.")
                        interestFor = null
                        leads = Repo.myLeads()
                    } catch (e: ApiException) {
                        if (e.code == 409) {
                            toast("You already sent interest for this plot.")
                            interestFor = null
                        } else {
                            toast("Could not send. Please try again.")
                        }
                    } catch (e: Exception) {
                        toast("Could not send. Check your internet.")
                    }
                }
            },
        )
    }
}

// ---------------- Login ----------------

@Composable
fun LoginScreen(onGoogle: () -> Unit, onOpen: (String) -> Unit, onSkip: () -> Unit) {
    var terms by rememberSaveable { mutableStateOf(false) }
    var broker by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Ground).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)).background(Green),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("BhoomiMap", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(TAGLINE, color = Green, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        PromiseChips()
        Spacer(Modifier.height(20.dp))
        HowItWorksCard()
        Spacer(Modifier.height(14.dp))
        ConsentChecks(terms, { terms = it }, broker, { broker = it }, onOpen)
        Spacer(Modifier.height(10.dp))
        Button(onClick = onGoogle, enabled = terms && broker, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Continue with Google", fontSize = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Skip for now – just browse the map")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "We use your name and phone only to contact you about plots you choose.",
            fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
        )
    }
}

// ---------------- Map ----------------

@Composable
fun MapScreen(
    listings: List<Listing>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpen: (Listing) -> Unit,
    onInterested: (Listing) -> Unit,
    agents: List<Agent>,
    onAgent: (Agent) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("all") }
    var size by rememberSaveable { mutableStateOf("any") }
    var query by rememberSaveable { mutableStateOf("") }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var threeD by rememberSaveable { mutableStateOf(false) }
    var satellite by rememberSaveable { mutableStateOf(true) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(SOLAPUR, 8.5f) }
    val range = SIZE_RANGES.first { it.key == size }
    val q = query.trim()
    val shown = listings.filter {
        (filter == "all" || it.kind == filter) && range.has(it.areaSqft) &&
            (q.isEmpty() || it.place.contains(q, ignoreCase = true) || it.title.contains(q, ignoreCase = true))
    }
    val picked = shown.firstOrNull { it.id == pickedId }

    LaunchedEffect(q) {
        if (q.isEmpty()) return@LaunchedEffect
        delay(700)
        val hits = listings.filter { it.place.contains(q, ignoreCase = true) || it.title.contains(q, ignoreCase = true) }
        if (hits.isNotEmpty()) {
            val b = LatLngBounds.builder()
            hits.forEach { h -> h.boundary.forEach { b.include(it) } }
            try {
                camera.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 160))
            } catch (_: Exception) {
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapType = if (satellite) MapType.HYBRID else MapType.NORMAL),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
            onMapClick = { pickedId = null },
        ) {
            shown.forEach { l ->
                key(l.id) {
                    val sel = l.id == pickedId
                    Polygon(
                        points = l.boundary,
                        fillColor = PlotFill,
                        strokeColor = if (sel) Color.White else PlotLine,
                        strokeWidth = if (sel) 7f else 4f,
                        clickable = true,
                        onClick = { pickedId = l.id },
                    )
                    MarkerComposable(
                        l.id, sel,
                        state = rememberMarkerState(key = l.id, position = l.center),
                        onClick = {
                            pickedId = l.id
                            true
                        },
                    ) {
                        Text(
                            Fmt.shortArea(l.areaSqft),
                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(if (sel) Green else Red, RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(26.dp), shadowElevation = 4.dp, color = Color.White) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Red)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search village or taluka", fontSize = 15.sp, color = Muted)
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it.take(40); pickedId = null },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                    } else {
                        Text("${shown.size} for sale", fontSize = 13.sp, color = Muted)
                    }
                }
            }
            KindChips(filter) { filter = it; pickedId = null }
            SizeChips(size) { size = it; pickedId = null }
            when {
                loading -> StatusCard { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("Loading plots…") }
                error != null -> StatusCard {
                    Text(error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                listings.isEmpty() -> StatusCard { Text("No plots listed yet in Solapur. New plots coming soon.") }
                shown.isEmpty() -> StatusCard { Text("No land matches. Try another filter or create an alert.") }
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    threeD = !threeD
                    scope.launch {
                        camera.animate(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.builder(camera.position).tilt(if (threeD) 60f else 0f).build()
                            )
                        )
                    }
                },
                containerColor = if (threeD) Green else Color.White,
                contentColor = if (threeD) Color.White else Color.Black,
            ) { Text("3D", fontWeight = FontWeight.Bold) }
            SmallFloatingActionButton(
                onClick = { satellite = !satellite },
                containerColor = Color.White,
            ) { Text(if (satellite) "Map" else "Sat", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            SmallFloatingActionButton(
                onClick = { scope.launch { camera.animate(CameraUpdateFactory.newLatLngZoom(SOLAPUR, 8.5f)) } },
                containerColor = Color.White,
            ) { Icon(Icons.Default.Home, contentDescription = "Show all Solapur") }
        }

        if (picked == null && agents.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).padding(10.dp)) { TopAgentsRow(agents, onAgent) }
        }

        if (picked != null) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(6.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag("FOR SALE · ${picked.kindLabel}", Color(0xFFFDECEC), Color(0xFFB91C1C))
                        Spacer(Modifier.weight(1f))
                        if (picked.verified) Text("Checked by Team", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(picked.title.ifBlank { Fmt.shortArea(picked.areaSqft) }, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(picked.listedByLabel, color = Color(0xFF14502F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOf(Fmt.sqft(picked.areaSqft), Fmt.guntha(picked.areaSqft), picked.place)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = Muted, fontSize = 13.sp,
                    )
                    LockLine()
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { onOpen(picked) },
                            modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                        ) { Text("View Details") }
                        Button(
                            onClick = { onInterested(picked) },
                            modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                        ) { Text("I'm Interested") }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(content: @Composable RowScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun Tag(text: String, bg: Color, fg: Color) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun LockLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("4 side sizes, photos & 3D open after our team calls you.", fontSize = 12.sp, color = Muted)
    }
}

// ---------------- Details ----------------

@Composable
fun DetailsScreen(
    l: Listing,
    lead: Lead?,
    isSaved: Boolean,
    loggedIn: Boolean,
    onBack: () -> Unit,
    onInterested: () -> Unit,
    onSave: () -> Unit,
    onSafety: () -> Unit,
    nearby: List<Listing>,
    onOpenOther: (Listing) -> Unit,
    onShare: () -> Unit,
    onReport: () -> Unit,
    onEnquiry: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var detail by remember(l.id) { mutableStateOf<ListingDetail?>(null) }
    var checking by remember(l.id) { mutableStateOf(true) }
    var threeD by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(l.center, 18f) }

    LaunchedEffect(l.id, loggedIn, lead?.status) {
        checking = true
        detail = try { Repo.detail(l.id) } catch (_: Exception) { null }
        checking = false
    }

    Column(Modifier.fillMaxSize().background(Ground)) {
        TopAppBar(
            title = { Text("Land Details") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share") }
                IconButton(onClick = onReport) { Icon(Icons.Default.Warning, contentDescription = "Report this land") }
                IconButton(onClick = onSave) {
                    Icon(
                        if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isSaved) "Remove from saved" else "Save",
                        tint = if (isSaved) Red else Color.Black,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val d = detail
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(mapType = MapType.HYBRID),
                    uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
                    onMapLoaded = {
                        val b = LatLngBounds.builder()
                        l.boundary.forEach { b.include(it) }
                        camera.move(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
                    },
                ) {
                    Polygon(points = l.boundary, fillColor = PlotFill, strokeColor = PlotLine, strokeWidth = 5f)
                }
                if (d != null) {
                    SmallFloatingActionButton(
                        onClick = {
                            threeD = !threeD
                            scope.launch {
                                camera.animate(
                                    CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.builder(camera.position)
                                            .tilt(if (threeD) 60f else 0f)
                                            .bearing(if (threeD) 30f else 0f)
                                            .build()
                                    )
                                )
                            }
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                        containerColor = if (threeD) Green else Color.White,
                        contentColor = if (threeD) Color.White else Color.Black,
                    ) { Text(if (threeD) "2D" else "3D View", modifier = Modifier.padding(horizontal = 10.dp), fontWeight = FontWeight.Bold) }
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text(l.title.ifBlank { Fmt.shortArea(l.areaSqft) }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(listOf(l.place, "Solapur").filter { it.isNotBlank() }.joinToString(", "), color = Muted, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(Fmt.num(Math.round(l.areaSqft).toDouble()), "sq ft", Modifier.weight(1f))
                    Stat(String.format(Locale.US, "%.2f", l.areaSqft / 1089.0), "Guntha", Modifier.weight(1f))
                    Stat(String.format(Locale.US, "%.2f", l.areaSqft / 43560.0), "Acre", Modifier.weight(1f))
                }
                InfoCard {
                    InfoRow("Land Type", if (l.kind == "farm") "Farm Land" else "Plot")
                    InfoRow("Listed by", if (l.listedBy == "agent") "Partner Agent" else "Owner")
                    if (l.facing.isNotBlank()) InfoRow("Facing", l.facing)
                    if (l.roadFt > 0) InfoRow("Road Width", Fmt.ft(l.roadFt))
                    InfoRow("Documents", if (l.verified) "Checked by Team" else "Being checked", if (l.verified) Green else Muted)
                    InfoRow("Price", "Ask our team")
                }
                when {
                    checking -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    d != null -> {
                        MeasurementsCard(d, l.areaSqft, l.roadFt)
                        PhotoSection("Team Site Photos", d.teamPhotos, true)
                        PhotoSection("Owner Photos", d.ownerPhotos, false)
                    }
                    else -> LockedCard(lead, onInterested)
                }
                if (nearby.isNotEmpty()) {
                    Text("Nearby Land for Sale (within 5 km)", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    nearby.forEach { n ->
                        ListingRow(
                            n,
                            trailing = { Text(String.format(Locale.US, "%.1f km", distanceKm(n.center, l.center)), fontSize = 12.sp, color = Muted) },
                            onClick = { onOpenOther(n) },
                        )
                    }
                }
                if (lead != null && lead.id.isNotBlank()) {
                    Button(onClick = { onEnquiry(lead.id) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                        Text("My Enquiry – Verify Caller & Stop Calls")
                    }
                }
                OutlinedButton(onClick = onSafety, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("Safe Payment Rules & Help")
                }
                Text(DISCLAIMER, fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Black) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MeasurementsCard(d: ListingDetail, areaSqft: Double, roadFt: Double) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plot Measurements", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Checked by Team", fontSize = 12.sp, color = Green, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 70.dp, vertical = 30.dp)) {
                val w = size.width
                val h = size.height
                val p = Path().apply {
                    moveTo(w * 0.04f, 0f)
                    lineTo(w, h * 0.02f)
                    lineTo(w * 0.95f, h)
                    lineTo(w * 0.06f, h)
                    close()
                }
                drawPath(p, PlotFill)
                drawPath(p, Red, style = Stroke(width = 5f))
                if (roadFt > 0) drawLine(Color(0xFF9AA394), Offset(-200f, h + 40f), Offset(w + 200f, h + 40f), strokeWidth = 18f)
            }
            Text("Back ${Fmt.ft(d.back)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopCenter))
            Text("Front ${Fmt.ft(d.front)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter))
            Text("Left\n${Fmt.ft(d.left)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.CenterStart))
            Text("Right\n${Fmt.ft(d.right)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.CenterEnd))
            Text(Fmt.sqft(areaSqft), color = Color(0xFF7F1D1D), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideBox("Front", d.front, Modifier.weight(1f))
            SideBox("Back", d.back, Modifier.weight(1f))
            SideBox("Left", d.left, Modifier.weight(1f))
            SideBox("Right", d.right, Modifier.weight(1f))
        }
    }
}

@Composable
fun SideBox(label: String, v: Double, modifier: Modifier) {
    Column(
        modifier.background(Ground, RoundedCornerShape(8.dp)).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, color = Muted)
        Text(Fmt.ft(v), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PhotoSection(title: String, photos: List<String>, team: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${photos.size} photos", fontSize = 13.sp, color = Muted)
        }
        if (photos.isEmpty()) {
            Text("Photos coming soon.", fontSize = 13.sp, color = Muted)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos) { url ->
                    Box {
                        AsyncImage(
                            model = url, contentDescription = title, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 150.dp, height = 110.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE1E4DD)),
                        )
                        Text(
                            "For " + (Session.name ?: Session.email ?: "you") + " only",
                            color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
                        )
                        if (team) {
                            Icon(
                                Icons.Default.CheckCircle, contentDescription = "Taken by our team", tint = Green,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.White, RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LockedCard(lead: Lead?, onInterested: () -> Unit) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Green)
            Spacer(Modifier.width(8.dp))
            Text("Full details are locked", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "All 4 side sizes, site photos and 3D View open after our team calls you and confirms.",
            fontSize = 14.sp, color = Muted,
        )
        if (lead != null) {
            Tag(Fmt.status(lead.status), Color(0xFFFFF4E5), Color(0xFF8A4B00))
            Text("Our team will call you within 24 hours.", fontSize = 13.sp, color = Muted)
        } else {
            Button(
                onClick = onInterested,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
            ) { Text("I'm Interested", fontSize = 16.sp) }
        }
    }
}

// ---------------- Interest dialog ----------------

@Composable
fun InterestDialog(l: Listing, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var name by remember { mutableStateOf(Session.name.orEmpty()) }
    var phone by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("I'm Interested") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${Fmt.shortArea(l.areaSqft)} · ${l.place}", color = Muted)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; err = null },
                    label = { Text("Your Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { v -> phone = v.filter { it.isDigit() }.take(10); err = null },
                    label = { Text("Mobile Number") },
                    prefix = { Text("+91 ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                err?.let { Text(it, color = Red, fontSize = 13.sp) }
                Text("By sending, you allow our team to call you about this plot.", fontSize = 12.sp, color = Muted)
                Text("BhoomiMap never asks for money before the deal.", fontSize = 12.sp, color = Red, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    name.trim().length < 2 -> { err = "Enter your name" }
                    phone.length != 10 || phone[0] !in '6'..'9' -> { err = "Enter a valid 10-digit mobile number" }
                    else -> { onSend(name.trim(), phone) }
                }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Saved & Profile ----------------

@Composable
fun ListingRow(l: Listing, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(PlotFill, RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title.ifBlank { Fmt.shortArea(l.areaSqft) }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("${Fmt.shortArea(l.areaSqft)} · ${l.place}", color = Muted, fontSize = 13.sp)
        }
        trailing()
    }
}

@Composable
fun SavedScreen(plots: List<Listing>, loggedIn: Boolean, onLogin: () -> Unit, onOpen: (Listing) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Saved Plots", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        when {
            !loggedIn -> {
                Text("Login to save plots you like.", color = Muted)
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("Continue with Google")
                }
            }
            plots.isEmpty() -> Text("No saved plots yet. Tap the heart on any plot to save it.", color = Muted)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(plots, key = { it.id }) { l -> ListingRow(l, onClick = { onOpen(l) }) }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    loggedIn: Boolean,
    leads: List<Lead>,
    listings: List<Listing>,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (Listing) -> Unit,
    onPage: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Profile", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (!loggedIn) {
            Text("Login to send interest and save plots.", color = Muted)
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Continue with Google")
            }
        } else {
            InfoCard {
                Text(Session.name ?: "BhoomiMap User", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(Session.email.orEmpty(), color = Muted, fontSize = 14.sp)
            }
            Text("My Interests", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (leads.isEmpty()) Text("You have not shown interest in any plot yet.", color = Muted, fontSize = 14.sp)
            leads.forEach { ld ->
                val l = listings.firstOrNull { it.id == ld.listingId }
                if (l != null) {
                    ListingRow(
                        l,
                        trailing = { Tag(Fmt.status(ld.status), Color(0xFFFFF4E5), Color(0xFF8A4B00)) },
                        onClick = { if (ld.id.isNotBlank()) onPage("enquiry:${ld.id}") else onOpen(l) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Logout")
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Delete Account", color = Red)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Tools", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onPage("converter") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Area Converter")
        }
        OutlinedButton(onClick = { onPage("guides") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Land Buying Guide")
        }
        OutlinedButton(onClick = { onPage("rules") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Promise & Rules")
        }
        OutlinedButton(onClick = { onPage("safety") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Safety & Help")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextButton(onClick = { onPage("terms") }) { Text("Terms of Use") }
            TextButton(onClick = { onPage("privacy") }) { Text("Privacy Policy") }
        }
        Text("BhoomiMap v${BuildConfig.VERSION_NAME} · Solapur", fontSize = 12.sp, color = Muted)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Account?") },
            text = { Text("Your account and your interests will be deleted within 7 days. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
