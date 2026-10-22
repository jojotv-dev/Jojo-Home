package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.toShortSynopsis
import coil3.request.transitionFactory
import com.jojotv.home.R
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource

private data class ModernHeroSecondaryMeta(
    val highlightText: String?,
    val ageRating: String?,
    val status: String?,
    val details: List<String>
)

private fun heroImageVariantWidth(requestWidthPx: Int): Int = when {
    requestWidthPx <= 480 -> 480
    requestWidthPx <= 960 -> 960
    requestWidthPx <= 1440 -> 1440
    else -> 1920
}

private fun heroImageSourceUrl(url: String): String {
    // TMDB is already delivered through its image CDN. Request the original
    // backdrop there so Coil can create an appropriately sized local variant
    // instead of enlarging a w780/w1280 image in the Hero.
    return if (url.contains("image.tmdb.org/t/p/")) {
        url.replace(Regex("/t/p/(w\\d+|original)(?=/)"), "/t/p/original")
    } else {
        url
    }
}

@Composable
internal fun ModernHeroScene(
    state: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    bgColor: Color,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit
) {
    ModernHeroMediaLayer(
        heroBackdrop = { state().heroBackdrop },
        enrichmentActive = { state().enrichmentActive },
        shouldPlayHeroTrailer = { state().shouldPlayTrailer },
        heroTrailerFirstFrameRendered = { state().trailerFirstFrameRendered },
        heroTrailerUrl = { state().trailerUrl },
        heroTrailerAudioUrl = { state().trailerAudioUrl },
        heroTrailerPlaybackKey = { state().trailerPlaybackKey },
        muted = { state().trailerMuted },
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
        modifier = modifier,
        requestWidthPx = requestWidthPx,
        requestHeightPx = requestHeightPx
    )
    ModernHeroGradientLayer(
        bgColor = bgColor,
        isFullScreen = isFullScreen,
        trailerPlaying = {
            val current = state()
            current.shouldPlayTrailer && current.trailerFirstFrameRendered
        },
        modifier = modifier
    )
}

