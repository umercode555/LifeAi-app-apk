package com.umer.lifeai

/** 10 realistic scenarios. Run offline (no tokens) by the in-app Test Lab and by unit tests / GitHub Actions. */
data class Step(
    val hhmm: String, val activity: String, val expect: String, val tags: List<String> = emptyList(), val delta: Int = 15,
    val hits: List<String> = emptyList(), val intentional: Boolean = false, val source: String = "camera",
    val breakMin: Int = 0, val setPrio: Pair<String, Int>? = null, val say: String = "", val rule: Boolean? = null,
)
data class Scenario(val n: Int, val title: String, val items: List<Item>, val steps: List<Step>)
data class SimResult(val lines: List<String>, val actual: List<String>, val pass: Boolean)

object Scenarios {
    private const val H = 3600_000L; private const val D = 24 * H

    fun all(day: Long): List<Scenario> {
        fun at(h: Int, m: Int = 0) = day + h * H + m * 60_000L
        fun later(days: Int, h: Int = 12) = day + days * D + h * H
        val ex = { p: Int -> Item("ex", "Exercise 45 min", "task", p, targetMin = 45, acts = setOf("exercising")) }
        return listOf(
            Scenario(1, "Lose 15 kg: daily exercise + food discipline",
                listOf(Item("g", "Lose 15 kg by March", "goal", 2, deadline = later(120)),
                    Item("ex", "Exercise 45 min", "task", 2, targetMin = 45, acts = setOf("exercising")),
                    Item("junk", "No junk food", "habit", 2, avoid = setOf("burger", "fries", "pizza", "soda"))),
                listOf(Step("07:00", "exercising", Act.NOTE, delta = 45, say = "Morning run done"),
                    Step("13:00", "eating", Act.NOTE, tags = listOf("salad", "chicken")),
                    Step("20:00", "eating", Act.REMIND, tags = listOf("burger", "fries")))),
            Scenario(2, "Exam in 3 days: escalating pressure while lying down",
                listOf(Item("st", "Study chapter 3 (exam)", "task", 3, deadline = later(3, 15), targetMin = 180, acts = setOf("studying"))),
                listOf(Step("15:00", "lying_down", Act.NOTE), Step("15:15", "lying_down", Act.REMIND),
                    Step("19:30", "lying_down", Act.INTERRUPT))),
            Scenario(3, "Short-term task: study 2 hours today",
                listOf(Item("st", "Study 2 hours today", "task", 2, targetMin = 120, acts = setOf("studying"))),
                listOf(Step("10:00", "studying", Act.NOTE, delta = 30), Step("10:30", "studying", Act.NOTE, delta = 30),
                    Step("11:00", "studying", Act.NOTE, delta = 30), Step("11:30", "studying", Act.NOTE, delta = 30, say = "target reached → COMPLETED"),
                    Step("12:30", "lying_down", Act.NOTE))),
            Scenario(4, "Three goals at once: exercise (LOW), study (HIGH), household (MED)",
                listOf(ex(1), Item("st", "Study 2h", "task", 3, targetMin = 120, acts = setOf("studying")),
                    Item("hh", "Clean the house", "task", 2, targetMin = 30, acts = setOf("household"))),
                listOf(Step("16:00", "household", Act.NOTE), Step("19:30", "household", Act.REMIND_LATER),
                    Step("20:30", "household", Act.INTERRUPT))),
            Scenario(5, "Conflict: exercising (LOW) while HIGH-priority exam approaches",
                listOf(ex(1), Item("st", "Study for exam", "task", 3, deadline = at(19), targetMin = 120, acts = setOf("studying"))),
                listOf(Step("14:00", "exercising", Act.REMIND_LATER, delta = 45), Step("17:00", "exercising", Act.INTERRUPT, delta = 15))),
            Scenario(6, "Priority flipped: exercising is HIGH, study LOW → never nag",
                listOf(ex(3).copy(targetMin = 90), Item("st", "Study for exam", "task", 1, deadline = at(19), targetMin = 120, acts = setOf("studying"))),
                listOf(Step("14:00", "exercising", Act.NOTE, delta = 45), Step("17:00", "exercising", Act.NOTE, delta = 45))),
            Scenario(7, "Tiny instruction: remind me to fix messy hair",
                listOf(Item("hair", "Fix hair when messy", "rule", 2)),
                listOf(Step("10:00", "working", Act.NOTE, tags = listOf("hair messy"), hits = listOf("hair"), say = "rule fires"),
                    Step("10:20", "working", Act.NOTE, tags = listOf("hair messy"), hits = listOf("hair"), say = "cooldown 60 min → silent"),
                    Step("11:10", "working", Act.NOTE, tags = listOf("hair messy"), hits = listOf("hair"), say = "fires again"))),
            Scenario(8, "Financial goal: save 50,000 PKR by Dec 31",
                listOf(Item("sv", "Save 50,000 PKR by Dec 31", "goal", 2, deadline = later(54)),
                    Item("log", "Log today's expenses", "task", 2, targetMin = 10, acts = setOf("budgeting")),
                    Item("imp", "No impulse shopping", "habit", 2, avoid = setOf("daraz", "checkout", "cart"))),
                listOf(Step("12:00", "scrolling_phone", Act.REMIND, tags = listOf("daraz", "checkout")),
                    Step("21:00", "budgeting", Act.NOTE, delta = 10, source = "chat", say = "told AI: 'logging expenses' → COMPLETED"))),
            Scenario(9, "Habits: sleep schedule + reduce scrolling",
                listOf(Item("bed", "In bed by 23:00 (no phone)", "habit", 3, acts = setOf("sleeping"), avoid = setOf("scrolling_phone"), winStart = 23 * 60, winEnd = 3 * 60),
                    Item("scr", "Reduce scrolling", "habit", 2, avoid = setOf("scrolling_phone"))),
                listOf(Step("15:00", "scrolling_phone", Act.NOTE), Step("15:15", "scrolling_phone", Act.REMIND),
                    Step("23:00", "scrolling_phone", Act.INTERRUPT), Step("23:20", "scrolling_phone", Act.REMIND),
                    Step("24:30", "sleeping", Act.NOTE, delta = 60))),
            Scenario(10, "Intentional break, cooldowns and a live priority change",
                listOf(Item("st", "Study chapter 5", "task", 3, deadline = at(20), targetMin = 180, acts = setOf("studying"))),
                listOf(Step("16:00", "lying_down", Act.NOTE, intentional = true, breakMin = 30, source = "chat", say = "'taking a 30 min break'"),
                    Step("16:15", "lying_down", Act.NOTE), Step("16:50", "lying_down", Act.INTERRUPT),
                    Step("17:05", "lying_down", Act.REMIND, say = "interrupt cooldown → reminder"),
                    Step("17:10", "lying_down", Act.NOTE, setPrio = "st" to 1, say = "user lowers study to LOW → no more nagging yet"),
                    Step("17:30", "lying_down", Act.REMIND, say = "wasted > 45 min even with LOW"))),
            Scenario(11, "\"Whenever you see me sitting, tell me to get up\" — fires from VISION alone (no model hint)",
                listOf(Item("sit", "When I'm sitting → Get up!", "rule", 2, targetMin = 20, acts = setOf("sitting"), details = "SAY: Get up!")),
                listOf(Step("10:00", "working", Act.NOTE, rule = false, say = "working at desk, not 'sitting' → silent"),
                    Step("10:10", "sitting", Act.NOTE, rule = true, say = "sees him sitting → rule fires → Brain says 'Get up!'"),
                    Step("10:20", "sitting", Act.NOTE, rule = false, say = "20 min cooldown not over → silent"),
                    Step("10:35", "sitting", Act.NOTE, rule = true, say = "cooldown over → fires again"))),
            Scenario(12, "\"I want to go as soon as I get a burger\" — conversation becomes a one-shot visual trigger",
                listOf(Item("bg", "When burger arrives → time to go", "rule", 3, keywords = setOf("burger"), details = "SAY: Your burger is here — you wanted to leave right after.\nONCE"),
                    Item("rem", "Leave at 7:15", "reminder", 2, dueMs = 1L)),
                listOf(Step("19:00", "eating", Act.NOTE, tags = listOf("fries"), rule = false, say = "fries only → silent"),
                    Step("19:05", "eating", Act.NOTE, tags = listOf("burger", "fries"), rule = true, say = "burger seen → rule fires (reminder items never count as goals)"))),
        )
    }

