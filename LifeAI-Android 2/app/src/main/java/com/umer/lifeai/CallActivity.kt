package com.umer.lifeai

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Locale

/** The "phone call" screen: TTS speaks, speech recognition listens, Supabase carries text to/from the Chrome extension. */
class CallActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: String? = null
    private var sr: SpeechRecognizer? = null
    private val lines = mutableStateListOf<Pair<Boolean, String>>()
    private var phase by mutableStateOf("ringing") // ringing|connecting|speaking|listening|thinking|idle
    private var muted by mutableStateOf(false)
    private var callId = ""; private var opening = ""; private var reason = ""
    private var endAfter = false; private var noSpeech = 0
    private var poll: Job? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) listen() else phase = "idle" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Ears.hold = true
        callId = intent.getStringExtra("call_id") ?: ""; opening = intent.getStringExtra("opening") ?: "Hey Umer."
        lifecycleScope.launch { runCatching { reason = Sb.get("calls?id=eq.$callId&select=reason").optJSONObject(0)?.optString("reason").orEmpty() } }
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault().let { if ((tts?.isLanguageAvailable(it) ?: -2) >= 0) it else Locale.US }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { runOnUiThread { afterSpeech() } }
                @Deprecated("x") override fun onError(id: String?) { runOnUiThread { afterSpeech() } }
            })
            pending?.let { pending = null; speakNow(it) }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4ADE80))) {
                CallUi(phase, lines, muted, ::answer, ::decline, ::hangup, { muted = !muted; if (muted) { sr?.cancel(); phase = "idle" } }, ::listen)
            }
        }
    }

    private fun answer() {
        Notifier.stopRing()
        phase = "connecting"
        lifecycleScope.launch {
            runCatching {
                Sb.patch("calls?id=eq.$callId", JSONObject().put("status", "active"))
                Sb.insert("messages", JSONObject().put("call_id", callId).put("role", "ai").put("content", opening).put("status", "spoken"))
            }
            say(opening)
        }
    }

    private fun decline() { Notifier.stopRing(); GlobalScope.launch(Dispatchers.IO) { runCatching { Sb.patch("calls?id=eq.$callId", JSONObject().put("status", "declined")) } }; finish() }

    private fun say(t: String) { lines.add(true to t); phase = "speaking"; if (ttsReady) speakNow(t) else pending = t }
    private fun speakNow(t: String) {
        val clean = t.replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D*_#`]"), "")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
    }
    private fun afterSpeech() { if (endAfter) hangup() else listen() }

    private fun listen() {
        if (muted || isFinishing) { phase = "idle"; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { lines.add(true to "(Speech recognition isn't available on this phone — install/enable the Google app.)"); phase = "idle"; return }
        tts?.stop(); sr?.destroy(); phase = "listening"
        sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if (t.isBlank()) retry() else submit(t) }
                override fun onError(e: Int) { retry() }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }
    private fun retry() { if (++noSpeech < 3) listen() else phase = "idle" }

    private fun submit(text: String) {
        noSpeech = 0; lines.add(false to text); phase = "thinking"
        poll?.cancel()
        poll = lifecycleScope.launch {
            val before = lines.toList().dropLast(1)
            launch { runCatching { Sb.insert("messages", JSONObject().put("call_id", callId).put("role", "user").put("content", text).put("status", "done")) } }
            val r = runCatching { Brain.callReply(reason, before, text) }
            r.onSuccess { out ->
                endAfter = out.end
                launch { runCatching { Sb.insert("messages", JSONObject().put("call_id", callId).put("role", "ai").put("content", out.reply).put("status", "spoken")) } }
                say(out.reply)
            }.onFailure { lines.add(true to "(AI error: ${it.message})"); phase = "idle" }
        }
    }

    private fun hangup() {
        poll?.cancel(); tts?.stop(); sr?.destroy()
        GlobalScope.launch(Dispatchers.IO) { runCatching { Sb.patch("calls?id=eq.$callId", JSONObject().put("status", "ended").put("ended_at", java.time.Instant.now().toString())) } }
        finish()
    }

    override fun onDestroy() { Ears.hold = false; Notifier.stopRing(); poll?.cancel(); sr?.destroy(); tts?.shutdown(); super.onDestroy() }
}

@Composable
private fun CallUi(phase: String, lines: List<Pair<Boolean, String>>, muted: Boolean, onAnswer: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit, onMute: () -> Unit, onTalk: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "p").animateFloat(1f, if (phase == "ringing" || phase == "speaking" || phase == "listening") 1.18f else 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    val label = when (phase) { "ringing" -> "Incoming call…"; "connecting" -> "Connecting…"; "speaking" -> "Speaking"; "listening" -> "Listening… talk now"; "thinking" -> "Thinking…"; else -> if (muted) "Mic muted" else "Tap the mic to talk" }
    val ls = rememberLazyListState(); LaunchedEffect(lines.size) { if (lines.isNotEmpty()) ls.animateScrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1F17), Color(0xFF0B0F14)))).statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).scale(pulse.value).background(Color(0x334ADE80), CircleShape))
            Box(Modifier.size(92.dp).background(Color(0xFF4ADE80), CircleShape), contentAlignment = Alignment.Center) { Text("AI", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color(0xFF04130A)) }
        }
        Spacer(Modifier.height(14.dp))
        Text("Life AI", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, color = Color(0xFF8B9BAB))
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = ls, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines) { (ai, t) ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (ai) Alignment.CenterStart else Alignment.CenterEnd) {
                    Text(t, color = Color.White, modifier = Modifier.widthIn(max = 300.dp).background(if (ai) Color(0xFF1B2530) else Color(0xFF14532D), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (phase == "ringing") Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            RoundBtn(Icons.Default.CallEnd, Color(0xFFEF4444), onDecline); RoundBtn(Icons.Default.Call, Color(0xFF22C55E), onAnswer)
        } else Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundBtn(if (muted) Icons.Default.MicOff else Icons.Default.Mic, if (muted) Color(0xFF475569) else Color(0xFF1B2530), onMute)
            RoundBtn(Icons.Default.RecordVoiceOver, Color(0xFF2563EB), onTalk)
            RoundBtn(Icons.Default.CallEnd, Color(0xFFEF4444), onEnd)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, onClick: () -> Unit) =
    FilledIconButton(onClick, Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg)) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
