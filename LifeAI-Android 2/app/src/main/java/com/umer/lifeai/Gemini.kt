package com.umer.lifeai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Minimal Gemini REST client, built to burn as few free tokens as possible. */
object Gemini {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val JT = "application/json".toMediaType()

    class Turn(val role: String, val text: String)     // role: user | model

    /** Returns raw text (JSON when json=true). Adds usage to today's token counter. */
    suspend fun ask(system: String, history: List<Turn> = emptyList(), user: String, image: ByteArray? = null,
                    maxOut: Int = 400, json: Boolean = true): String {
        if (!Cfg.aiReady) throw RuntimeException("Add your Gemini API key in Settings")
        if (Cfg.tokensToday > Cfg.tokenBudget) throw RuntimeException("Daily token budget reached (${Cfg.tokenBudget}). Raise it in Settings.")
        val contents = JSONArray()
        history.forEach { contents.put(JSONObject().put("role", it.role).put("parts", JSONArray().put(JSONObject().put("text", it.text)))) }
        val parts = JSONArray()
        if (image != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        parts.put(JSONObject().put("text", user))
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val gen = JSONObject().put("maxOutputTokens", maxOut)          // NOTE: 3.5-flash-lite rejects temperature/top_p/top_k
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents).put("generationConfig", gen).toString()
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${Cfg.model}:generateContent")
            .header("x-goog-api-key", Cfg.geminiKey).post(body.toRequestBody(JT)).build()
        var last = ""
        for (attempt in 0..2) {
            val (code, txt) = withContext(Dispatchers.IO) { http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) } }
            if (code == 429 || code == 503) { last = "HTTP $code"; delay(2500L * (attempt + 1)); continue }
            if (code !in 200..299) throw RuntimeException("Gemini HTTP $code: ${txt.take(200)}")
            val j = JSONObject(txt)
            j.optJSONObject("usageMetadata")?.let { Cfg.tokensToday = Cfg.tokensToday + it.optLong("totalTokenCount", 0) }
            val c = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder(); if (c != null) for (i in 0 until c.length()) sb.append(c.getJSONObject(i).optString("text"))
            if (sb.isBlank()) throw RuntimeException("Gemini returned nothing (${j.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")})")
            return sb.toString()
        }
        throw RuntimeException("Gemini busy: $last")
    }

    fun parse(t: String): JSONObject? {
        val s = t.replace("```json", "").replace("```", "")
        val a = s.indexOf('{'); val b = s.lastIndexOf('}'); if (a < 0 || b < a) return null
        return runCatching { JSONObject(s.substring(a, b + 1)) }.getOrNull()
    }
}
