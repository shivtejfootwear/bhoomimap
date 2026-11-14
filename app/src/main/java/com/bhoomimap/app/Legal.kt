@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bhoomimap.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

const val TAGLINE = "Land on the map. Checked by our team."

const val DISCLAIMER =
    "BhoomiMap is a property listing and broker service. We do a preliminary site and document check. " +
        "We do not guarantee ownership, title or measurements, and we do not handle land payments. " +
        "Please verify documents with your own lawyer before paying."

val HOW_IT_WORKS = listOf(
    "Owner lists land" to "Our Field Team visits the site.",
    "We measure & check" to "4 sides, GPS photos, 7/12 name match.",
    "You show interest" to "Our team calls you about the plot.",
    "Visit together" to "Our team member comes with you.",
    "Check papers, then pay" to "Pay the owner only by bank, at agreement or registry.",
)

object LegalText {
    const val UPDATED = "Last updated: 15 November 2026"

    val terms = listOf(
        "1. About BhoomiMap" to "BhoomiMap is a property listing and broker service for land and plots, starting in Solapur district. We help buyers find land, arrange site visits and connect buyers with owners. We are not the owner or seller of any land shown in the app.",
        "2. Preliminary check only" to "Our team visits listed land, takes measurements and photos, and matches the owner name on the 7/12 record. This is a preliminary check only. We do not guarantee ownership, title, measurements, boundaries, zone, approvals or the absence of loans or disputes.",
        "3. Your own checks" to "Before paying any money, you must check the documents yourself, including the latest 7/12 and 8A extracts, encumbrance search, NA order or zone, and measurement by a government surveyor, and take advice from your own lawyer.",
        "4. Payments" to "BhoomiMap never collects or holds land money. Land money must be paid by the buyer directly to the owner by bank transfer, at the time of agreement or registry. Never pay cash to anyone, including BhoomiMap staff.",
        "5. Commission" to "BhoomiMap charges a brokerage commission only when a deal is completed, as agreed in writing with the buyer and the owner. Commission is paid only to the BhoomiMap company bank account against a GST invoice.",
        "6. Details you unlock" to "Side measurements, photos, 3D view and other details are shared with you personally after our team calls you. You must not copy, share or publish them, or contact the owner to deal without BhoomiMap.",
        "7. Partner agents" to "Some buyers are brought by registered partner agents. Partner agents are independent businesses and are responsible for their own conduct.",
        "8. Limit of liability" to "To the extent allowed by law, BhoomiMap's total liability for any deal is limited to the commission actually received by BhoomiMap for that deal. BhoomiMap is not responsible for losses caused by documents, statements or acts of owners, buyers or third parties.",
        "9. Your account" to "Use correct details and keep your account safe. We may block accounts that give false details, misuse the app or try to bypass BhoomiMap.",
        "10. Changes and law" to "We may update these terms; the app will show the new date. These terms are governed by the laws of India, and courts at Solapur, Maharashtra have jurisdiction.",
    )

    val privacy = listOf(
        "1. What we collect" to "Your name and email from Google login; the name and mobile number you enter when you show interest; plots you save or show interest in; and basic app usage and error data needed to run the app.",
        "2. Why we use it" to "Only to contact you about plots you choose, arrange site visits, keep records of deals, prevent fraud, and improve the app. We do not sell your data and we do not use it for third-party advertising.",
        "3. Who can see it" to "Your number is visible only to authorised BhoomiMap team members handling your enquiry. Owner details and prices are never shown in the buyer app.",
        "4. Where it is stored" to "Data is stored on secure cloud servers with access rules, so each user can see only their own data. Team access is logged.",
        "5. How long we keep it" to "We keep your data while your account is active and as long as needed for deal records and the law. After you delete your account, we delete or anonymise your data within 30 days, except records we must keep by law.",
        "6. Your choices" to "You can see your interests in Profile, ask us to correct your data, withdraw consent, or delete your account from Profile > Delete Account.",
        "7. Complaints" to "For any privacy question or complaint, use Profile > Safety & Help > Report a Problem. We reply within 7 days and resolve within 30 days.",
    )
}

