@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime

private class TodayData(
    val lastImage: JSONObject?, val lastDecision: JSONObject?, val photosToday: Int, val alertsToday: Int,
    val tasks: List<JSONObject>, val progress: Map<String, Int>, val engine: Boolean?,
)

private suspend fun loadToday(): TodayData {
    val start = Store.dayStartIso()
    val img = runCatching { Sb.get("images?status=eq.done&usable=eq.true&order=captured_at.desc&limit=1").optJSONObject(0) }.getOrNull()
    val dec = runCatching { Sb.get("decisions?action=neq.note&order=ts.desc&limit=1").optJSONObject(0) }.getOrNull()
    val photos = runCatching { Sb.get("images?captured_at=gte.$start&select=id&limit=1000").length() }.getOrDefault(0)
    val alerts = runCatching { Sb.get("decisions?ts=gte.$start&action=in.(remind,interrupt,rule)&select=id&limit=500").length() }.getOrDefault(0)
    val a = runCatching { Sb.get("items?status=eq.active&kind=in.(goal,task,habit)&target_min=gt.0&order=priority.desc&limit=30") }.getOrNull()
    val tasks = a?.let { x -> List(x.length()) { x.getJSONObject(it) } } ?: emptyList()
    val prog = HashMap<String, Int>()
    runCatching { val ev = Sb.get("events?type=eq.progress&ts=gte.$start&select=item_id,minutes&limit=2000"); for (i in 0 until ev.length()) { val e = ev.getJSONObject(i); prog.merge(e.optString("item_id"), e.optInt("minutes"), Int::plus) } }
    return TodayData(img, dec, photos, alerts, tasks, prog, Brain.engineOnline())
}

