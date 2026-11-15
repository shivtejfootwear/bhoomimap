@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bhoomimap.team

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val SOLAPUR = LatLng(17.6599, 75.9064)

@Composable
fun PhotoStrip(urls: List<String>) {
    if (urls.isEmpty()) { Text("No photos.", color = Muted, fontSize = 13.sp); return }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        urls.forEach { u ->
            AsyncImage(model = u, contentDescription = "Plot photo", contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 140.dp, height = 105.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE1E4DD)))
        }
    }
}

// ---------- image helpers ----------

private fun loadScaled(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val rotation = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (_: Exception) { 0f }
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
    val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

private fun stamp(src: Bitmap, lines: List<String>): Bitmap {
    val b = src.copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(b)
    val size = b.width / 32f
    val bg = Paint().apply { color = android.graphics.Color.argb(150, 0, 0, 0) }
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = size }
    val h = size * 1.4f * lines.size + size
    c.drawRect(0f, b.height - h, b.width.toFloat(), b.height.toFloat(), bg)
    lines.forEachIndexed { i, s -> c.drawText(s, size * 0.6f, b.height - h + size * 1.4f * (i + 1), tp) }
    return b
}

private fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()

@SuppressLint("MissingPermission")
private fun lastLocation(ctx: Context): Location? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
        .maxByOrNull { it.time }
}

// =============================== FIELD TASKS ===============================

