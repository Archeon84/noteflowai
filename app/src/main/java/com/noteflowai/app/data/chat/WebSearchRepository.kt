package com.noteflowai.app.data.chat

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.noteflowai.app.data.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

class WebSearchRepository {

    private val gson = Gson()
    private val client = NetworkModule.newClientBuilder()
        .build()

    /**
     * Performs a web search against a user-configured search API.
     *
     * The [apiUrl] is expected to be a GET endpoint that accepts a `q` query parameter
     * and a Bearer token in the Authorization header (e.g. Brave Search, SerpAPI,
     * Google Custom Search, Perplexity). If [apiUrl] is blank the feature is disabled
     * and an empty list is returned.
     *
     * Parsing is defensive and supports several common JSON response shapes:
     *  - Google Custom Search: `items[].{title, link, snippet}`
     *  - Brave Search: `webPages.value[].{title, url, description}`
     *  - SerpAPI / generic: `organic[].{title, link, snippet}`
     *  - Bing: `webPages.value[].{name, url, snippet}`
     *
     * On any failure an empty list is returned so the chat never breaks.
     */
    suspend fun search(query: String, apiUrl: String, apiKey: String): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            if (apiUrl.isBlank()) return@withContext emptyList()
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                // Strip a trailing placeholder query param (e.g. user typed "...?q=" or
                // "...&q=") so we don't end up with duplicate/empty q parameters.
                val stripped = apiUrl.replace(Regex("[?&]q=?$"), "")
                val baseUrl = if (stripped.startsWith("http://", ignoreCase = true)) {
                    "https://" + stripped.substring(7)
                } else {
                    stripped
                }
                val separator = if (baseUrl.contains("?")) "&" else "?"
                val fullUrl = "$baseUrl${separator}q=$encoded"
                val requestBuilder = Request.Builder().url(fullUrl).get()
                    .header("Accept", "application/json")
                if (apiKey.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $apiKey")
                    requestBuilder.header("X-Subscription-Token", apiKey)
                }
                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext emptyList()
                }
                val body = response.body?.string().orEmpty()
                response.close()
                parseResults(body)
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun parseResults(body: String): List<WebSearchResult> {
        if (body.isBlank()) return emptyList()
        return try {
            val root = gson.fromJson(body, JsonElement::class.java) ?: return emptyList()
            val obj = root.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()

            // Google Custom Search / SerpAPI generic
            obj.getAsJsonArray("items")?.let { items ->
                return mapItems(items) { el ->
                    val o = el.asJsonObject
                    WebSearchResult(
                        title = str(o, "title"),
                        url = str(o, "link", "url"),
                        snippet = str(o, "snippet", "description")
                    )
                }
            }

            // SerpAPI alternative
            obj.getAsJsonArray("organic")?.let { items ->
                return mapItems(items) { el ->
                    val o = el.asJsonObject
                    WebSearchResult(
                        title = str(o, "title"),
                        url = str(o, "link", "url"),
                        snippet = str(o, "snippet", "description")
                    )
                }
            }

            // Brave Search: web.results[] and news.results[]
            obj.getAsJsonObject("web")?.getAsJsonArray("results")?.let { items ->
                return mapItems(items) { el ->
                    val o = el.asJsonObject
                    WebSearchResult(
                        title = str(o, "title", "name"),
                        url = str(o, "url", "link"),
                        snippet = str(o, "description", "snippet")
                    )
                }
            }
            obj.getAsJsonObject("news")?.getAsJsonArray("results")?.let { items ->
                return mapItems(items) { el ->
                    val o = el.asJsonObject
                    WebSearchResult(
                        title = str(o, "title", "name"),
                        url = str(o, "url", "link"),
                        snippet = str(o, "description", "snippet")
                    )
                }
            }

            // Brave / Bing webPages.value[]
            obj.getAsJsonObject("webPages")?.getAsJsonArray("value")?.let { items ->
                return mapItems(items) { el ->
                    val o = el.asJsonObject
                    WebSearchResult(
                        title = str(o, "title", "name"),
                        url = str(o, "url", "link"),
                        snippet = str(o, "description", "snippet")
                    )
                }
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mapItems(array: com.google.gson.JsonArray, transform: (JsonElement) -> WebSearchResult): List<WebSearchResult> {
        val out = mutableListOf<WebSearchResult>()
        for (el in array) {
            if (!el.isJsonObject) continue
            val r = transform(el)
            if (r.url.isNotBlank()) out.add(r)
            if (out.size >= 5) break
        }
        return out
    }

    private fun str(obj: com.google.gson.JsonObject, vararg keys: String): String {
        for (k in keys) {
            val el = obj.get(k) ?: continue
            if (el.isJsonPrimitive) return el.asString.orEmpty()
        }
        return ""
    }
}
