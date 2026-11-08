@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/** Plans = everything the AI is working on for you, in plain words. Changing things = just ask Coach (or use the small buttons). */
@Composable
fun GoalsScreen(onChat: () -> Unit) {
    var rows by remember { mutableStateOf(listOf<JSONObject>()) }
    var done by remember { mutableStateOf(mapOf<String, Int>()) }
    var rem by remember { mutableStateOf(Reminders.all()) }
    var text by remember { mutableStateOf("") }; var tick by remember { mutableIntStateOf(0) }; var err by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) {
        while (true) {
            rem = Reminders.all()
            if (Cfg.ready) runCatching {
                val a = Sb.get("items?status=neq.deleted&kind=neq.reminder&order=created_at.desc&limit=300"); rows = List(a.length()) { a.getJSONObject(it) }
                val ev = Sb.get("events?type=eq.progress&ts=gte.${Store.dayStartIso()}&select=item_id,minutes&limit=2000")
                val m = HashMap<String, Int>(); for (i in 0 until ev.length()) { val e = ev.getJSONObject(i); m.merge(e.optString("item_id"), e.optInt("minutes"), Int::plus) }
                done = m; err = null
            }.onFailure { err = it.message }
            delay(8000)
        }
    }
    fun patch(id: String, o: JSONObject) { scope.launch { runCatching { Sb.patch("items?id=eq.$id", o) }; tick++ } }
    val open = setOf("active", "paused")
    val live = rows.filter { it.optString("status") in open }; val past = rows.filter { it.optString("status") !in open }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Plans", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Everything your AI is working on for you. To change anything, just tell it.", color = Mut, fontSize = 13.sp)
            err?.let { Text(it, color = Red, fontSize = 12.sp) }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Tell your AI what you want…") }, singleLine = true, shape = RoundedCornerShape(24.dp))
                FilledIconButton(onClick = { val t = text.trim(); if (t.isNotEmpty()) { text = ""; Brain.send(t); onChat() } }, Modifier.padding(start = 8.dp), enabled = text.isNotBlank()) { Icon(Icons.Default.Check, "Send") }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, { Text("Active") }); FilterChip(tab == 1, { tab = 1 }, { Text("Finished") })
            }
        }
        if (tab == 0) {
            val up = rem.filter { !it.fired }.sortedBy { it.at }
            item { SectionHeader("⏰ Reminders") }
            if (up.isEmpty()) item { Text("None scheduled. Say “remind me at 7:15 to leave”.", color = Mut, fontSize = 13.sp) }
            items(up) { r ->
                Panel { Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, fontWeight = FontWeight.SemiBold)
                        Text(Lang.clock(Instant.ofEpochMilli(r.at).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), java.time.LocalDateTime.now()), color = Green, fontSize = 13.sp)
                    }
                    IconButton({ Reminders.delete(r.id); tick++ }) { Icon(Icons.Default.Delete, "Delete", tint = Mut) }
                } }
            }
            val rules = live.filter { it.optString("kind") == "rule" }
            item { SectionHeader("👁 Things I watch for") }
            if (rules.isEmpty()) item { Text("Nothing yet. Try “whenever you see me sitting, tell me to get up”.", color = Mut, fontSize = 13.sp) }
            items(rules) { g ->
                val it0 = Store.parse(g); val id = g.getString("id"); val st = g.optString("status")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("When I see: " + (it0.acts.map { a -> a.replace('_', ' ') } + it0.keywords).joinToString(" / ").ifBlank { g.optString("title") }, fontWeight = FontWeight.SemiBold)
                            Text("I say: “${it0.say.ifBlank { g.optString("title") }}”", color = Mut, fontSize = 13.sp)
                            Text((if (it0.once) "one time" else "at most every ${it0.cooldownMin} min") + (if (st == "paused") " · paused" else ""), color = Mut, fontSize = 11.sp)
                        }
                        Switch(st == "active", { v -> patch(id, JSONObject().put("status", if (v) "active" else "paused")) })
                        IconButton({ patch(id, JSONObject().put("status", "deleted")) }) { Icon(Icons.Default.Delete, "Delete", tint = Mut) }
                    }
                }
            }
            for ((kind, label) in listOf("goal" to "🎯 Goals", "task" to "✅ To-dos", "habit" to "🔁 Habits")) {
                val list = live.filter { it.optString("kind") == kind }; if (list.isEmpty()) continue
                item { SectionHeader(label) }
                items(list) { g -> ItemCard(g, done, ::patch, scope) { tick++ } }
            }
            if (live.none { it.optString("kind") != "rule" } && up.isEmpty() && rules.isEmpty()) item { EmptyState("📋", "Nothing planned yet", "Describe a goal, a habit or a reminder in your own words in the box above.") }
        } else {
            items(past) { g -> ItemCard(g, done, ::patch, scope) { tick++ } }
            items(rem.filter { it.fired }.sortedByDescending { it.at }.take(30)) { r -> Panel { Text("✔ ${r.text}", fontSize = 14.sp); Text("Delivered · " + fmtTs(Instant.ofEpochMilli(r.at).toString()), color = Mut, fontSize = 12.sp) } }
            if (past.isEmpty() && rem.none { it.fired }) item { EmptyState("🏁", "Nothing finished yet", "Completed goals and delivered reminders will collect here.") }
        }
    }
}

@Composable
private fun ItemCard(g: JSONObject, done: Map<String, Int>, patch: (String, JSONObject) -> Unit, scope: kotlinx.coroutines.CoroutineScope, refresh: () -> Unit) {
    val id = g.getString("id"); val st = g.optString("status"); val p = g.optInt("priority", 2); val tgt = g.optInt("target_min", 0); val d = done[id] ?: 0
    val dl = clean(g.optString("deadline"))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.getString("title"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (st == "active" || st == "paused") Switch(st == "active", { v -> patch(id, JSONObject().put("status", if (v) "active" else "paused")) })
            else Pill(st.uppercase(), if (st == "done") Green else Red)
        }
        clean(g.optString("details"))?.let { Text(it, color = Mut, fontSize = 13.sp, maxLines = 3, modifier = Modifier.padding(top = 2.dp)) }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = { patch(id, JSONObject().put("priority", if (p >= 3) 1 else p + 1)) }, label = { Text(prioName(p) + " priority", color = prioColor(p), fontSize = 12.sp) })
            dl?.let { Text("due " + fmtTs(it, "d MMM HH:mm"), color = Mut, fontSize = 12.sp) }
        }
        if (tgt > 0) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator(progress = { (d.toFloat() / tgt).coerceIn(0f, 1f) }, Modifier.fillMaxWidth()); Text("today $d / $tgt min", color = Mut, fontSize = 12.sp) }
        if (st == "active" || st == "paused") Row {
            IconButton({ scope.launch { runCatching { Sb.patch("items?id=eq.$id", JSONObject().put("status", "done")); Store.event(id, "completed", 0, "marked done") }; refresh() } }) { Icon(Icons.Default.Check, "Mark done", tint = Green) }
            if (dl != null) IconButton({ scope.launch { runCatching { Store.postpone(Store.parse(g)) }; refresh() } }) { Icon(Icons.Default.Schedule, "Move to tomorrow", tint = Mut) }
            Spacer(Modifier.weight(1f))
            IconButton({ patch(id, JSONObject().put("status", "deleted")) }) { Icon(Icons.Default.Delete, "Delete", tint = Mut) }
        }
    }
}
