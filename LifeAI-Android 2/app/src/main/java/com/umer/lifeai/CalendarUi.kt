@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private class DayData(val images: List<JSONObject>, val acts: List<JSONObject>, val events: List<JSONObject>, val decisions: List<JSONObject>, val items: Map<String, JSONObject>, val usage: List<JSONObject>)
private fun jl(a: JSONArray) = List(a.length()) { a.getJSONObject(it) }
private fun epoch(s: String) = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

private suspend fun loadDay(d: LocalDate): DayData {
    val a = Store.dayStartIso(d); val b = Store.dayStartIso(d.plusDays(1)); val q = "ts=gte.$a&ts=lt.$b"; val e = emptyList<JSONObject>()
    val imgs = jl(Sb.get("images?captured_at=gte.$a&captured_at=lt.$b&order=captured_at.asc&limit=600"))
    val acts = runCatching { jl(Sb.get("activity?$q&order=ts.asc&limit=1000")) }.getOrDefault(e)
    val evs = runCatching { jl(Sb.get("events?$q&limit=2000")) }.getOrDefault(e)
    val dec = runCatching { jl(Sb.get("decisions?$q&order=ts.asc&limit=1000&select=ts,action,item_id,reason,activity,message")) }.getOrDefault(e)
    val items = runCatching { jl(Sb.get("items?select=id,title,kind,target_min,status&limit=500")).associateBy { it.getString("id") } }.getOrDefault(emptyMap())
    val use = runCatching { jl(Sb.get("usage?day=eq.$d&order=seconds.desc&limit=200")) }.getOrDefault(e)
    return DayData(imgs, acts, evs, dec, items, use)
}

/** One entry of the day timeline (Google-Calendar style agenda). */
private class TL(val ts: Long, val kind: String, val icon: String, val title: String, val body: String, val color: Color, val photos: List<JSONObject> = emptyList())

private fun timeline(dd: DayData, d: LocalDate): List<TL> {
    val out = mutableListOf<TL>(); val zone = ZoneId.systemDefault()
    // photos: group neighbours (≤ 40 min apart) so 96 photos don't become 96 rows
    var grp = mutableListOf<JSONObject>(); var lastT = 0L
    fun flush() {
        if (grp.isEmpty()) return
        val acts = grp.mapNotNull { clean(it.optString("activity")) }.distinct().take(4)
        out += TL(epoch(grp.first().getString("captured_at")), "photo", "📷", if (grp.size == 1) "Photo" else "${grp.size} photos",
            acts.joinToString("  ") { actEmoji(it) + " " + actLabel(it) }.ifBlank { clean(grp.first().optString("interpretation")).orEmpty() }, Green, grp.toList())
        grp = mutableListOf()
    }
    for (j in dd.images) { val t = epoch(j.getString("captured_at")); if (grp.isNotEmpty() && t - lastT > 40 * 60_000L) flush(); grp.add(j); lastT = t }
    flush()
    for (x in dd.decisions) {
        val a = x.optString("action"); if (a == "note") continue
        val (icon, title, c) = when (a) { "interrupt" -> Triple("📞", "AI called you", Red); "remind" -> Triple("🔔", "Reminder", Amber); "rule" -> Triple("👁", "Rule triggered", Blue); "remind_later" -> Triple("🕒", "Will remind later", Mut); else -> Triple("•", a, Mut) }
        out += TL(epoch(x.getString("ts")), "nudge", icon, title, clean(x.optString("message")) ?: clean(x.optString("reason")).orEmpty(), c)
    }
    for (e in dd.events) {
        val t = e.optString("type"); if (t !in setOf("completed", "failed", "postponed")) continue
        val name = dd.items[e.optString("item_id")]?.optString("title") ?: "item"
        val (icon, label, c) = when (t) { "completed" -> Triple("✔", "Completed", Green); "failed" -> Triple("✖", "Missed", Red); else -> Triple("⏸", "Postponed", Amber) }
        out += TL(epoch(e.getString("ts")), "goal", icon, "$label: $name", clean(e.optString("note")).orEmpty(), c)
    }
    for (r in Reminders.all()) {
        val day = java.time.Instant.ofEpochMilli(r.at).atZone(zone).toLocalDate(); if (day != d) continue
        out += TL(r.at, "goal", "⏰", if (r.fired) "Reminder (delivered)" else "Reminder (scheduled)", r.text, Blue)
    }
    return out.sortedBy { it.ts }
}

