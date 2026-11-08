package com.umer.lifeai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

private fun JSONObject.strs(k: String): Set<String> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() }.toSet() } ?: emptySet()
private fun arr(c: Collection<String>) = JSONArray().also { a -> c.forEach { a.put(it) } }

/** Chat history, persisted locally (kept compact – only the tail is ever sent to Gemini). */
data class ChatMsg(val role: String, val text: String, val ts: Long = System.currentTimeMillis())
object ChatStore {
    val msgs = MutableStateFlow<List<ChatMsg>>(emptyList())
    val busy = MutableStateFlow(false)
    /** role: user | ai | sys  (sys = verified result of an action, written by the APP, never by the model) */
    @Volatile var photoAskedAt = 0L
    private fun f() = File(Cfg.app.filesDir, "chat.json")
    fun load() { if (msgs.value.isNotEmpty()) return; runCatching { val a = JSONArray(f().readText()); msgs.value = List(a.length()) { val o = a.getJSONObject(it); ChatMsg(o.getString("r"), o.getString("t"), o.optLong("ts")) } } }
    fun add(role: String, text: String) { msgs.update { (it + ChatMsg(role, text)).takeLast(80) }; save() }
    fun replace(l: List<ChatMsg>) { msgs.value = l; save() }
    private fun save() { runCatching { val a = JSONArray(); msgs.value.forEach { a.put(JSONObject().put("r", it.role).put("t", it.text).put("ts", it.ts)) }; f().writeText(a.toString()) } }
}

object Store {
    private val zone get() = ZoneId.systemDefault()
    fun dayStartIso(d: LocalDate = LocalDate.now()) = d.atStartOfDay(zone).toInstant().toString()
    fun dayStartMs(d: LocalDate = LocalDate.now()) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    fun parse(j: JSONObject, done: Int = 0) = Item(
        id = j.getString("id"), title = j.getString("title"), kind = j.optString("kind", "task"), priority = j.optInt("priority", 2),
        status = j.optString("status", "active"),
        deadline = j.optString("deadline").takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
        targetMin = j.optInt("target_min", 0), doneMin = done, acts = j.strs("acts"), keywords = j.strs("keywords"), avoid = j.strs("avoid"),
        winStart = if (j.isNull("win_start")) null else j.optInt("win_start"), winEnd = if (j.isNull("win_end")) null else j.optInt("win_end"),
        details = j.optString("details").takeIf { it != "null" } ?: "")

    /** Items (active + paused) with minutes already achieved today. */
    suspend fun items(raw: ((JSONObject) -> Unit)? = null): List<Item> {
        val a = Sb.get("items?status=in.(active,paused)&order=created_at.asc&limit=200")
        val ev = Sb.get("events?type=eq.progress&ts=gte.${dayStartIso()}&select=item_id,minutes&limit=2000")
        val done = HashMap<String, Int>(); for (i in 0 until ev.length()) { val e = ev.getJSONObject(i); done.merge(e.optString("item_id"), e.optInt("minutes"), Int::plus) }
        return List(a.length()) { val j = a.getJSONObject(it); raw?.invoke(j); parse(j, done[j.getString("id")] ?: 0) }
    }

    suspend fun event(itemId: String, type: String, minutes: Int = 0, note: String? = null, tsIso: String? = null) {
        val o = JSONObject().put("item_id", itemId).put("type", type).put("minutes", minutes).put("note", note ?: JSONObject.NULL)
        if (tsIso != null) o.put("ts", tsIso)
        Sb.insert("events", o)
    }

    suspend fun postpone(i: Item) {
        val o = JSONObject(); if (i.deadline != null) o.put("deadline", java.time.Instant.ofEpochMilli(i.deadline + 24 * 3600_000L).toString())
        if (o.length() > 0) Sb.patch("items?id=eq.${i.id}", o)
        event(i.id, "postponed", 0, "moved to tomorrow")
    }

