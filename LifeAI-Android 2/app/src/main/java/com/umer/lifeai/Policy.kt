package com.umer.lifeai

import kotlin.math.max
import kotlin.math.min

/**
 * Pure decision engine (no Android imports → unit-testable, costs zero tokens).
 * Gemini only SEES the photo and WRITES the words; this file decides WHETHER to interrupt.
 */
object Act { const val NOTE = "note"; const val REMIND_LATER = "remind_later"; const val REMIND = "remind"; const val INTERRUPT = "interrupt" }

val ACTIVITIES = listOf("eating", "exercising", "studying", "working", "resting", "sitting", "lying_down", "scrolling_phone", "entertainment",
    "socialising", "commuting", "household", "budgeting", "sleeping", "grooming", "other", "unknown")
val WASTE = setOf("scrolling_phone", "lying_down", "idle", "entertainment")
fun prioName(p: Int) = when (p) { 3 -> "HIGH"; 1 -> "LOW"; else -> "MED" }

data class Item(
    val id: String, val title: String, val kind: String = "task",      // goal | task | habit | rule
    val priority: Int = 2, val status: String = "active",              // active | paused | done | failed
    val deadline: Long? = null, val targetMin: Int = 0, val doneMin: Int = 0,
    val acts: Set<String> = emptySet(), val keywords: Set<String> = emptySet(), val avoid: Set<String> = emptySet(),
    val winStart: Int? = null, val winEnd: Int? = null,                 // minute-of-day window in which the item applies
    val details: String = "",                                           // rule: "SAY: …" (what to tell him) and optional "ONCE" line
    val dueMs: Long? = null,                                            // kind=reminder: exact time
) {
    /** For rules the column target_min is the cooldown in minutes (0 → default). */
    val cooldownMin get() = if (kind == "rule" && targetMin > 0) targetMin else Engine.RULE_GAP
    val say: String get() = Regex("(?m)^SAY:\\s*(.+)$").find(details)?.groupValues?.get(1)?.trim().orEmpty()
    val once: Boolean get() = Regex("(?m)^ONCE\\s*$").containsMatchIn(details)
}

data class Obs(
    val activity: String, val deltaMin: Int = 0, val intentional: Boolean = false,
    val rulesHit: Set<String> = emptySet(), val tags: Set<String> = emptySet(), val source: String = "camera",
)

data class Ev(val itemId: String, val type: String, val minutes: Int = 0)   // progress | completed

data class Verdict(
    val act: String, val itemId: String?, val reason: String, val fulfilled: List<String>, val wasted: Boolean,
    val events: List<Ev>, val evals: List<String>, val rules: List<String>, val afterMin: Int = 0, val distractMin: Int = 0,
)

class State(
    var distractStart: Long = -1, var lastInterrupt: Long = 0, var lastRemind: Long = 0, var interruptsToday: Int = 0,
    var day: Int = -1, var intentUntil: Long = 0, val ruleLast: MutableMap<String, Long> = mutableMapOf(),
) {
    fun encode() = "ds=$distractStart;li=$lastInterrupt;lr=$lastRemind;it=$interruptsToday;d=$day;iu=$intentUntil;rl=" +
        ruleLast.entries.joinToString("|") { "${it.key}:${it.value}" }
    companion object {
        fun decode(s: String): State {
            val st = State(); if (s.isBlank()) return st
            for (p in s.split(";")) {
                val i = p.indexOf('='); if (i < 0) continue; val k = p.substring(0, i); val v = p.substring(i + 1)
                when (k) {
                    "ds" -> st.distractStart = v.toLongOrNull() ?: -1; "li" -> st.lastInterrupt = v.toLongOrNull() ?: 0
                    "lr" -> st.lastRemind = v.toLongOrNull() ?: 0; "it" -> st.interruptsToday = v.toIntOrNull() ?: 0
                    "d" -> st.day = v.toIntOrNull() ?: -1; "iu" -> st.intentUntil = v.toLongOrNull() ?: 0
                    "rl" -> v.split("|").forEach { e -> val c = e.lastIndexOf(':'); if (c > 0) e.substring(c + 1).toLongOrNull()?.let { t -> st.ruleLast[e.substring(0, c)] = t } }
                }
            }
            return st
        }
    }
}

object Engine {
    private const val MIN = 60_000L
    const val INTERRUPT_GAP = 25; const val REMIND_GAP = 15; const val LATER_GAP = 45; const val RULE_GAP = 60; const val MAX_INTERRUPTS = 5

