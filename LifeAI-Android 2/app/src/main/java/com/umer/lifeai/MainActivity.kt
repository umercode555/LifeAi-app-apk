@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, onPrimary = Color(0xFF04130A), background = Bg, surface = CardC,
                onSurface = Color(0xFFE6EDF3), onBackground = Color(0xFFE6EDF3), surfaceVariant = Card2)) { Root() }
        }
    }
}

private val tabs = listOf("Today" to Icons.Default.Home, "Coach" to Icons.Default.Forum, "Calendar" to Icons.Default.CalendarMonth,
    "Memories" to Icons.Default.PhotoLibrary, "Plans" to Icons.Default.Flag)

@Composable
private fun Root() {
    var onboarded by remember { mutableStateOf(Cfg.onboarded) }
    var tab by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<String?>(null) }   // settings | usage
    if (!onboarded) { Onboarding(onSettings = { Cfg.onboarded = true; onboarded = true; overlay = "settings" }, onDone = { Cfg.onboarded = true; onboarded = true }); return }
    androidx.activity.compose.BackHandler(enabled = overlay != null || tab != 0) { if (overlay != null) overlay = null else tab = 0 }
    Scaffold(containerColor = Bg, bottomBar = {
        if (overlay == null) NavigationBar(containerColor = CardC) {
            tabs.forEachIndexed { i, (l, ic) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, l) }, label = { Text(l, fontSize = 11.sp, maxLines = 1) }) }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (overlay) {
                "settings" -> Column { BackBar("Settings") { overlay = null }; SettingsScreen() }
                "usage" -> Column { BackBar("Screen time") { overlay = null }; UsageScreen() }
                else -> when (tab) {
                    0 -> TodayScreen(openChat = { tab = 1 }, openPlans = { tab = 4 }, openSettings = { overlay = "settings" }, openUsage = { overlay = "usage" }, openPhotos = { tab = 3 })
                    1 -> ChatScreen(onSettings = { overlay = "settings" })
                    2 -> CalendarScreen()
                    3 -> PhotosScreen()
                    else -> GoalsScreen { tab = 1 }
                }
            }
        }
    }
}

@Composable
private fun BackBar(title: String, onBack: () -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }; Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
}

// ───────────────────────── ONBOARDING ─────────────────────────
private class Slide(val emoji: String, val title: String, val body: String)
private val SLIDES = listOf(
    Slide("🧠", "Meet LifeAI", "An AI that knows your goals, notices what you're doing, and nudges you at the right moment — like a coach who is always around."),
    Slide("👀", "It has eyes and a brain", "Your phone camera quietly takes a photo every few minutes (the Eyes). The Brain looks at it, compares it with your goals and rules, and decides whether to speak up."),
    Slide("💬", "You just talk to it", "No forms. Say things like:\n“Remind me at 7:15 to leave”\n“Whenever you see me sitting, tell me to get up”\n“I want to study 2 hours every day”"),
    Slide("✅", "Everything is visible", "After each instruction you get a green confirmation card showing exactly what was scheduled. If something couldn't be saved, you'll see an orange warning — never a silent “Okay”."),
)

@Composable
private fun Onboarding(onSettings: () -> Unit, onDone: () -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(24.dp)) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val sl = SLIDES[i]
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sl.emoji, fontSize = 72.sp); Spacer(Modifier.height(20.dp))
                Text(sl.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(sl.body, color = Mut, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 24.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            repeat(SLIDES.size) { i -> Box(Modifier.padding(4.dp).size(if (pager.currentPage == i) 22.dp else 8.dp, 8.dp).background(if (pager.currentPage == i) Green else Card2, RoundedCornerShape(4.dp))) }
        }
        val last = pager.currentPage == SLIDES.size - 1
        Button(onClick = { if (last) onSettings() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (last) "Set it up (2 minutes)" else "Next", fontSize = 16.sp) }
        TextButton(onClick = onDone, Modifier.fillMaxWidth()) { Text(if (last) "Explore first" else "Skip", color = Mut) }
    }
}

// ───────────────────────── SETTINGS ─────────────────────────
@Composable
private fun SettingsScreen() {
    var lab by remember { mutableStateOf(false) }
    if (lab) LabScreen { lab = false } else SettingsMain { lab = true }
}

