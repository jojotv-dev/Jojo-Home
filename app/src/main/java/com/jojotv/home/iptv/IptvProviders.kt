package com.jojotv.home.iptv

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import okhttp3.ConnectionPool
import kotlinx.serialization.Serializable
import org.xbill.DNS.Address
import org.xbill.DNS.ARecord
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.Cache
import org.xbill.DNS.ExtendedResolver
import org.xbill.DNS.Name
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import org.xbill.DNS.lookup.LookupSession
import java.net.InetAddress
import java.net.UnknownHostException
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.io.InterruptedIOException
import com.jojotv.home.BuildConfig
import com.jojotv.home.HomeMediaRules
import com.jojotv.home.MainActivity

/** Supplemental artwork/calendar data never changes a provider's identity or playback command. */
internal object IptvMediaPresentation {
    const val CACHE_VERSION = 3
    @Serializable data class Cast(val id: Int, val name: String, val character: String, val artwork: String)
    @Serializable data class Episode(val season: Int, val number: Int, val title: String, val airDate: String,
        val artwork: String, val description: String, val runtime: Int)
    @Serializable data class Season(val number: Int, val count: Int, val dates: List<String>)
    @Serializable data class Details(val tmdbId: Int, val mediaType: String, val logo: String = "",
        val backdrops: List<String> = emptyList(), val posters: List<String> = emptyList(),
        val cast: List<Cast> = emptyList(), val status: String = "", val seasonCount: Int = 0,
        val seasons: List<Season> = emptyList(), val episodes: List<Episode> = emptyList(),
        val genre: String = "", val year: String = "", val runtime: Int = 0,
        val description: String = "", val director: String = "",
        val broadcastSeason: Season? = null, val broadcastStatusLoaded: Boolean = false)

    internal fun creditPeople(mediaType: String, credits: JsonObject, fallback: JsonObject? = null): List<Cast> {
        fun presenterCount(person: JsonObject): Int = person["jobs"]?.array().orEmpty().map { it.obj() }
            .filter { HomeMediaRules.isTmdbPresenter(it.string("job")) }.maxOfOrNull { it.number("episode_count") }
            ?: if (HomeMediaRules.isTmdbPresenter(person.string("job"))) 0 else -1
        val cast = credits["cast"]?.array().orEmpty().map { it.obj() }
        val presenters = credits["crew"]?.array().orEmpty().map { it.obj() }
            .filter { presenterCount(it) >= 0 && it.string("name").isNotBlank() }.sortedByDescending(::presenterCount)
        if (mediaType == "tv" && presenters.isEmpty() && fallback != null) return creditPeople(mediaType, fallback)
        val people = HomeMediaRules.preferredTmdbPeople(mediaType, cast, presenters)
        return people.mapNotNull { person ->
            val name = HomeMediaRules.mediaMetadataText(person.string("name")).takeIf(String::isNotBlank) ?: return@mapNotNull null
            val role = if (people === presenters) "Presenter" else person.string("character").ifBlank {
                person["roles"]?.array()?.firstOrNull()?.obj()?.string("character").orEmpty()
            }
            Cast(person.number("id"), name, role, image(person.string("profile_path"), "w185"))
        }.distinctBy { it.id.takeIf { id -> id > 0 }?.toString() ?: it.name.lowercase(java.util.Locale.ROOT) }.take(12)
    }

    internal fun editorial(item: IptvItem, details: Details?, episode: IptvItem? = null): IptvItem {
        val selected = episode?.takeIf { item.type == ContentType.SERIES && it.type == ContentType.EPISODE &&
            it.sourceId == item.sourceId && it.seriesId == item.seriesId.ifBlank { item.providerId } }
        val duration = if (selected != null) {
            if (HomeMediaRules.providerRuntimeMinutes(selected.duration) > 0) selected.duration
            else details?.episodes?.firstOrNull { it.season == selected.season && it.number == selected.episode }
                ?.runtime?.takeIf { it > 0 }?.let { "$it min" }.orEmpty()
        } else if (HomeMediaRules.providerRuntimeMinutes(item.duration) > 0 || (details?.runtime ?: 0) <= 0) item.duration
            else "${details?.runtime} min"
        return item.copy(genre = HomeMediaRules.mediaMetadataText(item.genre).ifBlank { details?.genre.orEmpty() },
            year = HomeMediaRules.mediaMetadataText(item.year).ifBlank { details?.year.orEmpty() },
            duration = duration, description = HomeMediaRules.mediaMetadataText(item.description).ifBlank { details?.description.orEmpty() },
            cast = if (details?.mediaType == "tv" && details.cast.any { HomeMediaRules.isTmdbPresenter(it.character) })
                details.cast.joinToString(", ") { it.name }
            else HomeMediaRules.mediaMetadataText(item.cast).ifBlank { details?.cast.orEmpty().joinToString(", ") { it.name } },
            director = HomeMediaRules.mediaMetadataText(item.director).ifBlank { details?.director.orEmpty() })
    }

    internal fun cacheKey(item: IptvItem) = "v$CACHE_VERSION|${item.id}|${item.tmdbId}|${item.year}|${searchTitle(item.title)}"

    internal fun cachedDetails(context: Context, item: IptvItem): Details? {
        val cache = context.getSharedPreferences("home_hero_editorial_cache", Context.MODE_PRIVATE)
        val key = "native-media-v${HomeMediaRules.TITLE_LOGO_POLICY_VERSION}|${cacheKey(item)}"
        if (System.currentTimeMillis() - cache.getLong("native-media-at|$key", 0) >= 86_400_000L) return null
        return runCatching { iptvJson.decodeFromString<Details>(cache.getString(key, "").orEmpty()) }.getOrNull()
            ?.takeIf { item.tmdbId <= 0 || item.tmdbId == it.tmdbId }
    }

    internal fun playerPresentation(item: IptvItem, details: Details?, series: IptvItem? = null): org.json.JSONObject {
        val source = series?.takeIf { item.type == ContentType.EPISODE && it.type == ContentType.SERIES &&
            it.sourceId == item.sourceId && item.seriesId == it.seriesId.ifBlank { it.providerId } } ?: item
        val editorial = editorial(source, details, item.takeIf { it.type == ContentType.EPISODE })
        val names = HomeMediaRules.mediaMetadataNames(editorial.cast)
        val cast = names.map { name ->
            val member = details?.cast?.firstOrNull { it.name.equals(name, ignoreCase = true) }
            org.json.JSONObject().put("name", name).put("character", member?.character.orEmpty())
                .put("photo", member?.artwork.orEmpty()).put("tmdbId", member?.id ?: 0)
        }
        return org.json.JSONObject()
            .put("tmdbId", source.tmdbId.takeIf { it > 0 } ?: details?.tmdbId ?: 0)
            .put("mediaType", if (source.type == ContentType.MOVIE) "movie" else "tv")
            .put("description", editorial.description)
            .put("year", editorial.year).put("runtimeMinutes", HomeMediaRules.providerRuntimeMinutes(editorial.duration))
            .put("genres", org.json.JSONArray(HomeMediaRules.mediaMetadataNames(editorial.genre.replace('\u2022', ','))))
            .put("cast", org.json.JSONArray(names)).put("castDetails", org.json.JSONArray(cast))
            .put("director", org.json.JSONArray(HomeMediaRules.mediaMetadataNames(editorial.director)))
            .put("rating", HomeMediaRules.mediaMetadataText(item.rating).ifBlank { source.rating })
            .put("status", details?.status.orEmpty()).put("logo", titleLogo(source, details))
            .put("poster", item.artwork.ifBlank { source.artwork })
            .put("backdrop", item.backdrop.ifBlank { source.backdrop })
    }

    internal fun credits(item: IptvItem, presenters: Boolean = false): String {
        val actors = HomeMediaRules.mediaMetadataNames(item.cast)
        return listOfNotNull(actors.takeIf { it.isNotEmpty() }?.let {
            (if (presenters) "Pr\u00e9sentation : " else "Acteurs : ") + it.take(3).joinToString(", ") + if (it.size > 3) "\u2026" else ""
        }, HomeMediaRules.mediaMetadataText(item.director).takeIf(String::isNotBlank)?.let { "R\u00e9alisateur : $it" }).joinToString("\n")
    }

