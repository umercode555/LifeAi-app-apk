package com.umer.lifeai

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure (no Android) language helpers. They run BEFORE the AI, cost 0 tokens and make the two most important
 * instructions reliable even when Gemini is offline or answers "Okay" without producing an action:
 *   • "remind me at 7:15" / "in 10 minutes"      → exact time
 *   • "whenever you see me sitting, tell me to get up" → visual trigger rule
 */
object Lang {
    data class Reminder(val at: LocalDateTime, val text: String)
    data class Watch(val acts: Set<String>, val keywords: Set<String>, val say: String, val title: String)

    private val REMIND_WORDS = Regex("(?i)\\b(remind|reminder|alarm|alert me|notify me|wake me|yaad dila|yaad dilana|batana)\\b")
    private val REL = Regex("(?i)\\b(?:in|after|within)\\s+(\\d{1,3}|an|a|one|two|three|five|ten|fifteen|twenty|thirty|half an)\\s*(minutes?|mins?|hours?|hrs?|hr)\\b|\\b(\\d{1,3})\\s*(minutes?|mins?|hours?|hrs?)\\s*(?:baad|later)\\b")
    private val ABS = Regex("(?i)(?:\\bat\\b|\\bby\\b|@|\\bfor\\b|\\bto\\b)?\\s*\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?\\b")
    private val WORDNUM = mapOf("an" to 1, "a" to 1, "one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30)

    fun wantsReminder(t: String) = REMIND_WORDS.containsMatchIn(t)

    /** Returns the reminder time + the thing to remind about (may be blank → caller fills it from context). */
    fun reminder(text: String, now: LocalDateTime): Reminder? {
        if (!wantsReminder(text)) return null
        REL.find(text)?.let { m ->
            val g = m.groupValues
            val numS = (g[1].ifBlank { g[3] }).lowercase(); val unit = (g[2].ifBlank { g[4] }).lowercase()
            val n = numS.toIntOrNull() ?: if (numS == "half an") 30 else WORDNUM[numS] ?: return@let
            val mins = if (numS == "half an") 30 else if (unit.startsWith("h")) n * 60 else n
            return Reminder(now.plusMinutes(mins.toLong()).withSecond(0).withNano(0), clean(text, m.value))
        }
        val tomorrow = Regex("(?i)\\b(tomorrow|kal)\\b").containsMatchIn(text)
        val tonight = Regex("(?i)\\btonight\\b").containsMatchIn(text)
        // choose the first match that looks like a clock time (has ':' or am/pm) – a bare "to 5 friends" is not a time
        for (m in ABS.findAll(text)) {
            val hS = m.groupValues[1]; val mi = m.groupValues[2]; val ap = m.groupValues[3].lowercase().replace(".", "")
            if (mi.isEmpty() && ap.isEmpty()) {
                // bare number only counts right after "at"/"by"/"@": "remind me at 7"
                if (!Regex("(?i)(?:\\bat|\\bby|@)\\s*" + hS + "\\b").containsMatchIn(text)) continue
            }
            var h = hS.toIntOrNull() ?: continue; val minute = mi.toIntOrNull() ?: 0
            if (h > 24 || minute > 59) continue
            if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
            if (h == 24) h = 0
            var base = now.toLocalDate()
            fun cand(hh: Int) = base.atTime(hh, minute)
            val at: LocalDateTime
            if (ap.isNotEmpty() || h >= 13 || h == 0) {
                var c = cand(h); if (!c.isAfter(now)) c = if (tomorrow) c else c.plusDays(1)
                at = if (tomorrow) base.plusDays(1).atTime(h, minute) else c
            } else if (tonight) { at = cand(if (h < 12) h + 12 else h).let { if (it.isAfter(now)) it else it.plusDays(1) } }
            else {
                // ambiguous (e.g. "7:15"): next occurrence of AM or PM version
                val opts = listOf(cand(h % 12), cand(h % 12 + 12)).flatMap { listOf(it, it.plusDays(1)) }.filter { it.isAfter(now) }.sorted()
                at = if (tomorrow) cand(h % 12).plusDays(1).let { if (h in 7..11) it else cand(h % 12 + 12).plusDays(1) } else opts.first()
            }
            return Reminder(at, clean(text, m.value))
        }
        return null
    }

    private fun clean(text: String, timePhrase: String): String {
        var s = text.replace(timePhrase, " ")
        s = s.replace(Regex("(?i)\\b(ok(ay)?|please|pls|hey|so|and|now|then)\\b[, ]*"), " ")
        s = s.replace(Regex("(?i)\\b(remind me|remind|set a reminder|set reminder|reminder|alarm|alert me|notify me|wake me up|wake me|yaad dila(na)?)\\b(\\s+(to|about|that)\\b)?"), " ")
        s = s.replace(Regex("(?i)\\b(tomorrow|tonight|today|kal|baad|later)\\b"), " ")
        return s.replace(Regex("[\\s,.:;!-]+"), " ").trim().trim(',', '.', ' ')
    }

    fun clock(at: LocalDateTime, now: LocalDateTime): String {
        val t = at.format(DateTimeFormatter.ofPattern("h:mm a"))
        val d = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        return when (d) { 0L -> "$t today"; 1L -> "$t tomorrow"; else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " $t" }
    }

    // ───── visual triggers: "whenever you see me X, tell me Y" ─────
    private val ACT_WORDS = linkedMapOf(
        "sitting" to listOf("sitting", "sit down", "sat down", "seated", "sitting down", "baith"),
        "lying_down" to listOf("lying", "laying", "lie down", "lying down", "laid down", "leta", "lete"),
        "eating" to listOf("eating", "eat", "having food", "khana kha", "khate"),
        "scrolling_phone" to listOf("scrolling", "on my phone", "using my phone", "on phone", "reels", "tiktok", "instagram"),
        "sleeping" to listOf("sleeping", "asleep", "so rah", "sote"),
        "studying" to listOf("studying", "reading", "parh"),
        "exercising" to listOf("exercising", "working out", "workout"),
        "entertainment" to listOf("watching tv", "watching youtube", "gaming", "playing games"),
    )
    private val WATCH = Regex("(?i)(?:whenever|every time|each time|jab bhi|as soon as|the moment|once|if)\\s+(?:you\\s+)?(?:see|spot|notice|catch|find|dekho|dekhte)\\s+(?:me|mujhe)\\s+(.+?)\\s*[,.;]?\\s*(?:then\\s+)?(?:please\\s+)?(?:you\\s+)?(?:tell|remind|say|warn|alert|ask|nudge|bol|bolo|batao)\\s+(?:me|mujhe)?\\s*(?:to\\s+)?(.+)")

    fun watch(text: String): Watch? {
        val m = WATCH.find(text.trim()) ?: return null
        val trig = m.groupValues[1].trim().trimEnd('.', ',', '!', '?'); val say = m.groupValues[2].trim().trimEnd('.', '!', '?')
        if (trig.isBlank() || say.isBlank()) return null
        val low = trig.lowercase()
        val acts = ACT_WORDS.filter { (_, ws) -> ws.any { low.contains(it) } }.keys.toSet()
        val kw = if (acts.isNotEmpty()) emptySet() else low.split(Regex("[^a-z0-9]+")).filter { it.length > 3 && it !in STOP }.toSet()
        if (acts.isEmpty() && kw.isEmpty()) return null
        val msg = say.replaceFirstChar { it.uppercase() }.let { if (Regex("(?i)^(get|stand|go|drink|stop|put|take|wash|eat|sleep|study|move|walk|call|come)\\b").containsMatchIn(it)) "$it!" else it }
        return Watch(acts, kw, msg, "When I'm $trig → ${say.take(60)}")
    }
    private val STOP = setOf("with", "that", "this", "your", "have", "from", "doing", "while", "when", "into", "them", "something", "looking", "again")
}
