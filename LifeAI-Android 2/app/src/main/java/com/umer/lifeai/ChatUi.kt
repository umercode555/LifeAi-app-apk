@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mine = Color(0xFF14532D)
private val SUGGEST = listOf("Remind me in 10 minutes to drink water", "Whenever you see me sitting, tell me to get up", "I want to study 2 hours every day", "I'm eating lunch now")

/** Coach = the Brain. Everything said here is executed by the app and the result is shown as a verified card. */
@Composable
fun ChatScreen(prefill: String? = null, onPrefillUsed: () -> Unit = {}, onSettings: () -> Unit = {}) {
    val ctx = LocalContext.current; val perms = rememberPermRunner()
    val msgs by ChatStore.msgs.collectAsState(); val busy by ChatStore.busy.collectAsState()
    var text by remember { mutableStateOf("") }
    val ls = rememberLazyListState()
    LaunchedEffect(Unit) { ChatStore.load() }
    LaunchedEffect(prefill) { if (!prefill.isNullOrBlank()) { Brain.send(prefill); onPrefillUsed() } }
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) ls.animateScrollToItem(msgs.size) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = (text + " " + it).trim() }
    }
    fun photo(cam: String) = perms {
        ChatStore.add("user", "📷 Showing you a photo…"); Brain.expectPhoto()
        if (text.isNotBlank()) { Brain.send(text); text = "" }
        LifeService.capture(ctx, cam, "chat")
    }
    fun send() { val t = text.trim(); if (t.isNotEmpty()) { text = ""; Brain.send(t) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("Coach", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Just talk. I set reminders, watch the camera for things you ask, and track your goals.", color = Mut, fontSize = 13.sp)
        if (!Cfg.aiReady) Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Amber.copy(alpha = .14f)).clickable { onSettings() }.padding(12.dp)) {
            Text("Add your Gemini key in Settings so I can understand you (reminders with a clear time still work without it).", color = Amber, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).padding(vertical = 10.dp), state = ls, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msgs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👋", fontSize = 40.sp)
                    Text("What do you want me to help with?", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
                    Text("Tap an example or type your own.", color = Mut, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    SUGGEST.forEach { s -> Text(s, Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(CardC).clickable { Brain.send(s) }.padding(14.dp), fontSize = 14.sp) }
                }
            }
            items(msgs) { m ->
                when (m.role) {
                    "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Text(m.text, Modifier.widthIn(max = 300.dp).background(Mine, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(12.dp)) }
                    "sys" -> {
                        val warn = m.text.startsWith("⚠") || m.text.contains("✖"); val c = if (warn) Amber else Green
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = .10f)).padding(12.dp)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(c)); Spacer(Modifier.width(10.dp))
                            Text(m.text, fontSize = 13.sp, color = if (warn) Amber else Color(0xFFD1FAE5))
                        }
                    }
                    else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { Text(m.text, Modifier.widthIn(max = 310.dp).background(Card2, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).padding(12.dp)) }
                }
            }
            if (busy) item { Text("…thinking", color = Mut, fontSize = 13.sp) }
        }
        if (msgs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGEST.take(3).forEach { s -> AssistChip(onClick = { Brain.send(s) }, label = { Text(s.take(28) + if (s.length > 28) "…" else "", fontSize = 12.sp) }) }
        }
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message your AI…") }, maxLines = 4, shape = RoundedCornerShape(24.dp),
                trailingIcon = { IconButton(onClick = { runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } }) { Icon(Icons.Default.Mic, "Voice", tint = Mut) } })
            IconButton(onClick = { photo("front") }) { Icon(Icons.Default.PhotoCamera, "Show me", tint = Green) }
            FilledIconButton(onClick = { send() }, enabled = text.isNotBlank() && !busy) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
