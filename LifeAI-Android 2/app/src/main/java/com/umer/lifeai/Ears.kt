package com.umer.lifeai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** Swap point: replace [EnergyVad] with Silero (ONNX) later without touching anything else. */
interface Vad { fun speech(f: ShortArray): Boolean }

/** Adaptive-noise-floor energy VAD (0 dependencies, ~0 CPU). */
class EnergyVad : Vad {
    private var floor = 250.0
    override fun speech(f: ShortArray): Boolean {
        var s = 0.0; for (x in f) s += x.toDouble() * x
        val rms = sqrt(s / f.size)
        floor = if (rms < floor) floor * 0.9 + rms * 0.1 else minOf(1500.0, floor + (rms - floor) * 0.0004)   // falls fast, rises slowly
        return rms > maxOf(floor * 2.8, 380.0)
    }
}

/**
 * The EARS. One mic, three jobs, zero cloud:
 *  1. rolling 9-min PCM ring buffer in RAM (old audio is overwritten = "working memory"),
 *  2. VAD cuts the stream into speech segments → on-device recognizer (Android 13+, audio piped in, so the mic is opened once),
 *  3. a keyword hit → wait 90 s → cut [-6.5 min, +1.5 min] → AAC .m4a → durable clip outbox → Supabase `clips` (pending) → laptop Gemini.
 * Everything between mic and Supabase is a persistent file queue, so a dead network / closed laptop never loses a clip.
 */
object Ears {
    const val RATE = 16000
    private const val FRAME = 480                                   // 30 ms
    private const val CAP = RATE * 60 * 9                           // ring = 9 min (~17 MB)
    private const val PRE = RATE * 60 * 13 / 2
    private const val POST = RATE * 90
    private const val COOLDOWN_MS = 6 * 60_000L
    private const val MAX_PER_DAY = 40

    val status = MutableStateFlow("Ears off")
    val speaking = MutableStateFlow(false)
    @Volatile var hold = false                                      // CallActivity sets this while IT needs the mic

