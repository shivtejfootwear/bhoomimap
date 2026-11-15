package com.bhoomimap.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

// ---------- Models ----------

data class Listing(
    val id: String,
    val title: String,
    val kind: String, // plot | farm
    val place: String,
    val areaSqft: Double,
    val facing: String,
    val roadFt: Double,
    val verified: Boolean,
    val boundary: List<LatLng>,
    val createdAt: String = "",
) {
    val center: LatLng
        get() = LatLng(boundary.map { it.latitude }.average(), boundary.map { it.longitude }.average())
    val kindLabel: String get() = if (kind == "farm") "FARM LAND" else "PLOT"
}

data class ListingDetail(
    val front: Double,
    val back: Double,
    val left: Double,
    val right: Double,
    val ownerPhotos: List<String>,
    val teamPhotos: List<String>,
)

data class Lead(val listingId: String, val status: String)

data class Agent(val id: String, val name: String, val area: String, val deals: Int)

data class LandAlert(
    val id: String,
    val place: String,
    val kind: String, // all | plot | farm
    val minSqft: Double,
    val maxSqft: Double, // 0 = no limit
    val createdAt: String,
) {
    fun matches(l: Listing): Boolean =
        (place.isBlank() || l.place.contains(place.trim(), ignoreCase = true) || l.title.contains(place.trim(), ignoreCase = true)) &&
            (kind == "all" || kind == l.kind) &&
            l.areaSqft >= minSqft && (maxSqft <= 0 || l.areaSqft <= maxSqft)

    fun isNew(l: Listing): Boolean = l.createdAt > createdAt
}

/** Size filter used on the map and in alerts. maxSqft 0 = no limit. */
data class SizeRange(val key: String, val label: String, val minSqft: Double, val maxSqft: Double)

val SIZE_RANGES = listOf(
    SizeRange("any", "Any Size", 0.0, 0.0),
    SizeRange("s", "Under 10 Guntha", 0.0, 10890.0),
    SizeRange("m", "10 Guntha – 1 Acre", 10890.0, 43560.0),
    SizeRange("l", "1 – 5 Acre", 43560.0, 217800.0),
    SizeRange("xl", "5+ Acre", 217800.0, 0.0),
)

fun SizeRange.has(v: Double) = v >= minSqft && (maxSqft <= 0 || v < maxSqft)

/** Distance in km between two points. */
fun distanceKm(a: LatLng, b: LatLng): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(h))
}

// ---------- Formatting ----------

object Fmt {
    private val nf = NumberFormat.getNumberInstance(Locale("en", "IN"))
    fun num(v: Double): String = if (v % 1.0 == 0.0) nf.format(v.toLong()) else String.format(Locale.US, "%.2f", v)
    fun sqft(v: Double) = nf.format(Math.round(v)) + " sq ft"
    fun guntha(v: Double) = String.format(Locale.US, "%.2f", v / 1089.0) + " Guntha"
    fun acre(v: Double) = String.format(Locale.US, "%.2f", v / 43560.0) + " Acre"
    fun shortArea(v: Double): String = when {
        v >= 21780 -> acre(v)
        v >= 1089 -> guntha(v)
        else -> sqft(v)
    }
    fun ft(v: Double) = num(v) + " ft"
    fun status(s: String) = when (s) {
        "new" -> "Waiting for Team Call"
        "called" -> "Team Called"
        "unlocked" -> "Details Unlocked"
        "visit" -> "Site Visit Booked"
        "closed" -> "Closed"
        else -> s.replaceFirstChar { it.uppercase() }
    }
}

// ---------- Supabase REST ----------

class ApiException(val code: Int, val body: String) : Exception("HTTP $code: $body")

object Supa {
    val url: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key: String = BuildConfig.SUPABASE_ANON_KEY
    val configured: Boolean get() = url.startsWith("https://") && key.isNotBlank()

    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        prefer: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val c = URL(url + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("apikey", key)
            c.setRequestProperty("Authorization", "Bearer " + (token ?: key))
            c.setRequestProperty("Content-Type", "application/json")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, text)
            text
        } finally {
            c.disconnect()
        }
    }
}

// ---------- Session / Google login ----------