@Composable
fun CalendarScreen() {
    var sel by remember { mutableStateOf(LocalDate.now()) }
    var monthOpen by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var counts by remember { mutableStateOf(mapOf<LocalDate, Int>()) }
    var data by remember { mutableStateOf<DayData?>(null) }; var err by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }
    var viewer by remember { mutableStateOf<Pair<List<JSONObject>, Int>?>(null) }
    val zone = ZoneId.systemDefault()
    LaunchedEffect(month) {
        while (true) {
            runCatching {
                val a = Sb.get("images?select=captured_at&captured_at=gte.${Store.dayStartIso(month.atDay(1))}&captured_at=lt.${Store.dayStartIso(month.plusMonths(1).atDay(1))}&limit=5000")
                counts = (0 until a.length()).map { OffsetDateTime.parse(a.getJSONObject(it).getString("captured_at")).atZoneSameInstant(zone).toLocalDate() }.groupingBy { it }.eachCount()
            }
            delay(60_000)
        }
    }
    LaunchedEffect(sel) { while (true) { runCatching { data = loadDay(sel); err = null }.onFailure { err = it.message }; delay(if (sel == LocalDate.now()) 20_000 else 600_000) } }

    @Composable fun Day(d: LocalDate, modifier: Modifier) {
        val isSel = d == sel; val isToday = d == LocalDate.now(); val cnt = counts[d] ?: 0
        Column(modifier.padding(2.dp).clip(RoundedCornerShape(12.dp)).background(if (isSel) Green else Color.Transparent)
            .then(if (isToday && !isSel) Modifier.border(BorderStroke(1.dp, Green), RoundedCornerShape(12.dp)) else Modifier)
            .clickable { sel = d; month = YearMonth.from(d); data = null }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3), fontSize = 10.sp, color = if (isSel) Color(0xFF04130A) else Mut)
            Text("${d.dayOfMonth}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isSel) Color(0xFF04130A) else Color.White)
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (cnt > 0) (if (isSel) Color(0xFF04130A) else Green) else Color.Transparent))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { monthOpen = !monthOpen }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${sel.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${sel.year}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Icon(if (monthOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Toggle month view")
                }
                TextButton({ sel = LocalDate.now(); month = YearMonth.now(); data = null }) { Text("Today") }
                IconButton({ if (monthOpen) month = month.minusMonths(1) else { sel = sel.minusWeeks(1); month = YearMonth.from(sel); data = null } }) { Icon(Icons.Default.ChevronLeft, "Previous") }
                IconButton({ if (monthOpen) month = month.plusMonths(1) else { sel = sel.plusWeeks(1); month = YearMonth.from(sel); data = null } }) { Icon(Icons.Default.ChevronRight, "Next") }
            }
            err?.let { Text(it, color = Red, fontSize = 12.sp) }
        }
        item {
            Panel(Modifier) {
                if (!monthOpen) {
                    val start = sel.with(DayOfWeek.MONDAY)
                    Row(Modifier.fillMaxWidth()) { for (i in 0..6) Day(start.plusDays(i.toLong()), Modifier.weight(1f)) }
                } else {
                    val lead = month.atDay(1).dayOfWeek.value - 1; val n = month.lengthOfMonth(); val cells = ((lead + n + 6) / 7) * 7
                    Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Mut, fontSize = 12.sp) } }
                    for (r in 0 until cells / 7) Row(Modifier.fillMaxWidth()) {
                        for (c in 0..6) { val dn = r * 7 + c - lead + 1; if (dn < 1 || dn > n) Spacer(Modifier.weight(1f)) else Day(month.atDay(dn), Modifier.weight(1f)) }
                    }
                }
            }
        }
        val dd = data
        item { Text(dayLabel(sel) + " · " + sel.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        if (dd == null) item { Text("Loading…", color = Mut) } else {
            val ev = dd.events; fun ids(t: String) = ev.filter { it.optString("type") == t }.map { it.optString("item_id") }.distinct()
            val completed = ids("completed")
            val prog = HashMap<String, Int>(); ev.filter { it.optString("type") == "progress" }.forEach { prog.merge(it.optString("item_id"), it.optInt("minutes"), Int::plus) }
            val tasks = dd.items.values.filter { it.optString("kind") in setOf("goal", "task", "habit") && it.optInt("target_min") > 0 && it.optString("status") in setOf("active", "done", "failed") }
            val sumT = tasks.sumOf { it.optInt("target_min") }; val sumD = tasks.sumOf { minOf(prog[it.getString("id")] ?: 0, it.optInt("target_min")) }
            val actMin = HashMap<String, Int>(); dd.acts.forEach { actMin.merge(it.optString("activity"), it.optInt("minutes"), Int::plus) }
            val wasted = dd.acts.filter { it.optBoolean("wasted") }.sumOf { it.optInt("minutes") }
            val nudges = dd.decisions.count { it.optString("action") in setOf("interrupt", "remind", "rule") }

            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${dd.images.size}", "photos", Modifier.width(100.dp))
                    StatTile("$nudges", "nudges", Modifier.width(100.dp), Amber)
                    StatTile(fmtMin(actMin.values.sum().toLong()), "tracked", Modifier.width(110.dp))
                    StatTile(fmtMin(wasted.toLong()), "off-task", Modifier.width(110.dp), if (wasted > 0) Red else Color.White)
                }
            }
            if (sumT > 0) item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(sumD.toFloat() / sumT, 76.dp, "${completed.size}/${tasks.size}", "goals")
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("${fmtMin(sumD.toLong())} of ${fmtMin(sumT.toLong())} planned time reached", fontWeight = FontWeight.SemiBold)
                            Text(tasks.filter { (prog[it.getString("id")] ?: 0) == 0 && it.getString("id") !in completed && it.optString("status") == "active" }.joinToString { it.optString("title") }.let { if (it.isBlank()) "Everything got some attention" else "No time yet: $it" }, color = Mut, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "photo" to "Photos", "nudge" to "Nudges", "goal" to "Goals & reminders").forEach { (k, l) -> FilterChip(filter == k, { filter = k }, { Text(l) }) }
                }
            }
            val tl = timeline(dd, sel).filter { filter == "all" || it.kind == filter }
            if (tl.isEmpty()) item { EmptyState("🗓", "Nothing here", if (sel.isAfter(LocalDate.now())) "Reminders you schedule for this day will show up here." else "No ${if (filter == "all") "events" else "matching events"} recorded for this day.") }
            else items(tl.size) { i ->
                val t = tl[i]
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                    Text(fmtTs(java.time.Instant.ofEpochMilli(t.ts).toString(), "h:mm a"), Modifier.width(62.dp).padding(top = 12.dp), color = Mut, fontSize = 12.sp)
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(CardC).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(t.color))
                        Column(Modifier.padding(12.dp)) {
                            Text("${t.icon} ${t.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (t.body.isNotBlank()) Text(t.body, color = Mut, fontSize = 13.sp, maxLines = 3, modifier = Modifier.padding(top = 2.dp))
                            if (t.photos.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                t.photos.forEach { j ->
                                    val p = clean(j.optString("storage_path"))
                                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Card2).clickable { viewer = dd.images to dd.images.indexOf(j).coerceAtLeast(0) }) {
                                        if (p != null) AsyncImage(Sb.publicUrl(p), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Expand("Time by activity") {
                    val mx = (actMin.values.maxOrNull() ?: 1).coerceAtLeast(1)
                    actMin.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${actEmoji(k)} ${actLabel(k)}", Modifier.width(130.dp), fontSize = 13.sp)
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Card2)) { Box(Modifier.fillMaxWidth(v.toFloat() / mx).height(8.dp).background(Green)) }
                            Text(" ${fmtMin(v.toLong())}", fontSize = 12.sp, color = Mut)
                        }
                    }
                    if (actMin.isEmpty()) Text("No activity recorded.", color = Mut, fontSize = 13.sp)
                }
            }
            item {
                Expand("Screen time") {
                    val m = HashMap<String, Pair<String, Long>>(); dd.usage.forEach { m.merge(it.getString("app_key"), it.optString("label") to it.optLong("seconds")) { a, b -> a.first to (a.second + b.second) } }
                    m.values.sortedByDescending { it.second }.take(8).forEach { Text("${it.first}: ${fmtSec(it.second)}", fontSize = 13.sp) }
                    if (m.isEmpty()) Text("No usage data.", color = Mut, fontSize = 13.sp)
                }
            }
        }
    }
    viewer?.let { (list, i) -> PhotoViewer(list, i) { viewer = null } }
}
