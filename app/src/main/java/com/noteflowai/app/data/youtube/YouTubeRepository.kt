package com.noteflowai.app.data.youtube

import android.util.Log
import com.noteflowai.app.data.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeRepository {

    companion object {
        private const val TAG = "YouTubeRepo"
        private val VIDEO_ID_PATTERNS = listOf(
            Pattern.compile("(?:v=|/v/|youtu\\.be/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("(?:embed/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("(?:shorts/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("^([a-zA-Z0-9_-]{11})$")
        )
    }

    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client = NetworkModule.newClientBuilder()
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        for (pattern in VIDEO_ID_PATTERNS) {
            val matcher = pattern.matcher(trimmed)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    suspend fun fetchVideoTitle(videoId: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext "Unknown Title"
            response.close()

            val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(body)
            titleMatch?.groupValues?.get(1) ?: "Unknown Title"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch title: ${e.message}")
            "Unknown Title"
        }
    }

    suspend fun fetchCaptions(videoId: String): YouTubeCaptionResult = withContext(Dispatchers.IO) {
        // Strategy 1: Extract transcript params from watch page, then use get_transcript API
        Log.i(TAG, "Strategy 1: Extract transcript params from watch page...")
        val transcriptResult = fetchTranscriptFromWatchPage(videoId)
        if (transcriptResult.isNotBlank()) {
            return@withContext YouTubeCaptionResult(
                success = true,
                transcript = transcriptResult,
                language = "en",
                source = "innertube_transcript"
            )
        }

        // Strategy 2: Use innertube WEB player API to get caption track URLs, then fetch
        Log.i(TAG, "Strategy 2: WEB player API for caption tracks...")
        val webResult = fetchCaptionsViaWebPlayer(videoId)
        if (webResult.isNotBlank()) {
            return@withContext YouTubeCaptionResult(
                success = true,
                transcript = webResult,
                language = "en",
                source = "web_player_captions"
            )
        }

        // Strategy 3: Use innertube ANDROID player API
        Log.i(TAG, "Strategy 3: ANDROID player API...")
        val androidResult = fetchCaptionsViaAndroidClient(videoId)
        if (androidResult.isNotBlank()) {
            return@withContext YouTubeCaptionResult(
                success = true,
                transcript = androidResult,
                language = "en",
                source = "innertube_android"
            )
        }

        // Strategy 4: youtube-transcript.ai free API (no key required)
        Log.i(TAG, "Strategy 4: youtube-transcript.ai free API...")
        val freeApiResult = fetchFromYoutubeTranscriptAi(videoId)
        if (freeApiResult.isNotBlank()) {
            return@withContext YouTubeCaptionResult(
                success = true,
                transcript = freeApiResult,
                language = "en",
                source = "youtube_transcript_ai"
            )
        }

        // Strategy 5: Invidious API (public instances)
        Log.i(TAG, "Strategy 5: Invidious API...")
        val invidiousResult = fetchFromInvidious(videoId)
        if (invidiousResult.isNotBlank()) {
            return@withContext YouTubeCaptionResult(
                success = true,
                transcript = invidiousResult,
                language = "en",
                source = "invidious"
            )
        }

        YouTubeCaptionResult(
            success = false, transcript = "", language = "", source = "none",
            error = "No captions available. This video may not have subtitles, or YouTube may be blocking requests."
        )
    }

    private fun fetchTranscriptFromWatchPage(videoId: String): String {
        return try {
            val html = fetchWatchPage(videoId) ?: return ""

            // Extract ytInitialData (contains engagement panels with transcript params)
            val initialData = extractInitialData(html)
            if (initialData == null) {
                Log.w(TAG, "Could not extract ytInitialData")
                return ""
            }

            // Find transcript params from engagement panels
            val transcriptParams = extractTranscriptParams(initialData)
            if (transcriptParams == null) {
                Log.w(TAG, "No transcript engagement panel found in ytInitialData")
                return ""
            }

            Log.i(TAG, "Found transcript params, fetching transcript...")
            fetchTranscriptWithParams(transcriptParams, videoId)
        } catch (e: Exception) {
            Log.w(TAG, "Strategy 1 failed: ${e.message}")
            ""
        }
    }

    private fun extractInitialData(html: String): JSONObject? {
        val patterns = listOf(
            Pattern.compile("var ytInitialData\\s*=\\s*(\\{.+?\\})\\s*;\\s*</script>"),
            Pattern.compile("var ytInitialData\\s*=\\s*(\\{.+?\\})\\s*;"),
            Pattern.compile("window\\.ytInitialData\\s*=\\s*(\\{.+?\\})\\s*;")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                try {
                    return JSONObject(matcher.group(1)!!)
                } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun extractTranscriptParams(initialData: JSONObject): String? {
        val panels = initialData.optJSONArray("engagementPanels") ?: return null
        for (i in 0 until panels.length()) {
            val panel = panels.optJSONObject(i) ?: continue
            val section = panel.optJSONObject("engagementPanelSectionListRenderer") ?: continue
            val panelId = section.optString("panelIdentifier", "")
            if (panelId == "engagement-panel-searchable-transcript") {
                val content = section.optJSONObject("content") ?: continue
                val continuationItem = content.optJSONObject("continuationItemRenderer") ?: continue
                val endpoint = continuationItem.optJSONObject("continuationEndpoint") ?: continue
                val getTranscript = endpoint.optJSONObject("getTranscriptEndpoint") ?: continue
                val params = getTranscript.optString("params", "")
                if (params.isNotEmpty()) {
                    Log.i(TAG, "Extracted transcript params (length=${params.length})")
                    return params
                }
            }
        }
        return null
    }

    private fun fetchTranscriptWithParams(params: String, videoId: String): String {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20250701.00.00")
                    put("hl", "en")
                })
            })
            put("params", params)
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/get_transcript?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
            .header("Content-Type", "application/json")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/watch?v=$videoId")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: ""
        val code = response.code
        response.close()

        Log.i(TAG, "get_transcript: HTTP $code, body length=${bodyStr.length}")

        if (code != 200 || bodyStr.isBlank()) {
            Log.w(TAG, "get_transcript failed: HTTP $code, body empty=${bodyStr.isBlank()}")
            if (bodyStr.isNotBlank()) {
                Log.w(TAG, "Response body present (${bodyStr.length} chars), status=$code")
            }
            return ""
        }

        return parseInnertubeTranscript(bodyStr)
    }

    private fun parseInnertubeTranscript(json: String): String {
        return try {
            val root = JSONObject(json)
            val actions = root.optJSONArray("actions") ?: return ""
            val sb = StringBuilder()

            for (i in 0 until actions.length()) {
                val action = actions.getJSONObject(i)
                val panel = action.optJSONObject("updateEngagementPanelAction")
                    ?.optJSONObject("content")
                    ?.optJSONObject("transcriptRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("transcriptSearchPanelRenderer")
                    ?: action.optJSONObject("updateEngagementPanelAction")
                        ?.optJSONObject("content")
                        ?.optJSONObject("transcriptRenderer")
                        ?.optJSONObject("body")
                        ?.optJSONObject("transcriptBodyRenderer")
                    ?: continue

                val cueGroups = panel.optJSONArray("cueGroups")
                val initialSegments = panel.optJSONArray("initialSegments")

                if (cueGroups != null) {
                    for (j in 0 until cueGroups.length()) {
                        val cueGroup = cueGroups.getJSONObject(j)
                        val renderer = cueGroup.optJSONObject("transcriptCueGroupRenderer") ?: continue
                        val cues = renderer.optJSONArray("cues") ?: continue
                        for (k in 0 until cues.length()) {
                            val cue = cues.getJSONObject(k)
                            val cueRenderer = cue.optJSONObject("transcriptCueRenderer") ?: continue
                            val text = cueRenderer.optJSONObject("cue")?.optString("simpleText") ?: ""
                            if (text.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append(" ")
                                sb.append(text)
                            }
                        }
                    }
                }

                if (initialSegments != null) {
                    for (j in 0 until initialSegments.length()) {
                        val segment = initialSegments.getJSONObject(j)
                        val renderer = segment.optJSONObject("transcriptSegmentRenderer") ?: continue
                        val snippet = renderer.optJSONObject("snippet")
                        val runs = snippet?.optJSONArray("runs")
                        if (runs != null) {
                            for (k in 0 until runs.length()) {
                                val text = runs.getJSONObject(k).optString("text", "")
                                if (text.isNotBlank()) {
                                    if (sb.isNotEmpty()) sb.append(" ")
                                    sb.append(text)
                                }
                            }
                        }
                    }
                }
            }

            val result = sb.toString().replace(Regex("\\s+"), " ").trim()
            Log.i(TAG, "Transcript parsed: ${result.length} chars")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse transcript: ${e.message}")
            ""
        }
    }

    private fun fetchCaptionsViaWebPlayer(videoId: String): String {
        return try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250701.00.00")
                        put("hl", "en")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val code = response.code
            response.close()

            Log.i(TAG, "WEB player: HTTP $code, body length=${bodyStr.length}")

            if (code != 200 || bodyStr.isBlank()) return ""

            extractAndFetchCaptions(bodyStr, videoId)
        } catch (e: Exception) {
            Log.w(TAG, "WEB player failed: ${e.message}")
            ""
        }
    }

    private fun fetchCaptionsViaAndroidClient(videoId: String): String {
        return try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 30)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip")
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            val code = response.code
            response.close()

            Log.i(TAG, "ANDROID player: HTTP $code, body length=${bodyStr.length}")

            if (code != 200 || bodyStr.isBlank()) return ""

            extractAndFetchCaptions(bodyStr, videoId)
        } catch (e: Exception) {
            Log.w(TAG, "ANDROID client failed: ${e.message}")
            ""
        }
    }

    private fun extractAndFetchCaptions(playerBody: String, videoId: String): String {
        val playerResponse = JSONObject(playerBody)
        val captions = playerResponse.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return ""

        if (captions.length() == 0) {
            Log.w(TAG, "No caption tracks in player response")
            return ""
        }

        Log.i(TAG, "Found ${captions.length()} caption tracks")

        // Try to find English first, fallback to first available
        var selectedTrack: JSONObject? = null
        for (i in 0 until captions.length()) {
            val track = captions.getJSONObject(i)
            val lang = track.optString("languageCode", "")
            if (lang.startsWith("en")) {
                selectedTrack = track
                break
            }
        }
        if (selectedTrack == null) selectedTrack = captions.getJSONObject(0)

        val baseUrl = selectedTrack.optString("baseUrl", "")
        val lang = selectedTrack.optString("languageCode", "unknown")
        Log.i(TAG, "Selected track: $lang")

        if (baseUrl.isBlank()) return ""

        // Fetch the caption text - try json3 first, then default XML
        val captionUrl = "$baseUrl&fmt=json3"
        val text = fetchCaptionText(captionUrl, videoId)
        if (text.isNotBlank()) return text

        // Fallback: try without fmt (XML)
        return fetchCaptionText(baseUrl, videoId)
    }

    private fun fetchCaptionText(url: String, videoId: String): String {
        return try {
            Log.i(TAG, "Fetching captions from: ${url.substring(0, Math.min(120, url.length))}...")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")
                .header("Origin", "https://www.youtube.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return ""
            val code = response.code
            response.close()

            Log.i(TAG, "Caption fetch: HTTP $code, body length=${body.length}")

            if (body.isBlank()) {
                Log.w(TAG, "Caption body blank for HTTP $code")
                return ""
            }

            if (body.trimStart().startsWith("{")) {
                parseJson3Caption(body)
            } else {
                parseXmlCaption(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Caption fetch failed: ${e.message}")
            ""
        }
    }

    private fun parseJson3Caption(json: String): String {
        val sb = StringBuilder()
        try {
            val root = JSONObject(json)
            val events = root.optJSONArray("events")
            if (events == null) {
                return parseXmlCaption(json)
            }

            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val segs = event.optJSONArray("segs") ?: continue

                for (j in 0 until segs.length()) {
                    val seg = segs.getJSONObject(j)
                    val text = seg.optString("utf8", "")
                    if (text.isNotBlank() && text != "\n") {
                        sb.append(text)
                    }
                }
                if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                    sb.append(" ")
                }
            }
        } catch (e: Exception) {
            return parseXmlCaption(json)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun parseXmlCaption(xml: String): String {
        val sb = StringBuilder()
        val textPattern = Pattern.compile("<text[^>]*>(.*?)</text>", Pattern.DOTALL)
        val matcher = textPattern.matcher(xml)

        while (matcher.find()) {
            val text = matcher.group(1)
                ?.replace("&amp;", "&")
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")
                ?.replace("&#39;", "'")
                ?.replace("\n", " ")
                ?.trim()

            if (!text.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(text)
            }
        }
        return sb.toString().trim()
    }

    private fun fetchFromYoutubeTranscriptAi(videoId: String): String {
        return try {
            val url = "https://youtube-transcript.ai/transcript/$videoId.txt"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val code = response.code
            response.close()

            Log.i(TAG, "youtube-transcript.ai: HTTP $code, body length=${body.length}")

            if (code != 200 || body.isBlank()) return ""

            // Response is markdown-formatted text with timestamps like [0:01]
            // Strip the header and timestamps to get clean text
            val transcriptSection = if (body.contains("## Transcript")) {
                body.substringAfter("## Transcript")
            } else {
                body
            }
            // Strip footer after ---
            val cleanBody = if (transcriptSection.contains("---")) {
                transcriptSection.substringBefore("---")
            } else {
                transcriptSection
            }

            val lines = cleanBody.lines()
            val transcriptLines = mutableListOf<String>()

            for (line in lines) {
                val cleaned = line.trim()
                    .replace(Regex("\\[\\d+:\\d+\\]"), "")      // Remove [0:01] timestamps
                    .replace(Regex("\\[\\s*\\]"), "")            // Remove empty [ ] brackets
                    .replace(Regex("\\s+"), " ")                 // Collapse whitespace
                    .trim()
                if (cleaned.isNotBlank()) {
                    transcriptLines.add(cleaned)
                }
            }

            val result = transcriptLines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (result.isNotBlank()) {
                Log.i(TAG, "youtube-transcript.ai success: ${result.length} chars")
                result
            } else {
                Log.w(TAG, "youtube-transcript.ai: no transcript content found")
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "youtube-transcript.ai failed: ${e.message}")
            ""
        }
    }

    private fun fetchFromInvidious(videoId: String): String {
        val instances = listOf(
            "https://inv.nadeko.net",
            "https://invidious.fdn.fr",
            "https://vid.puffyan.us",
            "https://invidious.snopyta.org",
            "https://yewtu.be"
        )

        for (instance in instances) {
            try {
                // Get list of available captions
                val captionsUrl = "$instance/api/v1/captions/$videoId"
                val captionsRequest = Request.Builder()
                    .url(captionsUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build()

                val captionsResponse = client.newCall(captionsRequest).execute()
                val captionsBody = captionsResponse.body?.string() ?: ""
                val captionsCode = captionsResponse.code
                captionsResponse.close()

                Log.i(TAG, "Invidious $instance: HTTP $captionsCode, body length=${captionsBody.length}")

                if (captionsCode != 200 || captionsBody.isBlank()) continue

                val captionsJson = JSONObject(captionsBody)
                val captionsArray = captionsJson.optJSONArray("captions") ?: continue

                if (captionsArray.length() == 0) continue

                // Find English caption, fallback to first available
                var selectedCaption: JSONObject? = null
                for (i in 0 until captionsArray.length()) {
                    val cap = captionsArray.getJSONObject(i)
                    val lang = cap.optString("languageCode", "")
                    if (lang.startsWith("en")) {
                        selectedCaption = cap
                        break
                    }
                }
                if (selectedCaption == null) selectedCaption = captionsArray.getJSONObject(0)

                val captionLabel = selectedCaption.optString("label", "")
                val lang = selectedCaption.optString("languageCode", "unknown")
                Log.i(TAG, "Invidious: selected caption '$captionLabel' ($lang)")

                // Fetch the actual caption content
                val contentUrl = "$instance${selectedCaption.optString("url", "")}"
                val contentRequest = Request.Builder()
                    .url(contentUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build()

                val contentResponse = client.newCall(contentRequest).execute()
                val contentBody = contentResponse.body?.string() ?: ""
                val contentCode = contentResponse.code
                contentResponse.close()

                Log.i(TAG, "Invidious caption content: HTTP $contentCode, length=${contentBody.length}")

                if (contentCode == 200 && contentBody.isNotBlank()) {
                    val parsed = parseXmlCaption(contentBody)
                    if (parsed.isNotBlank()) {
                        Log.i(TAG, "Invidious success via $instance: ${parsed.length} chars")
                        return parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious $instance failed: ${e.message}")
            }
        }
        return ""
    }

    private fun fetchWatchPage(videoId: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&hl=en")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            Log.i(TAG, "Watch page fetched: ${body?.length ?: 0} chars")
            body
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch watch page: ${e.message}")
            null
        }
    }

    private data class CaptionTrack(
        val baseUrl: String,
        val language: String,
        val name: String,
        val kind: String
    )
}

data class YouTubeCaptionResult(
    val success: Boolean,
    val transcript: String,
    val language: String = "en",
    val source: String = "youtube",
    val error: String? = null
)
