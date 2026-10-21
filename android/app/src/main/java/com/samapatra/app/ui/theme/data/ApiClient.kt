package com.samapatra.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiConfig {
    // The Android emulator reaches your laptop at 10.0.2.2.
    // On a real tablet on the same Wi-Fi, use your laptop's IP, e.g. "http://192.168.1.5:3000".
    const val BASE_URL = "http://localhost:3000"
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String, val code: Int = 0) : ApiResult<Nothing>
}

/** Converts a successful result. A reply in an unexpected shape becomes a Failure, not a crash. */
fun <A, B> ApiResult<A>.map(transform: (A) -> B): ApiResult<B> = when (this) {
    is ApiResult.Ok -> try {
        ApiResult.Ok(transform(value))
    } catch (e: JSONException) {
        ApiResult.Failure("The server sent a reply the app didn’t understand.")
    }
    is ApiResult.Failure -> this
}

object ApiClient {
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Reading a whole paper or making a student's paper with AI can take a few minutes
    private val longClient: OkHttpClient = http.newBuilder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** True only if the backend answers /health with a success code. */
    suspend fun isBackendReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${ApiConfig.BASE_URL}/health").build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    suspend fun get(path: String, token: String? = null): ApiResult<JSONObject> =
        call(path, token, http) { it.get() }

    suspend fun post(path: String, body: JSONObject, token: String? = null): ApiResult<JSONObject> =
        call(path, token, http) { it.post(body.toString().toRequestBody(JSON_TYPE)) }

    suspend fun put(path: String, body: JSONObject, token: String? = null): ApiResult<JSONObject> =
        call(path, token, http) { it.put(body.toString().toRequestBody(JSON_TYPE)) }

    suspend fun delete(path: String, token: String? = null): ApiResult<JSONObject> =
        call(path, token, http) { it.delete() }

    /** Like post, but waits up to five minutes for the reply. */
    suspend fun postLong(path: String, body: JSONObject, token: String? = null): ApiResult<JSONObject> =
        call(path, token, longClient) { it.post(body.toString().toRequestBody(JSON_TYPE)) }

    /** Sends text fields and then one file. The server needs the text fields first. */
    suspend fun postMultipart(
        path: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        fileType: String,
        fileBytes: ByteArray,
        token: String?,
    ): ApiResult<JSONObject> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart(fileField, fileName, fileBytes.toRequestBody(fileType.toMediaType()))
        }.build()
        return call(path, token, longClient) { it.post(body) }
    }

    private suspend fun call(
        path: String,
        token: String?,
        client: OkHttpClient,
        build: (Request.Builder) -> Request.Builder,
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url("${ApiConfig.BASE_URL}/api$path")
            if (token != null) builder.header("Authorization", "Bearer $token")
            client.newCall(build(builder).build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    JSONObject()
                }
                if (response.isSuccessful) {
                    ApiResult.Ok(json)
                } else {
                    ApiResult.Failure(errorMessage(response.code, json), response.code)
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure("Can’t reach the server. Check your connection and try again.")
        }
    }

    private fun errorMessage(status: Int, json: JSONObject): String {
        val serverMessage = json.optString("message")
        return when {
            // 502 to 504 come from our own routes, with a message written for the user
            status in 502..504 && serverMessage.isNotBlank() -> serverMessage
            status >= 500 -> "Something went wrong on our side. Please try again."
            serverMessage.isNotBlank() -> serverMessage
            status == 401 -> "Please sign in again."
            else -> "Something went wrong. Please try again."
        }
    }
}