@Composable
private fun SettingsMain(openLab: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Cfg.url) }; var key by remember { mutableStateOf(Cfg.key) }
    var gkey by remember { mutableStateOf(Cfg.geminiKey) }; var model by remember { mutableStateOf(Cfg.model) }; var budget by remember { mutableStateOf(Cfg.tokenBudget.toString()) }
    var back by remember { mutableStateOf(Cfg.backEnabled) }; var flash by remember { mutableStateOf(Cfg.flashMode) }; var shake by remember { mutableStateOf(Cfg.shake) }; var ears by remember { mutableStateOf(Cfg.ears) }; val earsStatus by Ears.status.collectAsState()
    var msg by remember { mutableStateOf("") }; var snd by remember { mutableIntStateOf(0) }; var smsg by remember { mutableStateOf("") }; var rmsg by remember { mutableStateOf("") }
    val pickN = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { smsg = if (Sounds.import(ctx, u, "notif")) "Notification sound saved" else "Couldn't read that file"; snd++ } }
    val pickR = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { smsg = if (Sounds.import(ctx, u, "ring")) "Call ringtone saved" else "Couldn't read that file"; snd++ } }
    fun save() { Cfg.url = url; Cfg.key = key; Cfg.geminiKey = gkey; Cfg.model = model; budget.toLongOrNull()?.let { Cfg.tokenBudget = it } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Settings", "Set up once, then forget about it")
        Expand("1 · Connect (required)", open0 = !Cfg.ready || !Cfg.aiReady) {
            Text("Database (Supabase)", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Supabase URL") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Supabase anon key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp))
            Text("AI brain (Gemini)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(gkey, { gkey = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Model id") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(budget, { budget = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Daily token budget (hard stop)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            var av by remember { mutableStateOf(Cfg.apiVision) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Analyse photos with the API"); Text("OFF = the laptop Chrome extension reads photos (free). ON only if your key has vision credits.", color = Mut, fontSize = 12.sp) }; Switch(av, { av = it; Cfg.apiVision = it }) }
            Text("Used today: ${Cfg.tokensToday} tokens", color = Mut, fontSize = 12.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); msg = "Saved." }) { Text("Save") }
                OutlinedButton(onClick = { save(); msg = "Testing…"; scope.launch { msg = runCatching { Sb.get("images?limit=1"); "✔ Supabase OK" }.getOrElse { "✖ Supabase: ${it.message}" } } }) { Text("Test DB") }
                OutlinedButton(onClick = { save(); msg = "Testing…"; scope.launch { msg = runCatching { Gemini.ask("Reply JSON {\"ok\":true}", user = "ping", maxOut = 20); "✔ Gemini OK (${Cfg.model})" }.getOrElse { "✖ ${it.message}" } } }) { Text("Test AI") }
            }
            if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("✖")) Red else Green, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Run supabase/schema.sql once in the Supabase SQL editor (safe to re-run), then use the same URL + key in the Chrome extension.", color = Mut, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Expand("2 · Reminders & alarms") {
            val exact = Reminders.canExact(ctx)
            Text(if (exact) "✔ Exact-time alarms allowed" else "⚠ Android may delay reminders by several minutes until you allow “Alarms & reminders”.", color = if (exact) Green else Amber, fontSize = 13.sp)
            if (!exact && android.os.Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Allow exact alarms") }
            OutlinedButton(onClick = { scope.launch { rmsg = runCatching { Store.remind("Test reminder — it works!", System.currentTimeMillis() + 10_000) }.getOrElse { "✖ ${it.message}" } } }) { Text("Send a test reminder in 10 s") }
            if (rmsg.isNotBlank()) Text(rmsg, color = if (rmsg.startsWith("✖")) Red else Green, fontSize = 12.sp)
            OutlinedButton(onClick = { openBatterySettings(ctx) }) { Text("Allow background activity (Vivo: also enable Autostart)") }
        }
        Expand("3 · Camera") {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Photo every"); Text("How often the Eyes look at you", color = Mut, fontSize = 12.sp) } }
            var iv by remember { mutableIntStateOf(Cfg.intervalMin) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 15, 30, 60).forEach { m -> FilterChip(iv == m, { iv = m; Cfg.intervalMin = m }, { Text("$m min") }) } }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Also use back camera"); Text("Off = front camera only", color = Mut, fontSize = 12.sp) }; Switch(back, { back = it; Cfg.backEnabled = it }) }
            Text("Flash (front uses a white-screen flash)", color = Mut, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("off", "auto", "always").forEach { m -> FilterChip(flash == m, { flash = m; Cfg.flashMode = m }, { Text(m) }) } }
            val overlay = Settings.canDrawOverlays(ctx)
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(if (overlay) "✔ Screen flash allowed" else "Allow screen flash (display over other apps)") }
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Shake to capture"); Text("Shake 3× → photo even inside other apps (restart monitoring after changing)", color = Mut, fontSize = 12.sp) }; Switch(shake, { shake = it; Cfg.shake = it }) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Ears (keyword listening)"); Text("Rolling 9-min mic buffer + voice detection + ~110 keywords → 8-min clip sent to Gemini. Keywords need Android 13+. Restart monitoring after changing. Now: $earsStatus", color = Mut, fontSize = 12.sp) }; Switch(ears, { ears = it; Cfg.ears = it }) }
        }
        Expand("4 · Sounds") {
            androidx.compose.runtime.key(snd) {
                SoundRow("Notification sound", "notif", { pickN.launch(arrayOf("audio/*")) }, { snd++ })
                SoundRow("Call ringtone", "ring", { pickR.launch(arrayOf("audio/*")) }, { snd++ })
            }
            if (smsg.isNotBlank()) Text(smsg, color = Green, fontSize = 12.sp)
        }
        Expand("5 · Permissions & tools") {
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(if (Usage.granted(ctx)) "✔ Usage access granted" else "Allow usage access (screen time)") }
            OutlinedButton(onClick = openLab) { Text("🧪 Test lab: ${Scenarios.all(0L).size} scenarios + live pipeline test") }
            OutlinedButton(onClick = { Cfg.onboarded = false }) { Text("Show the intro again (next launch)") }
            Text("A permanent notification shows while the camera service runs. Turn Watching off on the Today tab to stop all captures.", color = Mut, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SoundRow(label: String, kind: String, onPick: () -> Unit, onChange: () -> Unit) {
    Panel {
        Text("$label: ${if (Sounds.has(kind)) "custom MP3" else "system default"}", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick) { Text("Choose") }
            OutlinedButton(onClick = { Sounds.play(kind, false, 6000) }) { Text("Preview") }
            OutlinedButton(onClick = { Sounds.reset(kind); onChange() }, enabled = Sounds.has(kind)) { Text("Reset") }
        }
    }
}

