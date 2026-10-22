package com.jojotv.home

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A last resort after TMDB: programme pages only, never a channel logo or an opaque poster. */
object OfficialProgrammeLogoResolver {
    private const val PREFIX = "official_programme_logo_v1_"
    private const val DAY = 86_400_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val work by lazy { Executors.newFixedThreadPool(2) }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
    private val pending = mutableMapOf<String, MutableList<Callback>>()
    internal data class ProgrammePage(val url: String, val title: String)
    private data class Catalog(val pages: List<ProgrammePage>, val expiresAt: Long)
    private val catalogs = ConcurrentHashMap<Source, FutureTask<Catalog>>()

    internal enum class Source(val host: String, val catalog: String, val imageHost: String) {
        M6("www.m6.fr", "https://www.m6.fr/sitemap-program.xml", "images-fio.6play.fr"),
        TF1("www.tf1.fr", "https://www.tf1.fr/programmes-tv", "photos.tf1.fr"),
        FRANCE_TV("www.france.tv", "https://www.france.tv/sitemaps/programs_index.xml", "medias.france.tv")
    }

    fun interface Callback { fun onLoaded(url: String) }

    private fun mediaType(type: String?): String = when (type?.lowercase(Locale.ROOT)) {
        "movie", "movies" -> "movie"
        "tv", "series", "unknown", "", null -> "tv"
        else -> ""
    }