@Composable
fun FieldTasksScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var listings by remember { mutableStateOf<Map<String, TListing>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var openId by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            try {
                tasks = Repo.tasks("&status=eq.open")
                listings = Repo.listings().associateBy { it.id }
            } catch (e: Exception) { toast(ctx, errText(e)) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val open = tasks.firstOrNull { it.id == openId }
    val openListing = open?.let { listings[it.listingId] }
    if (open != null && openListing != null) {
        FieldTaskScreen(open, openListing, onBack = { openId = null }, onDone = { openId = null; load() })
        return
    }
    Page {
        Loading(loading, tasks.isEmpty(), "No open site visits. New tasks from the Senior Team appear here.")
        tasks.forEach { t ->
            val l = listings[t.listingId]
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable { openId = t.id }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Site Visit – " + (l?.name ?: "Plot"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text((l?.place ?: "") + " · Given " + Fmt.date(t.createdAt), color = Muted, fontSize = 13.sp)
                if (t.note.isNotBlank()) Text("Note: " + t.note, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun FieldTaskScreen(t: Task, l: TListing, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var owner by remember { mutableStateOf<JSONObject?>(null) }
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var left by remember { mutableStateOf("") }
    var right by remember { mutableStateOf("") }
    var road by remember { mutableStateOf(if (l.road > 0) Fmt.num(l.road) else "") }
    var facing by remember { mutableStateOf(l.facing.ifBlank { "East" }) }
    var nameMatch by remember { mutableStateOf(false) }
    var areaMatch by remember { mutableStateOf(false) }
    var noDispute by remember { mutableStateOf(false) }
    val teamPhotos = remember { mutableStateListOf<String>() }
    val ownerPhotos = remember { mutableStateListOf<String>() }
    val previews = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun upload(uri: Uri, team: Boolean) {
        busy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    val bmp = loadScaled(ctx, uri) ?: throw IllegalStateException("Could not read photo")
                    if (team) {
                        val loc = lastLocation(ctx)
                        val time = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
                        jpeg(stamp(bmp, listOf("BhoomiMap Team · $time",
                            if (loc != null) String.format(Locale.US, "GPS: %.6f, %.6f", loc.latitude, loc.longitude) else "GPS: not available",
                            l.place)))
                    } else jpeg(bmp)
                }
                val path = "${l.id}/${if (team) "team" else "owner"}_${System.currentTimeMillis()}.jpg"
                Api.uploadPhoto(path, bytes)
                if (team) teamPhotos.add(path) else ownerPhotos.add(path)
                Api.signedPhoto(path)?.let { previews.add(it) }
            } catch (e: Exception) { toast(ctx, "Upload failed: " + errText(e)) }
            busy = false
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending
        if (ok && f != null) upload(Uri.fromFile(f), true)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) upload(uri, false)
    }

    val f = front.toDoubleOrNull() ?: 0.0
    val b = back.toDoubleOrNull() ?: 0.0
    val lf = left.toDoubleOrNull() ?: 0.0
    val r = right.toDoubleOrNull() ?: 0.0
    val area = ((f + b) / 2) * ((lf + r) / 2)

    Column(Modifier.fillMaxSize()) {
        BackBar("Site Visit – ${l.name}", onBack)
        Page {
            Section("Owner Contact") {
                val o = owner
                if (o == null) {
                    Text("Price is hidden for Field Team. Opening owner contact is recorded.", color = Muted, fontSize = 13.sp)
                    BigButton("Show Owner Contact", {
                        scope.launch { try { owner = Api.rpcJson("task_owner", JSONObject().put("p_task", t.id)) ?: JSONObject() } catch (e: Exception) { toast(ctx, errText(e)) } }
                    }, outlined = true)
                } else {
                    Row2("Owner", o.str("owner_name").ifBlank { "-" })
                    Row2("Phone", o.str("owner_phone").ifBlank { "-" })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            val ph = o.str("owner_phone")
                            if (ph.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+91$ph")))
                        }, modifier = Modifier.weight(1f).height(46.dp)) { Text("Call Owner") }
                        OutlinedButton(onClick = {
                            val c = l.boundary.firstOrNull()
                            if (c != null) {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${c.first},${c.second}?q=${c.first},${c.second}"))) } catch (_: Exception) {}
                            }
                        }, modifier = Modifier.weight(1f).height(46.dp)) { Text("Navigate") }
                    }
                }
            }
            Section("Measure All 4 Sides (ft)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(front, { front = it.filter { c -> c.isDigit() || c == '.' } }, "Front", true, Modifier.weight(1f))
                    Field(back, { back = it.filter { c -> c.isDigit() || c == '.' } }, "Back", true, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(left, { left = it.filter { c -> c.isDigit() || c == '.' } }, "Left", true, Modifier.weight(1f))
                    Field(right, { right = it.filter { c -> c.isDigit() || c == '.' } }, "Right", true, Modifier.weight(1f))
                }
                Field(road, { road = it.filter { c -> c.isDigit() || c == '.' } }, "Road width (ft)", true)
                ChipRow(listOf("East" to "East", "West" to "West", "North" to "North", "South" to "South"), facing) { facing = it }
                if (area > 0) Text("Auto area: ${Fmt.num(Math.round(area).toDouble())} sq ft · ${Fmt.area(area)}", color = Green, fontWeight = FontWeight.SemiBold)
                Text("Listed size: ${Fmt.area(l.area)}", color = Muted, fontSize = 13.sp)
            }
            Section("Photos") {
                Text("Site photos: camera only, with GPS and time stamped. At least 2.", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val file = File(ctx.cacheDir, "photos/p_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
                        pending = file
                        camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file))
                    }, enabled = !busy, modifier = Modifier.weight(1f).height(46.dp)) { Text("Take Photo") }
                    OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f).height(46.dp)) { Text("Owner Photo") }
                }
                Text("Site: ${teamPhotos.size} · Owner: ${ownerPhotos.size}" + if (busy) " · Uploading…" else "", fontSize = 13.sp)
                PhotoStrip(previews)
            }
            Section("Documents Check") {
                CheckLine("7/12 name matches owner", nameMatch) { nameMatch = it }
                CheckLine("Area on 7/12 matches site", areaMatch) { areaMatch = it }
                CheckLine("No dispute / loan entry", noDispute) { noDispute = it }
            }
            BigButton("Submit to Senior Team", {
                if (f <= 0 || b <= 0 || lf <= 0 || r <= 0) { toast(ctx, "Enter all 4 sides"); return@BigButton }
                if (teamPhotos.size < 2) { toast(ctx, "Take at least 2 site photos"); return@BigButton }
                busy = true
                scope.launch {
                    try {
                        Api.rpc("submit_task", JSONObject()
                            .put("p_task", t.id).put("p_front", f).put("p_back", b).put("p_left", lf).put("p_right", r)
                            .put("p_road", road.toDoubleOrNull() ?: 0.0).put("p_facing", facing)
                            .put("p_checks", JSONObject().put("name_match", nameMatch).put("area_match", areaMatch).put("no_dispute", noDispute))
                            .put("p_owner_photos", JSONArray(ownerPhotos.toList())).put("p_team_photos", JSONArray(teamPhotos.toList())))
                        toast(ctx, "Sent to Senior Team"); onDone()
                    } catch (e: Exception) { toast(ctx, errText(e)) }
                    busy = false
                }
            }, enabled = !busy)
        }
    }
}

@Composable
fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 14.sp)
    }
}

// =============================== ADD LISTING (Senior / CEO) ===============================

private fun polygonAreaSqft(pts: List<LatLng>): Double {
    if (pts.size < 3) return 0.0
    val lat0 = Math.toRadians(pts.map { it.latitude }.average())
    val xy = pts.map { Pair(it.longitude * 111_320.0 * Math.cos(lat0), it.latitude * 110_540.0) }
    var s = 0.0
    for (i in xy.indices) {
        val (x1, y1) = xy[i]
        val (x2, y2) = xy[(i + 1) % xy.size]
        s += x1 * y2 - x2 * y1
    }
    return Math.abs(s) / 2.0 * 10.7639
}

