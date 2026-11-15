@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bhoomimap.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// ---------------- Share ----------------

fun shareListing(ctx: Context, l: Listing) {
    val type = if (l.kind == "farm") "Farm Land" else "Plot"
    val text = "For sale on BhoomiMap: ${Fmt.shortArea(l.areaSqft)} $type at ${l.place}, Solapur. " +
        "Open BhoomiMap to see it on the map."
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text).setType("text/plain")
    ctx.startActivity(Intent.createChooser(send, "Share Land"))
}

// ---------------- Common chips ----------------

@Composable
fun GreenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White,
            selectedContainerColor = Green,
            selectedLabelColor = Color.White,
        ),
    )
}

@Composable
fun KindChips(kind: String, onKind: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to "All Land", "plot" to "Plots", "farm" to "Farm Land").forEach { (k, label) ->
            GreenChip(label, kind == k) { onKind(k) }
        }
    }
}

@Composable
fun SizeChips(size: String, onSize: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SIZE_RANGES) { r -> GreenChip(r.label, size == r.key) { onSize(r.key) } }
    }
}

@Composable
fun ScreenTitle(text: String, onBack: (() -> Unit)? = null) {
    if (onBack == null) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    } else {
        TopAppBar(
            title = { Text(text) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
        )
    }
}

@Composable
fun LoginNeeded(text: String, onLogin: () -> Unit) {
    Text(text, color = Muted)
    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
        Text("Continue with Google")
    }
}

// ---------------- Top agents ----------------

