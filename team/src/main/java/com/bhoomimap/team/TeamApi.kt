package com.bhoomimap.team

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

class ApiException(val code: Int, val body: String) : Exception(
    try { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("msg") }.ifBlank { "Error $code" } } catch (_: Exception) { "Error $code" }
)

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

object Fmt {
    private val nf = NumberFormat.getNumberInstance(Locale("en", "IN"))
    fun num(v: Double): String = if (v % 1.0 == 0.0) nf.format(v.toLong()) else String.format(Locale.US, "%.2f", v)
    fun rupees(v: Double) = "₹" + nf.format(Math.round(v))
    fun area(v: Double): String = when {
        v >= 21780 -> String.format(Locale.US, "%.2f Acre", v / 43560.0)
        v >= 1089 -> String.format(Locale.US, "%.2f Guntha", v / 1089.0)
        else -> nf.format(Math.round(v)) + " sq ft"
    }
    fun date(iso: String): String = if (iso.length >= 16) iso.substring(0, 10) + " " + iso.substring(11, 16) else iso
}

object Api {
    val url: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key: String = BuildConfig.SUPABASE_ANON_KEY
    val configured: Boolean get() = url.startsWith("https://") && key.isNotBlank()

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("bhoomimap_team", Context.MODE_PRIVATE)
        if (prefs.getString("device_id", null) == null) {
            prefs.edit().putString("device_id", UUID.randomUUID().toString()).apply()
        }
    }

    val deviceId: String get() = prefs.getString("device_id", "") ?: ""
    val deviceLabel: String get() = (Build.MANUFACTURER + " " + Build.MODEL).trim()
    val shortDevice: String get() = deviceId.take(8).uppercase()

    private fun get(k: String): String? = prefs.getString(k, null)
    private fun put(k: String, v: String?) = prefs.edit().putString(k, v).apply()

    val loggedIn: Boolean get() = get("refresh") != null
    val email: String? get() = get("email")

    // ---------- raw HTTP ----------
    suspend fun raw(method: String, path: String, body: String? = null, token: String? = null, prefer: String? = null,
                    contentType: String = "application/json", bytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val c = URL(url + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("apikey", key)
            val bearer = token ?: if (key.startsWith("eyJ")) key else null
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            c.setRequestProperty("Content-Type", contentType)
            c.setRequestProperty("x-device-id", deviceId)
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            val payload = bytes ?: body?.toByteArray()
            if (payload != null) {
                c.doOutput = true
                c.outputStream.use { it.write(payload) }
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

    // ---------- session ----------
    private fun save(j: JSONObject) {
        put("access", j.getString("access_token"))
        put("refresh", j.getString("refresh_token"))
        put("expires", ((System.currentTimeMillis() / 1000) + j.optLong("expires_in", 3600)).toString())
        j.optJSONObject("user")?.let { put("email", it.optString("email")) }
    }

    suspend fun token(): String? {
        if (!loggedIn) return null
        val exp = get("expires")?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() / 1000 > exp - 60) {
            try {
                save(JSONObject(raw("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", get("refresh")).toString())))
            } catch (e: ApiException) {
                if (e.code in 400..499) logout()
                return null
            }
        }
        return get("access")
    }

    private suspend fun t(): String = token() ?: throw IllegalStateException("Please login again.")

    fun userId(): String = claim("sub")

    fun aal(): String = claim("aal")

    private fun claim(name: String): String {
        val a = get("access") ?: return ""
        return try {
            val part = a.split(".")[1]
            JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).optString(name)
        } catch (_: Exception) { "" }
    }

    suspend fun signIn(email: String, password: String) {
        require(configured) { "Server is not connected yet." }
        save(JSONObject(raw("POST", "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password).toString())))
    }

    fun logout() {
        val dev = deviceId
        prefs.edit().clear().apply()
        prefs.edit().putString("device_id", dev).apply()
    }

    // ---------- 2-step (TOTP) ----------
    suspend fun verifiedFactorId(): String? {
        val user = JSONObject(raw("GET", "/auth/v1/user", token = t()))
        val f = user.optJSONArray("factors") ?: return null
        for (i in 0 until f.length()) {
            val o = f.getJSONObject(i)
            if (o.optString("factor_type") == "totp" && o.optString("status") == "verified") return o.getString("id")
        }
        return null
    }

    /** Returns (factorId, secret, otpauth uri) for a new authenticator setup. */
    suspend fun enrollTotp(): Triple<String, String, String> {
        val tok = t()
        val user = JSONObject(raw("GET", "/auth/v1/user", token = tok))
        user.optJSONArray("factors")?.let { f ->
            for (i in 0 until f.length()) {
                val o = f.getJSONObject(i)
                if (o.optString("status") != "verified") {
                    try { raw("DELETE", "/auth/v1/factors/" + o.getString("id"), token = tok) } catch (_: Exception) {}
                }
            }
        }
        val r = JSONObject(raw("POST", "/auth/v1/factors",
            JSONObject().put("factor_type", "totp").put("friendly_name", "Team " + System.currentTimeMillis()).put("issuer", "BhoomiMap Team").toString(),
            token = tok))
        val totp = r.getJSONObject("totp")
        return Triple(r.getString("id"), totp.optString("secret"), totp.optString("uri"))
    }

    suspend fun verifyTotp(factorId: String, code: String) {
        val tok = t()
        val ch = JSONObject(raw("POST", "/auth/v1/factors/$factorId/challenge", "{}", token = tok))
        save(JSONObject(raw("POST", "/auth/v1/factors/$factorId/verify",
            JSONObject().put("challenge_id", ch.getString("id")).put("code", code.trim()).toString(), token = tok)))
    }

    // ---------- REST helpers ----------
    suspend fun rpc(fn: String, args: JSONObject = JSONObject()): String =
        raw("POST", "/rest/v1/rpc/$fn", args.toString(), token = t())

    suspend fun rpcJson(fn: String, args: JSONObject = JSONObject()): JSONObject? {
        val r = rpc(fn, args).trim()
        return if (r.isEmpty() || r == "null") null else JSONObject(r)
    }

    suspend fun list(path: String): JSONArray = JSONArray(raw("GET", "/rest/v1/$path", token = t()))

    suspend fun insert(table: String, body: JSONObject, returning: Boolean = false): JSONArray? {
        val r = raw("POST", "/rest/v1/$table", body.toString(), token = t(), prefer = if (returning) "return=representation" else "return=minimal")
        return if (returning) JSONArray(r) else null
    }

    suspend fun update(table: String, filter: String, body: JSONObject) {
        raw("PATCH", "/rest/v1/$table?$filter", body.toString(), token = t(), prefer = "return=minimal")
    }

    suspend fun delete(table: String, filter: String) {
        raw("DELETE", "/rest/v1/$table?$filter", token = t(), prefer = "return=minimal")
    }

    suspend fun uploadPhoto(path: String, jpeg: ByteArray) {
        raw("POST", "/storage/v1/object/plot-photos/$path", token = t(), contentType = "image/jpeg", bytes = jpeg)
    }

    suspend fun signedPhoto(path: String): String? = try {
        val r = JSONObject(raw("POST", "/storage/v1/object/sign/plot-photos/$path", JSONObject().put("expiresIn", 3600).toString(), token = t()))
        val s = r.optString("signedURL").ifBlank { r.optString("signedUrl") }
        if (s.isBlank()) null else "$url/storage/v1" + (if (s.startsWith("/")) s else "/$s")
    } catch (_: Exception) { null }
}

// ---------- Models ----------

data class TeamStatus(
    val member: Boolean, val name: String, val role: String, val active: Boolean,
    val codeAccepted: Boolean, val aal: String, val deviceApproved: Boolean,
)

data class TListing(
    val id: String, val title: String, val kind: String, val place: String, val area: Double,
    val facing: String, val road: Double, val listedBy: String, val verified: Boolean,
    val status: String, val expiresAt: String, val boundary: List<Pair<Double, Double>>,
) {
    val name: String get() = title.ifBlank { Fmt.area(area) + " · " + place }
    val daysLeft: Long
        get() = try {
            val ms = java.time.OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli() - System.currentTimeMillis()
            ms / 86_400_000L
        } catch (_: Exception) { 0 }
}

data class TLead(
    val id: String, val listingId: String, val buyerName: String, val status: String,
    val assignedTo: String?, val attempts: Int, val stopCalls: Boolean, val createdAt: String,
)

data class Member(val userId: String, val name: String, val role: String, val callPhone: String, val active: Boolean)

data class Task(val id: String, val listingId: String, val assignedTo: String, val status: String, val note: String, val createdAt: String)

data class Ticket(val id: Long, val kind: String, val listingId: String?, val message: String, val status: String, val reply: String, val createdAt: String) {
    val overdue: Boolean
        get() = status == "open" && try {
            System.currentTimeMillis() - java.time.OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() > 48 * 3_600_000L
        } catch (_: Exception) { false }
}

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
fun JSONObject.dbl(k: String): Double = if (isNull(k)) 0.0 else optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }

object Repo {
    suspend fun status(): TeamStatus {
        try {
            Api.rpc("register_device", JSONObject().put("p_device", Api.deviceId).put("p_label", Api.deviceLabel))
        } catch (_: Exception) {
        }
        val o = Api.rpcJson("my_team_status", JSONObject().put("p_device", Api.deviceId)) ?: JSONObject()
        return TeamStatus(
            o.optBoolean("member"), o.str("name"), o.str("role"), o.optBoolean("active"),
            o.optBoolean("code_accepted"), o.str("aal"), o.optBoolean("device_approved"),
        )
    }

    suspend fun listings(): List<TListing> {
        val a = Api.list("listings?select=id,title,kind,place,area_sqft,facing,road_width_ft,listed_by,verified,status,expires_at,boundary&order=created_at.desc")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val b = o.optJSONArray("boundary")
            val pts = if (b == null) emptyList() else (0 until b.length()).map { k -> b.getJSONArray(k).let { p -> p.getDouble(0) to p.getDouble(1) } }
            TListing(o.getString("id"), o.str("title"), o.str("kind"), o.str("place"), o.dbl("area_sqft"), o.str("facing"),
                o.dbl("road_width_ft"), o.str("listed_by"), o.optBoolean("verified"), o.str("status"), o.str("expires_at"), pts)
        }
    }

    suspend fun leads(): List<TLead> {
        val a = Api.list("leads?select=id,listing_id,buyer_name,status,assigned_to,call_attempts,stop_calls,created_at&order=created_at.desc&limit=200")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TLead(o.getString("id"), o.str("listing_id"), o.str("buyer_name"), o.str("status"),
                o.str("assigned_to").ifBlank { null }, o.optInt("call_attempts"), o.optBoolean("stop_calls"), o.str("created_at"))
        }
    }

    suspend fun members(): List<Member> {
        val a = Api.list("team_members?select=user_id,name,role,call_phone,active&order=name")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Member(o.getString("user_id"), o.str("name"), o.str("role"), o.str("call_phone"), o.optBoolean("active"))
        }
    }

    suspend fun tasks(filter: String = ""): List<Task> {
        val a = Api.list("site_tasks?select=id,listing_id,assigned_to,status,note,created_at&order=created_at.desc$filter")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Task(o.getString("id"), o.str("listing_id"), o.str("assigned_to"), o.str("status"), o.str("note"), o.str("created_at"))
        }
    }

    suspend fun tickets(): List<Ticket> {
        val a = Api.list("tickets?select=id,kind,listing_id,message,status,reply,created_at&order=created_at.desc&limit=200")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Ticket(o.getLong("id"), o.str("kind"), o.str("listing_id").ifBlank { null }, o.str("message"), o.str("status"), o.str("reply"), o.str("created_at"))
        }
    }
}
