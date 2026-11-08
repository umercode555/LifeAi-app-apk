package com.umer.lifeai

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() { super.onCreate(); Cfg.init(this); Brain.scope.launch { runCatching { Reminders.restore() } } }
}

object Cfg {
    private lateinit var p: SharedPreferences
    lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext; p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private fun s(k: String, d: String = "") = p.getString(k, d)!!
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    var url: String get() = s("url").trim().trimEnd('/'); set(v) = put("url", v.trim())
    var key: String get() = s("key").trim(); set(v) = put("key", v.trim())
    var intervalMin: Int get() = p.getInt("interval", 15); set(v) = p.edit().putInt("interval", v).apply()
    var onboarded: Boolean get() = b("onboarded", false); set(v) = putB("onboarded", v)
    var monitoring: Boolean get() = b("monitoring", false); set(v) = putB("monitoring", v)
    val ready: Boolean get() = url.startsWith("http") && key.isNotEmpty()

    // ── Gemini API ──
    var geminiKey: String get() = s("gkey").trim(); set(v) = put("gkey", v.trim())
    var model: String get() = s("model", "gemini-3.5-flash-lite").ifBlank { "gemini-3.5-flash-lite" }; set(v) = put("model", v.trim())
    var apiVision: Boolean get() = b("apivision", false); set(v) = putB("apivision", v)   // photos via paid API vision (off = laptop Gemini-web engine does it)
    val aiReady: Boolean get() = geminiKey.isNotEmpty()
    var tokenBudget: Long get() = p.getLong("tbudget", 300_000L); set(v) = p.edit().putLong("tbudget", v).apply()
    var tokenDay: String get() = s("tday"); set(v) = put("tday", v)
    var tokensToday: Long get() = if (tokenDay == LocalDate.now().toString()) p.getLong("ttoday", 0) else 0
        set(v) { tokenDay = LocalDate.now().toString(); p.edit().putLong("ttoday", v).apply() }

    // ── Camera ──
    var backEnabled: Boolean get() = b("back", false); set(v) = putB("back", v)       // default: FRONT ONLY
    var flashMode: String get() = s("flash", "auto"); set(v) = put("flash", v)         // off | auto | always
    var shake: Boolean get() = b("shake", true); set(v) = putB("shake", v)

    // ── Engine state ──
    var lastObs: Long get() = p.getLong("lastObs", 0); set(v) = p.edit().putLong("lastObs", v).apply()
    var engine: String get() = s("engine"); set(v) = put("engine", v)
    var lastDecayDay: String get() = s("sweepDay"); set(v) = put("sweepDay", v)
    var blindCount: Int get() = p.getInt("blind", 0); set(v) = p.edit().putInt("blind", v).apply()
    var lastBlindCall: Long get() = p.getLong("blindCall", 0); set(v) = p.edit().putLong("blindCall", v).apply()
    var ears: Boolean get() = b("ears", false); set(v) = putB("ears", v)            // mic rolling buffer + keyword clips (opt-in)
    var lastCurious: Long get() = p.getLong("curious", 0); set(v) = p.edit().putLong("curious", v).apply()
    var skippedDark: Int get() = p.getInt("dark", 0); set(v) = p.edit().putInt("dark", v).apply()

    // ── Pairing ──
    var pairCode: String get() = s("pair"); set(v) = put("pair", v)
    val deviceId: String get() = s("devid").ifBlank { UUID.randomUUID().toString().take(8).also { put("devid", it) } }

    // ── Chat memory (compact) ──
    var chatSummary: String get() = s("csum"); set(v) = put("csum", v)
}