    private var job: Job? = null
    private var ring: ShortArray? = null
    @Volatile private var total = 0L; private var t0 = 0L
    private var vad: Vad = EnergyVad()
    private var inSpeech = false; private var run = 0; private var hang = 0; private var segStart = 0L
    private val segs = Channel<Pair<ShortArray, Long>>(6, BufferOverflow.DROP_OLDEST)
    private var pendHit = 0L; private val pendKw = LinkedHashSet<String>(); private val pendText = StringBuilder()
    private var lastClipAt = 0L; private var cnt = 0; private var cntDay = ""
    private lateinit var app: Context

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status.value = "Ears: microphone permission missing"; return }
        app = ctx.applicationContext
        job = scope.launch(Dispatchers.Default) { coroutineScope { launch { mic() }; launch { recognizeLoop() }; launch { clipLoop() } } }
    }
    fun stop() { job?.cancel(); job = null; status.value = "Ears off"; speaking.value = false; ring = null }

    // ───────── 1. mic + ring buffer ─────────
    @SuppressLint("MissingPermission")
    private suspend fun mic() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        while (currentCoroutineContext().isActive) {
            if (hold || !Cfg.ears) { status.value = if (hold) "Ears paused (call)" else "Ears off"; speaking.value = false; delay(1000); continue }
            val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 16)) }
            catch (e: Exception) { status.value = "Ears: mic unavailable"; delay(5000); continue }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); status.value = "Ears: mic busy — retrying"; delay(5000); continue }
            reset()
            try {
                rec.startRecording(); status.value = "Ears listening" + if (Build.VERSION.SDK_INT < 33) " (keywords need Android 13+)" else ""
                val f = ShortArray(FRAME)
                while (currentCoroutineContext().isActive && !hold && Cfg.ears) {
                    var got = 0
                    while (got < FRAME) { val n = rec.read(f, got, FRAME - got); if (n <= 0) throw IllegalStateException("mic read $n"); got += n }
                    push(f)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.value = "Ears: ${e.message}"; delay(3000) }
            finally { runCatching { rec.stop() }; rec.release() }
        }
    }

    private fun reset() { if (ring == null) ring = ShortArray(CAP); total = 0; t0 = System.currentTimeMillis(); inSpeech = false; run = 0; hang = 0 }

    private fun push(f: ShortArray) {
        val r = ring ?: return
        for (i in f.indices) r[((total + i) % CAP).toInt()] = f[i]
        total += f.size
        val s = vad.speech(f); val pad = RATE / 4
        if (!inSpeech) {
            if (s) { if (++run >= 3) { inSpeech = true; hang = 0; segStart = maxOf(0L, total - run * FRAME - pad); speaking.value = true } } else run = 0
        } else {
            if (s) hang = 0 else hang++
            if (hang >= 20) { inSpeech = false; run = 0; speaking.value = false; cut(segStart, minOf(total, total - hang * FRAME + pad)) }
            else if (total - segStart > RATE * 15) { cut(segStart, total); segStart = total }
        }
    }

    private fun copy(from: Long, to: Long): ShortArray {
        val r = ring ?: return ShortArray(0); val a = maxOf(from, total - CAP + RATE); val n = (to - a).toInt()
        if (n <= 0) return ShortArray(0)
        return ShortArray(n) { r[((a + it) % CAP).toInt()] }
    }
    private fun cut(a: Long, b: Long) { if (b - a < RATE / 2) return; val pcm = copy(a, b); if (pcm.isNotEmpty()) segs.trySend(pcm to b) }

    // ───────── 2. keyword spotting (VAD-gated, on-device) ─────────
    private suspend fun recognizeLoop() {
        for ((pcm, pos) in segs) {
            if (Build.VERSION.SDK_INT < 33 || hold) continue
            val text = runCatching { recognize(pcm) }.getOrNull().orEmpty()
            if (text.isBlank()) continue
            val hits = Keywords.match(text)
            if (hits.isNotEmpty()) synchronized(this) { pendKw += hits; if (pendText.length < 600) pendText.append(text).append(" / "); if (pendHit == 0L) pendHit = pos }
        }
    }

    @SuppressLint("NewApi")
    private suspend fun recognize(pcm: ShortArray): String = withContext(Dispatchers.Main) {
        withTimeout(15_000) {
            suspendCancellableCoroutine<String> { cont ->
                val sr = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
                val pipe = ParcelFileDescriptor.createPipe()
                fun done(t: String) { if (cont.isActive) cont.resume(t); runCatching { sr.destroy() }; runCatching { pipe[0].close() } }
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) { done(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ").orEmpty()) }
                    override fun onError(e: Int) { done("") }
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
                cont.invokeOnCancellation { runCatching { sr.destroy() }; runCatching { pipe[0].close() }; runCatching { pipe[1].close() } }
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra("android.speech.extra.AUDIO_SOURCE", pipe[0])
                    .putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1)
                    .putExtra("android.speech.extra.AUDIO_SOURCE_ENCODING", AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", RATE)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                sr.startListening(i)
                Thread {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { os ->
                            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(pcm); os.write(bb.array())
                        }
                    }
                }.start()
            }
        }
    }

    // ───────── 3. keyword hit → clip → durable outbox ─────────
    private suspend fun clipLoop() {
        while (currentCoroutineContext().isActive) {
            delay(2000)
            val hit = pendHit; if (hit == 0L || total < hit + POST) continue
            val (kws, txt) = synchronized(this) { Pair(pendKw.toList(), pendText.toString()).also { pendKw.clear(); pendText.setLength(0); pendHit = 0L } }
            val now = System.currentTimeMillis(); val day = LocalDate.now().toString()
            if (cntDay != day) { cntDay = day; cnt = 0 }
            if (now - lastClipAt < COOLDOWN_MS || cnt >= MAX_PER_DAY) continue
            val end = minOf(total, hit + POST); val start = maxOf(0L, hit - PRE)
            val pcm = copy(start, end); if (pcm.size < RATE * 3) continue
            lastClipAt = now; cnt++
            withContext(Dispatchers.IO) { save(pcm, t0 + start * 1000 / RATE, t0 + end * 1000 / RATE, kws, txt) }
        }
    }

    private fun save(pcm: ShortArray, startMs: Long, endMs: Long, kws: List<String>, txt: String) {
        val id = UUID.randomUUID().toString(); val dir = File(app.filesDir, "clipbox").apply { mkdirs() }
        val m4a = encodeM4a(pcm); val ext = if (m4a != null) "m4a" else "wav"
        File(dir, "$id.$ext").writeBytes(m4a ?: wav(pcm))
        File(dir, "$id.json").writeText(JSONObject().put("id", id).put("ext", ext).put("started_at", Instant.ofEpochMilli(startMs).toString()).put("ended_at", Instant.ofEpochMilli(endMs).toString())
            .put("trigger", kws.joinToString(", ")).put("transcript", txt.take(600)).put("ctx", AppTrack.ctx(app)).toString())
        dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.lastModified() }?.let { l -> if (l.size > 12) l.take(l.size - 12).forEach { f -> File(dir, f.nameWithoutExtension + ".m4a").delete(); File(dir, f.nameWithoutExtension + ".wav").delete(); f.delete() } }
        status.value = "Ears: clip saved (${kws.joinToString(", ")})"
    }

    /** Upload queued clips, oldest first. A file is deleted only after its DB row exists. Called from the service loop. */
    suspend fun flushClips() {
        if (!Cfg.ready || !::app.isInitialized) return
        val dir = File(app.filesDir, "clipbox"); val metas = dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: return
        for (f in metas) {
            val j = JSONObject(f.readText()); val id = j.getString("id"); val ext = j.getString("ext"); val file = File(dir, "$id.$ext")
            if (!file.exists()) { f.delete(); continue }
            Sb.upload("clip-$id.$ext", file.readBytes(), if (ext == "m4a") "audio/mp4" else "audio/wav")
            val row = JSONObject().put("id", id).put("started_at", j.getString("started_at")).put("ended_at", j.getString("ended_at")).put("trigger", j.optString("trigger"))
                .put("transcript", j.optString("transcript")).put("ctx", j.optString("ctx")).put("storage_path", "clip-$id.$ext")
            try { Sb.insert("clips", row) } catch (e: Exception) { if (e.message?.contains("409") != true) throw e }
            f.delete(); file.delete()
        }
    }

    // ───────── encoders ─────────
    private fun wav(p: ShortArray): ByteArray {
        val n = p.size * 2; val b = ByteBuffer.allocate(44 + n).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n)
        for (x in p) b.putShort(x); return b.array()
    }

    private fun encodeM4a(pcm: ShortArray): ByteArray? = runCatching {
        val out = File.createTempFile("clip", ".m4a", app.cacheDir)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 24_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(pcm); val bytes = bb.array()
        var off = 0; var pts = 0L; var inDone = false; var track = -1; var started = false; val info = MediaCodec.BufferInfo(); var guard = 0
        try {
            while (guard++ < 400_000) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!; ib.clear(); val n = minOf(ib.remaining(), bytes.size - off)
                        if (n <= 0) { codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { ib.put(bytes, off, n); codec.queueInputBuffer(ii, 0, n, pts, 0); off += n; pts += (n / 2) * 1_000_000L / RATE }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start(); started = true }
                else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(track, ob, info) }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); if (started) runCatching { mux.stop() }; mux.release() }
        val b = out.readBytes(); out.delete(); if (b.size < 1000) null else b
    }.getOrNull()
}