private val PHONE_LIKE = Regex("[0-9]{10}")

@Composable
fun AddListingScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pts = remember { mutableStateListOf<LatLng>() }
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("plot") }
    var place by remember { mutableStateOf("") }
    var facing by remember { mutableStateOf("East") }
    var road by remember { mutableStateOf("") }
    var listedBy by remember { mutableStateOf("owner") }
    var areaText by remember { mutableStateOf("") }
    var oName by remember { mutableStateOf("") }
    var oPhone by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(SOLAPUR, 10f) }
    val auto = polygonAreaSqft(pts)

    Column(Modifier.fillMaxSize()) {
        BackBar("Add Listing", onBack)
        Page {
            Text("Tap the corners of the land on the map (at least 3).", color = Muted, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp))) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(mapType = MapType.HYBRID),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
                    onMapClick = { pts.add(it) },
                ) {
                    if (pts.size >= 3) Polygon(points = pts.toList(), fillColor = Color(0x59DC2626), strokeColor = Color(0xFFFF3B3B), strokeWidth = 4f)
                    pts.forEach { p -> Marker(state = MarkerState(position = p)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { if (pts.isNotEmpty()) pts.removeAt(pts.size - 1) }, modifier = Modifier.weight(1f)) { Text("Undo Point") }
                OutlinedButton(onClick = { pts.clear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
            }
            if (auto > 0) Text("Map area: ${Fmt.num(Math.round(auto).toDouble())} sq ft · ${Fmt.area(auto)}", color = Green, fontWeight = FontWeight.SemiBold)
            Section("Public Details (buyers see these)") {
                Field(title, { title = it }, "Title (e.g. Residential Plot 40 × 30 ft)")
                ChipRow(listOf("plot" to "Plot", "farm" to "Farm Land"), kind) { kind = it }
                Field(place, { place = it }, "Place / Village")
                Field(areaText, { areaText = it.filter { c -> c.isDigit() || c == '.' } }, "Area sq ft (empty = map area)", true)
                Field(road, { road = it.filter { c -> c.isDigit() || c == '.' } }, "Road width (ft)", true)
                ChipRow(listOf("East" to "East", "West" to "West", "North" to "North", "South" to "South"), facing) { facing = it }
                ChipRow(listOf("owner" to "Listed by Owner", "agent" to "Listed by Partner Agent"), listedBy) { listedBy = it }
            }
            Section("Private (encrypted, Senior & CEO only)") {
                Field(oName, { oName = it }, "Owner name")
                Field(oPhone, { v -> oPhone = v.filter { it.isDigit() }.take(10) }, "Owner phone", number = true)
                Field(ask, { v -> ask = v.filter { it.isDigit() } }, "Asking price (₹)", number = true)
                Field(min, { v -> min = v.filter { it.isDigit() } }, "Minimum price (₹)", number = true)
            }
            BigButton("Save as Draft", {
                val area = areaText.toDoubleOrNull() ?: auto
                when {
                    pts.size < 3 -> { toast(ctx, "Tap at least 3 corners on the map"); return@BigButton }
                    place.isBlank() -> { toast(ctx, "Enter the place"); return@BigButton }
                    area <= 0 -> { toast(ctx, "Enter the area"); return@BigButton }
                    PHONE_LIKE.containsMatchIn(title) || PHONE_LIKE.containsMatchIn(place) -> {
                        toast(ctx, "Phone numbers are not allowed in public details"); return@BigButton
                    }
                }
                busy = true
                scope.launch {
                    try {
                        val boundary = JSONArray(pts.map { JSONArray().put(it.latitude).put(it.longitude) })
                        val row = Api.insert("listings", JSONObject().put("title", title.trim()).put("kind", kind).put("place", place.trim())
                            .put("area_sqft", Math.round(area)).put("facing", facing).put("road_width_ft", road.toDoubleOrNull() ?: 0.0)
                            .put("listed_by", listedBy).put("boundary", boundary).put("status", "draft"), returning = true)
                        val id = row?.optJSONObject(0)?.optString("id") ?: throw IllegalStateException("Saved, but could not read new listing")
                        if (oName.isNotBlank() || oPhone.isNotBlank() || ask.isNotBlank()) {
                            Api.rpc("set_private", JSONObject().put("p_listing", id).put("p_owner_name", oName).put("p_owner_phone", oPhone)
                                .put("p_asking", ask.toDoubleOrNull() ?: 0.0).put("p_min", min.toDoubleOrNull() ?: 0.0))
                        }
                        toast(ctx, "Saved as draft. Now send the Field Team."); onSaved()
                    } catch (e: Exception) { toast(ctx, errText(e)) }
                    busy = false
                }
            }, enabled = !busy)
        }
    }
}
