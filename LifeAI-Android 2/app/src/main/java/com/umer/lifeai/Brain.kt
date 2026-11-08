package com.umer.lifeai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Orchestrator: photo → Gemini vision → observation → Engine (priority policy) → record + notify/call.
 * Token policy: images shrunk to one tile, JSON-only answers with tiny max output, short rolling history, compact item list,
 * Gemini is NOT asked to decide when to interrupt (Engine does that for free) – only to phrase the sentence.
 */
object Brain {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val ACT_LIST = ACTIVITIES.joinToString("|")
    private val COMMIT = Regex("(?i)(send (this|it|that|all)( now)? to (the )?gemini|(ok|okay),? now send|send it now|save (this|it|all) now|finali[sz]e (this|it))")
    fun isCommit(t: String) = COMMIT.containsMatchIn(t)
    private fun short(id: String) = id.take(6)

    private val VISION_SYS = "Eyes of a life-tracking app for Umer. Reply JSON only: {\"ok\":bool,\"desc\":\"max 22 words: what is visible, what he appears to be doing\",\"act\":\"$ACT_LIST\",\"conf\":\"high|med|low\",\"tags\":[max 6 lowercase keywords: foods, objects, e.g. 'hair messy','laptop'; add 'ceiling' when ONLY a ceiling/wall/blank surface is visible],\"hits\":[ids of RULES that clearly apply now]}. ok=false if black/blocked/blurry. Say 'appears to' when unsure."
    private val CHAT_SYS = """You are LifeAI, Umer's personal AI. You are NOT a chatbot: you have a camera that sees him (Vision), a real scheduler for reminders, and a rule engine that checks every photo. Casual English/Roman Urdu, under 40 words.
YOU MUST ACT. Anything he wants remembered, reminded, watched for, tracked or planned MUST be returned in "actions". Never say it is set/saved/scheduled unless the action is in the list - the app executes it and shows him the verified result. If a reminder has no time, ask ONE short question and return no action. Never claim to see him right now: sightings only come from the camera as OBSERVATIONS.
JSON only: {"reply":"","actions":[],"now":null|{"act":"$ACT_LIST","desc":"","intentional":bool,"min":int,"break":int}}
ACTIONS:
{"op":"remind","text":"what to remind him","at":"YYYY-MM-DDTHH:mm" or null,"in_min":int or null}
{"op":"watch","title":"short","acts":[from ACTIVITIES],"keywords":["words the camera should spot"],"say":"exact sentence you will say when you SEE it","once":bool,"cooldown_min":int,"priority":1-3}
{"op":"add","kind":"goal|task|habit","title":"","details":"","priority":1-3,"target_min":0,"deadline":null,"acts":[],"keywords":[],"avoid":[]}
{"op":"update|pause|resume|done|delete","id":"6-char id from ITEMS"}
EXAMPLES:
"remind me at 7:15 to leave" -> {"reply":"Done - 7:15 PM.","actions":[{"op":"remind","text":"Leave","at":"<next 19:15 as YYYY-MM-DDTHH:mm>"}]}
"whenever you see me sitting, tell me to get up" -> actions:[{"op":"watch","title":"Get up when sitting","acts":["sitting"],"keywords":[],"say":"Get up!","once":false,"cooldown_min":20,"priority":2}]
"I want to go as soon as I get a burger" -> actions:[{"op":"watch","title":"Leave once burger arrives","acts":["eating"],"keywords":["burger"],"say":"Your burger is here - you wanted to leave right after.","once":true,"cooldown_min":0,"priority":2}]
"I want to lose 5 kg" -> actions: one goal + daily exercise task/habit.
If he is only chatting, actions is []. If he reports what he is doing now, fill now."""
    private val SAW_SYS = "You are LifeAI, Umer's coach. The camera just showed you the OBSERVATION (he may have shown it on purpose). React like a smart friend in max 30 words, casual: say what you SEE (only what the observation says), connect it to his goals/rules/reminders if relevant, add one useful nudge or question. JSON only: {\"reply\":\"\"}"
    private val EXTRACT_SYS = "Turn the conversation into actions the app can execute. Output JSON only: {\"actions\":[...]} using ONLY these shapes: {\"op\":\"remind\",\"text\":\"\",\"at\":\"YYYY-MM-DDTHH:mm\"|null,\"in_min\":int|null} {\"op\":\"watch\",\"title\":\"\",\"acts\":[subset of $ACT_LIST],\"keywords\":[],\"say\":\"exact words to say when the camera sees it\",\"once\":bool,\"cooldown_min\":int,\"priority\":1-3} {\"op\":\"add\",\"kind\":\"goal|task|habit\",\"title\":\"\",\"details\":\"\",\"priority\":1-3,\"target_min\":0,\"deadline\":null,\"acts\":[],\"keywords\":[],\"avoid\":[]}. Use the whole conversation (a later 'remind me at 7:15' refers to the earlier plan). A conditional plan like 'as soon as I get X do Y' = watch with keywords [X]. Empty list if nothing concrete."
    private val CALL_SYS = "You are Umer's AI coach on a LIVE phone call. 1-2 short casual sentences, no markdown. Firm but listen: accept good reasons. JSON only: {\"reply\":\"\",\"end\":bool,\"snooze\":minutes you promise not to bother him (0 if none)}. end=true only when the talk is finished."
    private fun commitSys() = "Turn the conversation into JSON ops for the app. Keep ALL his details, never oversimplify. {\"ops\":[{\"op\":\"add|update|delete|pause|resume|done\",\"id\":\"6-char id (not for add)\",\"kind\":\"goal|task|habit|rule\",\"title\":\"\",\"details\":\"full plan/specifics\",\"priority\":1-3 (3=high, default 2),\"target_min\":daily minutes or 0,\"deadline\":\"YYYY-MM-DD or YYYY-MM-DDTHH:mm or null\",\"acts\":[subset of $ACT_LIST that count as doing it],\"keywords\":[],\"avoid\":[activities or photo keywords that violate it],\"window\":\"HH:mm-HH:mm or null\"}],\"summary\":\"one line\"}. Split big ambitions into one goal + daily tasks/habits. kind=rule is a tiny instruction (details = what to look for in a photo). Today-only tasks get deadline today. Today is ${LocalDate.now()}."

