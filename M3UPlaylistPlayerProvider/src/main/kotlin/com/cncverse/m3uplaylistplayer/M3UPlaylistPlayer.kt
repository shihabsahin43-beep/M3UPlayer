package com.cncverse.M3UPlaylistPlayer

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.util.UUID

class HeaderReplacementInterceptor(private val customHeaders: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()
        customHeaders.keys.forEach { headerName -> requestBuilder.removeHeader(headerName) }
        customHeaders.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        return chain.proceed(requestBuilder.build())
    }
}

class M3UPlaylistPlayer(
    private val customName: String = "FanCode DRM Player",
    private val customMainUrl: String = "https://your-playlist-link-here.com/live.m3u"
) : MainAPI() {
    
    override var lang = "en"
    override var mainUrl = customMainUrl
    override var name = customName
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val headers = mapOf(
        "User-Agent" to "ReactNativeVideo/9.7.0 (Linux;Android 10) AndroidXMedia3/1.6.1"
    )

    private val customHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor(HeaderReplacementInterceptor(headers)).build()
    }

    private suspend fun getWithCustomHeaders(url: String): String {
        val request = Request.Builder().url(url).build()
        return customHttpClient.newCall(request).execute().use { response ->
            response.body.string()
        }
    }

    // Hex থেকে Base64Url এ কনভার্ট করার ফাংশন (এক্সোপ্লেয়ারের জন্য জরুরি)
    private fun String.hexToBase64UrlOrNull(): String? {
        val normalizedHex = trim().replace("-", "")
        if (normalizedHex.isEmpty() || normalizedHex.length % 2 != 0 || !normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
            return null
        }
        return try {
            val bytes = normalizedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    // প্লেলিস্ট থেকে চ্যানেল ডেটা এক্সট্র্যাক্ট করার কাস্টম পার্সার
    private fun parseCustomM3U(content: String): List<LoadData> {
        val parsedChannels = mutableListOf<LoadData>()
        var currentTitle = "Live Stream"
        var currentPoster = ""
        var currentGroup = "Channels"
        var currentKid = ""
        var currentKey = ""

        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                currentTitle = trimmed.substringAfterLast(",").trim().ifEmpty { "Live Stream" }
                currentPoster = Regex("""tvg-logo="(.*?)"""").find(trimmed)?.groupValues?.get(1) ?: ""
                currentGroup = Regex("""group-title="(.*?)"""").find(trimmed)?.groupValues?.get(1) ?: "Channels"
            } else if (trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                // সরাসরি KID এবং KEY বের করা
                val keyData = trimmed.substringAfter("license_key=").trim()
                if (keyData.contains(":")) {
                    currentKid = keyData.substringBefore(":")
                    currentKey = keyData.substringAfter(":")
                }
            } else if (trimmed.startsWith("http")) {
                parsedChannels.add(
                    LoadData(
                        url = trimmed,
                        title = currentTitle,
                        poster = currentPoster,
                        nation = currentGroup,
                        key = currentKey,
                        keyid = currentKid,
                        userAgent = "",
                        cookie = "",
                        licenseUrl = "",
                        headers = emptyMap()
                    )
                )
                // পরবর্তী চ্যানেলের জন্য ভ্যারিয়েবল রিসেট
                currentTitle = "Live Stream"
                currentPoster = ""
                currentGroup = "Channels"
                currentKid = ""
                currentKey = ""
            }
        }
        return parsedChannels
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawContent = getWithCustomHeaders(mainUrl)
        val channels = parseCustomM3U(rawContent)

        return newHomePageResponse(channels.groupBy { it.nation }.map { group ->
            val title = group.key.ifEmpty { "Channels" }
            val show = group.value.map { channel ->
                newLiveSearchResponse(channel.title, channel.toJson(), TvType.Live) {
                    this.posterUrl = channel.poster
                    this.lang = channel.nation
                }
            }
            HomePageList(title, show, isHorizontalImages = true)
        }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rawContent = getWithCustomHeaders(mainUrl)
        val channels = parseCustomM3U(rawContent)
        
        return channels.filter { it.title.contains(query, ignoreCase = true) }.map { channel ->
            newLiveSearchResponse(channel.title, channel.toJson(), TvType.Live) {
                this.posterUrl = channel.poster
                this.lang = channel.nation
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = data.nation
        }
    }

    data class LoadData(
        val url: String,
        val title: String,
        val poster: String,
        val nation: String,
        val key: String,
        val keyid: String,
        val userAgent: String,
        val cookie: String,
        val licenseUrl: String,
        val headers: Map<String, String>,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        
        // ডাইনামিক হেডার সেটআপ
        val reqHeaders = mutableMapOf<String, String>()
        reqHeaders["User-Agent"] = "ReactNativeVideo/9.7.0 (Linux;Android 10) AndroidXMedia3/1.6.1"
        reqHeaders["Referer"] = "https://fancode.com/"
        
        if (loadData.url.contains("mpd")) {
            // ১. চেক করা হচ্ছে প্লেলিস্টে সরাসরি চাবি (KID:KEY) দেওয়া আছে কিনা
            if (loadData.key.isNotEmpty() && loadData.keyid.isNotEmpty()) {
                val playerKid = loadData.keyid.hexToBase64UrlOrNull() ?: loadData.keyid
                val playerKey = loadData.key.hexToBase64UrlOrNull() ?: loadData.key

                callback.invoke(
                    newDrmExtractorLink(
                        this.name,
                        this.name,
                        loadData.url,
                        INFER_TYPE,
                        CLEARKEY_UUID
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = reqHeaders
                        this.kid = playerKid
                        this.key = playerKey
                    }
                )
                return true
            } else {
                // ডিআরএম চাবি না থাকলে রেগুলার MPD হিসেবে প্লে করবে
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = loadData.url,
                        ExtractorLinkType.DASH
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = reqHeaders
                    }
                )
            }
        } else {
            // M3U8 বা সাধারণ লিঙ্ক প্লে করার অপশন
            callback.invoke(
                newExtractorLink(
                    this.name,
                    loadData.title,
                    url = loadData.url,
                    if (loadData.url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = reqHeaders
                }
            )
        }
        return true
    }
}