    private fun parseDeadline(s: String?): String? {
        if (s.isNullOrBlank() || s == "null") return null
        return runCatching { if (s.length <= 10) LocalDate.parse(s).atTime(23, 0).atZone(zone).toInstant().toString() else LocalDateTime.parse(s).atZone(zone).toInstant().toString() }.getOrNull()
    }
    private fun parseWin(s: String?): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").find(s ?: return null) ?: return null
        val g = m.groupValues.drop(1).map { it.toInt() }; return (g[0] * 60 + g[1]) to (g[2] * 60 + g[3])
    }

    class Applied(val lines: List<String>, val failed: Int, private val summary: String) { override fun toString() = summary; val ok get() = lines.isNotEmpty() }

    private fun parseLocal(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let { x ->
        runCatching { LocalDateTime.parse(x.take(16)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(x).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Creates a reminder from an AI op / local parse and returns the human confirmation line (only after it is truly armed). */
    suspend fun remind(text: String, atMs: Long): String {
        val r = Reminders.add(text, atMs)
        check(Reminders.isArmed(r)) { "alarm could not be armed" }
        val exact = if (Reminders.canExact()) "" else " (approx. — allow “Alarms & reminders” in Settings for exact time)"
        return "⏰ Reminder set for ${Lang.clock(java.time.Instant.ofEpochMilli(r.at).atZone(zone).toLocalDateTime(), LocalDateTime.now())} — “${r.text}”$exact"
    }

    suspend fun watch(title: String, say: String, acts: Collection<String>, keywords: Collection<String>, once: Boolean, cooldown: Int, priority: Int, look: String = ""): String {
        val a = acts.map { it.lowercase() }.filter { it in ACTIVITIES }.toSet(); val k = keywords.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
        require(a.isNotEmpty() || k.isNotEmpty()) { "nothing for the camera to look for" }
        val details = buildString { if (say.isNotBlank()) append("SAY: ").append(say.take(200)).append('\n'); if (once) append("ONCE\n"); if (look.isNotBlank()) append(look.take(300)) }.trim()
        Sb.insert("items", JSONObject().put("kind", "rule").put("title", title.ifBlank { "Watch: " + (a + k).joinToString("/") }.take(120)).put("details", details)
            .put("priority", priority.coerceIn(1, 3)).put("target_min", cooldown.coerceIn(0, 1440)).put("acts", arr(a)).put("keywords", arr(k)))
        val what = (a.map { it.replace('_', ' ') } + k).joinToString(" / ")
        return "👁 Watching for: $what → I'll say “${say.ifBlank { title }}”" + (if (once) " (one time)" else " (at most every ${if (cooldown > 0) cooldown else Engine.RULE_GAP} min)")
    }

    /** Apply AI-produced ops. Every op yields one verified confirmation line; failures are reported, never hidden. */
    suspend fun apply(ops: JSONArray): Applied {
        val all = if (Cfg.ready) runCatching { JSONArray(Sb.get("items?status=neq.deleted&select=id,title&limit=300").toString()) }.getOrDefault(JSONArray()) else JSONArray()
        fun resolve(p: String): String? = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.getString("id").startsWith(p.trim()) }?.getString("id")
        val c = LinkedHashMap<String, Int>(); val lines = mutableListOf<String>(); var failed = 0
        for (n in 0 until ops.length()) {
            val o = ops.optJSONObject(n) ?: continue; val op = o.optString("op")
            try {
                when (op) {
                    "remind" -> {
                        val at = parseLocal(o.optString("at")) ?: o.optInt("in_min", 0).takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 60_000L } ?: throw RuntimeException("no time given")
                        lines += remind(o.optString("text", o.optString("title")), at)
                    }
                    "watch" -> lines += watch(o.optString("title"), o.optString("say"), o.strs("acts"), o.strs("keywords"), o.optBoolean("once"), o.optInt("cooldown_min", 0), o.optInt("priority", 2), o.optString("details"))
                    "add" -> {
                        val kind = o.optString("kind", "task").takeIf { it in setOf("goal", "task", "habit", "rule") } ?: "task"
                        var det = o.optString("details").take(1500)
                        if (kind == "rule" && o.optString("say").isNotBlank()) det = ("SAY: " + o.optString("say") + (if (o.optBoolean("once")) "\nONCE" else "") + "\n" + det).trim()
                        val row = JSONObject().put("kind", kind).put("title", o.getString("title").take(120)).put("details", det)
                            .put("priority", o.optInt("priority", 2).coerceIn(1, 3)).put("target_min", (if (kind == "rule") o.optInt("cooldown_min", o.optInt("target_min", 0)) else o.optInt("target_min", 0)).coerceIn(0, 1440))
                            .put("acts", arr(o.strs("acts").filter { it in ACTIVITIES })).put("keywords", arr(o.strs("keywords"))).put("avoid", arr(o.strs("avoid")))
                        parseDeadline(o.optString("deadline"))?.let { row.put("deadline", it) }
                        parseWin(o.optString("window"))?.let { row.put("win_start", it.first).put("win_end", it.second) }
                        Sb.insert("items", row)
                        lines += "✅ Added ${when (kind) { "goal" -> "goal"; "habit" -> "habit"; "rule" -> "rule"; else -> "to-do" }}: ${o.getString("title").take(70)}"
                    }
                    "update", "pause", "resume", "done", "delete" -> {
                        val id = resolve(o.optString("id")) ?: throw RuntimeException("couldn't find that item")
                        val p = JSONObject().put("updated_at", java.time.Instant.now().toString())
                        when (op) {
                            "pause" -> p.put("status", "paused"); "resume" -> p.put("status", "active")
                            "done" -> p.put("status", "done"); "delete" -> p.put("status", "deleted")
                            else -> {
                                if (o.has("title")) p.put("title", o.getString("title").take(120)); if (o.has("details")) p.put("details", o.getString("details").take(1500))
                                if (o.has("priority")) p.put("priority", o.getInt("priority").coerceIn(1, 3)); if (o.has("target_min")) p.put("target_min", o.getInt("target_min"))
                                if (o.has("acts")) p.put("acts", arr(o.strs("acts").filter { it in ACTIVITIES })); if (o.has("keywords")) p.put("keywords", arr(o.strs("keywords")))
                                if (o.has("avoid")) p.put("avoid", arr(o.strs("avoid")))
                                if (o.has("deadline")) p.put("deadline", parseDeadline(o.optString("deadline")) ?: JSONObject.NULL)
                                parseWin(o.optString("window"))?.let { p.put("win_start", it.first).put("win_end", it.second) }
                            }
                        }
                        Sb.patch("items?id=eq.$id", p)
                        if (op == "done") event(id, "completed", 0, "marked done")
                        val verb = mapOf("update" to "Updated", "pause" to "Paused", "resume" to "Resumed", "done" to "Marked done", "delete" to "Deleted")[op]
                        val name = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { x -> x.getString("id") == id }?.optString("title") ?: o.optString("title").ifBlank { "item" }
                        lines += "✔ $verb: ${name.take(70)}"
                    }
                    else -> continue
                }
                c.merge(when (op) { "add" -> "added"; "remind" -> "reminders"; "watch" -> "rules"; "update" -> "updated"; "pause" -> "paused"; "resume" -> "resumed"; "done" -> "done"; else -> "deleted" }, 1, Int::plus)
            } catch (e: Exception) { failed++; lines += "✖ Couldn't $op: ${e.message?.take(80)}"; c.merge("failed", 1, Int::plus) }
        }
        return Applied(lines, failed, if (c.isEmpty()) "nothing changed" else c.entries.joinToString(" · ") { "${it.value} ${it.key}" })
    }
}
