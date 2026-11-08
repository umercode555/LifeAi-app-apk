package com.umer.lifeai

import android.content.Context
import android.Manifest
import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.sqrt

/**
 * Foreground service = the "always on" part. Auto photos (front camera by default), shake-to-capture, offline outbox,
 * on-device Gemini analysis, priority engine, reminders / AI calls, and per-app usage sync.
 */
class LifeService : LifecycleService(), SensorEventListener {
    companion object {
        const val ACTION_START = "start"; const val ACTION_STOP = "stop"; const val ACTION_CAPTURE = "capture"
        val status = MutableStateFlow("Idle")
        val lastCapture = MutableStateFlow("No capture yet")
        val outbox = MutableStateFlow(0)
        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, LifeService::class.java).setAction(ACTION_START))
        fun stop(c: Context) { c.startService(Intent(c, LifeService::class.java).setAction(ACTION_STOP)) }
        fun capture(c: Context, cam: String, source: String = "manual") =
            ContextCompat.startForegroundService(c, Intent(c, LifeService::class.java).setAction(ACTION_CAPTURE).putExtra("camera", cam).putExtra("source", source))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val capLock = Mutex()
    private var loop: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private val rung = HashSet<String>()
    private var altFront = true
    private var sensing = false; private var lastShake = 0L; private var spikes = 0; private var firstSpike = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifier.channels(this)
        goForeground(NotificationCompat.Builder(this, Notifier.CH_SVC).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Life AI is active").setContentText("Camera every ${Cfg.intervalMin} min" + (if (Cfg.ears) " · listening" else "") + " — open the app to stop")
            .setOngoing(true).build())
        when (intent?.action) {
            ACTION_STOP -> { Cfg.monitoring = false; loop?.cancel(); loop = null; stopShake(); Ears.stop(); status.value = "Stopped"; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_CAPTURE -> scope.launch {
                capture(intent.getStringExtra("camera") ?: "front", intent.getStringExtra("source") ?: "manual"); flush(); analyseNow()
                if (loop == null) awaitAnswer()
            }
            ACTION_START -> { Cfg.monitoring = true; startLoop() }
            else -> if (Cfg.monitoring) startLoop() else stopSelf()
        }
        return START_STICKY
    }

    /** Declare only the foreground types whose runtime permission is granted (a missing one would crash the service on Android 14). */
    private fun goForeground(n: Notification) {
        fun ok(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 30) {
            var t = 0
            if (ok(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (Cfg.ears && ok(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (t != 0) startForeground(1, n, t) else startForeground(1, n)
        } else startForeground(1, n)
    }

    /** One-off photo with no monitoring loop: stay alive (max 3 min) so Gemini's answer / call / nudge for THIS photo is delivered the moment it exists, not on the next photo. */
    private suspend fun awaitAnswer() {
        var seen = 0
        for (i in 0 until 60) {
            delay(3000)
            runCatching { flush() }
            val n = runCatching { withContext(Dispatchers.Default) { if (Cfg.apiVision && Cfg.aiReady) { Brain.processPending(); 0 } else Brain.processObserved() } }.getOrDefault(0)
            val before = rung.size
            runCatching { checkCalls() }
            val d = runCatching { Brain.deliverNudges() }.getOrDefault(0) + runCatching { Brain.pullReplies() }.getOrDefault(0)
            if (rung.size > before || d > 0) break
            if (n > 0) seen = 1
            if (seen > 0 && ++seen > 12) break
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun startLoop() {
        if (loop != null) { if (Cfg.ears) Ears.start(this, scope); return }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifeai:loop").apply { acquire() }
        startShake()
        if (Cfg.ears) Ears.start(this, scope)
        loop = scope.launch {
            var last = 0L; var lastUsage = 0L
            runCatching { withContext(Dispatchers.Default) { Reminders.restore() } }
            while (isActive) {
                runCatching { withContext(Dispatchers.Default) { Reminders.fireDue() } }   // local reminders work even without Supabase
                if (Cfg.ready) {
                    val now = System.currentTimeMillis()
                    if (now - last >= Cfg.intervalMin * 60_000L) { last = now; autoCapture() }
                    runCatching { flush() }.onFailure { status.value = "Upload waiting: ${it.message}" }
                    runCatching { analyseNow() }.onFailure { if (status.value.startsWith("Monitoring")) status.value = "AI waiting: ${it.message?.take(70)}" }
                    if (Cfg.backEnabled) runCatching { checkRetry() }
                    runCatching { checkCalls() }
                    runCatching { AppTrack.sync(this@LifeService) }                       // foreground-app ledger (phone)
                    runCatching { Ears.flushClips() }.onFailure { Ears.status.value = "Clip upload waiting: ${it.message?.take(50)}" }
                    runCatching { Brain.processClips() }
                    runCatching { Brain.deliverNudges() }                                  // Gemini's nudges / replies
                    runCatching { Brain.pullReplies() }                                    // Coach answers from the laptop Gemini
                    runCatching { withContext(Dispatchers.Default) { Brain.fireDue() } }
                    if (now - lastUsage >= 300_000L) { lastUsage = now; runCatching { withContext(Dispatchers.Default) { Usage.sync(this@LifeService) } } }
                    runCatching { withContext(Dispatchers.Default) { Brain.sweep() } }
                    if (!status.value.startsWith("Upload waiting") && !status.value.startsWith("AI waiting")) status.value = "Monitoring — next photo in ≤${Cfg.intervalMin} min"
                } else status.value = "Set Supabase URL + key in Settings"
                delay(10_000)
            }
        }
    }

    override fun onDestroy() { stopShake(); scope.cancel(); wake?.takeIf { it.isHeld }?.release(); super.onDestroy() }

    private suspend fun analyseNow() { withContext(Dispatchers.Default) { if (Cfg.apiVision && Cfg.aiReady) Brain.processPending() else Brain.processObserved() } }

    // ───── shake → secret front photo, even while Instagram etc. is open ─────
    private fun startShake() {
        if (!Cfg.shake || sensing) return
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI); sensing = true }
    }
    private fun stopShake() { if (sensing) { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this); sensing = false } }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onSensorChanged(e: SensorEvent) {
        if (!Cfg.shake) return
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        if (g < 2.7f) return
        val t = System.currentTimeMillis()
        if (t - firstSpike > 1200) { firstSpike = t; spikes = 0 }
        if (++spikes >= 3 && t - lastShake > 10_000) {
            lastShake = t; spikes = 0
            scope.launch { capture("front", "shake"); runCatching { flush() }; runCatching { analyseNow() } }
        }
    }

    // ───── capture ─────
    private suspend fun autoCapture() {
        val cam = if (Cfg.backEnabled && !altFront) "back" else "front"; altFront = !altFront
        capture(cam, "auto")
    }

    private suspend fun capture(camera: String, source: String) = capLock.withLock {
        val ok = shootAndSave(camera, source)
        if (!ok && camera == "back" && source == "auto") shootAndSave("front", "auto-retry")
        Unit
    }

    private suspend fun shootAndSave(cam: String, source: String): Boolean {
        val id = UUID.randomUUID().toString(); val ts = Instant.now().toString()
        status.value = "Capturing ($cam)…"
        var jpg: ByteArray? = null; var err: String? = null; var dark = false
        try {
            val mode = Cfg.flashMode
            var b = Cam.shoot(this, this, cam, mode == "always")
            if (mode == "auto" && Cam.luma(b) < 55) {                       // dark → retake with LED / white-screen flash
                val b2 = runCatching { Cam.shoot(this, this, cam, true) }.getOrNull()
                if (b2 != null && Cam.luma(b2) > Cam.luma(b)) b = b2
            }
            jpg = b
            if (Cam.luma(b) < 8) { dark = true; Cfg.skippedDark = Cfg.skippedDark + 1 }   // black = pocket/covered → still uploaded, AI will call you
        } catch (e: Exception) { err = e.message ?: e.toString() }
        if (dark && source == "chat") ChatStore.add("ai", "📷 All black — phone covered or too dark. Try Flash Auto/Always in Settings.")
        val dir = File(filesDir, "outbox").apply { mkdirs() }
        if (jpg != null) File(dir, "$id.jpg").writeBytes(jpg)
        File(dir, "$id.json").writeText(JSONObject().put("id", id).put("captured_at", ts).put("camera", cam)
            .put("source", source).put("has_image", jpg != null).put("dark", dark).put("error", err ?: JSONObject.NULL)
            .put("ctx", AppTrack.ctx(this)).toString())
        lastCapture.value = (if (dark) "Last: BLACK frame (pocket?) " else if (jpg != null) "Last: " else "Failed: ") + "$cam camera, " + java.time.LocalTime.now().withNano(0)
        runCatching { flush() }
        return jpg != null
    }

    /** Upload everything in the outbox, oldest first. Files are deleted only after the DB row exists. */
    private suspend fun flush() {
        val dir = File(filesDir, "outbox")
        val metas = dir.listFiles { f -> f.extension == "json" }?.map { it to JSONObject(it.readText()) }?.sortedBy { it.second.getString("captured_at") } ?: emptyList()
        outbox.value = metas.size
        if (!Cfg.ready) return
        for ((f, j) in metas) {
            val id = j.getString("id"); val has = j.getBoolean("has_image")
            if (has) Sb.upload("$id.jpg", File(dir, "$id.jpg").readBytes())
            val dark = j.optBoolean("dark", false)
            val row = JSONObject().put("id", id).put("captured_at", j.getString("captured_at")).put("camera", j.getString("camera"))
                .put("source", j.getString("source")).put("status", if (dark) "done" else if (has) "pending" else "failed")
                .put("storage_path", if (has) "$id.jpg" else JSONObject.NULL).put("error", j.opt("error")).put("ctx", j.optString("ctx").ifBlank { JSONObject.NULL })
            if (dark) row.put("interpretation", "Completely black — phone is probably in a pocket/bag or the lens is covered.").put("activity", "covered")
                .put("usable", false).put("observed", true).put("tags", org.json.JSONArray().put("black")).put("processed_at", Instant.now().toString())
            var fresh = true
            try { Sb.insert("images", row) } catch (e: Exception) { if (e.message?.contains("409") != true) throw e; fresh = false }
            if (dark && fresh) runCatching { withContext(Dispatchers.Default) { Brain.blind("black", id, j.getString("captured_at"), j.getString("source")) } }
            f.delete(); File(dir, "$id.jpg").delete(); outbox.value = outbox.value - 1
        }
    }

    // ───── (only when the back camera is enabled) unusable back photo → take front photo ─────
    private suspend fun checkRetry() {
        val a = Sb.get("images?retry_front=eq.true&retry_handled=eq.false&select=id&limit=1")
        if (a.length() == 0) return
        Sb.patch("images?id=eq.${a.getJSONObject(0).getString("id")}", JSONObject().put("retry_handled", true))
        capture("front", "auto-retry").also { flush() }
    }

    // ───── incoming AI calls (custom ringtone MP3 loops until answered) ─────
    private suspend fun checkCalls() {
        val a = Sb.get("calls?status=eq.ringing&order=created_at.asc&limit=3")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i); val id = c.getString("id")
            val age = System.currentTimeMillis() - OffsetDateTime.parse(c.getString("created_at")).toInstant().toEpochMilli()
            if (age > 100_000) { Sb.patch("calls?id=eq.$id&status=eq.ringing", JSONObject().put("status", "missed")); Notifier.stopRing() }
            else if (rung.add(id)) { Notifier.ring(id, c.optString("opening_message")); scope.launch { capture("front", "call"); runCatching { flush() } } }   // live snapshot + phone ctx the moment the companion calls
        }
    }
}