    private fun compact(items: List<Item>): String = if (items.isEmpty()) "(none)" else items.take(40).joinToString("\n") {
        "${short(it.id)}|${it.title.take(60)}|${it.kind}|P${it.priority}|${it.status}|${it.doneMin}/${it.targetMin}m|${it.deadline?.let { d -> java.time.Instant.ofEpochMilli(d).atZone(ZoneId.systemDefault()).let { z -> if (it.kind == "reminder") z.format(DateTimeFormatter.ofPattern("d MMM HH:mm")) else z.toLocalDate().toString() } } ?: "-"}${if (it.kind == "rule" && it.say.isNotBlank()) "|say:" + it.say.take(50) else ""}"
    }

    private suspend fun todayLine(): String = runCatching {
        val a = Sb.get("activity?ts=gte.${Store.dayStartIso()}&select=activity,minutes,wasted&limit=500")
        val m = HashMap<String, Int>(); var w = 0
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); m.merge(o.optString("activity"), o.optInt("minutes"), Int::plus); if (o.optBoolean("wasted")) w += o.optInt("minutes") }
        if (m.isEmpty()) "no records yet" else m.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key} ${it.value}m" } + " | wasted ${w}m"
    }.getOrDefault("?")

    // ───────────── image pipeline ─────────────
    /** Photos analysed by the LAPTOP (Gemini web engine): read its result and run the priority engine on the phone. No vision tokens. */
    suspend fun processObserved(): Int {
        if (!Cfg.ready) return 0
        val rows = Sb.get("images?status=eq.done&observed=eq.false&order=captured_at.asc&limit=3"); var n = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val id = row.getString("id")
            if (Sb.claim("images?id=eq.$id&observed=eq.false", JSONObject().put("observed", true)).length() == 0) continue
            val src = row.optString("source"); val ts = row.getString("captured_at")
            if (src == "call") { n++; continue }   // live snapshot taken when the companion rings: context for Gemini only, never a second decision
            val tags = row.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it).lowercase() }.filter { it.isNotBlank() } } ?: emptyList()
            if (row.optBoolean("usable", true).not() || "ceiling" in tags) {
                blind(if ("ceiling" in tags) "ceiling" else "black", id, ts, src)
                if (src == "chat") { chatPhotoDone(); ChatStore.add("ai", "📷 Couldn't see you — only a dark view or the ceiling.") }; n++; continue
            }
            Cfg.blindCount = 0
            val act = row.optString("activity", "unknown").let { if (it in ACTIVITIES) it else "other" }
            val desc = row.optString("interpretation").take(300)
            val hits = row.optJSONArray("rules_hit")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            val capMs = runCatching { OffsetDateTime.parse(ts).toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            try {
                observe(act, desc, tags, hits, if (src == "chat") "chat" else "camera", ts, capMs, id, null, onPurpose = src in PURPOSE); n++
                curious(row.optString("question"), src)
            } catch (e: Exception) {   // never lose an observation silently: give it back (max 3 tries) and say why
                val tries = row.optInt("attempts", 0) + 1
                runCatching { Sb.patch("images?id=eq.$id", JSONObject().put("observed", tries >= 3).put("attempts", tries).put("error", ("decision failed: " + e.message).take(250))) }
                if (src == "chat") ChatStore.add("sys", "⚠ I saw the photo but failed to decide what to do: ${e.message?.take(80)}")
            }
        }
        return n
    }

    /** Gemini's decisions (made on the laptop) arrive as `decisions` rows: remind = notification, reply = answer to a photo he showed on purpose. Calls arrive as `calls` rows. */
    suspend fun deliverNudges(): Int {
        if (!Cfg.ready) return 0
        val rows = Sb.get("decisions?made_by=eq.gemini&action=in.(remind,reply)&handled=eq.false&order=ts.asc&limit=3"); var n = 0
        for (i in 0 until rows.length()) {
            val d = rows.getJSONObject(i); val id = d.getString("id"); val msg = d.optString("message").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (Sb.claim("decisions?id=eq.$id&handled=eq.false", JSONObject().put("handled", true)).length() == 0) continue
            if (d.getString("action") == "reply") ChatStore.add("ai", "💬 $msg") else { ChatStore.add("ai", "🔔 $msg"); Notifier.remind("Life AI", msg.take(120)) }
            n++
        }
        return n
    }

    /** Gemini (laptop) is curious: at most one question per 40 min, only for automatic photos. */
    private fun curious(q: String, src: String) {
        val t = q.trim(); val now = System.currentTimeMillis()
        if (t.length < 5 || t == "null" || src in PURPOSE || now - Cfg.lastCurious < 40 * 60_000L) return
        Cfg.lastCurious = now; ChatStore.add("ai", "🤔 $t"); Notifier.remind("Life AI", t)
    }

    /** Keyword clips analysed by Gemini on the laptop → show what it heard in the chat (the call, if any, is already queued as a `calls` row). */
    suspend fun processClips(): Int {
        if (!Cfg.ready) return 0
        val rows = Sb.get("clips?status=eq.done&observed=eq.false&order=started_at.asc&limit=3"); var n = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i); val id = r.getString("id")
            if (Sb.claim("clips?id=eq.$id&observed=eq.false", JSONObject().put("observed", true)).length() == 0) continue
            ChatStore.add("ai", "🎧 Heard (${r.optString("trigger")}): ${r.optString("interpretation").take(240)}"); n++
        }
        return n
    }

    /** (Optional, needs vision credits) Analyse up to 3 pending photos (downloaded from storage → safe to retry after offline periods). */
    suspend fun processPending(): Int {
        if (!Cfg.aiReady || !Cfg.ready) return 0
        var n = 0
        repeat(3) {
            val rows = Sb.get("images?status=eq.pending&storage_path=not.is.null&attempts=lt.5&order=captured_at.asc&limit=1")
            if (rows.length() == 0) return n
            val row = rows.getJSONObject(0); val id = row.getString("id")
            if (Sb.claim("images?id=eq.$id&status=eq.pending", JSONObject().put("status", "processing")).length() == 0) return n
            try { analyze(row); n++ } catch (e: Exception) {
                val a = row.optInt("attempts", 0) + 1
                Sb.patch("images?id=eq.$id", JSONObject().put("status", if (a >= 5) "failed" else "pending").put("attempts", a).put("error", (e.message ?: "error").take(250)))
                return n
            }
        }
        return n
    }

    private suspend fun analyze(row: JSONObject) {
        val id = row.getString("id"); val cam = row.optString("camera"); val src = row.optString("source")
        val items = Store.items()
        val rules = items.filter { it.kind == "rule" && it.status == "active" }
        val sys = VISION_SYS + if (rules.isEmpty()) "" else "\nRULES: " + rules.joinToString("; ") { "${short(it.id)}=${it.title.take(70)}" }
        val img = Cam.shrink(Sb.download(row.getString("storage_path"))); if (img.isEmpty()) throw RuntimeException("empty image")
        val j = Gemini.parse(Gemini.ask(sys, user = "$cam camera photo.", image = img, maxOut = 220)) ?: throw RuntimeException("vision reply not JSON")
        val ok = j.optBoolean("ok", true); val desc = j.optString("desc").take(300)
        val act = j.optString("act", "unknown").let { if (it in ACTIVITIES) it else "other" }
        val tags = j.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it).lowercase() }.filter { it.isNotBlank() } } ?: emptyList()
        val hits = j.optJSONArray("hits")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
        Sb.patch("images?id=eq.$id", JSONObject().put("status", "done").put("interpretation", desc).put("activity", act).put("usable", ok)
            .put("confidence", j.optString("conf")).put("tags", JSONArray(tags)).put("processed_at", Instant.now().toString()).put("error", JSONObject.NULL)
            .put("retry_front", !ok && cam == "back" && src != "auto-retry" && Cfg.backEnabled).put("observed", true))
        if (!ok || "ceiling" in tags) { blind(if (ok) "ceiling" else "black", id, row.getString("captured_at"), src); if (src == "chat") { chatPhotoDone(); ChatStore.add("ai", "📷 Couldn't see you — only ${if (ok) "the ceiling" else "a dark/blocked view"}.") }; return }
        Cfg.blindCount = 0
        val capMs = runCatching { OffsetDateTime.parse(row.getString("captured_at")).toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        observe(act, desc, tags, hits, if (src == "chat") "chat" else "camera", row.getString("captured_at"), capMs, id, items, onPurpose = src in PURPOSE)
    }

    /** Pocket (black) or ceiling-only photo. 2 in a row (~2 intervals) → AI calls you; at most one such call per 30 min. */
    suspend fun blind(kind: String, imageId: String?, tsIso: String, source: String) {
        runCatching { Sb.insert("activity", JSONObject().put("ts", tsIso).put("source", "camera").put("activity", "unknown").put("description", if (kind == "ceiling") "Camera only sees the ceiling/wall" else "Black frame (pocket/covered)")
            .put("minutes", Cfg.intervalMin).put("image_id", imageId ?: JSONObject.NULL)) }
        Cfg.blindCount = Cfg.blindCount + 1
        // v4: NO local decision. The laptop Gemini sees the run of covered/ceiling frames (loopBlind) and decides whether to call.
    }

    /** Shared by photos AND chat/voice progress reports. Returns a one-line summary. */
    suspend fun observe(act: String, desc: String, tags: List<String>, hitsShort: List<String>, source: String, tsIso: String, capMs: Long,
                        imageId: String?, preloaded: List<Item>? = null, intentional: Boolean = false, minutes: Int? = null, snoozeMin: Int = 0, onPurpose: Boolean = false): String = lock.withLock {
        val items = preloaded ?: Store.items(); val now = System.currentTimeMillis()
        val stale = !onPurpose && now - capMs > 25 * 60_000L
        val delta = minutes ?: if (Cfg.lastObs == 0L) Cfg.intervalMin else ((capMs - Cfg.lastObs) / 60_000).toInt().coerceIn(1, maxOf(5, Cfg.intervalMin * 2)).let { if (stale) Cfg.intervalMin else it }
        if (capMs > Cfg.lastObs) Cfg.lastObs = capMs
        suspend fun record(v: Verdict?) = Sb.insert("activity", JSONObject().put("ts", tsIso).put("source", source).put("activity", act).put("description", desc.take(300)).put("minutes", delta)
            .put("wasted", v?.wasted ?: false).put("intentional", intentional).put("item_ids", JSONArray(v?.fulfilled ?: emptyList<String>())).put("image_id", imageId ?: JSONObject.NULL))
        if (stale) { record(null); return@withLock "recorded (old photo, no decision)" }
        if (onPurpose && imageId != null) { chatPhotoDone(); ChatStore.add("ai", "📷 I see: $desc") }

        val st = State.decode(Cfg.engine); if (snoozeMin > 0) st.intentUntil = now + snoozeMin * 60_000L
        val hits = items.filter { it.kind == "rule" && hitsShort.any { h -> h.isNotBlank() && it.id.startsWith(h) } }.map { it.id }.toSet()
        val today = LocalDate.now(); val dayStart = Store.dayStartMs(today)
        val lt = LocalTime.now()
        val v = Engine.step(items, Obs(act, delta, intentional || snoozeMin > 0, hits, tags.toSet(), source), now, lt.hour * 60 + lt.minute, Engine.horizonFor(now, dayStart), today.toEpochDay().toInt(), st)
        Cfg.engine = st.encode()
        record(v)
        v.events.forEach { runCatching { Store.event(it.itemId, it.type, it.minutes, null, tsIso) } }
        // v4: the Kotlin engine only does BOOKKEEPING (activity minutes, goal progress events). It never nudges, calls or fires rules:
        // Gemini decides from photo + device ledger + audio, and its decision arrives through deliverNudges() / the `calls` table.
        "LOGGED (Gemini decides) — ${act}" + v.events.filter { it.type == "completed" }.joinToString("") { e -> "  ✔ done: " + (items.firstOrNull { it.id == e.itemId }?.title ?: "") }
    }

    private val PURPOSE = setOf("chat", "manual", "shake")
    private fun chatPhotoDone() { ChatStore.photoAskedAt = 0 }

    /** Is the laptop vision engine alive? (extension writes a heartbeat row every ~15 s while "Start engine" is on) */
    suspend fun engineOnline(): Boolean? = runCatching {
        val a = Sb.get("devices?id=eq.engine&select=last_seen&limit=1"); if (a.length() == 0) return@runCatching false
        System.currentTimeMillis() - OffsetDateTime.parse(a.getJSONObject(0).getString("last_seen")).toInstant().toEpochMilli() < 90_000L
    }.getOrNull()

    /** Called when a chat photo is taken: if nobody analyses it in 100 s, SAY so instead of staying silent. */
    fun expectPhoto() {
        val t = System.currentTimeMillis(); ChatStore.photoAskedAt = t
        scope.launch {
            kotlinx.coroutines.delay(100_000)
            if (ChatStore.photoAskedAt != t) return@launch
            ChatStore.photoAskedAt = 0
            val on = engineOnline()
            ChatStore.add("sys", "⚠ Nobody has analysed that photo yet. " + when (on) {
                false -> "The laptop vision engine is OFFLINE — open the Chrome extension dashboard and press “Start engine”."
                true -> "The laptop engine is online but slow or stuck — check its log."
                null -> "Check Supabase settings and that the extension engine is running."
            })
        }
    }

    private suspend fun composeRule(r: Item, base: String, desc: String): String {
        if (!Cfg.aiReady) return base
        return runCatching {
            Gemini.parse(Gemini.ask("You are Umer's coach. He gave you this instruction: \"$base\". The camera just saw: $desc. Say it to him now in ONE casual sentence, max 20 words, same meaning. JSON only: {\"m\":\"\"}", user = "go", maxOut = 80))?.optString("m")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: base
    }

    private suspend fun fireRule(r: Item, act: String, desc: String, imageId: String?) {
        val base = r.say.ifBlank { r.title }
        val msg = composeRule(r, base, desc)
        ChatStore.add("ai", "👁 Rule “${r.title}” triggered\n💬 $msg")
        if (r.priority >= 3) Sb.insert("calls", JSONObject().put("opening_message", msg).put("reason", "rule: ${r.title}").put("image_id", imageId ?: JSONObject.NULL))
        else Notifier.remind(r.title.take(60), msg)
        runCatching { Sb.insert("decisions", JSONObject().put("action", "rule").put("item_id", r.id).put("reason", r.title).put("activity", act).put("message", msg).put("handled", true).put("image_id", imageId ?: JSONObject.NULL)) }
        if (r.once) runCatching { Sb.patch("items?id=eq.${r.id}", JSONObject().put("status", "done")) }
    }

    private suspend fun reactToShown(desc: String, act: String, tags: List<String>, source: String) {
        val items = runCatching { Store.items() }.getOrDefault(emptyList())
        val reply = if (!Cfg.aiReady) null else runCatching {
            val sys = SAW_SYS + "\nNOW ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))}\nITEMS id|title|kind|prio|status|done/target|due:\n${compact(items)}"
            Gemini.parse(Gemini.ask(sys, user = "OBSERVATION: activity=$act; $desc; tags=${tags.joinToString()}", maxOut = 160))?.optString("reply")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (reply == null) ChatStore.add("sys", if (!Cfg.aiReady) "ℹ I can see it, but the chat brain has no Gemini key yet (Settings), so I can't comment." else "ℹ I saw it but the chat brain didn't answer (token budget or network).")
        else { ChatStore.add("ai", "💬 $reply"); if (source != "chat") Notifier.remind("Life AI", reply) }
    }

    private suspend fun compose(kind: String, g: Item?, reason: String, desc: String): String {
        val fb = "Umer, ${reason.substringBefore(" |")}."
        if (!Cfg.aiReady) return fb
        return runCatching {
            val r = Gemini.ask("Strict but friendly coach talking to Umer. JSON only: {\"m\":\"one casual spoken sentence, max 22 words, use his name\"}",
                user = "kind=$kind goal=${g?.title ?: "-"} prio=${g?.let { prioName(it.priority) } ?: "-"} why=$reason now=$desc", maxOut = 90)
            Gemini.parse(r)?.optString("m")?.takeIf { it.isNotBlank() } ?: fb
        }.getOrDefault(fb)
    }

    /** Deferred "remind me after current activity" decisions. */
    suspend fun fireDue() {
        val a = Sb.get("decisions?action=eq.remind_later&handled=eq.false&due_at=lte.${Instant.now()}&limit=3"); if (a.length() == 0) return
        val items = Store.items()
        for (i in 0 until a.length()) {
            val d = a.getJSONObject(i); Sb.patch("decisions?id=eq.${d.getString("id")}", JSONObject().put("handled", true))
            val g = items.firstOrNull { it.id == d.optString("item_id") } ?: continue
            if (g.status != "active" || Engine.remaining(g) == 0) continue
            Notifier.remind(g.title, compose("remind", g, d.optString("reason"), "finished previous activity"))
        }
    }

    /** Once a day: yesterday's unfinished daily targets become 'failed' events; expired one-off tasks are closed. */
    suspend fun sweep() {
        val today = LocalDate.now(); if (Cfg.lastDecayDay == today.toString()) return
        if (Cfg.lastDecayDay.isBlank()) { Cfg.lastDecayDay = today.toString(); return }
        val y = today.minusDays(1); val ts = Instant.parse(Store.dayStartIso(today)).minusSeconds(60).toString()
        val items = Sb.get("items?status=eq.active&kind=neq.rule&target_min=gt.0&limit=300")
        val ev = Sb.get("events?ts=gte.${Store.dayStartIso(y)}&ts=lt.${Store.dayStartIso(today)}&select=item_id,type,minutes&limit=3000")
        val prog = HashMap<String, Int>(); val comp = HashSet<String>()
        for (i in 0 until ev.length()) { val e = ev.getJSONObject(i); if (e.optString("type") == "progress") prog.merge(e.optString("item_id"), e.optInt("minutes"), Int::plus); if (e.optString("type") == "completed") comp += e.optString("item_id") }
        for (i in 0 until items.length()) {
            val j = items.getJSONObject(i); val id = j.getString("id")
            val created = runCatching { OffsetDateTime.parse(j.getString("created_at")).toInstant().toEpochMilli() }.getOrDefault(0L)
            if (created >= Store.dayStartMs(today)) continue
            if (id in comp || (prog[id] ?: 0) >= j.optInt("target_min")) continue
            runCatching { Store.event(id, "failed", prog[id] ?: 0, "daily target missed", ts) }
            val dl = Store.parse(j).deadline
            if (dl != null && dl < Store.dayStartMs(today)) runCatching { Sb.patch("items?id=eq.$id", JSONObject().put("status", "failed")) }
        }
        Cfg.lastDecayDay = today.toString()
    }

    // ───────────── chat ─────────────
    fun send(text: String) { scope.launch { handle(text, text) } }
    fun commitNow() { scope.launch { handle("✅ Send this to Gemini", "Send it now.") } }

    private suspend fun handle(shown: String, text: String) {
        ChatStore.add("user", shown); ChatStore.busy.value = true
        try { if (isCommit(text) || text == "Send it now.") commit(text) else if (!toBrain(text)) talk(text); compactHistory() }
        catch (e: Exception) { ChatStore.add("ai", "⚠ ${e.message}") } finally { ChatStore.busy.value = false }
    }

    /** Coach → the laptop Gemini (the real brain), via table `messages`. Returns false only when the laptop engine is offline AND a phone-side API key exists (then the old phone chat answers). */
    private suspend fun toBrain(text: String): Boolean {
        if (!Cfg.ready) return false
        val now = LocalDateTime.now()
        // A plain clock reminder ("remind me at 7:15 to leave") is an explicit command: kept local = instant, offline, 0 tokens. Conditions ("whenever you see…") always go to Gemini.
        val conditional = Regex("(?i)\\b(whenever|when you see|if you see|once you see|every time|as soon as|when i|if i)\\b").containsMatchIn(text)
        if (Lang.wantsReminder(text) && !conditional) Lang.reminder(text, now)?.let { lr ->
            val prev = ChatStore.msgs.value.filter { it.role == "user" }.dropLast(1).lastOrNull()?.text?.takeIf { it.length < 120 && !Lang.wantsReminder(it) }
            val res = Store.apply(JSONArray().put(opOf("remind", JSONObject().put("text", lr.text.ifBlank { prev ?: "Reminder" }).put("at", lr.at.toString().take(16)))))
            ChatStore.add("sys", res.lines.joinToString("\n")); return true
        }
        if (engineOnline() != true) {
            if (Cfg.aiReady) return false
            ChatStore.add("sys", "⚠ The laptop engine (Gemini brain) is offline. Open the extension dashboard and press Start engine."); return true
        }
        Sb.insert("messages", JSONObject().put("role", "user").put("content", text).put("status", "pending"))
        repeat(80) { delay(2000); if (pullReplies() > 0) return true }
        ChatStore.add("sys", "⏳ Gemini is slow right now — its answer will appear here as soon as it is ready.")
        return true
    }

    /** Show Gemini's chat answers (written by the laptop into `messages`). Also called by the service loop so late answers are never lost. */
    suspend fun pullReplies(): Int {
        if (!Cfg.ready) return 0
        val rows = Sb.get("messages?role=eq.ai&call_id=is.null&status=eq.ready&order=created_at.asc&limit=5"); var n = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i); val id = r.getString("id")
            if (Sb.claim("messages?id=eq.$id&status=eq.ready", JSONObject().put("status", "shown")).length() == 0) continue
            ChatStore.add("ai", r.optString("content")); n++
        }
        return n
    }

    private fun turns(drop: Int, take: Int): List<Gemini.Turn> {
        val l = ChatStore.msgs.value.filter { it.role != "sys" }.dropLast(drop).takeLast(take).map { Gemini.Turn(if (it.role == "user") "user" else "model", it.text.take(600)) }
        return l.dropWhile { it.role != "user" }
    }

    private val ACTIONABLE = Regex("(?i)\\b(remind|reminder|alarm|whenever|every time|as soon as|once i|tell me|let me know|don'?t let me|make sure|i want to|i need to|i have to|i must|goal|plan|habit|every day|daily|yaad)\\b")
    private val CLAIM = Regex("(?i)\\b(i'?ll|i will|reminder (is )?set|scheduled|saved|noted|done|got it|set it|will do|sure|okay|ok)\\b")

    private fun opOf(type: String, o: JSONObject) = o.put("op", type)

    private suspend fun extract(text: String): JSONArray {
        val raw = Gemini.ask(EXTRACT_SYS + "\nNOW ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm"))}", turns(1, 12), text, maxOut = 700)
        return Gemini.parse(raw)?.optJSONArray("actions") ?: JSONArray()
    }

    private suspend fun talk(text: String) {
        val now = LocalDateTime.now()
        val items = if (Cfg.ready) runCatching { Store.items() }.getOrDefault(emptyList()) else emptyList()
        val sys = CHAT_SYS + "\nNOW ${now.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm"))}" +
            (if (Cfg.chatSummary.isNotBlank()) "\nEARLIER: ${Cfg.chatSummary}" else "") + "\nTODAY: ${if (Cfg.ready) todayLine() else "?"}\nITEMS id|title|kind|prio|status|done/target|due:\n${compact(items)}"
        var reply = ""; var ops = JSONArray(); var nowObj: JSONObject? = null; var aiErr: String? = null
        try {
            val raw = Gemini.ask(sys, turns(1, 10), text, maxOut = 700)
            val j = Gemini.parse(raw)
            reply = j?.optString("reply")?.takeIf { it.isNotBlank() } ?: if (j == null) raw.take(400) else ""
            ops = j?.optJSONArray("actions") ?: JSONArray(); nowObj = j?.optJSONObject("now")
        } catch (e: Exception) { aiErr = e.message }

        // Safety net 1 (0 tokens, works offline): the two instructions that must never be lost.
        val extra = JSONArray()
        fun has(op: String, pred: (JSONObject) -> Boolean = { true }) = (0 until ops.length()).any { ops.optJSONObject(it)?.let { o -> o.optString("op") == op && pred(o) } == true }
        Lang.reminder(text, now)?.let { lr ->
            if (has("remind")) {   // deterministic local time beats the model's date arithmetic
                for (i in 0 until ops.length()) ops.optJSONObject(i)?.takeIf { it.optString("op") == "remind" }?.let { it.put("at", lr.at.toString().take(16)); it.remove("in_min") }
            } else {
                val prev = ChatStore.msgs.value.filter { it.role == "user" }.dropLast(1).lastOrNull()?.text?.takeIf { it.length < 120 && !Lang.wantsReminder(it) }
                extra.put(opOf("remind", JSONObject().put("text", lr.text.ifBlank { prev ?: "Reminder" }).put("at", lr.at.toString().take(16))))
            }
        }
        Lang.watch(text)?.takeIf { !has("watch") }?.let { w -> extra.put(opOf("watch", JSONObject().put("title", w.title).put("acts", JSONArray(w.acts.toList())).put("keywords", JSONArray(w.keywords.toList())).put("say", w.say).put("cooldown_min", 20).put("priority", 2))) }

        // Safety net 2: the model answered but produced no action for an actionable sentence → ask once more, strictly for actions.
        if (ops.length() == 0 && extra.length() == 0 && aiErr == null && Cfg.aiReady && ACTIONABLE.containsMatchIn(text)) runCatching { ops = extract(text) }

        val all = JSONArray(); for (i in 0 until ops.length()) all.put(ops.get(i)); for (i in 0 until extra.length()) all.put(extra.get(i))
        if (reply.isNotBlank()) ChatStore.add("ai", reply)
        if (aiErr != null) ChatStore.add("sys", "⚠ Chat brain error: ${aiErr.take(120)}" + if (extra.length() > 0) "\nI handled the time/trigger locally instead." else "")
        if (all.length() > 0) {
            val res = Store.apply(all)
            ChatStore.add("sys", res.lines.joinToString("\n"))
        } else if (aiErr == null && ACTIONABLE.containsMatchIn(text) && (CLAIM.containsMatchIn(reply) || reply.length < 12)) {
            ChatStore.add("sys", "⚠ Nothing was saved or scheduled yet — I only talked. Tell me the exact time (“remind me at 7:15 to leave”) or what the camera should look for (“when you see me sitting, tell me to get up”).")
        }
        val n = nowObj ?: return
        val act = n.optString("act").let { if (it in ACTIVITIES) it else return }
        val brk = n.optInt("break", 0)
        val r = observe(act, n.optString("desc", act).take(200), emptyList(), emptyList(), "chat", Instant.now().toString(), System.currentTimeMillis(), null, null,
            n.optBoolean("intentional") || brk > 0, n.optInt("min", 0).takeIf { it > 0 } ?: Cfg.intervalMin, brk)
        ChatStore.add("sys", "📝 Logged: $act → $r")
    }

    private suspend fun commit(text: String) {
        if (!Cfg.ready) throw RuntimeException("Set Supabase URL + key in Settings first")
        val items = Store.items()
        val raw = Gemini.ask(commitSys() + "\nACTIVITIES: $ACT_LIST\nEXISTING id|title|kind|prio|status|done/target|due:\n${compact(items)}", turns(1, 16), text, maxOut = 2000)
        val j = Gemini.parse(raw) ?: throw RuntimeException("Gemini reply was not valid JSON — try again")
        val ops = j.optJSONArray("ops") ?: JSONArray()
        if (ops.length() == 0) { ChatStore.add("ai", "I didn't find anything concrete to save yet. Tell me a bit more."); return }
        val res = Store.apply(ops)
        ChatStore.add("sys", res.lines.joinToString("\n") + "\n" + j.optString("summary"))
    }

    private suspend fun compactHistory() {
        val m = ChatStore.msgs.value; if (m.size <= 26) return
        runCatching {
            val old = m.take(14)
            val s = Gemini.ask("Summarize in max 90 words: facts, goals, preferences, decisions. Plain text.", user = Cfg.chatSummary + "\n" + old.joinToString("\n") { "${it.role}:${it.text.take(300)}" }, json = false, maxOut = 220)
            Cfg.chatSummary = s.trim(); ChatStore.replace(m.drop(14))
        }
    }

    // ───────────── live call ─────────────
    data class CallOut(val reply: String, val end: Boolean)

    suspend fun callReply(reason: String, before: List<Pair<Boolean, String>>, text: String): CallOut {
        val items = runCatching { Store.items() }.getOrDefault(emptyList()).filter { it.kind != "rule" && it.status == "active" }
        val last = runCatching {   // freshest photo (the live snapshot taken when the call started) + what the phone was doing at that moment
            Sb.get("images?status=eq.done&usable=eq.true&order=captured_at.desc&limit=1&select=captured_at,interpretation,ctx").optJSONObject(0)?.let { o ->
                val ago = (System.currentTimeMillis() - OffsetDateTime.parse(o.getString("captured_at")).toInstant().toEpochMilli()) / 60_000
                "${ago}m ago: ${o.optString("interpretation")} | ${o.optString("ctx").takeIf { it != "null" } ?: ""}"
            }
        }.getOrNull()
        val sys = CALL_SYS + "\nNOW ${LocalTime.now().withNano(0)}\nWHY YOU CALLED: $reason\nSEEN LAST: ${last ?: "-"}\nTODAY: ${todayLine()}\nPHONE APPS: ${Usage.topLine(Cfg.app)}\nGOALS id|title|kind|prio|status|done/target|due:\n${compact(items.sortedByDescending { it.priority }.take(8))}"
        val hist = before.takeLast(8).map { Gemini.Turn(if (it.first) "model" else "user", it.second.take(400)) }.dropWhile { it.role != "user" }
        val raw = Gemini.ask(sys, hist, text, maxOut = 160)
        val j = Gemini.parse(raw)
        val sn = j?.optInt("snooze", 0) ?: 0
        if (sn > 0) { val st = State.decode(Cfg.engine); st.intentUntil = System.currentTimeMillis() + sn * 60_000L; Cfg.engine = st.encode() }
        return CallOut(j?.optString("reply")?.takeIf { it.isNotBlank() } ?: raw.take(200), j?.optBoolean("end") ?: false)
    }
}
