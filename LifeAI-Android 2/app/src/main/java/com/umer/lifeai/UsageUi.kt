@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

private data class AppRow(val key: String, val label: String, val phone: Long, val laptop: Long) { val total get() = phone + laptop }

@Composable
fun UsageScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var range by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(listOf<AppRow>()) }
    var devices by remember { mutableStateOf(listOf<JSONObject>()) }
    var msg by remember { mutableStateOf("") }; var granted by remember { mutableStateOf(Usage.granted(ctx)) }
    LaunchedEffect(range) {
        while (true) {
            granted = Usage.granted(ctx)
            runCatching {
                if (range == 0 && granted) Usage.sync(ctx)
                val days = intArrayOf(0, 6, 29)[range]
                val a = Sb.get("usage?day=gte.${LocalDate.now().minusDays(days.toLong())}&limit=5000")
                val m = HashMap<String, AppRow>()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i); val k = o.getString("app_key"); val s = o.optLong("seconds"); val prev = m[k] ?: AppRow(k, o.optString("label", k), 0, 0)
                    val lap = o.optString("device") == "laptop"
                    m[k] = prev.copy(label = if (!lap || prev.label == k) o.optString("label", k).ifBlank { k } else prev.label, phone = prev.phone + if (lap) 0 else s, laptop = prev.laptop + if (lap) s else 0)
                }
                rows = m.values.sortedByDescending { it.total }
                val d = Sb.get("devices?order=last_seen.desc&limit=6"); devices = List(d.length()) { d.getJSONObject(it) }
            }.onFailure { msg = it.message ?: "" }
            delay(30_000)
        }
    }
    fun pair(raw: String?) {
        val j = runCatching { JSONObject(raw ?: "") }.getOrNull()
        if (j == null || !j.has("u") || !j.has("k")) { msg = "That QR is not a Life AI pairing code"; return }
        Cfg.url = j.getString("u"); Cfg.key = j.getString("k"); Cfg.pairCode = j.optString("p")
        scope.launch { msg = runCatching { Usage.sync(ctx); "✔ Paired with laptop (code ${Cfg.pairCode})" }.getOrElse { "Saved, but sync failed: ${it.message}" } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Screen time", "Phone + laptop in one place")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Today", "7 days", "30 days").forEachIndexed { i, l -> FilterChip(range == i, { range = i }, { Text(l) }) } }
        val tp = rows.sumOf { it.phone }; val tl = rows.sumOf { it.laptop }
        Usage.current?.let { c -> Panel { Text("📱 Right now: ${c.label} — ${fmtSec(c.seconds)} today", fontWeight = FontWeight.SemiBold, color = Green) } }
        Panel {
            Text("Total screen time", color = Mut, fontSize = 12.sp)
            Text(fmtSec(tp + tl), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            val tot = (tp + tl).coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Card2)) {
                if (tp > 0) Box(Modifier.weight(tp.toFloat() / tot).fillMaxHeight().background(Green))
                if (tl > 0) Box(Modifier.weight(tl.toFloat() / tot).fillMaxHeight().background(Blue))
            }
            Row { Text("📱 Phone ${fmtSec(tp)}", Modifier.weight(1f), color = Green, fontSize = 13.sp); Text("💻 Laptop ${fmtSec(tl)}", color = Blue, fontSize = 13.sp) }
        }
        if (!granted) Panel {
            Text("Allow usage access", fontWeight = FontWeight.SemiBold)
            Text("Android only lets apps read per-app screen time after you switch on “Usage access” for Life AI (same as StayFree).", color = Mut, fontSize = 12.sp)
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open usage access settings") }
        }
        val max = (rows.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1)
        var showAll by remember { mutableStateOf(false) }
        if (rows.isNotEmpty()) SectionHeader("Most used apps")
        rows.take(if (showAll) 40 else 8).forEach { r ->
            Panel {
                Text(r.label, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Card2)) { Box(Modifier.fillMaxWidth((r.total.toFloat() / max).coerceIn(0.02f, 1f)).height(6.dp).background(Green)) }
                Text("Total ${fmtSec(r.total)}  ·  📱 ${fmtSec(r.phone)}  ·  💻 ${fmtSec(r.laptop)}", color = Mut, fontSize = 12.sp)
            }
        }
        if (rows.size > 8) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all ${minOf(rows.size, 40)} apps") }
        if (rows.isEmpty()) Text("No data yet. Phone: grant usage access. Laptop: install the extension and pair below.", color = Mut, fontSize = 13.sp)
        Expand("Pair with laptop", open0 = rows.none { it.laptop > 0 }) {
            Text("Pair with laptop", fontWeight = FontWeight.SemiBold)
            Text("Open the extension dashboard → “Pair phone” shows a QR. Scan it here: it copies the Supabase settings and links both devices.", color = Mut, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val o = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                val t = GmsBarcodeScanning.getClient(ctx, o).startScan()
                t.addOnSuccessListener { pair(it.rawValue) }.addOnFailureListener { msg = "Scan failed: ${it.message}" }
            }) { Text("Scan laptop QR") }
            if (Cfg.pairCode.isNotBlank()) Text("Pair code: ${Cfg.pairCode}", color = Green, fontSize = 12.sp)
            devices.forEach { d -> Text("• ${d.optString("device")} ${d.optString("label")} — seen ${fmtTs(d.optString("last_seen"))}", color = Mut, fontSize = 12.sp) }
            if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("✔")) Green else Amber, fontSize = 12.sp)
        }
    }
}