    fun inWin(i: Item, m: Int): Boolean {
        val s = i.winStart; val e = i.winEnd; if (s == null || e == null) return true
        return if (s <= e) m in s until e else (m >= s || m < e)
    }
    fun remaining(i: Item) = if (i.targetMin > 0) max(0, i.targetMin - i.doneMin) else 0
    fun leftMin(i: Item, now: Long, horizon: Long): Long { val h = i.deadline?.let { min(it, horizon) } ?: horizon; return max(1L, (h - now) / MIN) }

    /** 0 = no pressure … 3 = critical (cannot afford to lose time). */
    fun urgency(i: Item, now: Long, horizon: Long): Int {
        val rem = remaining(i); if (rem == 0) return 0
        if (i.deadline != null && i.deadline < now) return 3
        val ratio = rem.toDouble() / leftMin(i, now, horizon)
        var u = if (ratio >= 0.8) 3 else if (ratio >= 0.5) 2 else 1
        if (i.deadline != null && i.deadline - now <= 24 * 3600_000L) u = min(3, u + 1)
        return u
    }
    /** Deterministic visual trigger of a rule: activity or photo keyword matches (no model judgement needed). */
    fun triggers(r: Item, o: Obs) = (r.acts.isNotEmpty() && o.activity in r.acts) || (r.keywords.isNotEmpty() && o.tags.any { t -> r.keywords.any { k -> k.isNotBlank() && t.contains(k) } })
    fun matches(i: Item, o: Obs) = i.kind != "rule" && (o.activity in i.acts || (i.keywords.isNotEmpty() && o.tags.any { t -> i.keywords.any { k -> t.contains(k) } }))
    /** 0 = matched by tag (immediate), 1 = matched by activity (needs duration), -1 = no violation. */
    fun avoidKind(i: Item, o: Obs): Int {
        if (i.avoid.isEmpty()) return -1
        val tagTokens = i.avoid.filter { it !in ACTIVITIES }
        if (o.tags.any { t -> tagTokens.any { a -> t.contains(a) } }) return 0
        return if (o.activity in i.avoid) 1 else -1
    }