object Session {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("bhoomimap_session", Context.MODE_PRIVATE)
    }

    private fun get(k: String): String? = prefs.getString(k, null)
    private fun put(k: String, v: String?) = prefs.edit().putString(k, v).apply()

    val loggedIn: Boolean get() = get("refresh") != null
    val userId: String? get() = get("user_id")
    val email: String? get() = get("email")
    val name: String? get() = get("name")
    var termsAccepted: Boolean
        get() = prefs.getBoolean("terms_v1", false)
        set(v) = prefs.edit().putBoolean("terms_v1", v).apply()
    var skippedLogin: Boolean
        get() = prefs.getBoolean("skipped", false)
        set(v) = prefs.edit().putBoolean("skipped", v).apply()

    private fun save(j: JSONObject) {
        put("access", j.getString("access_token"))
        put("refresh", j.getString("refresh_token"))
        put("expires", ((System.currentTimeMillis() / 1000) + j.optLong("expires_in", 3600)).toString())
        j.optJSONObject("user")?.let { u ->
            put("user_id", u.optString("id"))
            put("email", u.optString("email"))
            val meta = u.optJSONObject("user_metadata")
            val n = meta?.optString("full_name").orEmpty().ifBlank { meta?.optString("name").orEmpty() }
            if (n.isNotBlank()) put("name", n)
        }
    }

    /** Valid access token, refreshed if needed. Null when logged out. */
    suspend fun token(): String? {
        if (!loggedIn) return null
        val exp = get("expires")?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() / 1000 > exp - 60) {
            try {
                val r = Supa.request(
                    "POST", "/auth/v1/token?grant_type=refresh_token",
                    JSONObject().put("refresh_token", get("refresh")).toString()
                )
                save(JSONObject(r))
            } catch (e: ApiException) {
                if (e.code in 400..499) logout()
                return null
            }
        }
        return get("access")
    }

    fun logout() {
        val skipped = skippedLogin
        val terms = termsAccepted
        prefs.edit().clear().apply()
        skippedLogin = skipped
        termsAccepted = terms
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun googleSignIn(activity: Activity) {
        require(BuildConfig.WEB_CLIENT_ID.isNotBlank()) { "Google login is not set up yet." }
        require(Supa.configured) { "Server is not connected yet." }
        val rawNonce = UUID.randomUUID().toString()
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.WEB_CLIENT_ID)
            .setNonce(sha256(rawNonce))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        val body = JSONObject()
            .put("provider", "google")
            .put("id_token", google.idToken)
            .put("nonce", rawNonce)
        val r = Supa.request("POST", "/auth/v1/token?grant_type=id_token", body.toString())
        save(JSONObject(r))
        if (name == null) google.displayName?.let { put("name", it) }
    }
}

// ---------- Repository ----------