    internal fun heroArtworkCandidates(item: IptvItem, details: Details?, selected: String = ""): List<String> =
        (listOf(selected) + details?.backdrops.orEmpty() + listOf(item.backdrop, item.artwork))
            .filter(String::isNotBlank).distinct()

    internal fun titleLogo(item: IptvItem, details: Details?, cached: String = ""): String =
        HomeMediaRules.preferredTitleLogoUrl(item.titleLogo,
            HomeMediaRules.preferredTitleLogoUrl(cached, details?.logo))

    private fun image(path: String, size: String) = if (path.startsWith("/")) "https://image.tmdb.org/t/p/$size$path" else ""
    internal fun searchTitle(title: String): String = title
        .replace(Regex("(?i)\\b(?:4k|uhd|fhd|2160p|1080p|720p|hdr|sdr|dv|vostfr|multi|vfq|vff)\\b"), " ")
        .replace(Regex("[\\(\\[\\{]\\s*(?:19|20)\\d{2}\\s*[\\)\\]\\}]"), " ")
        .replace(Regex("[()\\[\\]{}]"), " ").replace(Regex("\\s+"), " ").trim()
    internal fun searchTitles(title: String): List<String> = searchTitle(title).split(Regex("\\s+\\|\\s+"))
        .map(String::trim).filter(String::isNotBlank).distinct()
    private fun normalized(title: String) = java.text.Normalizer.normalize(searchTitle(title), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    internal fun match(item: IptvItem, candidates: JsonArray): Int? {
        val titles = searchTitles(item.title).map(::normalized).filter(String::isNotBlank).toSet()
        if (titles.isEmpty()) return null
        val year = item.year.take(4).takeIf { it.matches(Regex("(?:19|20)\\d{2}")) }
            ?: Regex("(?:19|20)\\d{2}").find(item.title)?.value.orEmpty()
        val matches = candidates.map { it.obj() }.filter { candidate ->
            listOf("title", "name", "original_title", "original_name").any { normalized(candidate.string(it)) in titles } &&
                (year.isBlank() || candidate.string("release_date").ifBlank { candidate.string("first_air_date") }.take(4) == year)
        }.map { it.number("id") }.filter { it > 0 }.distinct()
        return matches.singleOrNull()
    }

    internal fun broadcastSeason(series: JsonObject, today: java.time.LocalDate = java.time.LocalDate.now()): JsonObject? {
        val seasons = series["seasons"]?.array().orEmpty().map { it.obj() }.filter { it.number("season_number") > 0 }
        val lastAiredSeason = series["last_episode_to_air"]?.obj()?.number("season_number") ?: 0
        return seasons.filter { it.number("season_number") == lastAiredSeason ||
            HomeMediaRules.episodeAirDate(it.string("air_date"))?.isAfter(today) == false }
            .maxByOrNull { it.number("season_number") } ?: seasons.minByOrNull { it.number("season_number") }
    }

    internal fun seriesBroadcastStatus(details: Details?, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val season = details?.broadcastSeason
        return if (season != null) HomeMediaRules.seasonStatusLabel(season.count, season.dates, today)
            else details?.status?.takeIf { it == "TERMINÉ" }.orEmpty()
    }

    fun fetch(item: IptvItem, calendar: Boolean = false, publish: (Details) -> Unit) {
        if (item.type !in listOf(ContentType.MOVIE, ContentType.SERIES) || BuildConfig.TMDB_API_KEY.isBlank()) return
        val type = if (item.type == ContentType.MOVIE) "movie" else "tv"
        fun api(path: String, query: String? = null, imageLanguage: String = "fr,en,null"): JsonObject {
            val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", BuildConfig.TMDB_API_KEY).addQueryParameter("language", "fr-FR")
            if (query != null) url.addQueryParameter("query", query)
            else {
                url.addQueryParameter("include_image_language", imageLanguage)
                if (!path.endsWith("/images")) url.addQueryParameter("append_to_response",
                    if (type == "tv") "images,aggregate_credits,credits" else "images,credits")
            }
            return IptvHttp.json(url.build(), emptyMap()).obj()
        }
        val id = item.tmdbId.takeIf { it > 0 } ?: run {
            val candidates = searchTitles(item.title).flatMap { query ->
                api("search/$type", query)["results"]?.array().orEmpty()
            }
            match(item, JsonArray(candidates))
        } ?: return
        val value = api("$type/$id")
        val images = value["images"]?.obj() ?: JsonObject(emptyMap())
        fun artworks(kind: String, first: String, size: String) =
            (listOf(value.string(first)) + images[kind]?.array().orEmpty().map { it.obj().string("file_path") })
                .map { image(it, size) }.filter(String::isNotBlank).distinct()
        val originalLanguage = value.string("original_language")
        val logo = item.titleLogo.ifBlank {
            val logoImages = if (originalLanguage.isNotBlank() && originalLanguage !in listOf("fr", "en")) {
                runCatching { api("$type/$id/images", imageLanguage = HomeMediaRules.titleLogoLanguages(originalLanguage)) }
                    .getOrNull() ?: images
            } else images
            MainActivity.folderTitleLogoUrl(org.json.JSONArray(logoImages["logos"]?.toString() ?: "[]"),
                originalLanguage, false).ifBlank { HomeMediaRules.programmeTitleLogoFallback(item.title, id, type) }
        }
        val peopleCredits = (if (type == "tv") value["aggregate_credits"]?.obj() else null)
            ?: value["credits"]?.obj() ?: JsonObject(emptyMap())
        var details = Details(id, type, logo, artworks("backdrops", "backdrop_path", "w1280"),
            artworks("posters", "poster_path", "w500"), creditPeople(type, peopleCredits, value["credits"]?.obj()),
            if (type == "tv") HomeMediaRules.seriesStatusLabel(value.string("status"), value["in_production"]?.jsonPrimitive?.booleanOrNull == true) else "",
            value.number("number_of_seasons"),
            genre = value["genres"]?.array().orEmpty().map { it.obj().string("name") }.filter(String::isNotBlank).joinToString(" \u2022 "),
            year = value.string(if (type == "movie") "release_date" else "first_air_date").take(4),
            runtime = value.number("runtime").takeIf { it > 0 }
                ?: value["episode_run_time"]?.array().orEmpty().firstOrNull()?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                ?: value["last_episode_to_air"]?.obj()?.number("runtime") ?: 0,
            description = value.string("overview"),
            director = value["credits"]?.obj()?.get("crew")?.array().orEmpty().map { it.obj() }
                .filter { it.string("job") == "Director" }.map { it.string("name") }.filter(String::isNotBlank).distinct().joinToString(", "))
        // Publish the title logo and portraits before slower season calendar requests.
        publish(details)
        if (type != "tv") return
        // Match Home: the badge follows the latest started season, not renewal status
        // or the episode currently selected by the user. Only this season is needed for the Hero.
        val broadcast = broadcastSeason(value)
        val broadcastEpisodes = broadcast?.let { season ->
            api("tv/$id/season/${season.number("season_number")}")["episodes"]?.array().orEmpty().map { it.obj() }
        }
        details = details.copy(broadcastStatusLoaded = true, broadcastSeason = broadcast?.let { season ->
            Season(season.number("season_number"), season.number("episode_count"),
                broadcastEpisodes.orEmpty().map { it.string("air_date") })
        })
        publish(details)
        if (!calendar) return
        val ongoing = HomeMediaRules.ongoingSeries(value.string("status"), value["in_production"]?.jsonPrimitive?.booleanOrNull == true)
        for (entry in value["seasons"]?.array().orEmpty()) {
            IptvHttp.checkActive()
            val season = entry.obj(); val number = season.number("season_number")
            if (number <= 0 || season.number("episode_count") <= 0) continue
            val calendar = if (number == broadcast?.number("season_number")) broadcastEpisodes.orEmpty()
                else api("tv/$id/season/$number")["episodes"]?.array().orEmpty().map { it.obj() }
            val episodes = calendar.map { episode -> Episode(number, episode.number("episode_number"), episode.string("name"),
                episode.string("air_date"), image(episode.string("still_path"), "w780"), episode.string("overview"), episode.number("runtime")) }
            details = details.copy(seasons = details.seasons + Season(number, season.number("episode_count"), episodes.map { it.airDate }),
                episodes = details.episodes + if (ongoing) episodes else emptyList())
            publish(details)
        }
    }

    internal fun playable(item: IptvItem, today: java.time.LocalDate = java.time.LocalDate.now()): Boolean =
        !item.scheduledEpisode && (item.type != ContentType.EPISODE || HomeMediaRules.episodeAirDate(item.airDate)?.isAfter(today) != true)

    internal fun mergeEpisodes(series: IptvItem, provider: List<IptvItem>, announced: List<Episode>,
        today: java.time.LocalDate = java.time.LocalDate.now()): List<IptvItem> {
        val real = provider.filter { !it.scheduledEpisode && it.sourceId == series.sourceId &&
            it.seriesId == series.seriesId.ifBlank { series.providerId } }.distinctBy { it.id }
        val latest = real.maxWithOrNull(compareBy<IptvItem>({ it.season }, { it.episode }))
        val dates = announced.associateBy { it.season to it.number }
        val result = real.map { it.copy(airDate = it.airDate.ifBlank { dates[it.season to it.episode]?.airDate.orEmpty() }) }.toMutableList()
        val available = real.mapTo(hashSetOf()) { it.season to it.episode }
        for (episode in announced.distinctBy { it.season to it.number }) {
            if (episode.season to episode.number in available || !HomeMediaRules.announcedSeriesEpisode(episode.season, episode.number,
                    episode.airDate, latest?.season ?: 0, latest?.episode ?: 0, today)) continue
            result += series.copy(id = "announced:${series.id}:${episode.season}:${episode.number}", providerId = "", type = ContentType.EPISODE,
                title = episode.title, streamUrl = "", command = "", season = episode.season, episode = episode.number,
                seriesId = series.seriesId.ifBlank { series.providerId }, artwork = episode.artwork.ifBlank { series.artwork },
                description = episode.description, duration = episode.runtime.takeIf { it > 0 }?.let { "$it min" }.orEmpty(),
                airDate = episode.airDate, scheduledEpisode = true)
        }
        return result.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}

interface IptvProvider {
    fun testConnection(): String
    fun categories(type: ContentType): List<IptvCategory>
    fun items(type: ContentType, category: String, page: Int = 0): IptvPage
    fun loadCatalogue(type: ContentType, category: String, onPage: (Int, IptvPage) -> Unit) {
        val seen = hashSetOf<String>()
        var page = 0
        while (true) {
            IptvHttp.checkActive()
            val batch = items(type, category, page)
            val fresh = batch.items.count { seen.add(it.id) }
            val next = batch.nextPage?.takeIf { it > page && fresh > 0 }
            onPage(page, batch.copy(nextPage = next))
            page = next ?: return
        }
    }
    fun search(query: String, type: ContentType, page: Int = 0): IptvPage {
        val found = mutableListOf<IptvItem>()
        var current: Int? = 0
        while (current != null) {
            IptvHttp.checkActive()
            val batch = items(type, "", current)
            found += batch.items.filter { it.title.contains(query, ignoreCase = true) }
            current = batch.nextPage
        }
        return IptvPage(found.drop(page * 60).take(60), if (found.size > (page + 1) * 60) page + 1 else null)
    }
    fun details(item: IptvItem): IptvItem = item
    fun episodes(item: IptvItem): List<IptvItem> = emptyList()
    fun resolve(item: IptvItem): IptvStream
    fun epg(item: IptvItem): List<EpgProgram> = emptyList()
    fun guide(item: IptvItem, start: Long, end: Long): List<EpgProgram> = epg(item).filter { it.end > start && it.start < end }
}

internal val iptvJson = Json { ignoreUnknownKeys = true }
internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String): Int = string(key).toIntOrNull() ?: 0
private fun JsonElement.array(): JsonArray = this as? JsonArray ?: (this as? JsonObject)?.get("data") as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun iptvCatalogueObjects(input: java.io.InputStream): Sequence<JsonObject> =
    iptvJson.decodeToSequence<JsonElement>(input).flatMap { value ->
        val data = (value as? JsonObject)?.get("data")
        if (data is JsonArray) data.asSequence() else sequenceOf(value)
    }.mapNotNull { it as? JsonObject }

/** Only controlled messages may be returned to the Companion; network exceptions can contain account URLs. */
internal open class IptvProviderException(message: String) : IllegalStateException(message)

internal class IptvHttpException(val code: Int) : IptvProviderException(when (code) {
    401, 403 -> "Accès refusé par le fournisseur (HTTP $code). Vérifie les autorisations d’accès."
    else -> "Le fournisseur a répondu avec une erreur HTTP $code."
})

internal fun artworkRetryable(failure: Throwable?): Boolean = when (failure) {
    is IptvHttpException -> failure.code in setOf(408, 429, 500, 502, 503, 504)
    is javax.net.ssl.SSLException, is java.net.ProtocolException -> false
    is java.io.IOException -> true
    else -> false
}

private class IptvPortalRedirectException : IptvProviderException(
    "Le portail IPTV redirige la TV vers un autre site. Vérifie l’adresse officielle du fournisseur et la résolution DNS du réseau de la TV."
)

internal fun companionImportFailureMessage(type: SourceType, failure: Exception): String = when {
    failure is IptvProviderException -> failure.message.orEmpty()
    failure is UnknownHostException -> "Le DNS ne parvient pas à résoudre l’adresse du fournisseur. Vérifie le DNS et la connexion réseau de la TV."
    type == SourceType.M3U -> "Impossible de récupérer la playlist M3U. Vérifie son URL et la connexion réseau de la TV."
    else -> "Connexion au fournisseur impossible. Vérifie son adresse et ses identifiants."
}

@Serializable
internal data class IptvDnsPreset(val id: String, val name: String, val primary: String, val secondary: String, val note: String = "")

internal val iptvDnsPresets = listOf(
    IptvDnsPreset("cloudflare", "Cloudflare", "1.1.1.1", "1.0.0.1"),
    IptvDnsPreset("google", "Google Public DNS", "8.8.8.8", "8.8.4.4"),
    IptvDnsPreset("quad9", "Quad9", "9.9.9.9", "149.112.112.112", "Protection contre les domaines malveillants."),
    IptvDnsPreset("opendns", "OpenDNS (Cisco)", "208.67.222.222", "208.67.220.220", "Protection et filtrage selon la configuration OpenDNS."),
    IptvDnsPreset("verisign", "Verisign Public DNS", "64.6.64.6", "64.6.65.6", "Ancien service Verisign, transféré à Neustar. Teste sa disponibilité depuis cette TV."),
    IptvDnsPreset("yandex", "Yandex DNS", "77.88.8.8", "77.88.8.1", "Mode Basic, sans filtre de contenu."),
    IptvDnsPreset("gcore", "G-Core DNS", "95.85.95.85", "2.56.220.2"),
    IptvDnsPreset("freenom", "Freenom World", "80.80.80.80", "80.80.81.81", "Adresses historiques Freenom World. Teste leur disponibilité depuis cette TV."),
    IptvDnsPreset("cleanbrowsing", "CleanBrowsing", "185.228.168.9", "185.228.169.9", "Filtre Security : protection contre les domaines malveillants, sans filtre parental."),
    IptvDnsPreset("adguard", "AdGuard DNS", "94.140.14.14", "94.140.15.15", "Filtre les publicités et les traceurs ; certains domaines peuvent être bloqués.")
)

@Serializable
data class IptvDnsConfig(val mode: String = "automatic", val primary: String = "", val secondary: String = "") {
    fun normalized(): IptvDnsConfig {
        val selected = mode.trim().lowercase(java.util.Locale.ROOT)
        if (selected == "automatic") return IptvDnsConfig()
        iptvDnsPresets.firstOrNull { it.id == selected }?.let { return IptvDnsConfig(it.id, it.primary, it.secondary) }
        require(selected == "custom") { "Choix de DNS invalide." }
        fun numeric(value: String): String {
            val address = runCatching { Address.getByAddress(value.trim()) }.getOrNull()
            require(address != null && !address.isAnyLocalAddress && !address.isMulticastAddress) { "Saisis une adresse IP DNS valide (IPv4 ou IPv6)." }
            return address.hostAddress.orEmpty()
        }
        return IptvDnsConfig(selected, numeric(primary), secondary.trim().takeIf { it.isNotEmpty() }?.let(::numeric).orEmpty())
    }
}

/** LAN names always use the TV's network resolver, including the Freebox's private hostname. */
internal fun iptvDnsUsesSystem(hostname: String): Boolean = hostname.lowercase(java.util.Locale.ROOT).trimEnd('.').let {
    !it.contains('.') || it.endsWith(".local") || it.endsWith(".lan") || it.endsWith(".home") ||
        it.endsWith(".internal") || it == "mafreebox.freebox.fr"
}

internal fun iptvDnsTestHostname(value: String): String {
    val input = value.trim()
    require(input.isNotEmpty() && input.length <= 2048) { "Indique le nom du serveur à tester." }
    val parsed = runCatching { (if (input.contains("://")) input else "http://$input").toHttpUrl() }.getOrNull()
    require(parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Indique uniquement un nom de serveur ou une adresse sans identifiants." }
    require(runCatching { Address.getByAddress(parsed.host) }.getOrNull() == null) { "Indique un nom de serveur : une adresse IP directe ne teste pas le DNS." }
    return parsed.host
}

@Serializable
internal data class IptvDnsTestResult(val host: String, val mode: String, val addresses: List<String>, val elapsedMs: Long, val systemForLocalHost: Boolean)

/** Per-TV app setting; no Android system DNS changes and no account URLs enter DNS queries. */
internal object IptvDns : Dns {
    private data class State(val config: IptvDnsConfig, val resolver: Dns)
    @Volatile private var state = State(IptvDnsConfig(), Dns.SYSTEM)
    private val pools = mutableListOf<WeakReference<ConnectionPool>>()
    private val playlistClients = java.util.WeakHashMap<OkHttpClient, LinkedHashMap<IptvDnsConfig, OkHttpClient>>()
    private val diagnostics = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "iptv-dns-test").apply { isDaemon = true } }