@Composable
internal fun ModernHeroMediaLayer(
    heroBackdrop: () -> String?,
    enrichmentActive: () -> Boolean,
    shouldPlayHeroTrailer: () -> Boolean,
    heroTrailerFirstFrameRendered: () -> Boolean,
    heroTrailerUrl: () -> String?,
    heroTrailerAudioUrl: () -> String?,
    heroTrailerPlaybackKey: () -> String?,
    muted: () -> Boolean,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val shouldPlay by remember { derivedStateOf { shouldPlayHeroTrailer() } }
    val trailerRendered by remember { derivedStateOf { heroTrailerFirstFrameRendered() } }
    val transitionProgressState = animateFloatAsState(
        targetValue = if (shouldPlay && trailerRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "heroBackdropTrailerCrossfadeProgress"
    )
    val localContext = LocalContext.current

    // Backdrop URL is managed upstream (heroSceneStateLambda freezes it
    // during rapid nav / scroll). Only update when enrichment is not active
    val rawBackdrop by remember { derivedStateOf { heroBackdrop() } }
    val enriching by remember { derivedStateOf { enrichmentActive() } }
    var displayedBackdrop by remember { mutableStateOf(HeroBackdropState.lastDisplayedUrl ?: heroBackdrop()) }
    val resolvedDisplayedBackdrop = resolveDisplayedHeroBackdrop(
        requestedBackdrop = rawBackdrop,
        displayedBackdrop = displayedBackdrop,
        enrichmentActive = enriching
    )
    if (resolvedDisplayedBackdrop != displayedBackdrop) {
        displayedBackdrop = resolvedDisplayedBackdrop
    }
    val imageModel = remember(
        localContext,
        displayedBackdrop,
        requestWidthPx,
        requestHeightPx
    ) {
        displayedBackdrop?.let {
            val variantWidth = heroImageVariantWidth(requestWidthPx)
            ImageRequest.Builder(localContext)
                .data(heroImageSourceUrl(it))
                .size(width = variantWidth, height = maxOf(requestHeightPx, variantWidth * 9 / 16))
                .build()
        }
    }
    // Keep HeroBackdropState in sync for navigation transitions.
    LaunchedEffect(displayedBackdrop) {
        HeroBackdropState.update(displayedBackdrop)
    }

    Box(modifier = modifier) {
        androidx.compose.animation.Crossfade(
            targetState = imageModel,
            animationSpec = tween(durationMillis = 400),
            label = "heroBackdropCrossfade"
        ) { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f - transitionProgressState.value
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        if (shouldPlay) {
            val trailerUrlVal = heroTrailerUrl()
            val playbackKeyVal = heroTrailerPlaybackKey()
            val audioUrlVal = heroTrailerAudioUrl()
            val mutedVal = muted()
            key(playbackKeyVal ?: trailerUrlVal) {
                TrailerPlayer(
                    trailerUrl = trailerUrlVal,
                    trailerAudioUrl = audioUrlVal,
                    isPlaying = true,
                    onEnded = onTrailerEnded,
                    onFirstFrameRendered = onFirstFrameRendered,
                    muted = mutedVal,
                    cropToFill = true,
                    overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = transitionProgressState.value
                        }
                )
            }
        }
    }
}

@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    isFullScreen: () -> Boolean,
    trailerPlaying: () -> Boolean = { false },
    modifier: Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gradientAlpha by animateFloatAsState(
        targetValue = if (trailerPlaying()) 0f else 1f,
        animationSpec = tween(durationMillis = 480),
        label = "heroTrailerGradientFade"
    )
    Box(
        modifier = modifier
            .graphicsLayer { alpha = gradientAlpha }
            .drawWithCache {
                val fullScreen = isFullScreen()
                val horizontalFadeEndX = size.width * if (fullScreen) 0.65f else 0.45f
                val colorStops = if (fullScreen) {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.90f),
                        0.46f to bgColor.copy(alpha = 0.80f),
                        0.76f to bgColor.copy(alpha = 0.42f),
                        1.0f to Color.Transparent
                    )
                } else {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.86f),
                        0.46f to bgColor.copy(alpha = 0.56f),
                        0.76f to bgColor.copy(alpha = 0.16f),
                        1.0f to Color.Transparent
                    )
                }
                val horizontalGradient = if (isRtl) {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = size.width,
                        endX = size.width - horizontalFadeEndX
                    )
                } else {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = 0f,
                        endX = horizontalFadeEndX
                    )
                }

                // Folder heroes use a shorter, right-aligned backdrop. Start
                // its bottom fade earlier so the image blends into the rows
                // below instead of ending with a nearly hard edge.
                val bottomStripStartY = size.height * if (fullScreen) 0.64f else 0.58f
                val verticalGradient = Brush.verticalGradient(
                    colorStops = if (fullScreen) {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.30f to bgColor.copy(alpha = 0.35f),
                            0.60f to bgColor.copy(alpha = 0.75f),
                            1.0f to bgColor
                        )
                    } else {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.40f to bgColor.copy(alpha = 0.25f),
                            0.75f to bgColor.copy(alpha = 0.65f),
                            1.0f to bgColor
                        )
                    },
                    startY = bottomStripStartY,
                    endY = size.height
                )

                onDrawBehind {
                    // 1. Horizontal fade (reversed in RTL)
                    val rectLeft = if (isRtl) size.width - horizontalFadeEndX else 0f
                    drawRect(
                        brush = horizontalGradient,
                        topLeft = Offset(rectLeft, 0f),
                        size = Size(horizontalFadeEndX, size.height)
                    )
                    
                    // 2. Bottom vertical strip
                    drawRect(
                        brush = verticalGradient,
                        topLeft = Offset(0f, bottomStripStartY),
                        size = Size(size.width, size.height - bottomStripStartY)
                    )
                }
            }
    )
}

@Composable
internal fun HeroTitleBlock(
    previewProvider: () -> HeroPreview?,
    enrichmentActive: () -> Boolean = { false },
    portraitMode: Boolean,
    trailerPlaying: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val currentPreview = previewProvider()
    val isEnriching = enrichmentActive()
    
    var stablePreview by remember { mutableStateOf<HeroPreview?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { Pair(previewProvider(), enrichmentActive()) }.collect { (p, e) ->
            if (!e && p != null) {
                if (stablePreview != p) stablePreview = p
            } else if (e) {
                if (stablePreview != null) stablePreview = null
            }
        }
    }

    val displayPreview = if (!isEnriching && currentPreview != null) currentPreview else stablePreview
    if (displayPreview == null) return
    
    Box(
        modifier = modifier.fillMaxWidth(modernHeroTextWidthFraction()),
        contentAlignment = Alignment.BottomStart
    ) {
        HeroTitleContent(
            previewProvider = { displayPreview },
            portraitMode = portraitMode,
            trailerPlaying = trailerPlaying
        )
    }
}

