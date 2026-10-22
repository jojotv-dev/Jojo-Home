package com.nuvio.tv.core.tmdb

import android.util.Log
import com.jojotv.home.BuildConfig
import com.jojotv.home.HomeMediaRules
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.MetaCastMember
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbService"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY

@Singleton
class TmdbService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val imdbToTmdbCache = ConcurrentHashMap<String, Int>()
    private val tmdbToImdbCache = ConcurrentHashMap<Int, String>()
    private val imdbToTmdbInFlight = ConcurrentHashMap<String, CompletableDeferred<Int?>>()
    private val tmdbToImdbInFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val cacheMutex = Mutex()
    private data class CachedTitleMetadata(val value: FreeboxVideoMeta, val expiresAtMs: Long)
    private val titleMetadataCache = ConcurrentHashMap<String, CachedTitleMetadata>()
    private val titleMetadataInFlight = ConcurrentHashMap<String, CompletableDeferred<FreeboxVideoMeta?>>()
    private data class CachedArtwork(val value: List<TmdbPosterCandidate>, val expiresAtMs: Long)
    private val artworkCache = ConcurrentHashMap<String, CachedArtwork>()

    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? = withContext(Dispatchers.IO) {
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "Invalid IMDB ID format: $imdbId")
            return@withContext null
        }
        imdbToTmdbCache[imdbId]?.let { return@withContext it }

        val normalizedType = normalizeMediaType(mediaType)
        val requestKey = "$imdbId:$normalizedType"
        val requestDeferred = CompletableDeferred<Int?>()
        imdbToTmdbInFlight.putIfAbsent(requestKey, requestDeferred)?.let { existing ->
            return@withContext existing.await()
        }

        try {
            val response = tmdbApi.findByExternalId(
                externalId = imdbId,
                apiKey = TMDB_API_KEY,
                externalSource = "imdb_id"
            )
            if (!response.isSuccessful) {
                Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
                requestDeferred.complete(null)
                return@withContext null
            }
            val body = response.body()
            if (body == null) {
                requestDeferred.complete(null)
                return@withContext null
            }
            val result = when (normalizedType) {
                "movie" -> body.movieResults?.firstOrNull()
                "tv", "series" -> body.tvResults?.firstOrNull()
                else -> body.movieResults?.firstOrNull() ?: body.tvResults?.firstOrNull()
            }
            result?.let { found ->
                cacheMutex.withLock {
                    imdbToTmdbCache[imdbId] = found.id
                    tmdbToImdbCache[found.id] = imdbId
                }
                requestDeferred.complete(found.id)
                return@withContext found.id
            }
            requestDeferred.complete(null)
            null
        } catch (e: CancellationException) {
            requestDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up TMDB ID for $imdbId: ${e.message}", e)
            requestDeferred.complete(null)
            null
        } finally {
            imdbToTmdbInFlight.remove(requestKey, requestDeferred)
        }
    }

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? = withContext(Dispatchers.IO) {
        tmdbToImdbCache[tmdbId]?.let { return@withContext it }

        val normalizedType = normalizeMediaType(mediaType)
        val requestKey = "$tmdbId:$normalizedType"
        val requestDeferred = CompletableDeferred<String?>()
        tmdbToImdbInFlight.putIfAbsent(requestKey, requestDeferred)?.let { existing ->
            return@withContext existing.await()
        }

        try {
            val response = when (normalizedType) {
                "movie" -> tmdbApi.getMovieExternalIds(tmdbId, TMDB_API_KEY)
                "tv", "series" -> tmdbApi.getTvExternalIds(tmdbId, TMDB_API_KEY)
                else -> tmdbApi.getMovieExternalIds(tmdbId, TMDB_API_KEY)
            }
            if (!response.isSuccessful) {
                Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
                requestDeferred.complete(null)
                return@withContext null
            }
            val body = response.body()
            if (body == null) {
                requestDeferred.complete(null)
                return@withContext null
            }
            body.imdbId?.let { imdbId ->
                cacheMutex.withLock {
                    tmdbToImdbCache[tmdbId] = imdbId
                    imdbToTmdbCache[imdbId] = tmdbId
                }
                requestDeferred.complete(imdbId)
                return@withContext imdbId
            }
            requestDeferred.complete(null)
            null
        } catch (e: CancellationException) {
            requestDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up IMDB ID for $tmdbId: ${e.message}", e)
            requestDeferred.complete(null)
            null
        } finally {
            tmdbToImdbInFlight.remove(requestKey, requestDeferred)
        }
    }

    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        val cleanId = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")
        val idPart = cleanId.substringBefore(':').substringBefore('/').trim()
        if (idPart.startsWith("tt")) {
            return imdbToTmdb(idPart, normalizeMediaType(mediaType))?.toString()
        }
        if (idPart.all { it.isDigit() }) return idPart
        Log.w(TAG, "Unknown video ID format: $videoId")
        return null
    }

    private fun normalizeMediaType(mediaType: String): String = when (mediaType.lowercase()) {
        "series", "tv", "show", "tvshow" -> "tv"
        "movie", "film" -> "movie"
        else -> mediaType.lowercase()
    }

    fun clearCache() {
        imdbToTmdbCache.clear()
        tmdbToImdbCache.clear()
        imdbToTmdbInFlight.clear()
        tmdbToImdbInFlight.clear()
        Log.d(TAG, "Cache cleared")
    }

    fun preCacheMapping(imdbId: String, tmdbId: Int) {
        imdbToTmdbCache[imdbId] = tmdbId
        tmdbToImdbCache[tmdbId] = imdbId
    }

    fun cachedTmdbId(imdbId: String): Int? = imdbToTmdbCache[imdbId]

    fun apiKey(): String = TMDB_API_KEY

    suspend fun fetchImdbImages(imdbId: String, mediaType: String): TmdbImages? =
        withContext(Dispatchers.IO) {
            if (!imdbId.startsWith("tt")) return@withContext null
            val tmdbId = imdbToTmdb(imdbId, mediaType) ?: return@withContext null
            runCatching {
                val response = if (normalizeMediaType(mediaType) == "movie") {
                    tmdbApi.getMovieDetails(tmdbId, TMDB_API_KEY)
                } else {
                    tmdbApi.getTvDetails(tmdbId, TMDB_API_KEY)
                }
                val body = response.body() ?: return@runCatching null
                TmdbImages(
                    backdropUrl = body.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    posterUrl = body.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    runtimeMinutes = body.runtime
                )
            }.getOrNull()
        }

    suspend fun fetchImagesForTitleQuery(
        query: String,
        mediaTypeHint: String = "unknown",
        durationMinutes: Int? = null
    ): TmdbImages? {
        val metadata = fetchMetadataForTitleQuery(query, mediaTypeHint, durationMinutes) ?: return null
        return TmdbImages(metadata.backdropUrl, metadata.posterUrl, metadata.runtimeMinutes)
    }

    suspend fun fetchPosterCandidatesForTitleQuery(
        query: String,
        mediaTypeHint: String = "unknown",
        artworkType: TmdbArtworkType? = null,
        durationMinutes: Int? = null,
        yearHint: Int? = null
    ): List<TmdbPosterCandidate> = withContext(Dispatchers.IO) {
        val lookup = parseTmdbLookupInput(query, mediaTypeHint, durationMinutes, yearHint)
        val cleanQuery = lookup.title
        val queryYear = lookup.year
        if (cleanQuery.length < 2 || TMDB_API_KEY.isBlank()) return@withContext emptyList()
        val preferredType = lookup.mediaType
        val cacheKey = "${tmdbLooseTitleKey(cleanQuery)}:$queryYear:${lookup.durationMinutes}:$preferredType:$artworkType"
        val now = System.currentTimeMillis()
        artworkCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return@withContext cached.value
            artworkCache.remove(cacheKey, cached)
        }

        data class Candidate(
            val id: Int,
            val mediaType: String,
            val title: String,
            val originalTitle: String?,
            val posterPath: String?,
            val backdropPath: String?,
            val releaseDate: String?,
            val overview: String?,
            val score: Int
        )

        fun List<Candidate>.ranked(): List<Candidate> = distinctBy { "${it.mediaType}:${it.id}" }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.mediaType == preferredType }
                    .thenByDescending { it.releaseDate }
            )

        suspend fun searchCandidates(language: String): List<Candidate> = try {
            buildList {
                (1..2).forEach { page ->
                    if (preferredType == "tv") {
                        tmdbApi.searchTv(TMDB_API_KEY, cleanQuery, language, page, false)
                            .body()?.results.orEmpty().mapNotNull { result ->
                                val title = result.name ?: result.originalName ?: return@mapNotNull null
                                if (result.posterPath.isNullOrBlank() && result.backdropPath.isNullOrBlank()) return@mapNotNull null
                                val resultYear = result.firstAirDate?.take(4)?.toIntOrNull()
                                if (queryYear != null && (resultYear == null || kotlin.math.abs(resultYear - queryYear) > 1)) return@mapNotNull null
                                val score = maxOf(tmdbFuzzyTitleScore(cleanQuery, result.name), tmdbFuzzyTitleScore(cleanQuery, result.originalName))
                                if (score < 78) return@mapNotNull null
                                Candidate(result.id, "tv", title, result.originalName, result.posterPath,
                                    result.backdropPath, result.firstAirDate, result.overview, score)
                            }.forEach(::add)
                    } else {
                        tmdbApi.searchMovies(TMDB_API_KEY, cleanQuery, language, page, false)
                            .body()?.results.orEmpty().mapNotNull { result ->
                                val title = result.title ?: result.originalTitle ?: return@mapNotNull null
                                if (result.posterPath.isNullOrBlank() && result.backdropPath.isNullOrBlank()) return@mapNotNull null
                                val resultYear = result.releaseDate?.take(4)?.toIntOrNull()
                                if (queryYear != null && (resultYear == null || kotlin.math.abs(resultYear - queryYear) > 1)) return@mapNotNull null
                                val score = maxOf(tmdbFuzzyTitleScore(cleanQuery, result.title), tmdbFuzzyTitleScore(cleanQuery, result.originalTitle))
                                if (score < 78) return@mapNotNull null
                                Candidate(result.id, "movie", title, result.originalTitle, result.posterPath,
                                    result.backdropPath, result.releaseDate, result.overview, score)
                            }.forEach(::add)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "TMDB poster candidate search failed for $cleanQuery", error)
            emptyList()
        }

        val rankedCandidates = (searchCandidates("fr-FR").ifEmpty { searchCandidates("en-US") }).ranked()
        if (rankedCandidates.size > 1 && rankedCandidates[0].id != rankedCandidates[1].id &&
            rankedCandidates[0].score - rankedCandidates[1].score <= 5
        ) return@withContext emptyList()
        val candidates = rankedCandidates.take(1)
        if (candidates.isEmpty()) return@withContext emptyList()

        val artwork = buildList {
            candidates.forEach { candidate ->
                val primaryArtwork = listOfNotNull(
                    candidate.posterPath?.let { path ->
                        TmdbPosterCandidate(
                            url = "https://image.tmdb.org/t/p/w500$path",
                            language = null,
                            title = candidate.title,
                            releaseDate = candidate.releaseDate,
                            overview = candidate.overview,
                            mediaType = candidate.mediaType,
                            tmdbId = candidate.id,
                            originalTitle = candidate.originalTitle,
                            artworkType = TmdbArtworkType.PORTRAIT
                        )
                    },
                    candidate.backdropPath?.let { path ->
                        TmdbPosterCandidate(
                            url = "https://image.tmdb.org/t/p/w1280$path",
                            language = null,
                            title = candidate.title,
                            releaseDate = candidate.releaseDate,
                            overview = candidate.overview,
                            mediaType = candidate.mediaType,
                            tmdbId = candidate.id,
                            originalTitle = candidate.originalTitle,
                            artworkType = TmdbArtworkType.LANDSCAPE
                        )
                    }
                )
                addAll(primaryArtwork)

                val details = runCatching {
                    if (candidate.mediaType == "tv") {
                        tmdbApi.getTvDetails(candidate.id, TMDB_API_KEY, "fr-FR", appendToResponse = "images", includeImageLanguage = "fr,en,null").body()
                    } else {
                        tmdbApi.getMovieDetails(candidate.id, TMDB_API_KEY, "fr-FR", appendToResponse = "images", includeImageLanguage = "fr,en,null").body()
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "TMDB candidate details failed for ${candidate.mediaType}:${candidate.id}", error)
                }.getOrNull()
                val tmdbRuntime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
                if (lookup.durationMinutes != null && tmdbRuntime != null &&
                    kotlin.math.abs(lookup.durationMinutes - tmdbRuntime) > 8
                ) return@withContext emptyList()
                // The details request is localized for metadata; omit the image
                // language filter here so the chooser can expose the full image set.
                val images = runCatching {
                    if (candidate.mediaType == "tv") {
                        tmdbApi.getTvImages(candidate.id, TMDB_API_KEY, includeImageLanguage = null).body()
                    } else {
                        tmdbApi.getMovieImages(candidate.id, TMDB_API_KEY, includeImageLanguage = null).body()
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "TMDB image catalogue failed for ${candidate.mediaType}:${candidate.id}", error)
                }.getOrNull() ?: details?.images

                images?.posters.orEmpty().forEach { image ->
                    image.filePath?.takeIf(String::isNotBlank)?.let { path ->
                        add(TmdbPosterCandidate(
                            url = "https://image.tmdb.org/t/p/w500$path",
                            language = image.iso6391,
                            title = candidate.title,
                            releaseDate = candidate.releaseDate,
                            overview = candidate.overview,
                            mediaType = candidate.mediaType,
                            tmdbId = candidate.id,
                            originalTitle = candidate.originalTitle,
                            artworkType = TmdbArtworkType.PORTRAIT
                        ))
                    }
                }
                images?.backdrops.orEmpty().forEach { image ->
                    image.filePath?.takeIf(String::isNotBlank)?.let { path ->
                        add(TmdbPosterCandidate(
                            url = "https://image.tmdb.org/t/p/w1280$path",
                            language = image.iso6391,
                            title = candidate.title,
                            releaseDate = candidate.releaseDate,
                            overview = candidate.overview,
                            mediaType = candidate.mediaType,
                            tmdbId = candidate.id,
                            originalTitle = candidate.originalTitle,
                            artworkType = TmdbArtworkType.LANDSCAPE
                        ))
                    }
                }
            }
        }
        val result = artwork
            .distinctBy { "${it.mediaType}:${it.tmdbId}:${it.artworkType}:${it.url}" }
            .filter { artworkType == null || it.artworkType == artworkType }
            .sortedWith(
                compareByDescending<TmdbPosterCandidate> { it.language.equals("fr", ignoreCase = true) }
                    .thenByDescending { it.language.equals("en", ignoreCase = true) }
            )
        artworkCache[cacheKey] = CachedArtwork(result, System.currentTimeMillis() + 12 * 60 * 60 * 1_000L)
        result
    }

    suspend fun fetchMetadataForTitleQuery(
        query: String,
        mediaTypeHint: String = "unknown",
        durationMinutes: Int? = null,
        yearHint: Int? = null
    ): FreeboxVideoMeta? =
        withContext(Dispatchers.IO) {
            val settings = tmdbSettingsDataStore.settings.first()
            if (!settings.enabled) return@withContext null
            val lookup = parseTmdbLookupInput(query, mediaTypeHint, durationMinutes, yearHint)
            val cleanQuery = lookup.title
            if (cleanQuery.length < 2 || TMDB_API_KEY.isBlank()) return@withContext null
            val cacheKey = "${tmdbLooseTitleKey(cleanQuery)}:${lookup.mediaType}:${lookup.year}:${lookup.durationMinutes}"
            val now = System.currentTimeMillis()
            titleMetadataCache[cacheKey]?.let { cached ->
                if (cached.expiresAtMs > now) return@withContext cached.value
                titleMetadataCache.remove(cacheKey, cached)
            }
            val requestDeferred = CompletableDeferred<FreeboxVideoMeta?>()
            titleMetadataInFlight.putIfAbsent(cacheKey, requestDeferred)?.let { existing ->
                return@withContext existing.await()
            }
            try {
            val preferredType = lookup.mediaType
            val expectedYear = lookup.year
            fun yearMatches(date: String?): Boolean {
                if (expectedYear == null) return true
                val actualYear = date?.take(4)?.toIntOrNull() ?: return false
                return kotlin.math.abs(actualYear - expectedYear) <= 1
            }
            fun titleScore(originalLanguage: String?, vararg titles: String?): Int {
                val score = titles.maxOfOrNull { tmdbFuzzyTitleScore(cleanQuery, it) } ?: 0
                return score + if (score > 0 && originalLanguage.equals("fr", ignoreCase = true)) 20 else 0
            }
            val movie = if (preferredType != "tv") runCatching {
                tmdbApi.searchMovies(TMDB_API_KEY, cleanQuery, "fr-FR", 1, false).body()?.results.orEmpty()
                    .filter { yearMatches(it.releaseDate) }
                    .maxByOrNull {
                        titleScore(it.originalLanguage, it.title, it.originalTitle)
                    }
            }.getOrNull() else null
            val tv = if (preferredType != "movie") runCatching {
                tmdbApi.searchTv(TMDB_API_KEY, cleanQuery, "fr-FR", 1, false).body()?.results.orEmpty()
                    .filter { yearMatches(it.firstAirDate) }
                    .maxByOrNull {
                        titleScore(it.originalLanguage, it.name, it.originalName)
                    }
            }.getOrNull() else null
            val movieScore = movie?.let {
                titleScore(it.originalLanguage, it.title, it.originalTitle)
            } ?: 0
            val tvScore = tv?.let {
                titleScore(it.originalLanguage, it.name, it.originalName)
            } ?: 0
            val minimumTitleScore = 78
            val useTv = when (preferredType) {
                "tv" -> tvScore >= minimumTitleScore
                "movie" -> false
                else -> tvScore >= minimumTitleScore && tvScore > movieScore + 7
            }
            val useMovie = when (preferredType) {
                "movie" -> movieScore >= minimumTitleScore
                "tv" -> false
                else -> movieScore >= minimumTitleScore && movieScore > tvScore + 7
            }
            if (!useTv && !useMovie) return@withContext null
            if (useTv) {
                val item = tv ?: return@withContext null
                val details = runCatching {
                    tmdbApi.getTvDetails(
                        item.id,
                        TMDB_API_KEY,
                        "fr-FR",
                        appendToResponse = "aggregate_credits,images",
                        includeImageLanguage = "fr,en,null"
                    ).body()
                }.getOrNull()
                val englishDetails = if (details?.overview.isNullOrBlank() || details?.genres.isNullOrEmpty()) {
                    runCatching { tmdbApi.getTvDetails(item.id, TMDB_API_KEY, "en-US").body() }.getOrNull()
                } else null
                val aggregateCredits = details?.aggregateCredits ?: runCatching {
                    tmdbApi.getTvAggregateCredits(item.id, TMDB_API_KEY, "fr-FR").body()
                }.getOrNull()
                val credits = if (aggregateCredits == null || aggregateCredits.cast.isNullOrEmpty() ||
                    aggregateCredits.cast.orEmpty().any { it.profilePath.isNullOrBlank() } ||
                    aggregateCredits.crew.orEmpty().any { member -> member.profilePath.isNullOrBlank() &&
                        member.jobs.orEmpty().any { HomeMediaRules.isTmdbPresenter(it.job) } }) {
                    runCatching { tmdbApi.getTvCredits(item.id, TMDB_API_KEY, "fr-FR").body() }.getOrNull()
                } else null
                val images = details?.images ?: runCatching {
                    tmdbApi.getTvImages(item.id, TMDB_API_KEY, "fr,en,null").body()
                }.getOrNull()
                val originalLanguage = details?.originalLanguage ?: item.originalLanguage
                val logoImages = if (!originalLanguage.isNullOrBlank() && originalLanguage !in listOf("fr", "en")) {
                    runCatching {
                        tmdbApi.getTvImages(item.id, TMDB_API_KEY, HomeMediaRules.titleLogoLanguages(originalLanguage)).body()
                    }.getOrNull() ?: images
                } else images
                val tmdbRuntime = details?.episodeRunTime?.firstOrNull()
                if (lookup.durationMinutes != null && tmdbRuntime != null &&
                    kotlin.math.abs(lookup.durationMinutes - tmdbRuntime) > 8
                ) return@withContext null
                val aggregateCastMembers = aggregateCredits?.cast.orEmpty().mapNotNull { member ->
                    val standard = credits?.cast?.firstOrNull { it.id == member.id }
                    member.name?.takeIf(String::isNotBlank)?.let {
                        MetaCastMember(
                            it,
                            member.roles?.firstOrNull()?.character,
                            (member.profilePath?.takeIf(String::isNotBlank) ?: standard?.profilePath)
                                ?.let { path -> "https://image.tmdb.org/t/p/w500$path" },
                            member.id
                        )
                    }
                }
                val regularCastMembers = aggregateCastMembers.ifEmpty { credits?.cast.orEmpty().mapNotNull { member ->
                    member.name?.takeIf(String::isNotBlank)?.let {
                        MetaCastMember(it, member.character, member.profilePath?.let { path -> "https://image.tmdb.org/t/p/w500$path" }, member.id)
                    }
                } }
                val presenters = aggregateCredits?.crew.orEmpty()
                    .filter { member -> member.jobs.orEmpty().any { HomeMediaRules.isTmdbPresenter(it.job) } }
                    .sortedByDescending { member -> member.jobs.orEmpty().filter { HomeMediaRules.isTmdbPresenter(it.job) }
                        .maxOfOrNull { it.episodeCount ?: 0 } ?: 0 }
                    .mapNotNull { member -> member.name?.trim()?.takeIf(String::isNotBlank)?.let { name ->
                        val profile = member.profilePath?.takeIf(String::isNotBlank)
                            ?: credits?.crew?.firstOrNull { it.id == member.id }?.profilePath
                        MetaCastMember(name, "Presenter", profile?.let { "https://image.tmdb.org/t/p/w500$it" }, member.id)
                    } }.ifEmpty {
                        credits?.crew.orEmpty().filter { HomeMediaRules.isTmdbPresenter(it.job) }.mapNotNull { member ->
                            member.name?.trim()?.takeIf(String::isNotBlank)?.let { name ->
                                MetaCastMember(name, "Presenter", member.profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }, member.id)
                            }
                        }
                    }.distinctBy { it.tmdbId?.toString() ?: it.name.lowercase(Locale.ROOT) }
                val castMembers = HomeMediaRules.preferredTmdbPeople("tv", regularCastMembers, presenters)
                val aggregateDirectors = aggregateCredits?.crew.orEmpty()
                    .filter { member ->
                        member.jobs.orEmpty().any { job ->
                            job.job.equals("Director", true) || job.job.equals("Creator", true)
                        }
                    }
                    .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                val regularDirectors = credits?.crew.orEmpty()
                    .filter { it.job.equals("Director", true) || it.job.equals("Creator", true) }
                    .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                val creators = details?.createdBy.orEmpty()
                    .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                val directors = (aggregateDirectors + regularDirectors + creators)
                    .distinct()
                val result = FreeboxVideoMeta(
                    item.id,
                    "series",
                    item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    details?.overview?.takeIf(String::isNotBlank)
                        ?: englishDetails?.overview?.takeIf(String::isNotBlank)
                        ?: item.overview,
                    details?.voteAverage ?: item.voteAverage,
                    details?.firstAirDate?.take(4) ?: item.firstAirDate?.take(4),
                    (details?.genres?.takeIf { it.isNotEmpty() } ?: englishDetails?.genres)
                        ?.map { it.name }
                        .orEmpty(),
                    details?.episodeRunTime?.firstOrNull(),
                    cast = castMembers.map(MetaCastMember::name),
                    director = directors,
                    seasonCount = details?.numberOfSeasons,
                    logoUrl = tmdbLocalizedLogoUrl(logoImages?.logos.orEmpty(), originalLanguage),
                    castMembers = castMembers,
                    matchedTitle = item.name,
                    originalTitle = item.originalName,
                    artworkCandidates = tmdbArtworkCandidates(
                        images, item.name, item.originalName, details?.firstAirDate ?: item.firstAirDate,
                        details?.overview ?: item.overview, "tv", item.id
                    )
                )
                titleMetadataCache[cacheKey] = CachedTitleMetadata(result, System.currentTimeMillis() + 12 * 60 * 60 * 1_000L)
                requestDeferred.complete(result)
                result
            } else {
                val item = movie ?: return@withContext null
                val details = runCatching {
                    tmdbApi.getMovieDetails(
                        item.id,
                        TMDB_API_KEY,
                        "fr-FR",
                        appendToResponse = "credits,images",
                        includeImageLanguage = "fr,en,null"
                    ).body()
                }.getOrNull()
                val englishDetails = if (details?.overview.isNullOrBlank() || details?.genres.isNullOrEmpty()) {
                    runCatching { tmdbApi.getMovieDetails(item.id, TMDB_API_KEY, "en-US").body() }.getOrNull()
                } else null
                val credits = details?.credits ?: runCatching {
                    tmdbApi.getMovieCredits(item.id, TMDB_API_KEY, "fr-FR").body()
                }.getOrNull()
                val images = details?.images ?: runCatching {
                    tmdbApi.getMovieImages(item.id, TMDB_API_KEY, "fr,en,null").body()
                }.getOrNull()
                val originalLanguage = details?.originalLanguage ?: item.originalLanguage
                val logoImages = if (!originalLanguage.isNullOrBlank() && originalLanguage !in listOf("fr", "en")) {
                    runCatching {
                        tmdbApi.getMovieImages(item.id, TMDB_API_KEY, HomeMediaRules.titleLogoLanguages(originalLanguage)).body()
                    }.getOrNull() ?: images
                } else images
                val tmdbRuntime = details?.runtime
                if (lookup.durationMinutes != null && tmdbRuntime != null &&
                    kotlin.math.abs(lookup.durationMinutes - tmdbRuntime) > 8
                ) return@withContext null
                val castMembers = credits?.cast.orEmpty().mapNotNull { member ->
                    member.name?.takeIf(String::isNotBlank)?.let {
                        MetaCastMember(it, member.character, member.profilePath?.let { path -> "https://image.tmdb.org/t/p/w185$path" }, member.id)
                    }
                }
                val directors = credits?.crew.orEmpty()
                    .filter { it.job.equals("Director", true) }
                    .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                    .distinct()
                val result = FreeboxVideoMeta(
                    item.id,
                    "movie",
                    item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    details?.overview?.takeIf(String::isNotBlank)
                        ?: englishDetails?.overview?.takeIf(String::isNotBlank)
                        ?: item.overview,
                    details?.voteAverage ?: item.voteAverage,
                    details?.releaseDate?.take(4) ?: item.releaseDate?.take(4),
                    (details?.genres?.takeIf { it.isNotEmpty() } ?: englishDetails?.genres)
                        ?.map { it.name }
                        .orEmpty(),
                    details?.runtime,
                    cast = castMembers.map(MetaCastMember::name),
                    director = directors,
                    logoUrl = tmdbLocalizedLogoUrl(logoImages?.logos.orEmpty(), originalLanguage),
                    castMembers = castMembers,
                    matchedTitle = item.title,
                    originalTitle = item.originalTitle,
                    artworkCandidates = tmdbArtworkCandidates(
                        images, item.title, item.originalTitle, details?.releaseDate ?: item.releaseDate,
                        details?.overview ?: item.overview, "movie", item.id
                    )
                )
                titleMetadataCache[cacheKey] = CachedTitleMetadata(result, System.currentTimeMillis() + 12 * 60 * 60 * 1_000L)
                requestDeferred.complete(result)
                result
            }
            } catch (cancellation: CancellationException) {
                requestDeferred.cancel(cancellation)
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "TMDB metadata lookup failed for $cleanQuery", error)
                requestDeferred.complete(null)
                null
            } finally {
                if (!requestDeferred.isCompleted) requestDeferred.complete(null)
                titleMetadataInFlight.remove(cacheKey, requestDeferred)
            }
        }

    suspend fun resolveTmdbIdForTitleQuery(
        @Suppress("UNUSED_PARAMETER") query: String,
        @Suppress("UNUSED_PARAMETER") mediaTypeHint: String
    ): Int? = fetchMetadataForTitleQuery(query, mediaTypeHint)?.tmdbId
}

