package com.viewshed.app.viewshed

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class CollaborationProject(
    val id: String,
    val name: String,
    val ownerId: String,
    val role: String,
    val version: Int,
    val updatedAt: Double,
    val payload: JsonObject,
)

data class CollaborationVersion(
    val version: Int,
    val authorId: String,
    val message: String,
    val createdAt: Double,
)

data class CollaborationComment(
    val id: String,
    val authorId: String,
    val body: String,
    val createdAt: Double,
)

class CollaborationClient(
    baseUrl: String,
    private val userId: String,
    private val token: String = "",
    private val gson: Gson = Gson(),
) {
    private val root = BackendViewshedClient.normalizeUrl(baseUrl)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun listProjects(): List<CollaborationProject> =
        request("GET", "/collaboration/projects").asJsonArray.map(::parseProject)

    suspend fun createProject(name: String, payload: JsonObject): CollaborationProject {
        val body = JsonObject().apply {
            addProperty("name", name)
            add("payload", payload)
        }
        return parseProject(request("POST", "/collaboration/projects", body).asJsonObject)
    }

    suspend fun addVersion(projectId: String, payload: JsonObject, message: String): Int {
        val body = JsonObject().apply {
            add("payload", payload)
            addProperty("message", message)
        }
        return request("POST", "/collaboration/projects/$projectId/versions", body)
            .asJsonObject.get("version").asInt
    }

    suspend fun versions(projectId: String): List<CollaborationVersion> =
        request("GET", "/collaboration/projects/$projectId/versions").asJsonArray.map { element ->
            val item = element.asJsonObject
            CollaborationVersion(
                version = item.get("version").asInt,
                authorId = item.get("author_id").asString,
                message = item.get("message").asString,
                createdAt = item.get("created_at").asDouble,
            )
        }

    suspend fun comments(projectId: String, after: Double = 0.0): List<CollaborationComment> =
        request("GET", "/collaboration/projects/$projectId/comments?after=$after").asJsonArray.map { element ->
            val item = element.asJsonObject
            CollaborationComment(
                id = item.get("id").asString,
                authorId = item.get("author_id").asString,
                body = item.get("body").asString,
                createdAt = item.get("created_at").asDouble,
            )
        }

    suspend fun addComment(projectId: String, text: String): CollaborationComment {
        val body = JsonObject().apply { addProperty("body", text) }
        val item = request("POST", "/collaboration/projects/$projectId/comments", body).asJsonObject
        return CollaborationComment(
            id = item.get("id").asString,
            authorId = item.get("author_id").asString,
            body = item.get("body").asString,
            createdAt = item.get("created_at").asDouble,
        )
    }

    suspend fun setMember(projectId: String, memberId: String, role: String) {
        val body = JsonObject().apply {
            addProperty("user_id", memberId)
            addProperty("role", role)
        }
        request("PUT", "/collaboration/projects/$projectId/members", body)
    }

    private suspend fun request(method: String, path: String, body: JsonObject? = null): JsonElement =
        withContext(Dispatchers.IO) {
            require(root.startsWith("http://") || root.startsWith("https://")) { "Invalid backend URL" }
            val builder = Request.Builder()
                .url(root + path)
                .header("X-Viewshade-User", userId)
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            val jsonBody = (body ?: JsonObject()).toString().toRequestBody(JSON_MEDIA)
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post(jsonBody)
                "PUT" -> builder.put(jsonBody)
                else -> error("Unsupported method $method")
            }
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        gson.fromJson(text, JsonObject::class.java).get("detail")?.asString
                    }.getOrNull() ?: text.take(240)
                    error("Collaboration HTTP ${response.code}: $detail")
                }
                gson.fromJson(text, JsonElement::class.java) ?: JsonObject()
            }
        }

    private fun parseProject(element: JsonElement): CollaborationProject {
        val item = element.asJsonObject
        return CollaborationProject(
            id = item.get("id").asString,
            name = item.get("name").asString,
            ownerId = item.get("owner_id").asString,
            role = item.get("role").asString,
            version = item.get("version").asInt,
            updatedAt = item.get("updated_at").asDouble,
            payload = item.getAsJsonObject("payload") ?: JsonObject(),
        )
    }

    private inline fun <T> JsonArray.map(transform: (JsonElement) -> T): List<T> =
        (0 until size()).map { index -> transform(get(index)) }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