    fun run(sc: Scenario, day: Long): SimResult {
        val st = State(); val done = HashMap<String, Int>(); var items = sc.items
        val lines = mutableListOf("━━ #${sc.n} ${sc.title}"); val actual = mutableListOf<String>(); var ok = true
        for (s in sc.steps) {
            val p = s.hhmm.split(":"); val min = p[0].toInt() * 60 + p[1].toInt(); val now = day + min * 60_000L
            s.setPrio?.let { (id, pr) -> items = items.map { if (it.id == id) it.copy(priority = pr) else it }; lines += "  ⚙ priority of '${items.first { it.id == id }.title}' changed to ${prioName(pr)}" }
            if (s.breakMin > 0) st.intentUntil = now + s.breakMin * 60_000L
            val dayStart = day + (min / 1440) * 24 * 3600_000L
            val cur = items.map { it.copy(doneMin = done[it.id] ?: 0) }
            val v = Engine.step(cur, Obs(s.activity, s.delta, s.intentional, s.hits.toSet(), s.tags.toSet(), s.source), now, min % 1440, Engine.horizonFor(now, dayStart), min / 1440, st)
            v.events.forEach { if (it.type == "progress") done[it.itemId] = (done[it.itemId] ?: 0) + it.minutes }
            val ruleOk = s.rule == null || (v.rules.isNotEmpty() == s.rule)
            val good = v.act == s.expect && ruleOk; if (!good) ok = false; actual += v.act
            val name = { id: String -> items.firstOrNull { it.id == id }?.title ?: id }
            lines += "[${s.hhmm}] OBSERVES  ${s.activity}${if (s.tags.isNotEmpty()) " " + s.tags else ""} via ${s.source}${if (s.say.isNotEmpty()) "  («${s.say}»)" else ""}"
            v.evals.forEach { lines += "         EVALUATES $it" }
            lines += "         PRIORITY  ${v.reason}"
            lines += "         DECISION  ${v.act.uppercase()}${v.itemId?.let { " about '" + name(it) + "'" } ?: ""}${if (v.rules.isNotEmpty()) "  + RULE fired: " + v.rules.joinToString { name(it) } else ""}"
            lines += "         CALENDAR  activity ${s.activity} ${s.delta}m${if (v.wasted) " (WASTED)" else ""}; decision logged" + v.events.filter { it.type == "completed" }.joinToString("") { "; ✔ COMPLETED ${name(it.itemId)}" }
            val prog = v.events.filter { it.type == "progress" }
            lines += "         PROGRESS  " + if (prog.isEmpty()) "no goal advanced (off-task ${v.distractMin}m)" else prog.joinToString { "${name(it.itemId)} ${done[it.itemId]}/${items.first { x -> x.id == it.itemId }.targetMin}m" }
            lines += "         EXPECTED  ${s.expect}${s.rule?.let { if (it) " + rule fires" else " + no rule" } ?: ""} ${if (good) "✓" else "✗ got ${v.act}${if (!ruleOk) " / rule fired=${v.rules.isNotEmpty()}" else ""}"}"
        }
        return SimResult(lines, actual, ok)
    }
}
