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
            loggedIn = Session.loggedIn
            loading = false
        }
    }

    fun login(after: () -> Unit = {}) {
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

    if (!loggedIn && !skipped) {
        LoginScreen(
            onGoogle = { login() },
            onSkip = {
                Session.skippedLogin = true
                skipped = true
            },
        )
    } else {
        val open = listings.firstOrNull { it.id == openId }
        if (open != null) {
            BackHandler { openId = null }
            DetailsScreen(
                l = open,
                lead = leads.firstOrNull { it.listingId == open.id },
                isSaved = open.id in saved,
                loggedIn = loggedIn,
                onBack = { openId = null },
                onInterested = { askInterest(open) },
                onSave = { toggleSave(open) },
            )
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
                        )
                        1 -> SavedScreen(
                            plots = listings.filter { it.id in saved },
                            loggedIn = loggedIn,
                            onLogin = { login() },
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
                                }
                            },
                            onOpen = { openId = it.id },
                        )
                    }
                }
            }
        }
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
fun LoginScreen(onGoogle: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ground).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
        Text("Find verified land and plots in Solapur", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        Button(onClick = onGoogle, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Continue with Google", fontSize = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Skip for now – just browse the map")
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "By continuing you agree to our Terms and Privacy Policy.",
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
) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("all") }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var threeD by rememberSaveable { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(SOLAPUR, 8.5f) }
    val shown = listings.filter { filter == "all" || it.kind == filter }
    val picked = shown.firstOrNull { it.id == pickedId }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapType = MapType.HYBRID),
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
                    Icon(Icons.Default.Place, contentDescription = null, tint = Red)
                    Spacer(Modifier.width(10.dp))
                    Text("BhoomiMap · Solapur", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${shown.size} for sale", fontSize = 13.sp, color = Muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All Land", "plot" to "Plots", "farm" to "Farm Land").forEach { (k, label) ->
                    FilterChip(
                        selected = filter == k,
                        onClick = {
                            filter = k
                            pickedId = null
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White,
                            selectedContainerColor = Green,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
            when {
                loading -> StatusCard { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("Loading plots…") }
                error != null -> StatusCard {
                    Text(error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                listings.isEmpty() -> StatusCard { Text("No plots listed yet in Solapur. New plots coming soon.") }
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
                onClick = { scope.launch { camera.animate(CameraUpdateFactory.newLatLngZoom(SOLAPUR, 8.5f)) } },
                containerColor = Color.White,
            ) { Icon(Icons.Default.Home, contentDescription = "Show all Solapur") }
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
                        if (picked.verified) Text("Verified by Team", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(picked.title.ifBlank { Fmt.shortArea(picked.areaSqft) }, fontSize = 19.sp, fontWeight = FontWeight.Bold)
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
                    if (l.facing.isNotBlank()) InfoRow("Facing", l.facing)
                    if (l.roadFt > 0) InfoRow("Road Width", Fmt.ft(l.roadFt))
                    InfoRow("Documents", if (l.verified) "Checked by Team" else "Being checked", if (l.verified) Green else Muted)
                    InfoRow("Price", "Ask our team")
                }
                when {
                    checking -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    d != null -> {
                        MeasurementsCard(d, l.areaSqft, l.roadFt)
                        PhotoSection("Verified by Team Photos", d.teamPhotos, true)
                        PhotoSection("Owner Photos", d.ownerPhotos, false)
                    }
                    else -> LockedCard(lead, onInterested)
                }
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
            Text("Verified by Team", fontSize = 12.sp, color = Green, fontWeight = FontWeight.SemiBold)
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
                        if (team) {
                            Icon(
                                Icons.Default.CheckCircle, contentDescription = "Verified by Team", tint = Green,
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
                Text("Our team will call you within 24 hours.", fontSize = 12.sp, color = Muted)
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
                        onClick = { onOpen(l) },
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
        Spacer(Modifier.height(8.dp))
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
