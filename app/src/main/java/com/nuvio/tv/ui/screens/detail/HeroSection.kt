package com.nuvio.tv.ui.screens.detail

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import android.util.Log
import com.jojotv.home.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import coil3.transform.Transformation
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.util.localizedGenreLabel
import java.util.Locale

private const val MAX_VISIBLE_HERO_GENRES = 6
private const val TITLE_LOGO_REFERENCE_ASPECT_RATIO = 3.7f

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroContentSection(
    meta: Meta,
    nextEpisode: Video?,
    nextToWatch: NextToWatch?,
    onPlayClick: () -> Unit,
    onPlayLongPress: (() -> Unit)? = null,
    isInLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    onToggleMovieWatched: () -> Unit,
    trailerAvailable: Boolean = false,
    onTrailerClick: () -> Unit = {},
    hideLogoDuringTrailer: Boolean = false,
    mdbListRatings: MDBListRatings? = null,
    hideMetaInfoImdb: Boolean = false,
    tmdbRating: Float? = null,
    showFullReleaseDate: Boolean = true,
    isTrailerPlaying: Boolean = false,
    playButtonFocusRequester: FocusRequester? = null,
    playButtonDownFocusRequester: FocusRequester? = null,
    restorePlayFocusToken: Int = 0,
    onHeroActionFocused: () -> Unit = {},
    onPlayFocusRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val isSeriesApi = remember(meta.apiType) {
        meta.apiType.equals("series", ignoreCase = true) || meta.apiType.equals("tv", ignoreCase = true)
    }
    val logoModel = remember(context, meta.logo) {
        meta.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .transformations(TrimTransparentEdgesTransformation())
                .build()
        }
    }
    var logoLoadFailed by remember(meta.logo) { mutableStateOf(false) }
    val shouldShowLogo =
        !meta.logo.isNullOrBlank() &&
            !logoLoadFailed &&
            !(isTrailerPlaying && hideLogoDuringTrailer)
    val libraryAddPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.library_add_plus
    )
    val trailerPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.trailer_play_button
    )
    val strCreator = stringResource(R.string.hero_creator)
    val strDirector = stringResource(R.string.hero_director)
    val strWriter = stringResource(R.string.hero_writer)
    val creditLine = remember(meta.director, meta.writer, isSeriesApi) {
        val directorLine = meta.director.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val writerLine = meta.writer.takeIf { it.isNotEmpty() }?.joinToString(", ")
        when {
            !directorLine.isNullOrBlank() -> {
                if (isSeriesApi) strCreator.format(directorLine) else strDirector.format(directorLine)
            }
            !writerLine.isNullOrBlank() -> strWriter.format(writerLine)
            else -> null
        }
    }

    // Animate logo properties for trailer mode
    val logoHeight by animateDpAsState(
        targetValue = if (isTrailerPlaying) 60.dp else 100.dp,
        animationSpec = tween(600),
        label = "logoHeight"
    )
    val logoBottomPadding by animateDpAsState(
        targetValue = if (isTrailerPlaying) 24.dp else 16.dp,
        animationSpec = tween(600),
        label = "logoPadding"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Keep the original compact height for short synopses, but let
            // the hero grow inside the detail LazyColumn when the synopsis is
            // longer. A fixed height clipped the last lines on TV screens.
            .heightIn(min = 280.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(600))
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(0.72f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    // Keep the title logo on the same top row as the play
                    // action so both primary elements share one visual line.
                    if (shouldShowLogo) {
                        AsyncImage(
                            model = logoModel,
                            contentDescription = meta.name,
                            onError = { logoLoadFailed = true },
                            modifier = Modifier
                                .height(logoHeight)
                                .widthIn(max = logoHeight * TITLE_LOGO_REFERENCE_ASPECT_RATIO)
                                .fillMaxWidth()
                                .padding(bottom = logoBottomPadding),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                    } else if (!isTrailerPlaying) {
                        Text(
                            text = meta.name,
                            style = MaterialTheme.typography.displayMedium,
                            color = NuvioColors.TextPrimary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !isTrailerPlaying,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(400))
                ) {
                    PlayButton(
                        text = nextToWatch?.displayText ?: when {
                            nextEpisode != null && nextEpisode.season != null && nextEpisode.episode != null ->
                                stringResource(R.string.hero_play_episode, nextEpisode.season, nextEpisode.episode)
                            else -> stringResource(R.string.hero_play)
                        },
                        onClick = onPlayClick,
                        onLongPress = onPlayLongPress,
                        focusRequester = playButtonFocusRequester,
                        downFocusRequester = playButtonDownFocusRequester,
                        restoreFocusToken = restorePlayFocusToken,
                        onFocusRestored = {
                            onHeroActionFocused()
                            onPlayFocusRestored()
                        }
                    )
                }
            }

            // Everything below the logo fades out during trailer
            AnimatedVisibility(
                visible = !isTrailerPlaying,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(400))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))

                    // Director/Writer line above description
                    if (!creditLine.isNullOrBlank()) {
                        Text(
                            text = creditLine,
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (mdbListRatings?.isEmpty() == false) {
                        MDBListRatingsRow(ratings = mdbListRatings)
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Always show series/movie description, not episode description
                    meta.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextPrimary,
                            overflow = TextOverflow.Clip,
                            maxLines = Int.MAX_VALUE,
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .padding(bottom = 12.dp)
                        )
                    }

                    MetaInfoRow(
                        meta = meta,
                        hideImdbRating = hideMetaInfoImdb,
                        showFullReleaseDate = showFullReleaseDate,
                        tmdbRating = tmdbRating
                    )

                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PlayButton(
    text: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    restoreFocusToken: Int = 0,
    onFocusRestored: () -> Unit = {}
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken > 0 && focusRequester != null) {
            focusRequester.requestFocusAfterFrames()
        }
    }
    val context = LocalContext.current
    val playPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.ic_player_play
    )

    Button(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocusRestored()
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (onLongPress != null &&
                    longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }

                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                    if (isSelectOrMenuKey(native.keyCode)) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties {
                up = FocusRequester.Cancel
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            },
        colors = ButtonDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.White,
            focusedContainerColor = androidx.compose.ui.graphics.Color.White,
            contentColor = androidx.compose.ui.graphics.Color.Black,
            focusedContentColor = androidx.compose.ui.graphics.Color.Black
        ),
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(32.dp)
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(32.dp)
            )
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = playPainter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButtonPainter(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = IconButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary,
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    selectedContainerColor: Color = Color(0xFF7CFF9B),
    selectedContentColor: Color = Color.Black,
    onFocused: () -> Unit = {}
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    IconButton(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (onLongPress != null &&
                    longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }

                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                    if (isSelectOrMenuKey(native.keyCode)) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = IconButtonDefaults.colors(
            containerColor = if (selected) selectedContainerColor else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary,
            contentColor = if (selected) selectedContentColor else NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoRow(
    meta: Meta,
    hideImdbRating: Boolean,
    showFullReleaseDate: Boolean = true,
    tmdbRating: Float? = null
) {
    val context = LocalContext.current
    val genresText = meta.genres
        .take(MAX_VISIBLE_HERO_GENRES)
        .joinToString(" • ") { localizedGenreLabel(it) }
    val runtimeText = remember(meta.runtime) { meta.runtime?.let { formatRuntime(it) } }
    val yearText = remember(meta.releaseInfo, meta.released, meta.type, showFullReleaseDate) {
        if (showFullReleaseDate && meta.type == ContentType.MOVIE) {
            meta.released
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
                ?.let { val locale = java.util.Locale.getDefault(); java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale).format(java.util.Date(it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())) }
                ?: formatYearRange(meta.releaseInfo)
        } else {
            formatYearRange(meta.releaseInfo)
        }
    }
    val imdbRating = if (hideImdbRating) null else meta.imdbRating
    val shouldShowImdbRating = imdbRating != null
    val imdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.imdb_logo_2016)
            .build()
    }
    val shouldShowTmdbRating = tmdbRating != null
    val tmdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.mdblist_tmdb)
            .build()
    }
    val ageRatingBadge = remember(meta.ageRating) {
        meta.ageRating?.trim()?.takeIf { it.isNotBlank() }
    }
    val isSeries = meta.type == ContentType.SERIES || meta.type == ContentType.TV
    val typeText = when (meta.type) {
        ContentType.MOVIE -> stringResource(R.string.type_movie)
        ContentType.SERIES, ContentType.TV -> stringResource(R.string.type_series)
        else -> null
    }
    val strStatusEnded = stringResource(if (isSeries) R.string.series_status_ended else R.string.movie_status_ended)
    val strStatusContinuing = stringResource(if (isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
    val strStatusCurrent = stringResource(if (isSeries) R.string.series_status_current else R.string.movie_status_current)
    val strStatusCancelled = stringResource(if (isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
    val strStatusReleased = stringResource(if (isSeries) R.string.series_status_released else R.string.movie_status_released)
    val strStatusPlanned = stringResource(if (isSeries) R.string.series_status_planned else R.string.movie_status_planned)
    val strStatusRumored = stringResource(if (isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
    val strStatusInProduction = stringResource(if (isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
    val strStatusPostProduction = stringResource(if (isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
    val statusBadge = remember(meta.status, isSeries) {
        when (meta.status?.trim()?.lowercase()) {
            "ended" -> strStatusEnded.uppercase()
            "continuing", "returning series" -> strStatusContinuing.uppercase()
            "current" -> strStatusCurrent.uppercase()
            "cancelled", "canceled" -> strStatusCancelled.uppercase()
            "released" -> strStatusReleased.uppercase()
            "planned" -> strStatusPlanned.uppercase()
            "rumored" -> strStatusRumored.uppercase()
            "in production" -> strStatusInProduction.uppercase()
            "post production" -> strStatusPostProduction.uppercase()
            else -> meta.status?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
        }
    }
    Log.d("HeroBadge", "name=${meta.name} ageRating=${meta.ageRating} status=${meta.status} ageRatingBadge=$ageRatingBadge statusBadge=$statusBadge")
    val secondaryItems = remember(meta.country, meta.language) {
        buildList<String> {
            meta.country?.trim()?.takeIf { it.isNotBlank() }?.let { add(normalizeCountryLabel(it)) }
            meta.language?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Primary row: Genres, Release, Ratings
        Row(
            modifier = Modifier.offset(y = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            typeText?.let { type ->
                Text(
                    text = type,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                if (meta.genres.isNotEmpty() || yearText != null || runtimeText != null || shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoHorizontalDivider()
                }
            }

            if (meta.genres.isNotEmpty()) {
                Text(
                    text = genresText,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                if (yearText != null || runtimeText != null || shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoHorizontalDivider()
                }
            }

            yearText?.let { year ->
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                if (runtimeText != null || shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoHorizontalDivider()
                }
            }

            runtimeText?.let { runtime ->
                Text(
                    text = runtime,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                if (shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoHorizontalDivider()
                }
            }

            imdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AsyncImage(
                        model = imdbModel,
                        contentDescription = stringResource(R.string.cd_rating),
                        modifier = Modifier.size(30.dp),
                        contentScale = ContentScale.Fit
                    )
                    val ratingText = remember(rating) { String.format("%.1f", rating) }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                }
            }

            tmdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AsyncImage(
                        model = tmdbModel,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                    val ratingText = remember(rating) { (rating * 10).toInt().toString() }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                }
            }
        }

        // Secondary row: Age Rating, Status, Country, Language
        if (ageRatingBadge != null || statusBadge != null || secondaryItems.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ageRatingBadge != null && statusBadge != null) {
                    CombinedMetaBadge(
                        leftText = ageRatingBadge,
                        leftColor = NuvioColors.TextSecondary,
                        rightText = statusBadge,
                        rightColor = NuvioColors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(text = badge)
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && secondaryItems.isNotEmpty()) {
                    MetaInfoHorizontalDivider()
                }
                secondaryItems.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextPrimary
                    )
                    if (index < secondaryItems.lastIndex) {
                        MetaInfoHorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    contentColor: Color = NuvioColors.TextSecondary
) {
    Box(
        modifier = Modifier
            .border(
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun CombinedMetaBadge(
    leftText: String,
    leftColor: Color = NuvioColors.TextSecondary,
    rightText: String,
    rightColor: Color = NuvioColors.TextPrimary
) {
    val dividerColor = leftColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .border(
                border = BorderStroke(1.dp, dividerColor),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = leftText,
            style = MaterialTheme.typography.labelMedium,
            color = leftColor,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(12.dp)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = MaterialTheme.typography.labelMedium,
            color = rightColor,
            maxLines = 1
        )
    }
}

private fun normalizeCountryLabel(raw: String): String {
    val displayLocale = Locale.getDefault()
    return raw
        .split(",")
        .joinToString(", ") { part ->
            val code = part.trim()
            if (code.matches(Regex("[A-Za-z]{2}"))) {
                Locale("", code).getDisplayCountry(displayLocale).takeIf { it.isNotBlank() } ?: code
            } else {
                code
            }
        }
}

@Composable
private fun MDBListRatingsRow(ratings: MDBListRatings) {
    val context = LocalContext.current
    val items = remember(ratings) {
        listOf(
            Triple("trakt", com.nuvio.tv.R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", com.nuvio.tv.R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", com.nuvio.tv.R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", com.nuvio.tv.R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("tomatoes", com.nuvio.tv.R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (provider, logoRes, rating) ->
            val resolvedRating = rating ?: return@forEach
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val model = remember(context, logoRes) {
                    ImageRequest.Builder(context)
                        .data(logoRes)
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = formatMDBListRating(provider, resolvedRating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.audience?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = com.nuvio.tv.R.drawable.mdblist_audience),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = formatMDBListRating("audience", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.metacritic?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = com.nuvio.tv.R.drawable.mdblist_metacritic),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = formatMDBListRating("metacritic", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun isSelectOrMenuKey(keyCode: Int): Boolean {
    return isSelectKey(keyCode) || keyCode == AndroidKeyEvent.KEYCODE_MENU
}

private fun formatMDBListRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format("%.1f", rating)
        else -> {
            if (rating % 1.0 == 0.0) rating.toInt().toString() else String.format("%.1f", rating)
        }
    }
}


private fun formatYearRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

private fun formatRuntime(runtime: String): String {
    val trimmed = runtime.trim()
    // Already in "Xh Ym" or "Xh" format
    if (trimmed.contains('h') || trimmed.contains('m')) {
        val hours = Regex("(\\d+)\\s*h").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("(\\d+)\\s*m").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        if (total > 0) return if (total >= 60) {
            val h = total / 60; val m = total % 60
            if (m > 0) "${h}h ${m}m" else "${h}h"
        } else "${total}m"
    }
    // "H:MM" or "HH:MM" format
    if (trimmed.contains(':')) {
        val parts = trimmed.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val mins = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        if (total > 0) return if (total >= 60) {
            val m = total % 60
            if (m > 0) "${hours}h ${m}m" else "${hours}h"
        } else "${total}m"
    }
    // Plain number (minutes)
    val minutes = trimmed.filter { it.isDigit() }.toIntOrNull() ?: return runtime
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

@Composable
private fun rememberRawSvgPainter(
    context: android.content.Context,
    @androidx.annotation.RawRes rawRes: Int
): Painter {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { 24.dp.roundToPx() }
    val model = remember(rawRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(rawRes)
            .size(sizePx)
            .build()
    }
    return coil3.compose.rememberAsyncImagePainter(model = model)
}

/** Removes transparent source margins so every title logo starts at the same visual edge. */
private class TrimTransparentEdgesTransformation : Transformation() {
    override val cacheKey: String = "trim_transparent_edges_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        var left = input.width
        var right = -1
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                if ((pixels[y * input.width + x] ushr 24) > 8) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (right < left) return input
        if (left == 0 && right == input.width - 1) return input
        return Bitmap.createBitmap(input, left, 0, right - left + 1, input.height)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoHorizontalDivider() {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.extendedColors.textTertiary
    )
}