@Composable
fun TopAgentsRow(agents: List<Agent>, onPick: (Agent) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("Top Agents", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(6.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(agents, key = { it.id }) { a ->
                    Row(
                        Modifier.background(Ground, RoundedCornerShape(20.dp)).clickable { onPick(a) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(a.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(a.area, fontSize = 11.sp, color = Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentDialog(a: Agent, onDismiss: () -> Unit, onConnect: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Green) },
        title = { Text(a.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("BhoomiMap Partner Agent", Color(0xFFE7F3EC), Green)
                Text("Area: ${a.area}", color = Muted)
                if (a.deals > 0) Text("Deals with BhoomiMap: ${a.deals}", color = Muted)
                Text("Our team will connect you with this agent and join your site visit.", fontSize = 13.sp, color = Muted)
            }
        },
        confirmButton = { Button(onClick = onConnect) { Text("Ask Team to Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------- Report listing ----------------

@Composable
fun ReportDialog(l: Listing, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val reasons = listOf("Land already sold", "Wrong location on map", "Fake or suspicious", "Other problem")
    var reason by remember { mutableStateOf(reasons[0]) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Red) },
        title = { Text("Report This Land") },
        text = {
            Column {
                reasons.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { reason = r }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r)
                    }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(300) },
                    label = { Text("More details (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSend("Listing report | ${l.id} | ${l.title.ifBlank { l.place }} | $reason | ${note.trim()}".take(1000))
            }) { Text("Send Report") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Sell land ----------------

@Composable
fun SellScreen(loggedIn: Boolean, onLogin: () -> Unit, onSend: (String, String, String, String, String, String, () -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf(Session.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("farm") }
    var area by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("Acre") }
    var note by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Sell Your Land")
        InfoCard {
            InfoRow("Listing", "Free", Green)
            InfoRow("Stays live", "Till sold")
            InfoRow("Your price", "Private to our team")
            Text(
                "Our team will call you, visit the land and check papers before it goes live on the map.",
                fontSize = 13.sp, color = Muted,
            )
        }
        if (!loggedIn) {
            LoginNeeded("Login to list your land.", onLogin)
            return@Column
        }
        OutlinedTextField(name, { name = it; err = null }, label = { Text("Owner Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            phone, { v -> phone = v.filter { it.isDigit() }.take(10); err = null },
            label = { Text("Mobile Number") }, prefix = { Text("+91 ") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(place, { place = it; err = null }, label = { Text("Village / Area, Taluka") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Land Type", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GreenChip("Farm Land", kind == "farm") { kind = "farm" }
            GreenChip("Plot", kind == "plot") { kind = "plot" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                area, { v -> area = v.filter { it.isDigit() || it == '.' }.take(10); err = null },
                label = { Text("Land Size") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Acre", "Guntha", "Sq ft").forEach { u -> GreenChip(u, unit == u) { unit = u } }
        }
        OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note for team (optional)") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.clickable { owner = !owner; err = null }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = owner, onCheckedChange = { owner = it; err = null })
            Text("I am the owner, or I have the owner's permission to sell.", fontSize = 13.sp)
        }
        err?.let { Text(it, color = Red, fontSize = 13.sp) }
        Button(
            enabled = !sending,
            onClick = {
                when {
                    name.trim().length < 2 -> err = "Enter owner name"
                    phone.length != 10 || phone[0] !in '6'..'9' -> err = "Enter a valid 10-digit mobile number"
                    place.trim().length < 2 -> err = "Enter village or area"
                    (area.toDoubleOrNull() ?: 0.0) <= 0.0 -> err = "Enter land size"
                    !owner -> err = "Please confirm you are the owner or have permission"
                    else -> {
                        sending = true
                        onSend(name.trim(), phone, place.trim(), kind, "$area $unit", note.trim()) {
                            sending = false
                            place = ""; area = ""; note = ""; owner = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
        ) { Text("List My Land", fontSize = 16.sp) }
    }
}

// ---------------- Alerts ----------------

@Composable
fun AlertsScreen(
    loggedIn: Boolean,
    alerts: List<LandAlert>,
    listings: List<Listing>,
    onLogin: () -> Unit,
    onAdd: (String, String, SizeRange) -> Unit,
    onDelete: (LandAlert) -> Unit,
    onOpen: (Listing) -> Unit,
) {
    var place by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("all") }
    var size by rememberSaveable { mutableStateOf("any") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Land Alerts")
        Text("Tell us what land you want. New matching land shows here.", color = Muted, fontSize = 14.sp)
        if (!loggedIn) {
            LoginNeeded("Login to create alerts.", onLogin)
            return@Column
        }
        InfoCard {
            Text("New Alert", fontWeight = FontWeight.Bold)
            OutlinedTextField(place, { place = it }, label = { Text("Village / Area (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            KindChips(kind) { kind = it }
            SizeChips(size) { size = it }
            Button(
                onClick = {
                    onAdd(place, kind, SIZE_RANGES.first { it.key == size })
                    place = ""
                },
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
            ) { Text("Create Alert") }
        }
        if (alerts.isEmpty()) Text("No alerts yet.", color = Muted)
        alerts.forEach { a ->
            val matches = listings.filter { a.matches(it) }
            val newOnes = matches.filter { a.isNew(it) }
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = Green)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(alertTitle(a), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("${matches.size} matching · ${newOnes.size} new", fontSize = 13.sp, color = Muted)
                    }
                    IconButton(onClick = { onDelete(a) }) { Icon(Icons.Default.Delete, contentDescription = "Delete alert", tint = Muted) }
                }
                matches.take(5).forEach { l ->
                    ListingRow(
                        l,
                        trailing = { if (a.isNew(l)) Tag("NEW", Color(0xFFFDECEC), Color(0xFFB91C1C)) },
                        onClick = { onOpen(l) },
                    )
                }
            }
        }
    }
}

fun alertTitle(a: LandAlert): String {
    val kind = when (a.kind) { "farm" -> "Farm Land"; "plot" -> "Plots"; else -> "Any Land" }
    val size = SIZE_RANGES.firstOrNull { it.minSqft == a.minSqft && it.maxSqft == a.maxSqft }?.label ?: "Any Size"
    return listOf(kind, size, a.place.ifBlank { "All Solapur" }).joinToString(" · ")
}

// ---------------- Area converter ----------------

private val UNITS = listOf(
    "Sq ft" to 1.0,
    "Sq m" to 10.7639,
    "Guntha" to 1089.0,
    "Are (R)" to 1076.391,
    "Acre" to 43560.0,
    "Hectare" to 107639.1,
)

@Composable
fun ConverterScreen(onBack: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("Acre") }
    val sqft = (value.toDoubleOrNull() ?: 0.0) * (UNITS.first { it.first == unit }.second)
    Column(Modifier.fillMaxSize().background(Ground)) {
        ScreenTitle("Area Converter", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value, { v -> value = v.filter { it.isDigit() || it == '.' }.take(12) },
                label = { Text("Enter Size") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(UNITS) { (u, _) -> GreenChip(u, unit == u) { unit = u } }
            }
            InfoCard {
                UNITS.forEach { (u, f) ->
                    val v = sqft / f
                    InfoRow(u, if (v >= 100) Fmt.num(Math.round(v).toDouble()) else String.format(Locale.US, "%.3f", v))
                }
            }
            Text("1 Acre = 40 Guntha · 1 Hectare = 2.47 Acre · 1 Guntha = 1,089 sq ft", fontSize = 12.sp, color = Muted)
        }
    }
}

// ---------------- Land guides ----------------

private val GUIDES = listOf(
    "7/12 Extract (Satbara)" to "Shows survey number, area, owner names, crops and loans or other rights on the land. Check that the seller's name matches. You can view it online on the Maharashtra Mahabhumi website.",
    "8A Extract" to "Shows all land held by one person (khata) in that village. Helps confirm what the seller owns.",
    "Mutation Entries (Ferfar)" to "History of every change on the 7/12 – sale, inheritance, loan. Ask for copies of recent entries.",
    "Search Report" to "A lawyer checks registration records (usually 30 years) for past sales, mortgages and disputes.",
    "NA Order and Layout" to "To build a house on farmland you need NA (non-agricultural) permission. For plots, check that the layout is approved.",
    "Measurement (Mojani)" to "Government measurement by the Land Records office confirms boundaries and exact area.",
    "Who Can Buy Farm Land" to "In Maharashtra, buying agricultural land usually needs farmer status. Ask a lawyer before you pay.",
    "Safe Payment" to "Never pay cash advance before papers are checked. Pay by bank transfer and keep receipts. Register the sale at the Sub-Registrar office.",
)

@Composable
fun GuidesScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ground)) {
        ScreenTitle("Land Buying Guide", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GUIDES.forEach { (t, body) ->
                var open by remember { mutableStateOf(false) }
                InfoCard {
                    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Green)
                        Spacer(Modifier.width(8.dp))
                        Text(t, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    if (open) Text(body, fontSize = 14.sp, color = Muted, lineHeight = 20.sp)
                }
            }
            Text(
                "This is general information only, not legal advice. Always check papers with a lawyer before buying.",
                fontSize = 12.sp, color = Muted,
            )
        }
    }
}
