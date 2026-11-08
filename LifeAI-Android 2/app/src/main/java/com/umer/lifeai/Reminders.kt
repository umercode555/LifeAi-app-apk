package com.umer.lifeai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Real reminders. The phone stores them locally FIRST (so they work offline and even if Supabase is down) and arms an
 * Android alarm that wakes the app at the exact time – no foreground service needed. Supabase gets a mirror row
 * (items.kind='reminder') so the laptop / calendar can see it. Nothing is "set" until [add] has returned.
 */
object Reminders {
    data class R(val id: String, val text: String, val at: Long, val fired: Boolean = false, val itemId: String? = null, val createdAt: Long = System.currentTimeMillis())

    const val ACTION = "com.umer.lifeai.REMIND"
    private val lock = Any()
    private fun file() = File(Cfg.app.filesDir, "reminders.json")

    fun all(): List<R> = synchronized(lock) {
        runCatching {
            val a = JSONArray(file().readText())
            List(a.length()) { val o = a.getJSONObject(it); R(o.getString("id"), o.getString("t"), o.getLong("at"), o.optBoolean("f"), o.optString("item").takeIf { s -> s.isNotBlank() && s != "null" }, o.optLong("c")) }
        }.getOrDefault(emptyList())
    }
    private fun save(l: List<R>) = synchronized(lock) {
        val keep = l.sortedBy { it.at }.takeLast(300)
        file().writeText(JSONArray().also { a -> keep.forEach { a.put(JSONObject().put("id", it.id).put("t", it.text).put("at", it.at).put("f", it.fired).put("item", it.itemId ?: "").put("c", it.createdAt)) } }.toString())
    }
    fun upcoming() = all().filter { !it.fired }.sortedBy { it.at }
    fun history() = all().filter { it.fired }.sortedByDescending { it.at }

    private fun pi(ctx: Context, r: R, flag: Int = PendingIntent.FLAG_UPDATE_CURRENT) =
        PendingIntent.getBroadcast(ctx, r.id.hashCode(), Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", r.id), flag or PendingIntent.FLAG_IMMUTABLE)

    fun canExact(ctx: Context = Cfg.app) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun arm(r: R) {
        val ctx = Cfg.app; val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = pi(ctx, r)
        runCatching {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, p)
        }.onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, p) } }
    }
    fun isArmed(r: R): Boolean = PendingIntent.getBroadcast(Cfg.app, r.id.hashCode(), Intent(Cfg.app, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", r.id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
    private fun disarm(r: R) { runCatching { val am = Cfg.app.getSystemService(Context.ALARM_SERVICE) as AlarmManager; pi(Cfg.app, r).let { am.cancel(it); it.cancel() } } }

    /** Creates + arms a reminder. Returns it only when it is really scheduled. */
    suspend fun add(text: String, atMs: Long): R {
        require(atMs > System.currentTimeMillis() - 5_000) { "that time has already passed" }
        val dup = upcoming().firstOrNull { Math.abs(it.at - atMs) < 60_000 && it.text.equals(text, true) }
        if (dup != null) return dup
        var r = R(UUID.randomUUID().toString(), text.ifBlank { "Reminder" }.take(160), atMs)
        save(all() + r); arm(r)
        if (Cfg.ready) runCatching {
            val row = Sb.insert("items", JSONObject().put("kind", "reminder").put("title", r.text).put("priority", 2).put("deadline", Instant.ofEpochMilli(atMs).toString()))
            val iid = row.optString("id").takeIf { it.isNotBlank() }
            if (iid != null) { r = r.copy(itemId = iid); save(all().map { if (it.id == r.id) r else it }) }
        }
        return r
    }

    fun delete(id: String) {
        val r = all().firstOrNull { it.id == id } ?: return
        disarm(r); save(all().filter { it.id != id })
        r.itemId?.let { iid -> Brain.scope.launch { runCatching { Sb.patch("items?id=eq.$iid", JSONObject().put("status", "deleted")) } } }
    }

    /** Delivers a reminder: notification + sound + chat message + calendar record. Idempotent. */
    suspend fun fire(id: String, late: Boolean = false) {
        val r = synchronized(lock) { val cur = all().firstOrNull { it.id == id && !it.fired } ?: return; save(all().map { if (it.id == id) it.copy(fired = true) else it }); cur }
        val mins = ((System.currentTimeMillis() - r.at) / 60_000).toInt()
        val txt = if (late && mins >= 2) "${r.text}  (was due $mins min ago)" else r.text
        Notifier.remind("⏰ Reminder", txt)
        ChatStore.add("ai", "⏰ Reminder: $txt")
        if (Cfg.ready) runCatching {
            r.itemId?.let { Sb.patch("items?id=eq.$it", JSONObject().put("status", "done")) }
            Sb.insert("decisions", JSONObject().put("action", "remind").put("reason", "Scheduled reminder").put("message", r.text).put("activity", "reminder").put("handled", true))
        }
    }

    /** Backup path (foreground service calls this every 10 s) in case an alarm was delayed or killed by the OEM. */
    suspend fun fireDue() { val now = System.currentTimeMillis(); upcoming().filter { it.at <= now }.forEach { fire(it.id, late = true) } }

    /** After reboot / app update: re-arm everything; overdue (<12 h) ones are delivered right away. */
    suspend fun restore() {
        val now = System.currentTimeMillis()
        for (r in upcoming()) if (r.at <= now) { if (now - r.at < 12 * 3600_000L) fire(r.id, true) else save(all().map { if (it.id == r.id) it.copy(fired = true) else it }) } else arm(r)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return; val p = goAsync()
        Brain.scope.launch { try { Reminders.fire(id) } finally { p.finish() } }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = goAsync()
        Brain.scope.launch { try { Reminders.restore(); if (Cfg.monitoring) LifeService.start(c) } finally { p.finish() } }
    }
}