internal fun tmdbLooseTitleKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun tmdbTitleMatchScore(query: String, title: String?, originalLanguage: String? = null): Int {
    if (title.isNullOrBlank()) return 0
    val q = tmdbLooseTitleKey(query)
    val t = tmdbLooseTitleKey(title)
    if (q.isBlank() || t.isBlank()) return 0
    val base = when {
        t == q -> 100
        t.startsWith(q) || q.startsWith(t) -> 80
        t.contains(q) || q.contains(t) -> 60
        else -> 0
    }
    return base + if (originalLanguage == "fr") 20 else 0
}

private fun tmdbCandidateTitleScore(
    query: String,
    title: String?,
    originalLanguage: String? = null
): Int {
    val strictScore = tmdbTitleMatchScore(query, title, originalLanguage)
    if (strictScore > 0 || title.isNullOrBlank()) return strictScore

    val ignoredWords = setOf("de", "des", "du", "la", "le", "les", "l", "un", "une", "et")
    val queryWords = tmdbLooseTitleKey(query)
        .split(' ')
        .filter { it.length > 1 && it !in ignoredWords }
        .toSet()
    val titleWords = tmdbLooseTitleKey(title)
        .split(' ')
        .filter { it.length > 1 && it !in ignoredWords }
        .toSet()
    if (queryWords.isEmpty() || titleWords.isEmpty()) return 0

    val overlap = queryWords.intersect(titleWords).size
    return overlap * 100 / maxOf(queryWords.size, titleWords.size)
}