@Composable
private fun HeroTitleContent(
    previewProvider: () -> HeroPreview?,
    portraitMode: Boolean,
    trailerPlaying: () -> Boolean = { false }
) {
    val preview = previewProvider() ?: return
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = 1f
    // The hero is vertically dense on TV. Tighten only the gaps between its
    // title, metadata, credits and synopsis; do not reduce the synopsis text.
    val titleSpacing = if (portraitMode) 5.dp else 6.dp
    val metaSpacing = 8.dp * metaScale
    val imdbMetaSpacing = 4.dp * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }

    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    val imdbLogoModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.imdb_logo_2016)
            .build()
    }

    val trailerPlayingValue = trailerPlaying()
    val metaAlpha by animateFloatAsState(
        targetValue = if (trailerPlayingValue) 0f else 1f,
        animationSpec = tween(durationMillis = 480),
        label = "heroMetaFade"
    )
    val scaledTitleStyle = remember(headlineLarge, titleScale) {
        headlineLarge.copy(
            fontSize = headlineLarge.fontSize * titleScale,
            lineHeight = headlineLarge.lineHeight * titleScale
        )
    }
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = ((bodyMedium.fontSize * descriptionScale).value - MODERN_HERO_SYNOPSIS_FONT_REDUCTION.value).sp,
            lineHeight = ((bodyMedium.lineHeight * descriptionScale).value - MODERN_HERO_SYNOPSIS_FONT_REDUCTION.value).sp
        )
    }
    val titleText = remember(preview.title) {
        formatHeroTitleLine(
            title = preview.title,
        )
    }

    Column(
        modifier = (if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier)
            .graphicsLayer { alpha = metaAlpha },
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        var logoLoadFailed by remember(preview.logo) { mutableStateOf(false) }
        val showLogo = !preview.logo.isNullOrBlank() && !logoLoadFailed
        if (showLogo) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                onError = { logoLoadFailed = true },
                modifier = Modifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else if (preview.title.isNotBlank()) {
            Text(
                text = titleText,
                style = scaledTitleStyle,
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val strStatusEnded = stringResource(if (preview.isSeries) R.string.series_status_ended else R.string.movie_status_ended)
        val strStatusContinuing = stringResource(if (preview.isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
        val strStatusCurrent = stringResource(if (preview.isSeries) R.string.series_status_current else R.string.movie_status_current)
        val strStatusCancelled = stringResource(if (preview.isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
        val strStatusReleased = stringResource(if (preview.isSeries) R.string.series_status_released else R.string.movie_status_released)
        val strStatusPlanned = stringResource(if (preview.isSeries) R.string.series_status_planned else R.string.movie_status_planned)
        val strStatusRumored = stringResource(if (preview.isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
        val strStatusInProduction = stringResource(if (preview.isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
        val strStatusPostProduction = stringResource(if (preview.isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
        val secondaryMeta = remember(
            preview.secondaryHighlightText,
            preview.ageRatingText,
            preview.statusText,
            preview.languageText
        ) {
            ModernHeroSecondaryMeta(
                highlightText = preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() },
                ageRating = preview.ageRatingText?.trim()?.takeIf { it.isNotBlank() },
                status = when (preview.statusText?.trim()?.lowercase()) {
                    "ended" -> strStatusEnded.uppercase()
                    "continuing", "returning series" -> strStatusContinuing.uppercase()
                    "current" -> strStatusCurrent.uppercase()
                    "cancelled", "canceled" -> strStatusCancelled.uppercase()
                    "released" -> strStatusReleased.uppercase()
                    "planned" -> strStatusPlanned.uppercase()
                    "rumored" -> strStatusRumored.uppercase()
                    "in production" -> strStatusInProduction.uppercase()
                    "post production" -> strStatusPostProduction.uppercase()
                    else -> preview.statusText?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
                },
                details = buildList {
                    preview.languageText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            )
        }

        val secondaryHighlightText = secondaryMeta.highlightText
        val ageRatingBadge = secondaryMeta.ageRating
        val statusBadge = secondaryMeta.status
        val secondaryDetails = secondaryMeta.details
        val hasSecondaryBadge = ageRatingBadge != null || statusBadge != null
        val showImdbInPrimary = !preview.isSeries && !hasSecondaryBadge && !preview.imdbText.isNullOrBlank()
        val showImdbInPrimaryWithHighlight = showImdbInPrimary && secondaryHighlightText == null
        val showImdbInSecondary = !preview.imdbText.isNullOrBlank() &&
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)

        Row(
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = metaAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            val leadingMetaText = remember(preview.contentTypeText, preview.seasonCount, preview.genres, preview.yearText) {
                buildList {
                    preview.contentTypeText?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.seasonCount?.takeIf { it > 0 }?.let { count ->
                        add("$count ${if (count == 1) "saison" else "saisons"}")
                    }
                    preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.yearText?.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(separator = " • ")
            }
            val hasLeadingMeta = leadingMetaText.isNotBlank()

            val runtimeText = preview.runtimeText
            val imdbText = preview.imdbText
            val hasTrailingMeta = !runtimeText.isNullOrBlank() ||
                showImdbInPrimaryWithHighlight

            if (hasLeadingMeta) {
                Text(
                    text = leadingMetaText,
                    style = labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) {
                    HeroMetaHorizontalDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!runtimeText.isNullOrBlank()) {
                        Text(
                            text = runtimeText,
                            style = labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (showImdbInPrimaryWithHighlight && !imdbText.isNullOrBlank()) {
                        HeroImdbMeta(
                            imdbText = imdbText,
                            imdbLogoModel = imdbLogoModel,
                            textStyle = labelMedium,
                            textColor = NuvioColors.TextSecondary,
                            logoSize = 30.dp * metaScale,
                            spacing = imdbMetaSpacing
                        )
                    }
                }
            }
        }

        if (secondaryHighlightText != null || ageRatingBadge != null || showImdbInSecondary || statusBadge != null || secondaryDetails.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = metaAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metaSpacing)
            ) {
                val semiBoldLabelMedium = remember(labelMedium) { labelMedium.copy(fontWeight = FontWeight.SemiBold) }
        secondaryHighlightText?.let { text ->
                    Text(
                        text = text,
                        style = semiBoldLabelMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryHighlightText != null && (hasSecondaryBadge || showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaHorizontalDivider(metaScale)
                }
                if (ageRatingBadge != null && statusBadge != null) {
                    HeroCombinedMetaBadge(
                        leftText = ageRatingBadge,
                        rightText = statusBadge,
                        textStyle = labelMedium,
                        contentColor = NuvioColors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && (showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaHorizontalDivider(metaScale)
                }
                if (showImdbInSecondary) {
                    HeroImdbMeta(
                        imdbText = preview.imdbText.orEmpty(),
                        imdbLogoModel = imdbLogoModel,
                        textStyle = labelMedium,
                        textColor = NuvioColors.TextSecondary,
                        logoSize = 30.dp * metaScale,
                        spacing = imdbMetaSpacing
                    )
                }
                if (showImdbInSecondary && secondaryDetails.isNotEmpty()) {
                    HeroMetaHorizontalDivider(metaScale)
                }
                secondaryDetails.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = labelMedium,
                        color = NuvioColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < secondaryDetails.lastIndex) {
                        HeroMetaHorizontalDivider(metaScale)
                    }
                }
            }
        }

        val actorsLine = remember(preview.cast) {
            preview.cast
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString(", ")
                .takeIf(String::isNotBlank)
        }
        val directorLine = remember(preview.director) {
            preview.director
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString(", ")
                .takeIf(String::isNotBlank)
        }
        if (actorsLine != null || directorLine != null) {
            Column(
                modifier = Modifier.graphicsLayer { alpha = metaAlpha },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                actorsLine?.let { line ->
                    HeroCreditLine(label = "Acteurs", value = line)
                }
                directorLine?.let { line ->
                    HeroCreditLine(label = "Réalisateur", value = line)
                }
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            FixedHeroSynopsis(
                description = description.toShortSynopsis(),
                modifier = Modifier
                    .graphicsLayer { alpha = metaAlpha },
                textStyle = scaledDescriptionStyle
            )
        }
    }
}

@Composable
private fun FixedHeroSynopsis(
    description: String,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium
) {
    Box(
        modifier = modifier
            // Keep the fixed-height synopsis slot, but use the complete hero
            // text width so long descriptions gain room toward the right.
            .fillMaxWidth()
            .height(MODERN_HERO_SYNOPSIS_HEIGHT)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.34f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = description.toShortSynopsis(),
                style = textStyle,
                color = NuvioColors.TextPrimary,
                maxLines = modernHeroSynopsisMaxLines(),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun formatHeroTitleLine(
    title: String
): String = title.replace(Regex("""\\s*\\(\\d{4}(?:\\s*[-–]\\s*\\d{4})?\\)\\s*$"""), "").trim()

@Composable
private fun HeroCreditLine(
    label: String,
    value: String
) {
    Text(
        text = "$label : $value",
        style = MaterialTheme.typography.bodySmall,
        color = NuvioColors.TextPrimary
    )
}

@Composable
private fun HeroImdbMeta(
    imdbText: String,
    imdbLogoModel: Any,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    logoSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        AsyncImage(
            model = imdbLogoModel,
            contentDescription = stringResource(R.string.cd_imdb),
            modifier = Modifier.size(logoSize),
            contentScale = ContentScale.Fit
        )
        Text(
            text = imdbText,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroCombinedMetaBadge(
    leftText: String,
    rightText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    val dividerColor = contentColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(1.dp, dividerColor),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val semiBoldStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = leftText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(12.dp)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) },
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaHorizontalDivider(scale: Float) {
    Box(
        modifier = Modifier
            .size((4.dp * scale).coerceAtLeast(2.dp))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioColors.TextTertiary.copy(alpha = 0.78f))
    )
}