    fun config(): IptvDnsConfig = state.config

    @Synchronized fun configure(value: IptvDnsConfig) {
        val config = value.normalized()
        if (state.config == config) return
        state = State(config, resolver(config))
        // Drop idle connections so the next connection uses the new DNS; active playback continues.
        pools.removeAll { reference -> reference.get()?.let { it.evictAll(); false } ?: true }
    }

    @Synchronized fun register(client: OkHttpClient): OkHttpClient {
        pools.removeAll { it.get() == null }
        if (pools.none { it.get() === client.connectionPool }) pools += WeakReference(client.connectionPool)
        return client
    }

    /** A null override follows the TV default; explicit settings get isolated DNS and connections. */
    @Synchronized fun clientFor(base: OkHttpClient, configOverride: IptvDnsConfig?): OkHttpClient {
        val config = configOverride?.normalized() ?: return base
        val clients = playlistClients.getOrPut(base) { LinkedHashMap() }
        return clients[config] ?: run {
            if (clients.size >= 24) clients.remove(clients.keys.first())?.connectionPool?.evictAll()
            val selected = resolver(config)
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = lookupWith(selected, hostname)
                    .sortedBy { it !is java.net.Inet4Address }
            }
            register(base.newBuilder().dns(dns).connectionPool(ConnectionPool()).build()).also { clients[config] = it }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> = lookupWith(state.resolver, hostname)

    internal fun lookupWith(resolver: Dns, hostname: String): List<InetAddress> {
        if (resolver === Dns.SYSTEM || iptvDnsUsesSystem(hostname)) return Dns.SYSTEM.lookup(hostname)
        if (hostname.contains(':') || hostname.all { it.isDigit() || it == '.' })
            runCatching { Address.getByAddress(hostname) }.getOrNull()?.let { return listOf(it) }
        return resolver.lookup(hostname)
    }

    fun test(value: IptvDnsConfig, input: String): IptvDnsTestResult {
        val config = value.normalized()
        val hostname = iptvDnsTestHostname(input)
        val started = System.nanoTime()
        val task = diagnostics.submit<List<InetAddress>> { lookupWith(resolver(config), hostname) }
        try {
            val addresses = task.get(5500, TimeUnit.MILLISECONDS).mapNotNull { it.hostAddress }.distinct()
            if (addresses.isEmpty()) throw UnknownHostException()
            return IptvDnsTestResult(hostname, config.mode, addresses, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), iptvDnsUsesSystem(hostname))
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IptvProviderException("Test DNS annulé.")
        } catch (_: Exception) {
            throw IptvProviderException("Le DNS ne répond pas ou ne retourne aucune adresse. Essaie un autre DNS ou le mode Automatique.")
        } finally { task.cancel(true) }
    }

