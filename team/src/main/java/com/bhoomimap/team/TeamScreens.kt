@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bhoomimap.team

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun isoInDays(days: Long): String = OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).toString()

@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Row(Modifier.fillMaxWidth().background(Color.White).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        options.forEach { (k, label) ->
            FilterChip(selected = selected == k, onClick = { onSelect(k) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Green, selectedLabelColor = Color.White))
        }
    }
}

// =============================== LEADS ===============================

@Composable
fun LeadsScreen(isCeo: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var leads by remember { mutableStateOf<List<TLead>>(emptyList()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var members by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var openId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }

    fun load() {
        scope.launch {
            loading = true
            try {
                leads = Repo.leads()
                names = Repo.listings().associate { it.id to it.name }
                members = Repo.members().associate { it.userId to it.name }
            } catch (e: Exception) { toast(ctx, errText(e)) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val open = leads.firstOrNull { it.id == openId }
    if (open != null) {
        LeadDetail(open, names[open.listingId] ?: "Plot", members[open.assignedTo] ?: "", isCeo, onBack = { openId = null }, onChanged = { load() })
        return
    }
    Page {
        ChipRow(listOf("all" to "All", "new" to "New", "mine" to "Mine", "unlocked" to "Unlocked", "closed" to "Closed"), filter) { filter = it }
        val shown = leads.filter {
            when (filter) {
                "new" -> it.status == "new"
                "mine" -> it.assignedTo == Api.userId()
                "unlocked" -> it.status == "unlocked"
                "closed" -> it.status == "closed"
                else -> true
            }
        }
        Loading(loading, shown.isEmpty(), "No leads yet.")
        shown.forEach { l ->
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable { openId = l.id }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.buyerName, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    statusPill(l.status)
                }
                Text(names[l.listingId] ?: "Plot", color = Muted, fontSize = 13.sp)
                Text(
                    (members[l.assignedTo]?.let { "With: $it" } ?: "Unassigned") + " · Calls ${l.attempts}/2" + (if (l.stopCalls) " · STOP CALLS" else ""),
                    color = if (l.stopCalls) Red else Muted, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun LeadDetail(l: TLead, plot: String, assignedName: String, isCeo: Boolean, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf("7") }
    var price by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun act(done: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block(); toast(ctx, done); onChanged() } catch (e: Exception) { toast(ctx, errText(e)) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("Lead – ${l.buyerName}", onBack)
        Page {
            Section("Buyer") {
                Row2("Name", l.buyerName)
                Row2("Plot", plot)
                Row2("Status", l.status.replaceFirstChar { it.uppercase() })
                Row2("Handled by", assignedName.ifBlank { "Nobody yet" })
                Row2("Calls", "${l.attempts} of 2", if (l.attempts >= 2) Red else Color.Black)
                if (l.stopCalls) Text("Buyer turned on STOP CALLS. Do not call.", color = Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Phone number is hidden. It is shown only to the member handling this lead, and the buyer can see who opened it.", color = Muted, fontSize = 12.sp)
            }
            if (l.assignedTo == null || isCeo) {
                BigButton("Take This Lead", { act("Lead is now yours") { Api.rpc("assign_to_me", JSONObject().put("p_lead", l.id)) } }, enabled = !busy)
            }
            BigButton(
                "Call Buyer",
                {
                    busy = true
                    scope.launch {
                        try {
                            Api.rpc("log_call", JSONObject().put("p_lead", l.id))
                            val phone = Api.rpc("lead_phone", JSONObject().put("p_lead", l.id)).trim().trim('"')
                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+91$phone")))
                            onChanged()
                        } catch (e: Exception) { toast(ctx, errText(e)) }
                        busy = false
                    }
                },
                enabled = !busy && !l.stopCalls && l.attempts < 2,
            )
            Section("Unlock Details for this Buyer") {
                Text("4 side sizes, photos and 3D View. Only for this buyer.", color = Muted, fontSize = 13.sp)
                ChipRow(listOf("7" to "7 days", "15" to "15 days", "30" to "30 days"), days) { days = it }
                BigButton("Unlock Details", {
                    act("Details unlocked for ${l.buyerName}") { Api.rpc("unlock_for_lead", JSONObject().put("p_lead", l.id).put("p_days", days.toInt())) }
                }, enabled = !busy, danger = true)
            }
            BigButton("Site Visit Booked", { act("Status updated") { Api.update("leads", "id=eq.${enc(l.id)}", JSONObject().put("status", "visit")) } },
                enabled = !busy, outlined = true)
            Section("Deal Done") {
                Text("Only after registry. Commission 2% + 2% is worked out automatically.", color = Muted, fontSize = 13.sp)
                Field(price, { v -> price = v.filter { it.isDigit() } }, "Sale price (₹)", number = true)
                BigButton("Mark Deal Done", {
                    val p = price.toDoubleOrNull()
                    if (p == null || p <= 0) { toast(ctx, "Enter the sale price"); return@BigButton }
                    busy = true
                    scope.launch {
                        try {
                            val r = Api.rpcJson("close_deal", JSONObject().put("p_lead", l.id).put("p_price", p))
                            toast(ctx, "Deal saved. Company share: " + Fmt.rupees(r?.dbl("company_share") ?: 0.0))
                            onChanged()
                        } catch (e: Exception) { toast(ctx, errText(e)) }
                        busy = false
                    }
                }, enabled = !busy)
            }
        }
    }
}

// =============================== LISTINGS ===============================

@Composable
fun ListingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TListing>>(emptyList()) }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var openId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            try { items = Repo.listings(); members = Repo.members() } catch (e: Exception) { toast(ctx, errText(e)) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    if (adding) { AddListingScreen(onBack = { adding = false }, onSaved = { adding = false; load() }); return }
    val open = items.firstOrNull { it.id == openId }
    if (open != null) { ListingDetailScreen(open, members, onBack = { openId = null }, onChanged = { load() }); return }

    Box(Modifier.fillMaxSize()) {
        Page {
            Loading(loading, items.isEmpty(), "No listings yet. Tap + to add.")
            items.forEach { l ->
                Column(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable { openId = l.id }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        statusPill(l.status)
                    }
                    Text("${Fmt.area(l.area)} · ${l.place} · ${if (l.listedBy == "agent") "Partner Agent" else "Owner"}", color = Muted, fontSize = 13.sp)
                    if (l.status == "live") {
                        Text(if (l.daysLeft <= 7) "Expires in ${l.daysLeft} days – ask owner & extend" else "${l.daysLeft} days left",
                            color = if (l.daysLeft <= 7) Red else Muted, fontSize = 12.sp)
                    }
                    if (!l.verified) Text("Not checked by Field Team yet", color = Color(0xFF8A4B00), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(70.dp))
        }
        FloatingActionButton(onClick = { adding = true }, containerColor = Green, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) { Icon(Icons.Default.Add, contentDescription = "Add Listing") }
    }
}

@Composable
fun ListingDetailScreen(l: TListing, members: List<Member>, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var priv by remember { mutableStateOf<JSONObject?>(null) }
    var fieldId by remember { mutableStateOf(members.firstOrNull { it.role == "field" && it.active }?.userId ?: "") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editOwner by remember { mutableStateOf(false) }
    var oName by remember { mutableStateOf("") }
    var oPhone by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("") }

    fun act(done: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block(); toast(ctx, done); onChanged() } catch (e: Exception) { toast(ctx, errText(e)) }
            busy = false
        }
    }
    fun setStatus(s: String, extend: Boolean) = act("Updated") {
        val b = JSONObject().put("status", s)
        if (extend) b.put("expires_at", isoInDays(90))
        Api.update("listings", "id=eq.${enc(l.id)}", b)
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(l.name, onBack)
        Page {
            Section("Listing") {
                Row2("Status", l.status.replaceFirstChar { it.uppercase() })
                Row2("Size", Fmt.area(l.area))
                Row2("Place", l.place)
                Row2("Type", if (l.kind == "farm") "Farm Land" else "Plot")
                Row2("Listed by", if (l.listedBy == "agent") "Partner Agent" else "Owner")
                Row2("Checked by Field Team", if (l.verified) "Yes" else "No", if (l.verified) Green else Red)
                if (l.status == "live") Row2("Days left", "${l.daysLeft}", if (l.daysLeft <= 7) Red else Color.Black)
            }
            Section("Owner & Price (Private)") {
                val p = priv
                if (p == null) {
                    Text("Opening this is recorded in the audit log.", color = Muted, fontSize = 13.sp)
                    BigButton("Show Owner & Price", { act("Opened") { priv = Api.rpcJson("get_private", JSONObject().put("p_listing", l.id)) ?: JSONObject() } }, enabled = !busy, outlined = true)
                } else {
                    Row2("Owner", p.str("owner_name"))
                    Row2("Owner phone", p.str("owner_phone"))
                    Row2("Asking price", Fmt.rupees(p.dbl("asking_price")))
                    Row2("Minimum price", Fmt.rupees(p.dbl("minimum_price")), Red)
                    Row2("Commission (2%)", Fmt.rupees(p.dbl("minimum_price") * 0.02) + " – " + Fmt.rupees(p.dbl("asking_price") * 0.02), Green)
                }
                TextButton(onClick = {
                    editOwner = !editOwner
                    priv?.let { oName = it.str("owner_name"); oPhone = it.str("owner_phone"); ask = it.dbl("asking_price").toLong().toString(); min = it.dbl("minimum_price").toLong().toString() }
                }) { Text(if (editOwner) "Close" else "Edit Owner & Price") }
                if (editOwner) {
                    Field(oName, { oName = it }, "Owner name")
                    Field(oPhone, { v -> oPhone = v.filter { it.isDigit() }.take(10) }, "Owner phone", number = true)
                    Field(ask, { v -> ask = v.filter { it.isDigit() } }, "Asking price (₹)", number = true)
                    Field(min, { v -> min = v.filter { it.isDigit() } }, "Minimum price (₹)", number = true)
                    BigButton("Save Private Data", {
                        act("Saved (encrypted)") {
                            Api.rpc("set_private", JSONObject().put("p_listing", l.id).put("p_owner_name", oName).put("p_owner_phone", oPhone)
                                .put("p_asking", ask.toDoubleOrNull() ?: 0.0).put("p_min", min.toDoubleOrNull() ?: 0.0))
                            priv = null; editOwner = false
                        }
                    }, enabled = !busy)
                }
            }
            Section("Send Field Team for Site Check") {
                val field = members.filter { it.role == "field" && it.active }
                if (field.isEmpty()) Text("No Field Team member yet. CEO can add in Team tab.", color = Muted, fontSize = 13.sp)
                else {
                    ChipRow(field.map { it.userId to it.name }, fieldId) { fieldId = it }
                    Field(note, { note = it }, "Note for Field Team (optional)")
                    BigButton("Create Site Task", {
                        if (fieldId.isBlank()) return@BigButton
                        act("Task sent to Field Team") {
                            Api.insert("site_tasks", JSONObject().put("listing_id", l.id).put("assigned_to", fieldId).put("note", note))
                        }
                    }, enabled = !busy)
                }
            }
            Section("Actions") {
                if (l.status != "live") BigButton("Make Live (90 days)", { setStatus("live", true) }, enabled = !busy && l.verified)
                if (l.status != "live" && !l.verified) Text("Make Live works after the Field Team check is approved.", color = Muted, fontSize = 12.sp)
                if (l.status == "live") BigButton("Owner Confirmed – Extend 90 Days", { setStatus("live", true) }, enabled = !busy, outlined = true)
                if (l.status == "live") BigButton("Mark Sold", { setStatus("sold", false) }, enabled = !busy, outlined = true)
                if (l.status != "removed") BigButton("Remove from Map", { setStatus("removed", false) }, enabled = !busy, danger = true)
            }
        }
    }
}

// =============================== SITE CHECKS (Senior) ===============================

@Composable
fun SiteChecksScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var members by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var openId by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            try {
                tasks = Repo.tasks()
                names = Repo.listings().associate { it.id to it.name }
                members = Repo.members().associate { it.userId to it.name }
            } catch (e: Exception) { toast(ctx, errText(e)) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val open = tasks.firstOrNull { it.id == openId }
    if (open != null) { ReviewTask(open, names[open.listingId] ?: "Plot", onBack = { openId = null }, onDone = { openId = null; load() }); return }

    Page {
        Loading(loading, tasks.isEmpty(), "No site tasks yet. Create one from a listing.")
        tasks.sortedBy { if (it.status == "submitted") 0 else 1 }.forEach { t ->
            Row(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable { if (t.status == "submitted") openId = t.id }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(names[t.listingId] ?: "Plot", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Field: " + (members[t.assignedTo] ?: "-") + " · " + Fmt.date(t.createdAt), color = Muted, fontSize = 12.sp)
                }
                statusPill(t.status)
            }
        }
    }
}

@Composable
fun ReviewTask(t: Task, plot: String, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var photos by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(t.id) {
        try {
            val a = Api.list("listing_details?listing_id=eq.${enc(t.listingId)}&select=*")
            if (a.length() > 0) {
                val o = a.getJSONObject(0)
                d = o
                val paths = mutableListOf<String>()
                listOf("team_photos", "owner_photos").forEach { k -> o.optJSONArray(k)?.let { arr -> for (i in 0 until arr.length()) paths.add(arr.getString(i)) } }
                photos = paths.mapNotNull { Api.signedPhoto(it) }
            }
        } catch (e: Exception) { toast(ctx, errText(e)) }
    }
    fun review(approve: Boolean, live: Boolean) {
        busy = true
        scope.launch {
            try {
                Api.rpc("review_task", JSONObject().put("p_task", t.id).put("p_approve", approve).put("p_make_live", live))
                toast(ctx, if (approve) "Approved" else "Sent back"); onDone()
            } catch (e: Exception) { toast(ctx, errText(e)) }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("Check – $plot", onBack)
        Page {
            val o = d
            Section("Measurements") {
                if (o == null) Text("Loading…", color = Muted)
                else {
                    Row2("Front", Fmt.num(o.dbl("front_ft")) + " ft"); Row2("Back", Fmt.num(o.dbl("back_ft")) + " ft")
                    Row2("Left", Fmt.num(o.dbl("left_ft")) + " ft"); Row2("Right", Fmt.num(o.dbl("right_ft")) + " ft")
                    val c = o.optJSONObject("checks")
                    if (c != null) {
                        Row2("7/12 name matches", if (c.optBoolean("name_match")) "Yes" else "No")
                        Row2("Area matches", if (c.optBoolean("area_match")) "Yes" else "No")
                        Row2("No dispute / loan", if (c.optBoolean("no_dispute")) "Yes" else "No")
                    }
                }
            }
            Section("Photos (${photos.size})") { PhotoStrip(photos) }
            BigButton("Approve & Make Live", { review(true, true) }, enabled = !busy)
            BigButton("Approve Only", { review(true, false) }, enabled = !busy, outlined = true)
            BigButton("Send Back (Reject)", { review(false, false) }, enabled = !busy, danger = true)
        }
    }
}

// =============================== TICKETS ===============================

@Composable
fun TicketsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Ticket>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var reply by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            loading = true
            try { items = Repo.tickets() } catch (e: Exception) { toast(ctx, errText(e)) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Page {
        Text("Reply within 48 hours. Late tickets are shown in red to the CEO.", color = Muted, fontSize = 13.sp)
        Loading(loading, items.isEmpty(), "No tickets.")
        items.forEach { t ->
            Column(
                Modifier.fillMaxWidth().background(if (t.overdue) Color(0xFFFDECEC) else Color.White, RoundedCornerShape(12.dp))
                    .clickable { openId = if (openId == t.id) null else t.id; reply = t.reply }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#BM-${t.id} · " + (if (t.kind == "report_listing") "Listing Report" else "Problem"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    statusPill(t.status)
                }
                Text(t.message, fontSize = 14.sp)
                Text(Fmt.date(t.createdAt) + if (t.overdue) " · LATE (over 48h)" else "", color = if (t.overdue) Red else Muted, fontSize = 12.sp)
                if (t.reply.isNotBlank() && openId != t.id) Text("Reply: " + t.reply, color = Green, fontSize = 13.sp)
                if (openId == t.id) {
                    Field(reply, { reply = it }, "Your reply")
                    BigButton("Send Reply & Mark Solved", {
                        if (reply.trim().length < 2) return@BigButton
                        scope.launch {
                            try {
                                Api.update("tickets", "id=eq.${t.id}", JSONObject().put("reply", reply.trim()).put("status", "solved").put("replied_at", isoInDays(0)))
                                toast(ctx, "Reply sent"); openId = null; load()
                            } catch (e: Exception) { toast(ctx, errText(e)) }
                        }
                    })
                }
            }
        }
    }
}

// =============================== CEO DASHBOARD ===============================

@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var audit by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var sells by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    fun load() {
        scope.launch {
            try {
                s = Api.rpcJson("ceo_stats")
                val a = Api.list("audit_log?select=at,who,action,target&order=at.desc&limit=60")
                audit = (0 until a.length()).map { a.getJSONObject(it) }
                val r = Api.list("sell_requests?select=id,owner_name,place,kind,area_text,note,status,created_at&status=eq.new&order=created_at.desc")
                sells = (0 until r.length()).map { r.getJSONObject(it) }
            } catch (e: Exception) { toast(ctx, errText(e)) }
        }
    }
    LaunchedEffect(Unit) { load() }

    Page {
        val st = s
        if (st == null) Loading(true, false, "")
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Company Income (30%)", Fmt.rupees(st.dbl("company_income")), Green, Modifier.weight(1f))
                Tile("Agent Payouts Due", Fmt.rupees(st.dbl("agent_due")), Red, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Live Listings", st.optInt("live_listings").toString(), Color.Black, Modifier.weight(1f))
                Tile("Deals Done", st.optInt("deals").toString(), Color.Black, Modifier.weight(1f))
            }
            Section("Needs Attention") {
                Row2("New leads", st.optInt("new_leads").toString())
                Row2("Site checks to review", st.optInt("pending_tasks").toString())
                Row2("Phones waiting approval", st.optInt("pending_devices").toString(), if (st.optInt("pending_devices") > 0) Red else Color.Black)
                Row2("Open tickets", st.optInt("open_tickets").toString())
                Row2("Late tickets (48h+)", st.optInt("overdue_tickets").toString(), if (st.optInt("overdue_tickets") > 0) Red else Color.Black)
                Row2("Listings expiring in 7 days", st.optInt("expiring_soon").toString())
                Row2("New sell requests", st.optInt("new_sell_requests").toString())
            }
        }
        if (sells.isNotEmpty()) Section("Sell Requests from Owners") {
            sells.forEach { r ->
                Column(Modifier.fillMaxWidth().background(Ground, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.str("owner_name")} · ${r.str("place")}", fontWeight = FontWeight.Bold)
                    Text("${r.str("kind")} · ${r.str("area_text")} · ${r.str("note")}", color = Muted, fontSize = 13.sp)
                    TextButton(onClick = {
                        scope.launch {
                            try { Api.update("sell_requests", "id=eq.${enc(r.str("id"))}", JSONObject().put("status", "contacted")); load() } catch (e: Exception) { toast(ctx, errText(e)) }
                        }
                    }) { Text("Mark Contacted") }
                }
            }
        }
        Section("Audit Log (latest 60)") {
            if (audit.isEmpty()) Text("Nothing yet.", color = Muted)
            audit.forEach { a ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${a.str("who")}: ${a.str("action")}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(Fmt.date(a.str("at")).takeLast(11), fontSize = 12.sp, color = Muted)
                }
            }
        }
        BigButton("Refresh", { load() }, outlined = true)
    }
}

@Composable
fun Tile(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(label, fontSize = 12.sp, color = Muted)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// =============================== TEAM (CEO) ===============================

@Composable
fun TeamManageScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var devices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("field") }
    var phone by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            try {
                members = Repo.members()
                val d = Api.list("team_devices?select=device_id,user_id,label,approved,created_at&order=created_at.desc")
                devices = (0 until d.length()).map { d.getJSONObject(it) }
            } catch (e: Exception) { toast(ctx, errText(e)) }
        }
    }
    LaunchedEffect(Unit) { load() }
    val nameOf = members.associate { it.userId to it.name }

    Page {
        Section("Phones") {
            if (devices.isEmpty()) Text("No phones yet.", color = Muted)
            devices.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${nameOf[d.str("user_id")] ?: "Member"} · ${d.str("device_id").take(8).uppercase()}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(d.str("label"), color = Muted, fontSize = 12.sp)
                    }
                    if (!d.optBoolean("approved")) {
                        TextButton(onClick = {
                            scope.launch { try { Api.update("team_devices", "device_id=eq.${enc(d.str("device_id"))}", JSONObject().put("approved", true)); load() } catch (e: Exception) { toast(ctx, errText(e)) } }
                        }) { Text("Approve") }
                    }
                    TextButton(onClick = {
                        scope.launch { try { Api.delete("team_devices", "device_id=eq.${enc(d.str("device_id"))}"); load() } catch (e: Exception) { toast(ctx, errText(e)) } }
                    }) { Text("Remove", color = Red) }
                }
            }
        }
        Section("Team Members") {
            members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        Text(m.role.replaceFirstChar { it.uppercase() } + " · " + m.callPhone, color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = m.active, onCheckedChange = { on ->
                        scope.launch { try { Api.update("team_members", "user_id=eq.${enc(m.userId)}", JSONObject().put("active", on)); load() } catch (e: Exception) { toast(ctx, errText(e)) } }
                    })
                }
            }
        }
        Section("Add Member") {
            Text("Step 1: Create the login in Supabase > Authentication > Add user (email + password).\nStep 2: Fill this form.", color = Muted, fontSize = 12.sp)
            Field(email, { email = it }, "Login email")
            Field(name, { name = it }, "Name")
            ChipRow(listOf("field" to "Field Team", "senior" to "Senior Team", "ceo" to "CEO"), role) { role = it }
            Field(phone, { v -> phone = v.filter { it.isDigit() }.take(10) }, "Official call number", number = true)
            BigButton("Add to Team", {
                if (email.isBlank() || name.isBlank()) { toast(ctx, "Enter email and name"); return@BigButton }
                scope.launch {
                    try {
                        Api.rpc("ceo_add_member", JSONObject().put("p_email", email).put("p_name", name).put("p_role", role).put("p_call_phone", phone))
                        toast(ctx, "$name added"); email = ""; name = ""; phone = ""; load()
                    } catch (e: Exception) { toast(ctx, errText(e)) }
                }
            })
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Logout") }
    }
}