@Composable
fun TodayScreen(openChat: () -> Unit, openPlans: () -> Unit, openSettings: () -> Unit, openUsage: () -> Unit, openPhotos: () -> Unit) {
    val ctx = LocalContext.current
    val st by LifeService.status.collectAsState(); val pend by LifeService.outbox.collectAsState()
    var on by remember { mutableStateOf(Cfg.monitoring) }
    var data by remember { mutableStateOf<TodayData?>(null) }
    var reminders by remember { mutableStateOf(Reminders.upcoming()) }
    val perms = rememberPermRunner()
    LaunchedEffect(Unit) { while (true) { reminders = Reminders.upcoming(); if (Cfg.ready) runCatching { data = loadToday() }; delay(8000) } }
    val greeting = LocalTime.now().hour.let { if (it < 5) "Still up?" else if (it < 12) "Good morning" else if (it < 18) "Good afternoon" else "Good evening" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(greeting, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("Here is what LifeAI is doing for you", color = Mut, fontSize = 13.sp) }
            IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings", tint = Mut) }
        }

        // 1) setup — only while something is missing
        val steps = listOf(
            Triple("Connect your database (Supabase)", Cfg.ready, "Settings"),
            Triple("Add Gemini key so I can understand you", Cfg.aiReady, "Settings"),
        )
        if (steps.any { !it.second }) Panel {
            Text("Finish setup", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            steps.forEach { (t, ok, _) -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (ok) "✅" else "⬜"); Spacer(Modifier.width(8.dp)); Text(t, color = if (ok) Mut else Color.White, fontSize = 14.sp) } }
            Spacer(Modifier.height(10.dp)); Button(onClick = openSettings) { Text("Open settings") }
        }

        // 2) watching switch + honest pipeline status
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) Green else Mut)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (on) "Watching over your day" else "Paused", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (on) st else "Switch on to take a photo every ${Cfg.intervalMin} min", color = Mut, fontSize = 12.sp)
                }
                Switch(checked = on, onCheckedChange = { v -> if (v && !Cfg.ready) { openSettings(); return@Switch }; on = v; if (v) perms { LifeService.start(ctx) } else LifeService.stop(ctx) })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (Cfg.ready) "Database ✓" else "Database ✗", if (Cfg.ready) Green else Red)
                val eng = data?.engine
                Pill(when (eng) { true -> "Eyes (laptop) online"; false -> "Eyes (laptop) offline"; null -> "Eyes: unknown" }, when (eng) { true -> Green; false -> Amber; null -> Mut })
                if (pend > 0) Pill("$pend waiting to upload", Amber)
            }
            if (data?.engine == false) Text("Photos are read by the Chrome extension on your laptop. Open its dashboard and press “Start engine”, or turn on API vision in Settings.", color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // 3) quick actions
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { openChat() }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("💬 Talk") }
            FilledTonalButton(onClick = { perms { ChatStore.add("user", "📷 Showing you a photo…"); Brain.expectPhoto(); LifeService.capture(ctx, "front", "chat"); openChat() } }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp), enabled = Cfg.ready) { Text("📷 Show me") }
            FilledTonalButton(onClick = openPlans, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("📋 Plans") }
        }

        // 4) what the AI saw and did most recently
        SectionHeader("Right now", "All photos", openPhotos)
        val img = data?.lastImage
        if (img == null) EmptyState("👀", "Nothing seen yet", "Turn Watching on, or tap “Show me”, and your first observation appears here with what I did about it.")
        else Panel {
            Row {
                val p = clean(img.optString("storage_path"))
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(Card2)) { if (p != null) AsyncImage(Sb.publicUrl(p), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(fmtTs(img.optString("captured_at"), "h:mm a"), color = Mut, fontSize = 12.sp)
                    clean(img.optString("activity"))?.let { Text("${actEmoji(it)} ${actLabel(it)}", fontWeight = FontWeight.SemiBold) }
                    Text(clean(img.optString("interpretation")) ?: "", fontSize = 13.sp, maxLines = 3)
                }
            }
            data?.lastDecision?.let { d ->
                Spacer(Modifier.height(10.dp))
                Text("Last thing I did: ${d.optString("action").replace('_', ' ')} · ${clean(d.optString("message")) ?: clean(d.optString("reason")) ?: ""}".take(160), color = Mut, fontSize = 12.sp)
            }
        }

        // 5) coming up
        SectionHeader("Coming up", "Manage", openPlans)
        if (reminders.isEmpty()) EmptyState("⏰", "No reminders", "Say “remind me at 7:15 to leave” in Coach and it appears here.", "Set one") { openChat() }
        else Panel { reminders.take(4).forEach { r -> Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTs(Instant.ofEpochMilli(r.at).toString(), if (r.at - System.currentTimeMillis() < 20 * 3600_000L) "h:mm a" else "EEE h:mm a"), Modifier.width(84.dp), color = Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(r.text, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2) } } }

        // 6) today's plan — never a meaningless 0 %
        SectionHeader("Today's plan")
        val d = data; val tasks = d?.tasks.orEmpty().filter { it.optString("kind") != "rule" }
        if (tasks.isEmpty()) EmptyState("🎯", "No daily targets yet", "Tell Coach a goal like “I want to study 2 hours every day” and I'll track it from your photos.", "Add a goal") { openChat() }
        else Panel {
            val tot = tasks.sumOf { it.optInt("target_min") }; val done = tasks.sumOf { minOf(d?.progress?.get(it.getString("id")) ?: 0, it.optInt("target_min")) }
            val finished = tasks.count { (d?.progress?.get(it.getString("id")) ?: 0) >= it.optInt("target_min") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(if (tot > 0) done.toFloat() / tot else -1f, 84.dp, "$finished/${tasks.size}", "done")
                Spacer(Modifier.width(14.dp))
                Column { Text(if (finished == tasks.size) "All targets reached 🎉" else "${tasks.size - finished} still to do", fontWeight = FontWeight.SemiBold); Text("${fmtMin(done.toLong())} of ${fmtMin(tot.toLong())} tracked today", color = Mut, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            tasks.take(5).forEach { t ->
                val got = d?.progress?.get(t.getString("id")) ?: 0; val tg = t.optInt("target_min")
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row { Text(t.getString("title"), Modifier.weight(1f), fontSize = 14.sp, maxLines = 1); Text("$got/$tg min", color = Mut, fontSize = 12.sp) }
                    LinearProgressIndicator(progress = { (got.toFloat() / tg).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 3.dp))
                }
            }
        }

        // 7) numbers + screen time
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${d?.photosToday ?: 0}", "photos today", Modifier.weight(1f).clickable { openPhotos() })
            StatTile("${d?.alertsToday ?: 0}", "nudges today", Modifier.weight(1f), Amber)
            StatTile("⏱", "Screen time", Modifier.weight(1f).clickable { openUsage() })
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Battery / keep-alive helper reused by Settings. */
fun openBatterySettings(ctx: android.content.Context) { runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