object Repo {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }

    suspend fun listings(): List<Listing> {
        val r = Supa.request(
            "GET",
            "/rest/v1/listings?select=id,title,kind,place,area_sqft,facing,road_width_ft,verified,boundary,created_at&status=eq.live&district=eq.Solapur&order=created_at.desc"
        )
        val arr = JSONArray(r)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val b = o.optJSONArray("boundary") ?: return@mapNotNull null
            val pts = (0 until b.length()).map { k ->
                val p = b.getJSONArray(k)
                LatLng(p.getDouble(0), p.getDouble(1))
            }
            if (pts.size < 3) return@mapNotNull null
            Listing(
                id = o.getString("id"),
                title = o.optString("title"),
                kind = o.optString("kind", "plot"),
                place = o.optString("place"),
                areaSqft = o.optDouble("area_sqft", 0.0),
                facing = o.optString("facing").takeIf { it != "null" }.orEmpty(),
                roadFt = o.optDouble("road_width_ft", 0.0).let { if (it.isNaN()) 0.0 else it },
                verified = o.optBoolean("verified"),
                boundary = pts,
                createdAt = o.optString("created_at"),
            )
        }
    }

    /** Returns null when not unlocked for this user (row hidden by server rules). */
    suspend fun detail(listingId: String): ListingDetail? {
        val t = Session.token() ?: return null
        val r = Supa.request("GET", "/rest/v1/listing_details?listing_id=eq.${enc(listingId)}&select=*", token = t)
        val arr = JSONArray(r)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        fun d(k: String) = o.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
        return ListingDetail(
            d("front_ft"), d("back_ft"), d("left_ft"), d("right_ft"),
            strList(o.optJSONArray("owner_photos")), strList(o.optJSONArray("team_photos"))
        )
    }

    suspend fun myLeads(): List<Lead> {
        val t = Session.token() ?: return emptyList()
        val arr = JSONArray(Supa.request("GET", "/rest/v1/leads?select=listing_id,status", token = t))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Lead(o.getString("listing_id"), o.optString("status", "new"))
        }
    }

    suspend fun sendInterest(listingId: String, name: String, phone: String) {
        val t = Session.token() ?: throw IllegalStateException("Please login first.")
        val body = JSONObject().put("listing_id", listingId).put("buyer_name", name).put("buyer_phone", phone)
        Supa.request("POST", "/rest/v1/leads", body.toString(), t, "return=minimal")
    }

    suspend fun savedIds(): Set<String> {
        val t = Session.token() ?: return emptySet()
        val arr = JSONArray(Supa.request("GET", "/rest/v1/saved?select=listing_id", token = t))
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("listing_id") }.toSet()
    }

    suspend fun setSaved(listingId: String, saved: Boolean) {
        val t = Session.token() ?: throw IllegalStateException("Please login first.")
        if (saved) {
            Supa.request(
                "POST", "/rest/v1/saved",
                JSONObject().put("listing_id", listingId).toString(), t,
                "return=minimal,resolution=ignore-duplicates"
            )
        } else {
            Supa.request("DELETE", "/rest/v1/saved?listing_id=eq.${enc(listingId)}", token = t, prefer = "return=minimal")
        }
    }

    suspend fun report(message: String) {
        val t = Session.token() ?: throw IllegalStateException("Please login first.")
        Supa.request(
            "POST", "/rest/v1/support_requests",
            JSONObject().put("message", message).toString(), t, "return=minimal"
        )
    }

    suspend fun agents(): List<Agent> {
        val arr = JSONArray(Supa.request("GET", "/rest/v1/agents?select=id,name,area,deals&active=eq.true&order=deals.desc&limit=20"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Agent(o.getString("id"), o.optString("name"), o.optString("area"), o.optInt("deals", 0))
        }
    }

    suspend fun alerts(): List<LandAlert> {
        val t = Session.token() ?: return emptyList()
        val arr = JSONArray(Supa.request("GET", "/rest/v1/alerts?select=*&order=created_at.desc", token = t))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            fun d(k: String) = o.optDouble(k, 0.0).let { v -> if (v.isNaN()) 0.0 else v }
            LandAlert(o.getString("id"), o.optString("place"), o.optString("kind", "all"), d("min_sqft"), d("max_sqft"), o.optString("created_at"))
        }
    }

    suspend fun addAlert(place: String, kind: String, size: SizeRange) {
        val t = Session.token() ?: throw IllegalStateException("Please login first.")
        val body = JSONObject().put("place", place.trim()).put("kind", kind)
            .put("min_sqft", size.minSqft).put("max_sqft", size.maxSqft)
        Supa.request("POST", "/rest/v1/alerts", body.toString(), t, "return=minimal")
    }

    suspend fun deleteAlert(id: String) {
        val t = Session.token() ?: return
        Supa.request("DELETE", "/rest/v1/alerts?id=eq.${enc(id)}", token = t, prefer = "return=minimal")
    }

    suspend fun sendSellRequest(name: String, phone: String, place: String, kind: String, area: String, note: String) {
        val t = Session.token() ?: throw IllegalStateException("Please login first.")
        val body = JSONObject().put("owner_name", name).put("phone", phone).put("place", place)
            .put("kind", kind).put("area_text", area).put("note", note)
        Supa.request("POST", "/rest/v1/sell_requests", body.toString(), t, "return=minimal")
    }

    suspend fun requestAccountDeletion() {
        val t = Session.token() ?: return
        Supa.request(
            "POST", "/rest/v1/account_deletion_requests", "{}", t,
            "return=minimal,resolution=ignore-duplicates"
        )
    }
}