private fun extractTmdbQueryYear(rawQuery: String): String? =
    Regex("""\b(19|20)\d{2}\b""").find(rawQuery)?.value

private fun tmdbYearBonus(expectedYear: String?, actualDate: String?): Int {
    val expected = expectedYear ?: return 0
    val actual = actualDate?.take(4) ?: return 0
    return if (actual == expected) 25 else -15
}

internal fun tmdbLocalizedLogoUrl(
    logos: List<com.nuvio.tv.data.remote.api.TmdbImage>,
    originalLanguage: String? = null
): String? {
    val candidates = logos.mapNotNull { image ->
        image.filePath?.takeIf(String::isNotBlank)?.let { it to image.iso6391.orEmpty() }
    }.toMap()
    return HomeMediaRules.originalTitleLogoUrl(candidates, originalLanguage).takeIf(String::isNotBlank)
}

internal fun cleanTmdbTitleQuery(rawQuery: String): String = rawQuery
    .replace('\\', '/')
    .substringAfterLast('/')
    .replace(Regex("\\.(mkv|mp4|m4v|avi|mov|mpeg|mpg|ts|webm|flv)$", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("\\bS\\d{1,2}[ ._-]*E\\d{1,3}\\b", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("\\b\\d{1,2}x\\d{1,3}\\b", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("\\bEP(?:ISODE)?[ ._-]*0*\\d{1,3}\\b", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("""^\s*\d+h\s*\d{1,2}(?:m)?\s+""", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("""^\s*\d+\s*min\s+""", RegexOption.IGNORE_CASE), " ")
    .replace("&", " et ")
    .replace(Regex("""\[[^]]*]"""), " ")
    .replace(Regex("""\b(?:4k|uhd|hdr10\+?|hdr|dv|dolby\s*vision|hevc|x265|x264|h\.?264|h\.?265|multi|vostfr|vf|truefrench|french|web-?dl|bluray|brrip|webrip|hdtv|aac|ac3|eac3|atmos|dts|proper|repack)\b""", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
    .replace(Regex("""[._-]+"""), " ")
    .replace(Regex("""\s+"""), " ")
    .replace(Regex("""\(\s*\)"""), " ")
    .trim()
    .ifBlank { rawQuery.trim() }

internal data class TmdbLookupInput(
    val title: String,
    val mediaType: String,
    val year: Int?,
    val durationMinutes: Int?
)

internal fun parseTmdbLookupInput(
    rawName: String,
    mediaTypeHint: String,
    durationMinutes: Int? = null,
    yearHint: Int? = null
): TmdbLookupInput {
    val basename = rawName.replace('\\', '/').substringAfterLast('/')
    val episodeName = Regex(
        "\\b(?:S\\d{1,2}[ ._-]*E\\d{1,3}|\\d{1,2}x\\d{1,3}|EP(?:ISODE)?[ ._-]*0*\\d{1,3})\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(basename)
    val year = Regex("\\b(?:19|20)\\d{2}\\b").find(basename)?.value?.toIntOrNull()
    val runtimeFromName = Regex("\\b(\\d{1,2})h[ ._-]?(\\d{1,2})m?\\b", RegexOption.IGNORE_CASE)
        .find(basename)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        ?: Regex("\\b(\\d{2,3})\\s*min\\b", RegexOption.IGNORE_CASE)
            .find(basename)?.groupValues?.get(1)?.toIntOrNull()
    val hintedType = when (mediaTypeHint.trim().lowercase()) {
        "tv", "series", "iptv_series" -> "tv"
        "movie", "film" -> "movie"
        else -> "unknown"
    }
    val type = if (episodeName) "tv" else hintedType
    return TmdbLookupInput(
        title = cleanTmdbTitleQuery(basename),
        mediaType = type,
        year = yearHint?.takeIf { it in 1870..2100 } ?: year,
        durationMinutes = durationMinutes?.takeIf { it > 0 } ?: runtimeFromName
    )
}

internal fun tmdbFuzzyTitleScore(query: String, candidate: String?): Int {
    if (candidate.isNullOrBlank()) return 0
    val left = tmdbLooseTitleKey(query)
    val right = tmdbLooseTitleKey(candidate)
    if (left.isBlank() || right.isBlank()) return 0
    if (left == right) return 100

    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (i in left.indices) {
        current[0] = i + 1
        for (j in right.indices) {
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + if (left[i] == right[j]) 0 else 1
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    val editScore = 100 * (maxOf(left.length, right.length) - previous[right.length]) /
        maxOf(left.length, right.length)
    val leftWords = left.split(' ').filter(String::isNotBlank).toSet()
    val rightWords = right.split(' ').filter(String::isNotBlank).toSet()
    val tokenScore = if (leftWords.isEmpty() || rightWords.isEmpty()) 0 else {
        200 * leftWords.intersect(rightWords).size / (leftWords.size + rightWords.size)
    }
    return maxOf(editScore, tokenScore)
}

data class FreeboxVideoMeta(
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
    val voteAverage: Double?,
    val year: String?,
    val genres: List<String>,
    val runtimeMinutes: Int? = null,
    val releaseDate: String? = null,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val seasonCount: Int? = null,
    val logoUrl: String? = null,
    val castMembers: List<com.nuvio.tv.domain.model.MetaCastMember> = emptyList(),
    /** Candidate identity retained so callers can reject an uncertain title match. */
    val matchedTitle: String? = null,
    val originalTitle: String? = null,
    val artworkCandidates: List<TmdbPosterCandidate> = emptyList()
)

enum class TmdbArtworkType {
    PORTRAIT,
    LANDSCAPE
}

data class TmdbPosterCandidate(
    val url: String,
    val language: String?,
    val title: String? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val mediaType: String? = null,
    val tmdbId: Int? = null,
    val artworkType: TmdbArtworkType = TmdbArtworkType.PORTRAIT,
    val originalTitle: String? = null
)

data class TmdbImages(
    val backdropUrl: String?,
    val posterUrl: String?,
    val runtimeMinutes: Int? = null
)

internal fun tmdbArtworkCandidates(
    images: com.nuvio.tv.data.remote.api.TmdbImagesResponse?,
    title: String?,
    originalTitle: String?,
    releaseDate: String?,
    overview: String?,
    mediaType: String,
    tmdbId: Int
): List<TmdbPosterCandidate> = buildList {
    images?.posters.orEmpty().forEach { image ->
        image.filePath?.takeIf(String::isNotBlank)?.let { path ->
            add(
                TmdbPosterCandidate(
                    url = "https://image.tmdb.org/t/p/w500$path",
                    language = image.iso6391,
                    title = title,
                    releaseDate = releaseDate,
                    overview = overview,
                    mediaType = mediaType,
                    tmdbId = tmdbId,
                    artworkType = TmdbArtworkType.PORTRAIT,
                    originalTitle = originalTitle
                )
            )
        }
    }
    images?.backdrops.orEmpty().forEach { image ->
        image.filePath?.takeIf(String::isNotBlank)?.let { path ->
            add(
                TmdbPosterCandidate(
                    url = "https://image.tmdb.org/t/p/w1280$path",
                    language = image.iso6391,
                    title = title,
                    releaseDate = releaseDate,
                    overview = overview,
                    mediaType = mediaType,
                    tmdbId = tmdbId,
                    artworkType = TmdbArtworkType.LANDSCAPE,
                    originalTitle = originalTitle
                )
            )
        }
    }
}.distinctBy { "${it.artworkType}:${it.url}" }