@Composable
fun LegalHeader(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
    )
}

@Composable
fun LegalScreen(title: String, sections: List<Pair<String, String>>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ground)) {
        LegalHeader(title, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(LegalText.UPDATED, fontSize = 12.sp, color = Muted)
            sections.forEach { (h, b) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(h, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(b, fontSize = 14.sp, color = Color(0xFF2E332C), lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun BulletCard(title: String, items: List<String>, bg: Color = Color.White, fg: Color = Color(0xFF1B1F1A), mark: String = "✓") {
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg)
        items.forEach { Text("$mark  $it", fontSize = 14.sp, color = fg, lineHeight = 20.sp) }
    }
}

@Composable
fun SafetyScreen(loggedIn: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Ground)) {
        LegalHeader("Safety & Help", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BulletCard(
                "What Our Team Does",
                listOf(
                    "Visits and measures every listed plot",
                    "Takes GPS-tagged site photos",
                    "Matches the owner name on the 7/12 (preliminary check)",
                    "Comes with you on the site visit",
                    "Shares your number only with our team",
                ),
            )
            BulletCard(
                "Safe Payment Rules",
                listOf(
                    "Pay land money only to the owner, by bank transfer, at agreement or registry.",
                    "Never pay cash to anyone, including BhoomiMap staff.",
                    "BhoomiMap commission is paid only to the company bank account, with a GST invoice.",
                    "Our staff will never ask for your OTP, UPI PIN or an advance \"booking fee\".",
                ),
                bg = Color(0xFFFDECEC), fg = Color(0xFF5C1515), mark = "•",
            )
            BulletCard(
                "Before You Pay – Check Yourself",
                listOf(
                    "Latest 7/12 and 8A extract",
                    "Encumbrance (loan) search",
                    "NA order or zone (for plots)",
                    "Measurement by a government surveyor",
                    "Review by your own lawyer",
                ),
                mark = "•",
            )
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Report a Problem", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (!loggedIn) {
                    Text("Please login to send a report.", fontSize = 14.sp, color = Muted)
                } else {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it.take(1000) },
                        label = { Text("Tell us what happened") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (message.trim().length < 5) {
                                Toast.makeText(ctx, "Please write a few words.", Toast.LENGTH_SHORT).show()
                            } else {
                                sending = true
                                scope.launch {
                                    try {
                                        Repo.report(message.trim())
                                        message = ""
                                        Toast.makeText(ctx, "Report sent. We reply within 7 days.", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "Could not send. Please try again.", Toast.LENGTH_LONG).show()
                                    }
                                    sending = false
                                }
                            }
                        },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(if (sending) "Sending…" else "Send Report") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Terms of Use", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onOpen("terms") }.padding(vertical = 10.dp))
                Text("Privacy Policy", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onOpen("privacy") }.padding(vertical = 10.dp))
            }
            Text(DISCLAIMER, fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ConsentChecks(
    terms: Boolean,
    onTerms: (Boolean) -> Unit,
    broker: Boolean,
    onBroker: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = terms, onCheckedChange = onTerms)
            Column(Modifier.padding(top = 12.dp)) {
                Text("I agree to the Terms of Use and Privacy Policy.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Read Terms", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onOpen("terms") }.padding(vertical = 6.dp))
                    Text("Read Privacy", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onOpen("privacy") }.padding(vertical = 6.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = broker, onCheckedChange = onBroker)
            Text(
                "I understand BhoomiMap is a listing and broker service. It does a preliminary check only and does not guarantee land title.",
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var terms by remember { mutableStateOf(false) }
    var broker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you login") },
        text = { ConsentChecks(terms, { terms = it }, broker, { broker = it }, onOpen) },
        confirmButton = {
            Button(onClick = onAccept, enabled = terms && broker) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun HowItWorksCard() {
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE1E4DD), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("How BhoomiMap Works", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        HOW_IT_WORKS.forEachIndexed { i, (t, d) ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(26.dp).background(Color(0xFFE8F3EC), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF14502F)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(t, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(d, fontSize = 13.sp, color = Muted)
                }
            }
        }
    }
}