// ───────────────────────── TEST LAB ─────────────────────────
@Composable
private fun LabScreen(onBack: () -> Unit) {
    val day = remember { Store.dayStartMs() }
    val results = remember { Scenarios.all(day).map { it to Scenarios.run(it, day) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            var live by remember { mutableStateOf("") }; val sc = rememberCoroutineScope()
            Panel {
                Text("Live pipeline test (real path, writes 1 test row)", fontWeight = FontWeight.SemiBold)
                Text("Pretends the camera just saw you SITTING. If you created the rule “whenever you see me sitting, tell me to get up”, you will get the notification and a chat message now.", color = Mut, fontSize = 12.sp)
                Button(onClick = { live = "Running…"; sc.launch { live = runCatching { Brain.observe("sitting", "(test) You are sitting on a chair", emptyList(), emptyList(), "camera", java.time.Instant.now().toString(), System.currentTimeMillis(), null, null, false, null, 0, true) }.getOrElse { "✖ ${it.message}" } } }, enabled = Cfg.ready) { Text("Simulate: camera sees me sitting") }
                if (live.isNotBlank()) Text(live, color = if (live.startsWith("✖")) Red else Green, fontSize = 12.sp)
            }
            Title("Test lab", "${results.count { it.second.pass }}/${results.size} scenarios behave as designed (offline, 0 tokens)")
        }
        items(results) { (sc, r) ->
            var open by remember { mutableStateOf(false) }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${sc.n} ${sc.title}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(if (r.pass) "PASS" else "FAIL", color = if (r.pass) Green else Red, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide transcript" else "Show: observes → evaluates → priority → decision → calendar → progress") }
                if (open) Text(r.lines.drop(1).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Mut)
            }
        }
    }
}