    private fun resolver(config: IptvDnsConfig): Dns {
        if (config.mode == "automatic") return Dns.SYSTEM
        val servers = listOf(config.primary, config.secondary).filter { it.isNotEmpty() }.distinct()
        val resolver = ExtendedResolver(servers.map { server ->
            SimpleResolver(server).apply { setTimeout(Duration.ofSeconds(2)) }
        }).apply { setTimeout(Duration.ofSeconds(4)); setRetries(1); setLoadBalance(false) }
        val cache = Cache().apply { setMaxEntries(256) }
        val session = LookupSession.builder().resolver(resolver).cache(cache).build()
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val name = Name.fromString(hostname.trimEnd('.') + ".")
                val requests = listOf(Type.A, Type.AAAA).map { type ->
                    session.lookupAsync(name, type).toCompletableFuture().handle { result, _ ->
                        result?.records.orEmpty().mapNotNull { record -> when (record) {
                            is ARecord -> record.address
                            is AAAARecord -> record.address
                            else -> null
                        } }
                    }
                }
                try {
                    CompletableFuture.allOf(*requests.toTypedArray()).get(4500, TimeUnit.MILLISECONDS)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw UnknownHostException("Résolution DNS annulée.")
                } catch (_: Exception) {
                    // A working address family remains usable when the other times out.
                } finally { requests.filter { !it.isDone }.forEach { it.cancel(true) } }
                val addresses = requests.filter { !it.isCancelled }.flatMap { it.getNow(emptyList()) }.distinct()
                if (addresses.isEmpty()) throw UnknownHostException("Le DNS sélectionné ne retourne aucune adresse.")
                return addresses
            }
        }
    }
}

internal object IptvHttp {
    // No interceptor: account URLs, headers and portal tokens must never enter logs.
    val client = OkHttpClient.Builder().dns(IptvDns).connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build().let(IptvDns::register)
    val searchClient = client.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    val artworkClient = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    val artworkRetryClient = artworkClient.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val portalRedirectGuard = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val redirect = response.header("Location")?.let { request.url.resolve(it) }
        if (response.isRedirect && redirect != null && redirect.host != request.url.host) {
            response.close()
            throw IptvPortalRedirectException()
        }
        response
    }
    private val portalClient = client.newBuilder().addNetworkInterceptor(portalRedirectGuard).build()
    private val currentScope = ThreadLocal<IptvRequestScope?>()

    fun checkActive() { currentScope.get()?.checkActive() }
    fun <T> withScope(scope: IptvRequestScope, block: () -> T): T {
        val previous = currentScope.get()
        currentScope.set(scope)
        try { scope.checkActive(); return block() } finally { currentScope.set(previous) }
    }

    fun <T> read(url: HttpUrl, headers: Map<String, String>, onResponse: (okhttp3.Response) -> Unit = {}, portal: Boolean = false, dns: IptvDnsConfig? = null, block: (okhttp3.ResponseBody) -> T): T {
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        val scope = currentScope.get()
        scope?.checkActive()
        val base = IptvDns.clientFor(scope?.client ?: client, dns)
        val requestClient = if (portal) {
            if (base === client) portalClient else base.newBuilder().addNetworkInterceptor(portalRedirectGuard).build()
        } else base
        val call = requestClient.newCall(request)
        scope?.attach(call)
        try { call.execute().use { response ->
            if (!response.isSuccessful) throw IptvHttpException(response.code)
            onResponse(response)
            return block(response.body ?: throw IptvProviderException("Le fournisseur a envoyé une réponse vide."))
        } } finally { scope?.detach(call) }
    }

    fun json(url: HttpUrl, headers: Map<String, String>, dns: IptvDnsConfig? = null): JsonElement = read(url, headers, dns = dns) {
        iptvJson.parseToJsonElement(it.string())
    }
}

