package com.localshare.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localshare.app.ui.QrCode

private const val UPI_ID = "9813986600@fam"
private val AMOUNTS = listOf(1, 2, 5, 10)

private fun upiLink(amount: Int?) =
    "upi://pay?pa=$UPI_ID&pn=DEMON%20GOD&cu=INR&tn=LocalShare" + (amount?.let { "&am=$it" } ?: "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DonateDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var amount by remember { mutableStateOf<Int?>(5) }
    var note by remember { mutableStateOf<String?>(null) }
    val link = upiLink(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support LocalShare") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Every rupee counts, even ₹1. Pick an amount, then scan with any UPI app or tap the button.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AMOUNTS.forEach { a -> FilterChip(amount == a, { amount = a }, { Text("₹$a") }) }
                    FilterChip(amount == null, { amount = null }, { Text("Any") })
                }
                QrCode(link, 200.dp, if (amount == null) "UPI QR code, you choose the amount" else "UPI QR code for ₹$amount")
                Text(UPI_ID, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ clip.setText(AnnotatedString(UPI_ID)); note = "UPI ID copied" }, Modifier.heightIn(min = 48.dp)) { Text("Copy ID") }
                    Button({
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                        catch (e: ActivityNotFoundException) { note = "No UPI app found. Scan the code from another phone." }
                    }, Modifier.heightIn(min = 48.dp)) { Text(if (amount == null) "Pay with UPI" else "Pay ₹$amount") }
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}
