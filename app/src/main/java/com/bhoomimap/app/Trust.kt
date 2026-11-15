package com.bhoomimap.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

val PROMISE = listOf(
    "No subscription. No advance fee. Ever.",
    "Commission only after registry, with a GST invoice.",
    "Your number is seen by only 1 team member – never sold, never shared with dealers.",
    "Max 2 calls per enquiry. Tap Stop Calls anytime.",
    "Every plot visited by our team. We always show who listed it.",
    "Every complaint gets a ticket. Reply within 48 hours.",
)

val BUYER_RULES = listOf(
    "Talk about plots only through BhoomiMap. Do not give your number to unknown people who say they are from BhoomiMap – check with Verify Caller.",
    "Never pay a token or booking amount before you check the papers.",
    "Pay land money only to the owner, by bank, at agreement or registry.",
    "Do not share unlocked sizes, photos or location with others.",
    "Report anyone who asks for money in BhoomiMap's name.",
)

val SELLER_RULES = listOf(
    "Listing is free. Commission only after the sale, as per your signed agreement.",
    "Give true papers and details. Wrong details = listing removed.",
    "Do not put your phone number in photos or details. All enquiries come through our team.",
    "Do not take a token from a buyer without a written agreement.",
    "A buyer introduced by BhoomiMap stays a BhoomiMap deal, even if you meet directly.",
)

val AGENT_RULES = listOf(
    "Must be RERA registered and approved by BhoomiMap.",
    "Never ask a buyer or owner for any advance.",
    "Do not contact owners directly – only with our team.",
    "Your 70% share is paid only from the BhoomiMap company account.",
)

@Composable
fun PromiseChips() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("No Subscription", "No Advance Fee", "Number Private").forEach {
            Text(it, color = Color(0xFF14502F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(Color(0xFFE8F3EC), RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
        }
    }
}

@Composable
fun FraudAlert() {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFFDECEC), RoundedCornerShape(14.dp))
            .border(2.dp, Color(0xFFE79A9A), RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text(
            "Fraud Alert: BhoomiMap never asks for money before the deal. If anyone asks for a token, booking fee, OTP or UPI PIN in our name – stop and check Verify Caller in My Enquiry.",
            color = Color(0xFF5C1515), fontSize = 14.sp, lineHeight = 20.sp,
        )
    }
}

@Composable
fun RulesScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ground)) {
        LegalHeader("Promise & Rules", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FraudAlert()
            Column(
                Modifier.fillMaxWidth().background(Green, RoundedCornerShape(14.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("The BhoomiMap Promise", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                PROMISE.forEachIndexed { i, p -> Text("${i + 1}. $p", color = Color.White, fontSize = 14.sp, lineHeight = 20.sp) }
            }
            BulletCard("Rules for Buyers", BUYER_RULES, mark = "•")
            BulletCard("Rules for Sellers (Owners)", SELLER_RULES, mark = "•")
            BulletCard("Rules for Partner Agents", AGENT_RULES, mark = "•")
            Text("Breaking these rules can lead to account block. $DISCLAIMER", fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun EnquiryScreen(lead: Lead, listing: Listing?, onBack: () -> Unit, onChanged: () -> Unit, onRules: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var caller by remember { mutableStateOf<Caller?>(null) }
    var views by remember { mutableStateOf<List<NumberView>>(emptyList()) }
    var tickets by remember { mutableStateOf<List<MyTicket>>(emptyList()) }
    var stop by remember(lead.id) { mutableStateOf(lead.stopCalls) }
    var newTicket by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    fun loadAll() {
        scope.launch {
            try {
                caller = Repo.myCaller(lead.id)
                views = Repo.numberViews(lead.id)
                tickets = Repo.myTickets()
            } catch (_: Exception) {
            }
        }
    }
    LaunchedEffect(lead.id) { loadAll() }

    Column(Modifier.fillMaxSize().background(Ground)) {
        LegalHeader("My Enquiry", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InfoCard {
                if (listing != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(listing.listedByLabel, Color(0xFFE8F3EC), Color(0xFF14502F))
                        if (listing.verified) Tag("Checked by Team", Color(0xFFEEF0EB), Color(0xFF3A4038))
                    }
                    Text(listing.title.ifBlank { Fmt.shortArea(listing.areaSqft) } + " · " + listing.place, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Text("Status: " + Fmt.status(lead.status), color = Color(0xFF8A4B00), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp))
                    .border(2.dp, Green, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Verify Caller", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val c = caller
                if (c == null) {
                    Text("A team member will be given to your enquiry soon. Until then, nobody from BhoomiMap will call you.", fontSize = 14.sp, color = Muted)
                } else {
                    Text("${c.name} – your BhoomiMap team member", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (c.phone.isNotBlank()) Text("Calls only from: +91 ${c.phone}", fontSize = 14.sp)
                    Text("Only this person will call you about this plot. Anyone else saying \"BhoomiMap\" is not us.", fontSize = 13.sp, color = Muted)
                }
            }

            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stop Calls", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Calls used: ${lead.callAttempts} of 2. Turn on to stop all calls.", fontSize = 13.sp, color = Muted)
                    }
                    Switch(checked = stop, onCheckedChange = { on ->
                        stop = on
                        scope.launch {
                            try {
                                Repo.setStopCalls(lead.id, on)
                                Toast.makeText(ctx, if (on) "Calls stopped" else "Calls allowed again", Toast.LENGTH_SHORT).show()
                                onChanged()
                            } catch (_: Exception) {
                                stop = !on
                                Toast.makeText(ctx, "Could not change. Try again.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                }
            }

            InfoCard {
                Text("Who Saw My Number", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (views.isEmpty()) Text("Nobody has opened your number yet.", fontSize = 14.sp, color = Muted)
                views.forEach { v ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(v.viewer, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(v.at.take(16).replace('T', ' '), fontSize = 12.sp, color = Muted)
                    }
                }
                Text("No one else can see your number. Dealers and agents never get it.", fontSize = 13.sp, color = Muted)
            }

            InfoCard {
                Text("My Tickets", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (tickets.isEmpty()) Text("No tickets yet.", fontSize = 14.sp, color = Muted)
                tickets.forEach { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#BM-${t.id} · " + t.message.take(40), fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (t.status == "solved") Tag("Solved", Color(0xFFE8F3EC), Color(0xFF14502F))
                            else Tag("Reply in 48h", Color(0xFFFFF4E5), Color(0xFF8A4B00))
                        }
                        if (t.reply.isNotBlank()) Text("Reply: " + t.reply, fontSize = 13.sp, color = Green)
                    }
                }
                OutlinedTextField(
                    value = newTicket, onValueChange = { newTicket = it.take(1000) },
                    label = { Text("New Ticket – tell us the problem") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (newTicket.trim().length < 5) {
                            Toast.makeText(ctx, "Please write a few words.", Toast.LENGTH_SHORT).show()
                        } else {
                            sending = true
                            scope.launch {
                                try {
                                    Repo.report(newTicket.trim(), "problem", lead.listingId)
                                    newTicket = ""
                                    Toast.makeText(ctx, "Ticket created. Reply within 48 hours.", Toast.LENGTH_LONG).show()
                                    loadAll()
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "Could not send. Try again.", Toast.LENGTH_SHORT).show()
                                }
                                sending = false
                            }
                        }
                    },
                    enabled = !sending, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                ) { Text("New Ticket") }
            }
            OutlinedButton(onClick = onRules, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Promise & Rules")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Opens the phone dialer, used by Verify Caller later if needed. */
fun dial(ctx: android.content.Context, phone: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) } catch (_: Exception) {}
}
