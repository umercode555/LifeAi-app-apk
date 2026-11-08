package com.umer.lifeai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTest {
    private val day = 20_400L * 86_400_000L

    @Test fun allScenariosBehaveAsExpected() {
        val all = Scenarios.all(day)
        assertEquals(12, all.size)
        for (sc in all) {
            val r = Scenarios.run(sc, day)
            assertTrue("Scenario #${sc.n} ${sc.title} failed:\n" + r.lines.joinToString("\n"), r.pass)
        }
    }

    @Test fun stateRoundTrips() {
        val s = State(distractStart = 5, lastInterrupt = 6, lastRemind = 7, interruptsToday = 2, day = 9, intentUntil = 11)
        s.ruleLast["abc"] = 42
        val d = State.decode(s.encode())
        assertEquals(s.encode(), d.encode())
    }

    @Test fun highPriorityNeverLosesToLowerWhenBusy() {
        val items = listOf(
            Item("a", "Exercise", priority = 3, targetMin = 60, acts = setOf("exercising")),
            Item("b", "Study", priority = 1, targetMin = 120, acts = setOf("studying")))
        val v = Engine.step(items, Obs("exercising", 15), day + 18 * 3600_000L, 18 * 60, day + 22 * 3600_000L, 1, State())
        assertEquals(Act.NOTE, v.act)
    }

    @Test fun reminderParsing() {
        val now = java.time.LocalDateTime.of(2026, 11, 7, 18, 50)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 7, 19, 15), Lang.reminder("Okay, remind me at 7:15", now)!!.at)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 8, 7, 15), Lang.reminder("remind me at 7:15", java.time.LocalDateTime.of(2026, 11, 7, 20, 0))!!.at)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 7, 19, 0), Lang.reminder("remind me in 10 minutes to drink water", now)!!.at)
        assertEquals("drink water", Lang.reminder("remind me in 10 minutes to drink water", now)!!.text)
        assertEquals(null, Lang.reminder("I eat at 7:15", now))
    }

    @Test fun visualWatchParsing() {
        val w = Lang.watch("Whenever you see me sitting down, tell me to get up.")!!
        assertEquals(setOf("sitting"), w.acts); assertEquals("Get up!", w.say)
        assertTrue("burger" in Lang.watch("as soon as you see me with a burger, remind me to leave")!!.keywords)
    }

    @Test fun keywordSpotting() {
        assertEquals(listOf("hungry", "burger"), Keywords.match("Man I'm so hungry, let's get a Burger!"))
        assertTrue("fast food" in Keywords.match("we ate fast food again"))
        assertTrue("tired" in Keywords.match("I am sooo tired of this"))
        assertTrue(Keywords.match("the weather is nice today").isEmpty())
    }
}