/** ~110 everyday trigger words (food, emotion, relationships, activity, money, health, Roman Urdu). Weak words (work, buy, friend…) are left out on purpose. */
object Keywords {
    private val WORDS = ("burger pizza fries biryani nihari karahi paratha samosa pakora chai coffee cola pepsi coke soda juice shake chocolate cake dessert sugar snack chips noodles pasta " +
        "chicken mutton kebab shawarma sandwich breakfast lunch dinner hungry starving eating ate diet calories fasting " +
        "tired exhausted stressed stress anxious anxiety depressed sad lonely angry annoyed frustrated hate worried scared nervous overwhelmed bored upset crying " +
        "mom mother ammi abu dad father brother sister wife girlfriend boyfriend fight argument breakup marriage " +
        "study studying exam assignment homework deadline gym workout exercise walk running sleep sleepy nap procrastinate instagram tiktok youtube netflix reels scrolling gaming pubg movie meeting lecture namaz quran cigarette smoke vape " +
        "money spend spent salary rent bill loan broke expensive delivery foodpanda " +
        "headache sick fever pain medicine doctor hospital " +
        "khana bhook thak thakk pareshan ghussa neend parhai paise dost udaas").split(" ").toSet()
    private val PHRASES = listOf("fast food", "ice cream", "cheat day", "fed up", "give up", "so tired", "can't do this", "cant do this", "tired of this", "don't care", "dont care", "shut up")

    fun match(text: String): List<String> {
        val t = text.lowercase().replace(Regex("[^a-z' ]"), " ")
        val hit = LinkedHashSet<String>()
        t.split(" ").forEach { if (it in WORDS) hit += it }
        PHRASES.forEach { if (t.contains(it)) hit += it }
        return hit.toList()
    }
}
