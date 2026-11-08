package com.umer.lifeai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Tiny Supabase REST + Storage client. */
object Sb {
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    private val JSON_T = "application/json".toMediaType()

    private fun rb(path: String) = Request.Builder().url(Cfg.url + path)
        .header("apikey", Cfg.key).header("Authorization", "Bearer ${Cfg.key}")

    private suspend fun run(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { res ->
            val b = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}: ${b.take(160)}")
            b
        }
    }

    suspend fun get(q: String): JSONArray = JSONArray(run(rb("/rest/v1/$q").build()).ifBlank { "[]" })

    suspend fun insert(table: String, o: JSONObject): JSONObject {
        val b = run(rb("/rest/v1/$table").header("Prefer", "return=representation").post(o.toString().toRequestBody(JSON_T)).build())
        return JSONArray(b.ifBlank { "[]" }).optJSONObject(0) ?: JSONObject()
    }

    /** Insert many rows; merges on [conflict] columns when given (idempotent upsert). */
    suspend fun upsert(table: String, rows: JSONArray, conflict: String) {
        if (rows.length() == 0) return
        run(rb("/rest/v1/$table?on_conflict=$conflict").header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(rows.toString().toRequestBody(JSON_T)).build())
    }

    suspend fun patch(q: String, o: JSONObject) { run(rb("/rest/v1/$q").patch(o.toString().toRequestBody(JSON_T)).build()) }

    /** PATCH that returns the updated rows – used to atomically "claim" a pending row. */
    suspend fun claim(q: String, o: JSONObject): JSONArray =
        JSONArray(run(rb("/rest/v1/$q").header("Prefer", "return=representation").patch(o.toString().toRequestBody(JSON_T)).build()).ifBlank { "[]" })

    suspend fun delete(q: String) { run(rb("/rest/v1/$q").delete().build()) }

    suspend fun upload(name: String, bytes: ByteArray, mime: String = "image/jpeg") {
        run(rb("/storage/v1/object/captures/$name").header("x-upsert", "true").post(bytes.toRequestBody(mime.toMediaType())).build())
    }

    suspend fun download(name: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(publicUrl(name)).build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
    }

    fun publicUrl(name: String) = "${Cfg.url}/storage/v1/object/public/captures/$name"
}