    internal fun programmeTitle(title: String): String {
        val programme = HomeMediaRules.recurringProgrammeName(title)
        return programme
            .replace(Regex("(?i)\\.(?:3g2|3gp|avi|flv|m2ts|m4v|mkv|mov|mp4|mpeg|mpg|mts|ts|webm|wmv)$"), "")
            .replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '.')
    }

    internal fun titleKey(title: String): String = Normalizer.normalize(unescape(title), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    // These catalogues carry French branding. Do not replace an original-language logo with a translation.
    internal fun supportsLanguage(language: String): Boolean {
        val code = language.lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')
        return code.isBlank() || code == "fr"
    }

    private fun cacheKey(title: String, id: Int, type: String?): String {
        val kind = mediaType(type)
        if (kind.isBlank()) return ""
        val identity = if (id > 0) "$kind:$id" else "$kind:${titleKey(programmeTitle(title))}"
        if (id <= 0 && titleKey(programmeTitle(title)).length < 3) return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return PREFIX + digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun saved(context: Context, key: String): JsonObject? = runCatching {
        json.parseToJsonElement(context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
            .getString(key, "").orEmpty()).jsonObject
    }.getOrNull()?.takeIf { it["until"]?.jsonPrimitive?.longOrNull?.let { until -> until > System.currentTimeMillis() } == true }

    @JvmStatic
    fun cached(context: Context, title: String?, tmdbId: Int, type: String?): String {
        if (mediaType(type).isBlank()) return ""
        val shared = OnlineAppIconLoader.cachedTitleLogo(context, mediaType(type), tmdbId)
        if (shared.isNotBlank()) return shared
        val known = HomeMediaRules.programmeTitleLogoFallback(title.orEmpty(), tmdbId, type)
        if (known.isNotBlank()) return known
        return saved(context, cacheKey(title.orEmpty(), tmdbId, type))?.text("url").orEmpty()
    }

    /** All HTTP work is independent of metadata/playback; simultaneous views share the same request. */
    @JvmStatic
    fun request(context: Context, title: String?, tmdbId: Int, type: String?, callback: Callback) {
        resolve(context, title, tmdbId, type) { logo -> if (logo.isNotBlank()) callback.onLoaded(logo) }
    }

    /** Also report a completed lookup with no logo, so title slots can reveal their fallback. */
    @JvmStatic
    fun resolve(context: Context, title: String?, tmdbId: Int, type: String?, callback: Callback) {
        val app = context.applicationContext
        val requestedTitle = programmeTitle(title.orEmpty())
        val key = cacheKey(requestedTitle, tmdbId, type)
        if (key.isBlank()) { main.post { callback.onLoaded("") }; return }
        val cached = cached(app, requestedTitle, tmdbId, type)
        if (cached.isNotBlank()) { main.post { callback.onLoaded(cached) }; return }
        if (saved(app, key) != null) { main.post { callback.onLoaded("") }; return }
        synchronized(pending) {
            pending[key]?.let { it.add(callback); return }
            pending[key] = mutableListOf(callback)
        }
        work.execute {
            var logo = ""
            var canonicalTitle = requestedTitle
            var failed = false
            try {
                var language = ""
                if (tmdbId > 0) {
                    // Recheck the production identity, rather than trusting a localized UI title.
                    val apiKey = java.net.URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8")
                    val details = json.parseToJsonElement(text("https://api.themoviedb.org/3/${mediaType(type)}/$tmdbId"
                        + "?api_key=$apiKey&language=fr-FR&append_to_response=images&include_image_language=fr,en,null")).jsonObject
                    language = details.text("original_language")
                    if (language.isBlank()) throw IOException("Missing production language")
                    canonicalTitle = details.text(if (mediaType(type) == "movie") "original_title" else "original_name")
                        .ifBlank { requestedTitle }
                    if (supportsLanguage(language)) {
                        val logos = (details.obj("images")["logos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                            .associate { it.text("file_path") to it.text("iso_639_1") }
                        logo = HomeMediaRules.originalTitleLogoUrl(logos, language)
                    }
                }
                if (logo.isBlank() && supportsLanguage(language)) for (source in Source.entries) {
                    try {
                        val pages = matchingPages(source, catalog(source), canonicalTitle)
                        // Multiple distinct programmes with the same name are not a certain match.
                        if (pages.size > 1) continue
                        val candidates = if (pages.isNotEmpty()) pages else if (source == Source.TF1) {
                            val slug = Normalizer.normalize(canonicalTitle, Normalizer.Form.NFD)
                                .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
                                .replace(Regex("[^a-z0-9]+"), "-").trim('-')
                            listOf("tf1", "tmc", "tfx", "tf1-series-films").map { "https://${source.host}/$it/$slug" }
                        } else emptyList()
                        for (page in candidates) {
                            val candidate = runCatching { logoFromPage(source, page, text(page), canonicalTitle) }
                                .onFailure { failed = true }.getOrDefault("")
                            if (candidate.isNotBlank() && transparentLogo(candidate)) { logo = candidate; break }
                        }
                        if (logo.isNotBlank()) break
                    } catch (_: Exception) { failed = true }
                }
            } catch (_: Exception) { failed = true }
            OnlineAppIconLoader.cachedTitleLogo(app, mediaType(type), tmdbId)
                .takeIf { it.startsWith("https://image.tmdb.org/") }?.let { logo = it }
            val until = System.currentTimeMillis() + when {
                logo.isNotBlank() -> 30 * DAY
                failed -> 10 * 60_000L
                else -> DAY
            }
            val value = buildJsonObject { put("url", logo); put("until", until) }.toString()
            val edit = app.getSharedPreferences("home_settings", Context.MODE_PRIVATE).edit().putString(key, value)
            if (logo.isNotBlank()) {
                val alias = cacheKey(canonicalTitle, 0, type)
                if (alias.isNotBlank()) edit.putString(alias, value)
                OnlineAppIconLoader.rememberTitleLogo(app, mediaType(type), tmdbId, logo)
            }
            edit.apply()
            val callbacks = synchronized(pending) { pending.remove(key).orEmpty().toList() }
            val result = logo
            main.post { callbacks.forEach { it.onLoaded(result) } }
        }
    }

    private fun catalog(source: Source): List<ProgrammePage> {
        while (true) {
            val task = FutureTask { Catalog(catalogPages(source, text(source.catalog)), System.currentTimeMillis() + DAY) }
            val active = catalogs.putIfAbsent(source, task) ?: task.also { it.run() }
            val result = try { active.get() } catch (error: Exception) { catalogs.remove(source, active); throw error }
            if (result.expiresAt > System.currentTimeMillis()) return result.pages
            catalogs.remove(source, active)
        }
    }

    internal fun catalogPages(source: Source, document: String): List<ProgrammePage> {
        if (source == Source.TF1) return Regex("(?is)<a\\b[^>]*>").findAll(document).mapNotNull { anchor ->
            fun attr(name: String) = Regex("(?i)\\b$name=[\"']([^\"']*)[\"']").find(anchor.value)?.groupValues?.get(1).orEmpty()
            val path = attr("href")
            if (!Regex("/(?:tf1|tmc|tfx|tf1-series-films|lci)/[^/?#]+").matches(path)) return@mapNotNull null
            val name = unescape(attr("title").ifBlank { attr("aria-label") })
            if (name.isBlank()) return@mapNotNull null
            ProgrammePage("https://${source.host}$path", name)
        }.distinctBy { it.url }.toList()
        val urls = Regex("(?s)<loc>\\s*(?:<!\\[CDATA\\[\\s*)?(https://[^<\\s]+)")
            .findAll(document).map { it.groupValues[1].removeSuffix("]]>") }
        return urls.mapNotNull { url ->
            val uri = runCatching { URI(unescape(url)) }.getOrNull() ?: return@mapNotNull null
            if (!officialUrl(uri.toString(), source.host)) return@mapNotNull null
            val page = when (source) {
                Source.M6 -> uri.toString().takeIf { Regex("/[^/]+-p_\\d+").matches(uri.path) }
                Source.TF1 -> null
                Source.FRANCE_TV -> uri.path.takeIf { it.startsWith("/sitemaps/program/") && it.endsWith(".xml") }
                    ?.removePrefix("/sitemaps/program/")?.removeSuffix(".xml")?.replace('_', '/')
                    ?.let { "https://${source.host}/$it/" }
            } ?: return@mapNotNull null
            ProgrammePage(page, URI(page).path.trim('/').substringAfterLast('/').replace(Regex("-p_\\d+$"), ""))
        }.distinctBy { it.url }.toList()
    }

    internal fun matchingPages(source: Source, pages: List<ProgrammePage>, title: String): List<String> {
        val wanted = titleKey(title)
        if (wanted.length < 3) return emptyList()
        return pages.filter { officialUrl(it.url, source.host) && titleKey(it.title) == wanted }.map { it.url }.distinct()
    }

    internal fun logoFromPage(source: Source, page: String, html: String, title: String): String {
        if (!officialUrl(page, source.host)) return ""
        val wanted = titleKey(title)
        if (wanted.length < 3) return ""
        when (source) {
            Source.M6 -> {
                val id = Regex("-p_(\\d+)$").find(URI(page).path)?.groupValues?.get(1) ?: return ""
                val start = html.indexOf("root.__dehydratedState =")
                if (start < 0) return ""
                val state = json.parseToJsonElement(jsonValue(html, html.indexOf('{', start))).jsonObject
                val redux = json.parseToJsonElement(state.text("__reduxState")).jsonObject
                val program = redux.obj("layout").obj("layouts").obj("main").obj("program").obj(id)
                val hero = program["blocks"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.obj("content")?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.obj("itemContent") ?: return ""
                val logo = hero.obj("logo")
                val identifiedPage = Regex("(?is)<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>")
                    .findAll(html).any { script ->
                        val tree = runCatching { json.parseToJsonElement(script.groupValues[1]) }.getOrNull()
                        tree != null && objects(tree).any {
                            it.text("@id").trimEnd('/') == page.trimEnd('/') && titleKey(it.text("name")) == wanted
                        }
                    }
                if ((!identifiedPage && titleKey(logo.text("caption")) != wanted) ||
                    !logo.text("id").matches(Regex("\\d+"))) return ""
                return "https://${source.imageHost}/v2/images/${logo.text("id")}/raw"
            }
            Source.TF1 -> {
                // Next.js streams data as JSON strings. Decode it; never evaluate website JavaScript.
                val payload = Regex("(?s)<script[^>]*>(.*?)</script>").findAll(html).mapNotNull { script ->
                    val body = script.groupValues[1]
                    val start = body.indexOf("self.__next_f.push(")
                    if (start < 0) null else runCatching {
                        json.parseToJsonElement(jsonValue(body, body.indexOf('[', start))).jsonArray
                            .getOrNull(1)?.jsonPrimitive?.content
                    }.getOrNull()
                }.joinToString("")
                for (row in payload.lineSequence()) {
                    val data = row.substringAfter(':', "")
                    if (!data.startsWith("[") && !data.startsWith("{")) continue
                    val root = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: continue
                    for (program in objects(root)) {
                        if (titleKey(program.text("name")) != wanted ||
                            program.text("slug") != URI(page).path.trim('/').substringAfterLast('/')) continue
                        val logo = program.obj("decoration").obj("logo")
                        if (!logo.text("id").startsWith("logo-programme-")) continue
                        val urls = logo["sourcesWithScales"] as? JsonArray ?: continue
                        val selected = urls.mapNotNull { it as? JsonObject }.filter {
                            it.text("type") == "png" && officialUrl(it.text("url"), source.imageHost)
                        }.maxByOrNull { it.text("width").toIntOrNull().orZero() * it.text("scale").toIntOrNull().orZero() }
                        if (selected != null) return selected.text("url")
                    }
                }
            }
            Source.FRANCE_TV -> {
                val heading = Regex("(?is)<h1\\b[^>]*>(.*?)</h1>").find(html) ?: return ""
                if (titleKey(heading.groupValues[1].replace(Regex("<[^>]*>"), "")) != wanted) return ""
                // The programme's transparent title image precedes its H1. Episode cards follow it.
                for (tag in Regex("(?is)<img\\b[^>]*>").findAll(html.substring(0, heading.range.last + 1))) {
                    fun attr(name: String) = Regex("(?i)\\b$name=[\"']([^\"']*)[\"']").find(tag.value)?.groupValues?.get(1).orEmpty()
                    if (titleKey(attr("alt")) != wanted || attr("aria-hidden") != "true") continue
                    val url = unescape(attr("srcSet").substringBefore(' ').ifBlank { attr("src") })
                    if (officialUrl(url, source.imageHost) && URI(url).path.endsWith(".png")) return url
                }
            }
        }
        return ""
    }

    private fun objects(value: JsonElement): Sequence<JsonObject> = sequence {
        when (value) {
            is JsonObject -> { yield(value); value.values.forEach { yieldAll(objects(it)) } }
            is JsonArray -> value.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }

    private fun jsonValue(text: String, start: Int): String {
        if (start < 0) return ""
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return ""
    }

    internal fun officialUrl(url: String, host: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == host && uri.userInfo == null && uri.port in listOf(-1, 443)
    }.getOrDefault(false)

    private fun bytes(address: String, limit: Int): ByteArray {
        var url = address
        val hosts = Source.entries.flatMap { listOf(it.host, it.imageHost) } + "api.themoviedb.org"
        repeat(4) {
            if (hosts.none { host -> officialUrl(url, host) }) throw IOException("Non-official URL")
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; JojoHome/1.0)")
                .header("Accept-Language", "fr-FR,fr;q=0.9").build()).execute().use { response ->
                if (response.code in 300..399) {
                    url = URI(url).resolve(response.header("Location") ?: throw IOException("Missing redirect")).toString()
                } else {
                    if (!response.isSuccessful) throw IOException("Official page unavailable")
                    val body = response.body ?: throw IOException("Empty response")
                    if (body.contentLength() > limit) throw IOException("Response too large")
                    val data = body.byteStream().readBytesBounded(limit)
                    return data
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw IOException("Response too large")
            output.write(buffer, 0, count)
        }
    }

    private fun text(url: String) = String(bytes(url, 4 * 1024 * 1024), Charsets.UTF_8)

    private fun transparentLogo(url: String): Boolean = runCatching {
        val data = bytes(url, 2 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
        val options = BitmapFactory.Options().apply {
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 512)
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return@runCatching false
        try {
            if (!bitmap.hasAlpha()) return@runCatching false
            var transparent = false
            var ink = false
            for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 32))
                for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 64)) {
                    val alpha = bitmap.getPixel(x, y) ushr 24
                    if (alpha < 32) transparent = true
                    if (alpha > 192) ink = true
                }
            transparent && ink
        } finally { bitmap.recycle() }
    }.getOrDefault(false)

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun Int?.orZero() = this ?: 0
    private fun unescape(text: String) = text.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
}