/** Cancels only requests belonging to one search or one artwork screen. */
internal class IptvRequestScope(val client: OkHttpClient) {
    @Volatile private var cancelled = false
    private val calls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    fun checkActive() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Requête annulée") }
    fun attach(call: okhttp3.Call) { calls.add(call); if (cancelled) call.cancel() }
    fun detach(call: okhttp3.Call) { calls.remove(call) }
    fun cancel() { cancelled = true; calls.forEach { it.cancel() } }
}

internal fun searchPage(items: List<IptvItem>, query: String, page: Int): IptvPage {
    IptvHttp.checkActive()
    val matches = items.asSequence().filter { it.title.contains(query, ignoreCase = true) }.drop(page * 60).take(61).toList()
    return IptvPage(matches.take(60), if (matches.size > 60) page + 1 else null,
        if (query.isBlank()) items.size else null)
}

/** Publish the first page while a large JSON response is still being consumed. */
internal fun collectCataloguePages(items: Sequence<IptvItem>, onPage: (Int, IptvPage) -> Unit): List<IptvItem> {
    val all = mutableListOf<IptvItem>()
    val batch = ArrayList<IptvItem>(60)
    var page = 0
    items.forEach { item ->
        IptvHttp.checkActive()
        all.add(item); batch.add(item)
        if (batch.size == 60) {
            onPage(page, IptvPage(batch.toList(), page + 1))
            page++; batch.clear()
        }
    }
    IptvHttp.checkActive()
    onPage(page, IptvPage(batch.toList(), total = all.size))
    return all
}

internal fun artworkHeaders(url: String, source: IptvSource?, headers: Map<String, String>): Map<String, String> {
    val host = runCatching { url.toHttpUrl().host }.getOrNull()
    val sourceHost = source?.let { runCatching { it.url.toHttpUrl().host }.getOrNull() }
    return if (host != null && host == sourceHost) headers else headers.filterKeys { it.equals("User-Agent", true) }
}

fun providerFor(source: IptvSource, epgPastDays: Int = 1, epgRefreshHours: Int = 0, udpProxy: String = ""): IptvProvider {
    val native = when (source.type) {
        SourceType.M3U -> M3uProvider(source, epgPastDays.coerceIn(1, 14), epgRefreshHours.coerceIn(0, 168), udpProxy)
        SourceType.XTREAM -> XtreamProvider(source)
        SourceType.STALKER -> StalkerProvider(source)
    }
    val provider = if (source.type != SourceType.M3U && source.epgUrl.isNotBlank())
        XmltvProvider(source, native, epgPastDays.coerceIn(1, 14), epgRefreshHours.coerceIn(0, 168)) else native
    return if (source.includeLive && source.includeVod) provider else IncludedContentProvider(source, provider)
}

