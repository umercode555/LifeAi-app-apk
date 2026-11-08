package com.umer.lifeai

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Device-activity ledger for the phone: WHICH app was in the foreground, WHEN and for HOW LONG (segments → table `app_log`),
 * plus a one-line "what is the phone doing right now" string ([ctx]) that is attached to every photo / audio clip so Gemini can
 * cross-check the picture against the screen (e.g. "lying on bed" + "Instagram for 38 min" = scrolling, not resting).
 */
object AppTrack {
    data class Seg(val key: String, val label: String, val start: Long, var end: Long, val open: Boolean)
    private val SKIP = setOf("com.android.systemui", "android", "com.umer.lifeai")
    private val labels = HashMap<String, String>()
    private var home: String? = null

    private fun label(c: Context, pkg: String) = labels.getOrPut(pkg) {
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** Foreground segments between [from] and [to] (consecutive same-app segments < 30 s apart are merged). */
    fun segments(c: Context, from: Long, to: Long): List<Seg> {
        if (!Usage.granted(c)) return emptyList()
        if (home == null) home = c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName ?: ""
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = usm.queryEvents(from, to); val e = UsageEvents.Event()
        val open = HashMap<String, Long>(); val out = ArrayList<Seg>()
        fun close(pkg: String, t: Long, isOpen: Boolean) {
            val s = open.remove(pkg) ?: return
            if (t - s >= 3000 && pkg !in SKIP && pkg != home) out.add(Seg(Usage.KNOWN[pkg] ?: pkg, label(c, pkg), s, t, isOpen))
        }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                1 -> open.putIfAbsent(e.packageName, e.timeStamp)
                2 -> close(e.packageName, e.timeStamp, false)
                16, 17, 26 -> open.keys.toList().forEach { close(it, e.timeStamp, false) }   // screen off / keyguard / shutdown
            }
        }
        open.keys.toList().forEach { close(it, to, true) }
        val merged = ArrayList<Seg>()
        for (s in out.sortedBy { it.start }) {
            val l = merged.lastOrNull()
            if (l != null && l.key == s.key && s.start - l.end < 30_000) merged[merged.size - 1] = Seg(l.key, l.label, l.start, maxOf(l.end, s.end), s.open)
            else merged.add(s)
        }
        return merged
    }

    /** One-line phone state for Gemini. Never throws. */
    fun ctx(c: Context): String = runCatching {
        val on = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val locked = (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        if (!Usage.granted(c)) return@runCatching "PHONE: screen ${if (on) "on" else "off"}; app unknown (Usage access not granted)."
        val now = System.currentTimeMillis(); val from = now - 10 * 60_000L
        val segs = segments(c, now - 3 * 3600_000L, now).filter { it.end > from }
        if (!on) {
            val last = segs.lastOrNull()
            return@runCatching "PHONE: screen OFF — nobody is using the phone right now." + (last?.let { " Last app: ${it.label}, ended ${(now - it.end) / 60_000} min ago." } ?: " No app used in the last 10 min.")
        }
        val tot = LinkedHashMap<String, Long>()
        segs.forEach { tot.merge(it.label, (it.end - maxOf(it.start, from)) / 1000, Long::plus) }
        val cur = segs.lastOrNull()?.takeIf { it.open }
        val top = tot.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} ${maxOf(1, it.value / 60)}m" }
        "PHONE: screen on${if (locked) " (lock screen)" else ""}. " + (cur?.let { "Now in ${it.label} for ${(now - it.start) / 60_000} min straight. " } ?: "Home screen / no app open. ") + (if (top.isNotBlank()) "Last 10 min: $top." else "")
    }.getOrDefault("PHONE: state unknown.")

    private var lastSync = 0L
    /** Push recent segments to Supabase (idempotent: id = device+start+app). Call every ~60 s. */
    suspend fun sync(c: Context) {
        val now = System.currentTimeMillis()
        if (!Cfg.ready || !Usage.granted(c) || now - lastSync < 55_000) return
        lastSync = now
        val rows = JSONArray()
        segments(c, now - 3 * 3600_000L, now).filter { it.end >= now - 6 * 60_000L }.forEach { s ->
            rows.put(JSONObject().put("id", "phone-${Cfg.deviceId}-${s.start}-${s.key}").put("device", "phone").put("app_key", s.key).put("label", s.label)
                .put("start_ts", Instant.ofEpochMilli(s.start).toString()).put("end_ts", Instant.ofEpochMilli(s.end).toString()).put("seconds", ((s.end - s.start) / 1000).toInt()))
        }
        Sb.upsert("app_log", rows, "id")
    }
}
