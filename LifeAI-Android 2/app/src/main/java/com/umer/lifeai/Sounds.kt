package com.umer.lifeai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/** Custom MP3s for ordinary notifications ("notif") and AI call ringing ("ring"). Falls back to system defaults. */
object Sounds {
    private val players = HashMap<String, MediaPlayer>(); private val rings = HashMap<String, Ringtone>()
    private val h = Handler(Looper.getMainLooper())

    fun file(kind: String) = File(Cfg.app.filesDir, "snd_$kind.mp3")
    fun has(kind: String) = file(kind).exists()

    fun import(ctx: Context, uri: Uri, kind: String): Boolean = runCatching {
        ctx.contentResolver.openInputStream(uri)!!.use { i -> file(kind).outputStream().use { o -> i.copyTo(o) } }; true
    }.getOrDefault(false)

    fun reset(kind: String) { stop(kind); file(kind).delete() }

    fun play(kind: String, loop: Boolean = false, maxMs: Long = 0) {
        stop(kind)
        val ring = kind == "ring"
        val attrs = AudioAttributes.Builder().setUsage(if (ring) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        runCatching {
            if (has(kind)) {
                val mp = MediaPlayer(); mp.setAudioAttributes(attrs); mp.setDataSource(file(kind).absolutePath); mp.isLooping = loop
                mp.setOnCompletionListener { if (!loop) { it.release(); players.remove(kind) } }; mp.prepare(); mp.start(); players[kind] = mp
            } else {
                val r = RingtoneManager.getRingtone(Cfg.app, RingtoneManager.getDefaultUri(if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION))
                r.setAudioAttributes(attrs); if (Build.VERSION.SDK_INT >= 28) r.isLooping = loop; r.play(); rings[kind] = r
            }
        }
        if (maxMs > 0) h.postDelayed({ stop(kind) }, maxMs)
    }

    fun stop(kind: String) {
        players.remove(kind)?.let { runCatching { it.stop() }; runCatching { it.release() } }
        rings.remove(kind)?.let { runCatching { it.stop() } }
    }
}