    fun step(items: List<Item>, o: Obs, now: Long, minuteOfDay: Int, horizon: Long, dayKey: Int, st: State): Verdict {
        if (st.day != dayKey) { st.day = dayKey; st.interruptsToday = 0 }
        val live = items.filter { it.kind != "rule" && it.kind != "reminder" && it.status == "active" && inWin(it, minuteOfDay) }
        val fulfilled = live.filter { matches(it, o) }
        val events = mutableListOf<Ev>()
        if (o.deltaMin > 0) for (f in fulfilled) {
            events += Ev(f.id, "progress", o.deltaMin)
            if (f.targetMin > 0 && f.doneMin < f.targetMin && f.doneMin + o.deltaMin >= f.targetMin) events += Ev(f.id, "completed")
        }
        if (fulfilled.isEmpty()) { if (st.distractStart < 0) st.distractStart = now } else st.distractStart = -1
        if (st.distractStart in 0 until st.intentUntil && st.intentUntil <= now) st.distractStart = st.intentUntil
        val dm = if (st.distractStart < 0) 0 else ((now - st.distractStart) / MIN).toInt()
        val wasted = fulfilled.isEmpty() && o.activity in WASTE && !o.intentional && now >= st.intentUntil

        val fired = items.filter { it.kind == "rule" && it.status == "active" && inWin(it, minuteOfDay) && (it.id in o.rulesHit || triggers(it, o)) && now - (st.ruleLast[it.id] ?: 0L) >= it.cooldownMin * MIN }.map { it.id }
        fired.forEach { st.ruleLast[it] = now }

        val unmet = live.filter { it !in fulfilled && remaining(it) > 0 }.sortedByDescending { it.priority * 10 + urgency(it, now, horizon) }
        val top = unmet.firstOrNull()
        val evals = live.map { i ->
            val tag = when { i in fulfilled -> "FULFILLED now"; i == top -> "UNMET (top)"; remaining(i) > 0 -> "unmet"; avoidKind(i, o) >= 0 -> "VIOLATED"; else -> "ok" }
            "${i.title} [${prioName(i.priority)}] need ${i.targetMin}m, done ${i.doneMin}m, urgency ${urgency(i, now, horizon)} → $tag"
        }
        fun done(act: String, id: String?, why: String, after: Int = 0) = Verdict(act, id, why, fulfilled.map { it.id }, wasted, events, evals, fired, after, dm)

        if (o.intentional || now < st.intentUntil) return done(Act.NOTE, null, "current activity is intentional / break is active → no interruption")

        var act = Act.NOTE; var tgt: Item? = null; var why = "nothing needs attention"; var after = 0
        if (fulfilled.isNotEmpty()) {
            val maxP = fulfilled.maxOf { it.priority }
            when {
                top == null -> why = "fulfilling ${fulfilled.joinToString { it.title }}; no other goal has work left"
                top.priority <= maxP -> why = "fulfilling ${fulfilled.first().title} (${prioName(maxP)}) which is ≥ priority of ${top.title} (${prioName(top.priority)}) → just note it"
                else -> {
                    val u = urgency(top, now, horizon); val slack = leftMin(top, now, horizon) - remaining(top)
                    tgt = top
                    if (top.priority == 3 && u == 3 && slack < 30) { act = Act.INTERRUPT; why = "${top.title} (HIGH) is critical: only ${slack}m slack left → interrupt even though you are busy with something lower" }
                    else if (u >= 2) { act = Act.REMIND_LATER; after = 30; why = "${top.title} (${prioName(top.priority)}) is getting urgent → remind after the current activity" }
                    else { tgt = null; why = "${top.title} is higher priority but not urgent yet → just note activity" }
                }
            }
        } else {
            val viol = live.filter { avoidKind(it, o) >= 0 }.maxWithOrNull(compareBy<Item> { it.priority }.thenBy { -avoidKind(it, o) })
            if (viol != null) {
                tgt = viol; val k = avoidKind(viol, o)
                if (k == 0 && viol.priority >= 2) { act = Act.REMIND; why = "${viol.title}: forbidden thing spotted in the photo" }
                else if (viol.priority == 3 && dm >= 10) { act = Act.INTERRUPT; why = "${viol.title} (HIGH) violated for ${dm}m" }
                else if (viol.priority >= 2 && dm >= 15) { act = Act.REMIND; why = "${viol.title} violated for ${dm}m" }
                else if (viol.priority == 1 && dm >= 30) { act = Act.REMIND; why = "${viol.title} (LOW) violated for ${dm}m" }
                else { tgt = null; why = "${viol.title} violated but only ${dm}m so far → watching" }
            } else if (top != null) {
                val u = urgency(top, now, horizon)
                if (top.priority == 3 && u >= 2 && dm >= 10) { act = Act.INTERRUPT; tgt = top; why = "${top.title} (HIGH, urgency $u) and you have been off-task ${dm}m" }
                else if (top.priority >= 2 && u >= 1 && dm >= 15) { act = Act.REMIND; tgt = top; why = "${top.title} (${prioName(top.priority)}) pending and off-task ${dm}m" }
                else if (wasted && dm >= 45) { act = Act.REMIND; tgt = top; why = "off-task ${dm}m (wasted time)" }
                else why = "off-task ${dm}m; ${top.title} is ${prioName(top.priority)} / urgency $u → not enough to disturb you yet"
            } else if (wasted && dm >= 45) { act = Act.REMIND; why = "off-task ${dm}m and no goal active" }
        }
        // gates
        if (act == Act.INTERRUPT && (now - st.lastInterrupt < INTERRUPT_GAP * MIN || st.interruptsToday >= MAX_INTERRUPTS)) { act = Act.REMIND; why += " | interrupt cooldown/cap → downgraded to reminder" }
        if (act == Act.REMIND && now - st.lastRemind < REMIND_GAP * MIN) { act = Act.NOTE; tgt = null; why += " | reminder cooldown → note only" }
        if (act == Act.REMIND_LATER && now - st.lastRemind < LATER_GAP * MIN) { act = Act.NOTE; tgt = null; why += " | recently reminded → note only" }
        when (act) {
            Act.INTERRUPT -> { st.lastInterrupt = now; st.lastRemind = now; st.interruptsToday++ }
            Act.REMIND, Act.REMIND_LATER -> st.lastRemind = now
        }
        return done(act, tgt?.id, why, after)
    }

    fun horizonFor(now: Long, dayStart: Long): Long = if (now - dayStart < 22 * 3600_000L) dayStart + 22 * 3600_000L else dayStart + 24 * 3600_000L
}