private class XmltvProvider(private val source: IptvSource, private val provider: IptvProvider,
    private val pastDays: Int, private val refreshHours: Int) : IptvProvider by provider {
    private var programs = emptyMap<String, List<EpgProgram>>()
    private var expires = 0L
    @Synchronized override fun epg(item: IptvItem): List<EpgProgram> {
        if (item.epgId.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        if (now >= expires) {
            val ids = mutableSetOf(item.epgId)
            var page: Int? = 0
            while (page != null) {
                val batch = provider.items(ContentType.LIVE, "", page)
                ids += batch.items.map { it.epgId }.filter(String::isNotBlank)
                page = batch.nextPage
            }
            programs = IptvHttp.read(source.epgUrl.toHttpUrl(), source.headers, dns = source.dns) {
                XmltvParser.parse(it.byteStream(), ids, now - pastDays * 86_400_000L, now + 7 * 86_400_000L)
            }
            expires = if (refreshHours == 0) Long.MAX_VALUE else now + TimeUnit.HOURS.toMillis(refreshHours.toLong())
        }
        return programs[item.epgId].orEmpty()
    }
    override fun guide(item: IptvItem, start: Long, end: Long): List<EpgProgram> = epg(item).filter { it.end > start && it.start < end }
}

internal class IncludedContentProvider(private val source: IptvSource, private val provider: IptvProvider) : IptvProvider by provider {
    override fun categories(type: ContentType): List<IptvCategory> = if (source.includes(type)) provider.categories(type) else emptyList()
    override fun items(type: ContentType, category: String, page: Int): IptvPage =
        if (source.includes(type)) provider.items(type, category, page) else IptvPage(emptyList())
    override fun loadCatalogue(type: ContentType, category: String, onPage: (Int, IptvPage) -> Unit) {
        if (source.includes(type)) provider.loadCatalogue(type, category, onPage)
        else onPage(0, IptvPage(emptyList()))
    }
    override fun search(query: String, type: ContentType, page: Int): IptvPage =
        if (source.includes(type)) provider.search(query, type, page) else IptvPage(emptyList())
    override fun episodes(item: IptvItem): List<IptvItem> = if (source.includeVod) provider.episodes(item) else emptyList()
}

internal fun proxiedStream(url: String, proxy: String): String {
    val uri = java.net.URI(url)
    if (uri.scheme?.lowercase() != "udp") return playlistStreamUrl(url)
    check(proxy.isNotBlank()) { "Configure le proxy UDP dans les paramètres IPTV pour lire cette chaîne." }
    require(!uri.host.isNullOrBlank() && uri.port in 1..65535) { "Adresse multicast invalide." }
    val base = httpUrl(if (proxy.contains("://")) proxy else "http://$proxy").toHttpUrl().newBuilder()
        .encodedPath("/").query(null).addPathSegment("udp").addPathSegment("${uri.host}:${uri.port}").build()
    return base.toString()
}

private class M3uProvider(private val source: IptvSource, private val epgPastDays: Int, private val epgRefreshHours: Int, private val udpProxy: String) : IptvProvider {
    private var playlist: List<IptvItem>? = null
    private var epgUrl = source.epgUrl
    private var guide = emptyMap<String, List<EpgProgram>>()
    private var guideExpires = 0L
    private fun catalogue(): List<IptvItem> = playlist ?: IptvHttp.read(source.url.toHttpUrl(), source.headers, dns = source.dns) {
        M3uParser.parse(it.charStream().buffered(), source) { url -> if (epgUrl.isBlank()) epgUrl = url }
    }.also {
        if (it.isEmpty()) throw IptvProviderException("La playlist ne contient aucun flux HTTP, RTSP ou UDP reconnu.")
        playlist = it
    }

    private fun matching(type: ContentType): List<IptvItem> = catalogue().filter {
        it.type == type || type == ContentType.SERIES && it.type == ContentType.EPISODE
    }
    override fun testConnection(): String = "${catalogue().size} contenus disponibles"
    override fun categories(type: ContentType): List<IptvCategory> = matching(type)
        .distinctBy { it.categoryId }.map { IptvCategory(it.categoryId, it.categoryName) }
    private fun entries(type: ContentType, category: String): List<IptvItem> {
        var values = matching(type).filter { category.isBlank() || it.categoryId == category }
        if (type == ContentType.SERIES) values = values.distinctBy { it.seriesId.ifBlank { it.title } }.map {
            it.copy(id = itemId(source.id, ContentType.SERIES, it.seriesId.ifBlank { it.title }), type = ContentType.SERIES,
                title = it.seriesId.ifBlank { it.title }, season = 0, episode = 0)
        }
        return values
    }
    override fun items(type: ContentType, category: String, page: Int): IptvPage = searchPage(entries(type, category), "", page)
    override fun search(query: String, type: ContentType, page: Int): IptvPage = searchPage(entries(type, ""), query, page)
    override fun episodes(item: IptvItem): List<IptvItem> = catalogue().filter {
        it.type == ContentType.EPISODE && it.seriesId == item.seriesId
    }.sortedWith(compareBy({ it.season }, { it.episode }))
    override fun resolve(item: IptvItem): IptvStream {
        val current = catalogue().firstOrNull { it.id == item.id }
            ?: error("Ce contenu n’est plus disponible dans la playlist. Actualise le catalogue.")
        return IptvStream(proxiedStream(current.streamUrl, udpProxy), current.headers, source.dns)
    }
    @Synchronized override fun epg(item: IptvItem): List<EpgProgram> {
        catalogue()
        if (epgUrl.isBlank() || item.epgId.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        if (now >= guideExpires) {
            guide = IptvHttp.read(epgUrl.toHttpUrl(), source.headers, dns = source.dns) {
                XmltvParser.parse(it.byteStream(), catalogue().map { entry -> entry.epgId }.toSet(), now - epgPastDays * 86_400_000L, now + 7 * 86_400_000L)
            }
            guideExpires = if (epgRefreshHours == 0) Long.MAX_VALUE else now + TimeUnit.HOURS.toMillis(epgRefreshHours.toLong())
        }
        return guide[item.epgId].orEmpty()
    }
}

private class XtreamProvider(private val source: IptvSource) : IptvProvider {
    private val base = source.url.toHttpUrl().newBuilder().encodedPath(source.url.toHttpUrl().encodedPath
        .removeSuffix("/").removeSuffix("/player_api.php") + "/").query(null).fragment(null).build()
    private fun apiUrl(action: String = "", parameters: Map<String, String> = emptyMap()): HttpUrl {
        val url = base.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", source.username).addQueryParameter("password", source.password)
        if (action.isNotEmpty()) url.addQueryParameter("action", action)
        parameters.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return url.build()
    }
    private fun api(action: String = "", parameters: Map<String, String> = emptyMap()): JsonElement =
        IptvHttp.json(apiUrl(action, parameters), source.headers, source.dns)
    private fun prefix(type: ContentType): String = when (type) {
        ContentType.LIVE -> "live"
        ContentType.MOVIE -> "vod"
        else -> "series"
    }
    private fun stream(id: String, type: ContentType, extension: String): String = base.newBuilder()
        .addPathSegment(when (type) { ContentType.LIVE -> "live"; ContentType.MOVIE -> "movie"; else -> "series" })
        .addPathSegment(source.username).addPathSegment(source.password)
        .addPathSegment("$id.${extension.ifBlank { if (type == ContentType.LIVE) "m3u8" else "mp4" }}").build().toString()

    override fun testConnection(): String {
        val info = api().obj()["user_info"].let { it?.obj() ?: JsonObject(emptyMap()) }
        check(info.number("auth") == 1 && info.string("status").equals("Active", true)) { "Compte refusé, expiré ou désactivé." }
        val expires = info.string("exp_date").toLongOrNull()?.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(it * 1000))
        }
        return "Compte actif${expires?.let { " · Expiration : $it" }.orEmpty()}${info.string("max_connections").takeIf(String::isNotBlank)?.let { " · $it connexion(s)" }.orEmpty()}"
    }
    override fun categories(type: ContentType): List<IptvCategory> = api("get_${prefix(type)}_categories").array().map {
        IptvCategory(it.obj().string("category_id"), it.obj().string("category_name"))
    }
    private val catalogues = mutableMapOf<String, List<IptvItem>>()
    private var searchQuery = ""
    private val searchMatches = mutableMapOf<ContentType, List<IptvItem>>()
    private fun readEntries(type: ContentType, category: String, query: String? = null,
        onPage: ((Int, IptvPage) -> Unit)? = null): List<IptvItem> =
        IptvHttp.read(apiUrl(if (type == ContentType.SERIES) "get_series" else "get_${prefix(type)}_streams",
            if (category.isBlank()) emptyMap() else mapOf("category_id" to category)), source.headers, dns = source.dns) { body ->
            val values = iptvCatalogueObjects(body.byteStream()).mapNotNull { data ->
                IptvHttp.checkActive()
                val id = data.string(if (type == ContentType.SERIES) "series_id" else "stream_id")
                val title = data.string("name").ifBlank { data.string("title") }
                if (id.isBlank() || query != null && !title.contains(query, ignoreCase = true)) null else from(data, type, id)
            }
            if (onPage == null) values.toList() else collectCataloguePages(values, onPage)
        }
    private fun entries(type: ContentType, category: String): List<IptvItem> {
        val key = "${type.name}:$category"
        val values = catalogues.getOrPut(key) { readEntries(type, category) }
        return values
    }
    override fun items(type: ContentType, category: String, page: Int): IptvPage = searchPage(entries(type, category), "", page)
    override fun loadCatalogue(type: ContentType, category: String, onPage: (Int, IptvPage) -> Unit) {
        val key = "${type.name}:$category"
        if (key in catalogues) super<IptvProvider>.loadCatalogue(type, category, onPage)
        else {
            val values = readEntries(type, category, onPage = onPage)
            IptvHttp.checkActive()
            catalogues[key] = values
        }
    }
    override fun search(query: String, type: ContentType, page: Int): IptvPage {
        if (!searchQuery.equals(query, ignoreCase = true)) {
            if (searchQuery.isNotBlank() && query.contains(searchQuery, ignoreCase = true)) {
                searchMatches.replaceAll { _, values -> values.filter { it.title.contains(query, ignoreCase = true) } }
            } else searchMatches.clear()
            searchQuery = query
        }
        val values = catalogues["${type.name}:"] ?: searchMatches.getOrPut(type) { readEntries(type, "", query) }
        return searchPage(values, query, page)
    }
    private fun from(data: JsonObject, type: ContentType, id: String): IptvItem {
        val info = data["info"]?.obj() ?: data
        val backdrop = (data["backdrop_path"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return IptvItem(itemId(source.id, type, id), source.id, id, type, data.string("name").ifBlank { data.string("title") },
            categoryId = data.string("category_id"), artwork = artworkUrl(source.url, data.string("stream_icon").ifBlank { data.string("cover") }.ifBlank { info.string("movie_image") }),
            backdrop = artworkUrl(source.url, backdrop), streamUrl = if (type == ContentType.SERIES) "" else stream(id, type, data.string("container_extension")),
            epgId = data.string("epg_channel_id"), description = info.string("plot"), year = data.string("year").ifBlank { info.string("releasedate").take(4) },
            genre = info.string("genre"), rating = data.string("rating"), duration = info.string("duration"), cast = info.string("cast"), director = info.string("director"), headers = source.headers,
            catchupDays = if (type == ContentType.LIVE && data.number("tv_archive") == 1) data.number("tv_archive_duration").coerceAtLeast(0) else 0,
            tmdbId = info.number("tmdb_id").takeIf { it > 0 } ?: data.number("tmdb_id"),
            titleLogo = artworkUrl(source.url, info.string("title_logo").ifBlank { data.string("title_logo") }),
            airDate = info.string("air_date").ifBlank { info.string("releasedate") },
            addedAt = iptvAddedAt(data.string("added")).takeIf { it > 0 } ?: iptvAddedAt(data.string("last_modified")))
    }
    override fun details(item: IptvItem): IptvItem {
        if (item.type !in listOf(ContentType.MOVIE, ContentType.SERIES)) return item
        val response = if (item.type == ContentType.SERIES) api("get_series_info", mapOf("series_id" to item.providerId))
            else api("get_vod_info", mapOf("vod_id" to item.providerId))
        val info = response.obj()["info"]?.obj() ?: return item
        val backdrop = (info["backdrop_path"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return item.copy(description = info.string("plot").ifBlank { item.description }, genre = info.string("genre").ifBlank { item.genre },
            duration = info.string("duration").ifBlank { item.duration }, cast = info.string("cast").ifBlank { item.cast }, director = info.string("director").ifBlank { item.director },
            artwork = artworkUrl(source.url, info.string("movie_image")).ifBlank { item.artwork },
            backdrop = artworkUrl(source.url, backdrop).ifBlank { item.backdrop },
            year = info.string("releaseDate").ifBlank { info.string("releasedate") }.take(4).ifBlank { item.year },
            tmdbId = info.number("tmdb_id").takeIf { it > 0 } ?: info.number("tmdb").takeIf { it > 0 } ?: item.tmdbId,
            titleLogo = artworkUrl(source.url, info.string("title_logo")).ifBlank { item.titleLogo })
    }
    override fun episodes(item: IptvItem): List<IptvItem> {
        val data = api("get_series_info", mapOf("series_id" to item.providerId)).obj()
        val seasons = data["episodes"]?.obj() ?: return emptyList()
        return seasons.flatMap { (season, episodes) -> episodes.array().mapNotNull {
            val entry = it.obj()
            val id = entry.string("id")
            if (id.isBlank()) null else from(entry, ContentType.EPISODE, id).copy(
                title = entry.string("title").ifBlank { "Épisode ${entry.string("episode_num")}" },
                season = entry.number("season").takeIf { it > 0 } ?: season.toIntOrNull() ?: 1,
                episode = entry.number("episode_num"), seriesId = item.providerId,
                artwork = artworkUrl(source.url, entry["info"]?.obj()?.string("movie_image").orEmpty()).ifBlank { item.artwork })
        } }.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    override fun resolve(item: IptvItem): IptvStream {
        // A saved favorite may have been created before this account was edited.
        val extension = runCatching { item.streamUrl.toHttpUrl().pathSegments.last().substringAfterLast('.', "") }.getOrDefault("")
        return IptvStream(stream(item.providerId, item.type, extension), source.headers, source.dns)
    }
    override fun epg(item: IptvItem): List<EpgProgram> = ProviderEpgParser.parse(
        api("get_short_epg", mapOf("stream_id" to item.providerId, "limit" to "12")).obj()["epg_listings"]?.array() ?: JsonArray(emptyList()), true)

    override fun guide(item: IptvItem, start: Long, end: Long): List<EpgProgram> = ProviderEpgParser.parse(
        api("get_simple_data_table", mapOf("stream_id" to item.providerId)).obj()["epg_listings"]?.array() ?: JsonArray(emptyList()), true)
        .filter { it.end > start && it.start < end }
}

private class StalkerProvider(private val source: IptvSource) : IptvProvider {
    private var endpoint = source.url.toHttpUrl()
    private var token = ""
    private var expiresAt = 0L
    private val sessionCookies = mutableListOf<Cookie>()
    private fun headers(auth: Boolean = true, profile: Boolean = false): Map<String, String> = source.headers + mapOf(
        "User-Agent" to (source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 2 rev: 250 Safari/533.3"),
        "X-User-Agent" to "Model: MAG254; Link: WiFi",
        "Referer" to source.url,
        "Cookie" to (listOf("mac=${if (endpoint.encodedPath.contains("stalker_portal")) java.net.URLEncoder.encode(source.mac, "UTF-8") else source.mac}",
            "stb_lang=fr", "timezone=${java.net.URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8")}") +
            sessionCookies.filter { it.matches(endpoint) && it.expiresAt > System.currentTimeMillis() && it.name !in setOf("mac", "stb_lang", "timezone", "token") }
                .map { "${it.name}=${it.value}" } +
            if (auth && token.isNotBlank() && !(profile && endpoint.encodedPath.contains("stalker_portal"))) listOf("token=$token") else emptyList()).joinToString("; "),
    ) + if (auth && token.isNotBlank()) mapOf("Authorization" to "Bearer $token") else emptyMap()

    private fun raw(type: String, action: String, parameters: Map<String, String> = emptyMap(), auth: Boolean = true): JsonElement {
        val url = endpoint.newBuilder().query(null).addQueryParameter("type", type).addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml")
        parameters.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val target = url.build()
        val response = IptvHttp.read(target, headers(auth, action == "get_profile"), { response ->
            Cookie.parseAll(target, response.headers).forEach { cookie ->
                sessionCookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                sessionCookies += cookie
            }
        }, portal = true, dns = source.dns) { body ->
            val payload = body.string()
            if (payload.isBlank()) throw IptvProviderException("Le portail IPTV a envoyé une réponse vide.")
            if (payload.trimStart().startsWith("<")) throw IptvProviderException(
                "Le portail a renvoyé une page web au lieu de données IPTV. Vérifie son adresse et le réseau/DNS de la TV."
            )
            try { iptvJson.parseToJsonElement(payload).obj() } catch (_: kotlinx.serialization.SerializationException) {
                throw IptvProviderException("Le portail IPTV a envoyé une réponse JSON invalide.")
            }
        }
        val value = response["js"] ?: throw IptvProviderException("Le portail a envoyé une réponse incompatible.")
        if (value is JsonNull || value.obj().string("error").isNotBlank() || value.obj().number("not_valid_token") == 1)
            throw IptvProviderException("Le portail a refusé cette requête ou la session a expiré.")
        return value
    }
    private fun session() {
        if (token.isNotBlank() && System.currentTimeMillis() < expiresAt) return
        val original = source.url.toHttpUrl()
        val path = original.encodedPath.trimEnd('/')
        val endpoints = if (path.endsWith(".php")) listOf(original) else {
            val root = path.removeSuffix("/c")
            listOf(original.newBuilder().encodedPath("$root/portal.php").query(null).build(),
                original.newBuilder().encodedPath("$root/server/load.php").query(null).build(),
                original.newBuilder().encodedPath("$root/stalker_portal/server/load.php").query(null).build()).distinct()
        }
        var last: Exception? = null
        for (candidate in endpoints) {
            try {
                endpoint = candidate
                token = raw("stb", "handshake", mapOf("token" to ""), false).obj().string("token")
                if (token.isBlank()) throw IptvProviderException("Le portail a refusé la session. Vérifie la MAC autorisée.")
                val profile = raw("stb", "get_profile", mapOf("hd" to "1", "stb_type" to "MAG254") +
                    if (source.deviceId.isNotBlank()) mapOf("device_id" to source.deviceId, "device_id2" to source.deviceId) else emptyMap()).obj()
                if (profile.number("blocked") == 1) throw IptvProviderException("Le portail a désactivé ce terminal.")
                profile.string("token").takeIf(String::isNotBlank)?.let { token = it }
                expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)
                return
            } catch (failure: Exception) {
                token = ""
                if (failure is IptvPortalRedirectException) throw failure
                last = failure
            }
        }
        throw last ?: IllegalStateException("Impossible d’ouvrir la session du portail.")
    }
    private fun api(type: String, action: String, parameters: Map<String, String> = emptyMap()): JsonElement {
        session()
        return try { raw(type, action, parameters) } catch (failure: IllegalStateException) {
            if (failure is IptvPortalRedirectException) throw failure
            // One renewed session only; never retry authentication indefinitely.
            token = ""
            session()
            raw(type, action, parameters)
        }
    }
    private fun kind(type: ContentType): String = when (type) { ContentType.LIVE -> "itv"; ContentType.MOVIE -> "vod"; else -> "series" }
    override fun testConnection(): String { session(); return "Session du portail ouverte" }
    override fun categories(type: ContentType): List<IptvCategory> = api(kind(type), if (type == ContentType.LIVE) "get_genres" else "get_categories")
        .array().map { IptvCategory(it.obj().string("id"), it.obj().string("title")) }

    private var liveChannels: List<IptvItem>? = null
    private var liveOrderedList = false
    private fun from(data: JsonObject, type: ContentType): IptvItem {
        val id = data.string("id")
        return IptvItem(itemId(source.id, type, id), source.id, id, type, data.string("name"),
            categoryId = data.string("tv_genre_id").ifBlank { data.string("category_id") },
            artwork = artworkUrl(source.url, data.string("logo").ifBlank { data.string("screenshot_uri") }),
            backdrop = artworkUrl(source.url, data.string("screenshot_uri")),
            command = data.string("cmd"), epgId = data.string("xmltv_id"), description = data.string("description"),
            year = data.string("year"), genre = data.string("genres_str"), rating = data.string("rating_imdb"),
            duration = data.string("time"), cast = data.string("actors"), director = data.string("director"), headers = headers(),
            catchupDays = if (type == ContentType.LIVE) data.number("tv_archive_duration").coerceAtLeast(0) else 0,
            tmdbId = data.number("tmdb_id"), titleLogo = artworkUrl(source.url, data.string("title_logo")),
            airDate = data.string("air_date"), addedAt = iptvAddedAt(data.string("added")))
    }
    private fun liveItems(category: String, page: Int, query: String? = null): IptvPage {
        if (!liveOrderedList) {
            val all = liveChannels ?: api("itv", "get_all_channels").array().map { from(it.obj(), ContentType.LIVE) }.also {
                if (it.isEmpty()) liveOrderedList = true else liveChannels = it
            }
            if (all.isNotEmpty()) {
                val values = all.filter { category.isBlank() || category == "*" || it.categoryId == category }
                return if (query != null) searchPage(values, query, page) else
                    IptvPage(values.drop(page * 60).take(60), if (values.size > (page + 1) * 60) page + 1 else null, values.size)
            }
        }
        // Some portals expose their live catalogue only through the paginated TV list.
        val data = api("itv", "get_ordered_list", mapOf("genre" to category.ifBlank { "*" }, "p" to (page + 1).toString()) +
            if (query != null) mapOf("search" to query) else emptyMap()).obj()
        val values = data["data"]?.array()?.map { from(it.obj(), ContentType.LIVE) }.orEmpty()
        val size = data.number("max_page_items").takeIf { it > 0 } ?: values.size
        return IptvPage(values, if (values.isNotEmpty() && size > 0 && (page + 1).toLong() * size < data.number("total_items")) page + 1 else null,
            data.string("total_items").toIntOrNull()?.takeIf { it >= 0 })
    }
    private fun vodListParameters(parameters: Map<String, String>): Map<String, String> =
        // Explicit zero IDs select the catalogue root; child requests replace only their own IDs.
        mapOf("movie_id" to "0", "season_id" to "0", "episode_id" to "0") + parameters

    override fun items(type: ContentType, category: String, page: Int): IptvPage {
        if (type == ContentType.LIVE) return liveItems(category, page)
        val data = api(kind(type), "get_ordered_list", vodListParameters(mapOf("category" to category.ifBlank { "*" }, "p" to (page + 1).toString()))).obj()
        val values = data["data"]?.array()?.map { from(it.obj(), type) }.orEmpty()
        val pageSize = data.number("max_page_items").takeIf { it > 0 } ?: values.size
        val total = data.number("total_items")
        return IptvPage(values, if (values.isNotEmpty() && pageSize > 0 && (page + 1) * pageSize < total) page + 1 else null,
            data.string("total_items").toIntOrNull()?.takeIf { it >= 0 })
    }
    private fun ordered(parameters: Map<String, String>): List<JsonElement> {
        val values = LinkedHashMap<String, JsonElement>()
        var page = 1
        while (true) {
            val data = api("series", "get_ordered_list", vodListParameters(parameters) + ("p" to page.toString()))
            val batch = data.array()
            val fresh = batch.filter { it.obj().string("id").isNotBlank() && it.obj().string("id") !in values }
            fresh.forEach { values[it.obj().string("id")] = it }
            if (fresh.isEmpty() || values.size >= data.obj().number("total_items")) break
            page++
        }
        return values.values.toList()
    }
    override fun episodes(item: IptvItem): List<IptvItem> {
        val seasons = ordered(mapOf("movie_id" to item.providerId))
        return seasons.flatMapIndexed { index, value ->
            val season = value.obj()
            val seasonNumber = season.number("season_number").takeIf { it > 0 }
                ?: Regex("(?i)(?:saison|season)\\s*(\\d+)").find(season.string("name"))?.groupValues?.get(1)?.toIntOrNull() ?: index + 1
            val numbers = season["series"] as? JsonArray
            val embedded = numbers?.mapNotNull { number ->
                val episode = (number as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                item.copy(id = itemId(source.id, ContentType.EPISODE, "${season.string("id")}:$episode"),
                    providerId = season.string("id"), type = ContentType.EPISODE, title = "${item.title} · S$seasonNumber E$episode",
                    season = seasonNumber, episode = episode, seriesId = item.providerId,
                    command = season.string("cmd").ifBlank { "/media/file_${season.string("id")}.mpg" })
            }.orEmpty()
            if (embedded.isNotEmpty()) embedded else ordered(mapOf("movie_id" to item.providerId,
                "season_id" to season.string("id"), "episode_id" to "0")).mapIndexed { episodeIndex, entry ->
                val data = entry.obj()
                val number = data.number("series_number").takeIf { it > 0 } ?: data.number("episode_num").takeIf { it > 0 } ?: episodeIndex + 1
                from(data, ContentType.EPISODE).copy(season = seasonNumber, episode = number, seriesId = item.providerId,
                    title = data.string("name").ifBlank { "${item.title} · S$seasonNumber E$number" },
                    artwork = artworkUrl(source.url, data.string("screenshot_uri")).ifBlank { item.artwork },
                    command = data.string("cmd").ifBlank { "/media/file_${data.string("id")}.mpg" })
            }
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    override fun search(query: String, type: ContentType, page: Int): IptvPage {
        if (type == ContentType.LIVE) return liveItems("", page, query)
        val data = api(kind(type), "get_ordered_list", vodListParameters(mapOf("category" to "*", "search" to query,
            "p" to (page + 1).toString()))).obj()
        val values = data["data"]?.array()?.map { from(it.obj(), type) }.orEmpty()
        val size = data.number("max_page_items").takeIf { it > 0 } ?: values.size
        return IptvPage(values, if (size > 0 && (page + 1) * size < data.number("total_items")) page + 1 else null)
    }
    private val guides = mutableMapOf<String, Pair<Long, List<EpgProgram>>>()
    override fun epg(item: IptvItem): List<EpgProgram> {
        val now = System.currentTimeMillis()
        guides[item.providerId]?.takeIf { now < it.first }?.let { return it.second }
        val programs = ProviderEpgParser.parse(api("itv", "get_short_epg", mapOf("ch_id" to item.providerId, "size" to "12")).array(), localDates = true)
        guides[item.providerId] = now + TimeUnit.MINUTES.toMillis(5) to programs
        return programs
    }
    private val dailyGuides = mutableMapOf<String, Pair<Long, List<EpgProgram>>>()
    override fun guide(item: IptvItem, start: Long, end: Long): List<EpgProgram> {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(start))
        val key = "${item.providerId}:$day"
        val now = System.currentTimeMillis()
        dailyGuides[key]?.takeIf { now < it.first }?.let { return it.second }
        val values = mutableListOf<EpgProgram>()
        var page = 1
        while (true) {
            val data = api("epg", "get_simple_data_table", mapOf("ch_id" to item.providerId, "date" to day, "p" to page.toString())).obj()
            val batch = ProviderEpgParser.parse(data["data"]?.array() ?: JsonArray(emptyList()), localDates = true)
            val fresh = batch.filter { program -> values.none { it.start == program.start && it.end == program.end } }
            values += fresh
            if (fresh.isEmpty() || values.size >= data.number("total_items")) break
            page++
        }
        val result = values.filter { it.end > start && it.start < end }.sortedBy { it.start }
        dailyGuides[key] = now + TimeUnit.MINUTES.toMillis(15) to result
        return result
    }
    override fun resolve(item: IptvItem): IptvStream {
        val data = api(if (item.type == ContentType.LIVE) "itv" else "vod", "create_link",
            mapOf("cmd" to item.command, "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0") +
                if (item.type == ContentType.EPISODE) mapOf("series" to item.episode.toString()) else emptyMap()).obj()
        val command = data.string("cmd")
        val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(command)?.value
            ?: error("Le portail n’a pas fourni d’adresse de lecture HTTP.")
        val stream = httpUrl(url)
        val streamHeaders = if (stream.toHttpUrl().host == endpoint.host) headers() else
            source.headers + headers(false).filterKeys { it == "User-Agent" || it == "Referer" }
        return IptvStream(stream, streamHeaders, source.dns)
    }
}
