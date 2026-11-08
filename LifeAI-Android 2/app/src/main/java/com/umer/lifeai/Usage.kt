package com.umer.lifeai

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-app screen time, the same way StayFree does it: Android UsageStatsManager events
 * (needs the "Usage access" special permission) → foreground time per package → synced to Supabase.
 */
object Usage {
    /** Android package → shared app key, so phone + laptop add up (the extension uses the same keys). */
    val KNOWN = mapOf(
        "com.google.android.youtube" to "youtube", "com.instagram.android" to "instagram", "com.reddit.frontpage" to "reddit",
        "com.facebook.katana" to "facebook", "com.twitter.android" to "x", "com.zhiliaoapp.musically" to "tiktok", "com.ss.android.ugc.trill" to "tiktok",
        "com.whatsapp" to "whatsapp", "com.netflix.mediaclient" to "netflix", "com.linkedin.android" to "linkedin", "com.snapchat.android" to "snapchat",
        "org.telegram.messenger" to "telegram", "com.discord" to "discord", "com.spotify.music" to "spotify", "com.github.android" to "github",
        "com.openai.chatgpt" to "chatgpt", "com.pinterest" to "pinterest", "com.anthropic.claude" to "claude", "com.google.android.apps.bard" to "gemini", "com.google.android.gm" to "gmail", "com.amazon.mShop.android.shopping" to "amazon")
    private val SKIP = setOf("com.android.systemui", "android", "com.umer.lifeai")

    @Suppress("DEPRECATION")
    fun granted(c: Context): Boolean {
        val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return m == AppOpsManager.MODE_ALLOWED
    }

    data class Row(val key: String, val label: String, val seconds: Long)
    /** App in the foreground right now + its total today (set by the last [today] call). */
    @Volatile var current: Row? = null

    /** Foreground seconds per app since local midnight. */
    fun today(c: Context): List<Row> {
        if (!granted(c)) return emptyList()
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); val end = System.currentTimeMillis()
        val ev = usm.queryEvents(start, end); val e = UsageEvents.Event()
        val open = HashMap<String, Long>(); val tot = HashMap<String, Long>()
        fun close(pkg: String, t: Long) { val s = open.remove(pkg) ?: return; val d = t - s; if (d in 1..(12 * 3600_000L)) tot.merge(pkg, d, Long::plus) }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                1 -> open.putIfAbsent(e.packageName, e.timeStamp)               // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
                2 -> close(e.packageName, e.timeStamp)                         // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
                17, 26 -> open.keys.toList().forEach { close(it, e.timeStamp) } // screen off / shutdown
            }
        }
        val nowPkg = open.maxByOrNull { it.value }?.key
        open.keys.toList().forEach { close(it, end) }
        val pm = c.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return tot.filter { it.key !in SKIP && it.key != home && it.value >= 5_000 }.map { (pkg, ms) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            Row(KNOWN[pkg] ?: pkg, label, ms / 1000)
        }.groupBy { it.key }.map { (k, v) -> Row(k, v.first().label, v.sumOf { it.seconds }) }.sortedByDescending { it.seconds }
            .also { rows -> val nk = nowPkg?.let { KNOWN[it] ?: it }; current = rows.firstOrNull { it.key == nk } }
    }

    fun topLine(c: Context, n: Int = 3): String = today(c).take(n).joinToString(", ") { "${it.label} ${it.seconds / 60}m" }

    suspend fun sync(c: Context) {
        if (!Cfg.ready) return
        val rows = today(c); val day = LocalDate.now().toString(); val now = java.time.Instant.now().toString()
        val a = JSONArray(); rows.forEach { a.put(JSONObject().put("day", day).put("device", "phone").put("app_key", it.key).put("label", it.label).put("seconds", it.seconds).put("updated_at", now)) }
        Sb.upsert("usage", a, "day,device,app_key")
        Sb.upsert("devices", JSONArray().put(JSONObject().put("id", "phone-${Cfg.deviceId}").put("device", "phone").put("label", Build.MODEL + (current?.let { " — now on ${it.label} (${it.seconds / 60}m today)" } ?: "")).put("pair_code", Cfg.pairCode).put("last_seen", now)), "id")
    }
}
