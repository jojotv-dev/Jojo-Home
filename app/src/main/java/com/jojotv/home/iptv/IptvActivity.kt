package com.jojotv.home.iptv

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnNextLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.displayName
import androidx.media3.ui.PlayerView
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.IptvLivePlayerHandoff
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import com.jojotv.home.HomeMediaRules
import com.jojotv.home.HomeWatchProgressBridge
import com.jojotv.home.MainActivity
import com.jojotv.home.NuvioPlayerActivity
import com.jojotv.home.OnlineAppIconLoader
import com.jojotv.home.SmbConnectionConfig
import com.jojotv.home.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.Calendar
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Native TV shell using the same view conventions and palette as Jojo Home. */
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class IptvActivity : ComponentActivity() {
    @Inject lateinit var playerSettingsDataStore: PlayerSettingsDataStore
    private var watchProgressBridge: HomeWatchProgressBridge? = null
    private val work = Executors.newSingleThreadExecutor()
    private val libraryWork = Executors.newSingleThreadExecutor()
    private val progressWork = Executors.newSingleThreadExecutor()
    private var detailProgressTask: Future<*>? = null
    private var vodCardProgressTask: Future<*>? = null
    private val pendingFavoriteChanges = mutableSetOf<String>()
    private var historyMutationPending = false
    private val catalogueWork = Executors.newSingleThreadExecutor()
    private val catalogueCacheWork = Executors.newSingleThreadExecutor()
    @Volatile private var catalogueGeneration = 0
    private var catalogueScope: IptvRequestScope? = null
    private var catalogueTask: Future<*>? = null
    private var catalogueCacheTask: Future<*>? = null
    @Volatile private var catalogueMetadataTask: Future<*>? = null
    private var catalogueSelectionSettings = emptyMap<String, String>()
    private val cataloguePreviews = LruCache<Triple<String, ContentType, String>, List<IptvItem>>(12)
    private val images = Executors.newFixedThreadPool(2)
    private val guideWork = Executors.newSingleThreadExecutor()
    private val searchWork = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())
    private val providers = ConcurrentHashMap<String, IptvProvider>()
    private val bitmaps = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private lateinit var store: IptvStore
    private var homeStartFromBeginning = false
    private var homePlayerPackage: String? = null
    private lateinit var shell: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var groups: LinearLayout
    private lateinit var groupScroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var contentHost: FrameLayout
    private var pageFocus: View? = null
    private lateinit var backdrop: ImageView
    private lateinit var info: LinearLayout
    private lateinit var informationHeader: LinearLayout
    private lateinit var vodHero: FrameLayout
    private lateinit var vodHeroArtwork: ImageView
    private lateinit var vodHeroEditorial: LinearLayout
    private lateinit var vodHeroTitle: TextView
    private lateinit var vodHeroTitleSlot: FrameLayout
    private lateinit var vodHeroLogo: ImageView
    private lateinit var vodHeroSeriesStatus: TextView
    private var vodHeroLogoKey = ""
    private var vodHeroBoundItem: IptvItem? = null
    private val mediaWork = Executors.newFixedThreadPool(2)
    private val mediaDetails = LruCache<String, IptvMediaPresentation.Details>(24)
    private var mediaRequest: Runnable? = null
    private var mediaScope: IptvRequestScope? = null
    private var mediaRequestKey = ""
    private var detailEpisodes = emptyList<IptvItem>()
    private var detailLoaded = false
    private var detailProgress = emptyList<com.nuvio.tv.domain.model.WatchProgress>()
    private var detailProgressGeneration = 0
    private val seriesEpisodeProgressFrames = linkedMapOf<Pair<Int, Int>, FrameLayout>()
    private var vodPopup: android.widget.PopupWindow? = null
    private var calendarRefresh: Runnable? = null
    private lateinit var vodHeroMetadata: TextView
    private lateinit var vodHeroCredits: TextView
    private lateinit var vodHeroSynopsis: TextView
    private lateinit var vodHeroSynopsisBlock: LinearLayout
    private lateinit var vodHeroTools: LinearLayout
    private lateinit var vodHeroSearch: ImageButton
    private lateinit var vodHeroAction: ImageButton
    private var vodCards: RecyclerView? = null
    private val vodCardItems = mutableListOf<IptvItem>()
    private var vodFocusRequest = 0
    private var vodCardProgressGeneration = 0
    private val vodCardProgress = mutableMapOf<Pair<String, String>, List<com.nuvio.tv.domain.model.WatchProgress>>()
    private val vodHeroDetails = LruCache<String, IptvItem>(24)
    private var vodHeroItemId = ""
    private var vodHeroRequest: Runnable? = null
    private var vodHeroScope: IptvRequestScope? = null
    private var selectedSeriesSeason: Int? = null
    private var focusedSeriesEpisode = ""
    private var vodSeasonScroll: HorizontalScrollView? = null
    private var vodEpisodeScroll: HorizontalScrollView? = null
    private data class PlaybackFocus(val tag: String, val accountId: String, val section: Int, val detailId: String?)
    private var playbackReturnFocus: PlaybackFocus? = null
    private var restoringPlaybackFocus = false
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewView: PlayerView
    private lateinit var previewPlaceholder: TextView
    private lateinit var previewFullscreenControls: ComposeView
    private var previewControlsViewModel: PlayerViewModel? = null
    private var previewControlsNavigation: NavHostController? = null
    private var previewControlsToken: String? = null
    private var previewAfterFullscreen: (() -> Unit)? = null
    private var previewFullscreen = false
    private var previewFullscreenClosing = false
    private var previewMiniBounds: Rect? = null
    private var previewMiniLayoutParams: LinearLayout.LayoutParams? = null
    private var previewFullscreenInfoVisibility = View.VISIBLE
    private var previewFullscreenPendingItemId: String? = null
    private var previewPlayer: ExoPlayer? = null
    private var previewTrackSelector: DefaultTrackSelector? = null
    private var previewStreamUrl = ""
    private var previewStreamHeaders = emptyMap<String, String>()
    private var previewPreparing = false
    private var previewListener: Player.Listener? = null
    private var previewHistoryRequest: Runnable? = null
    private var previewItem: IptvItem? = null
    private var previewPendingItem: IptvItem? = null
    private var previewGeneration = 0
    private var previewResumePending = false
    private var activityResumed = false
    private var settingsPage = "general"
    private var exitBackAt = 0L
    private var exitToast: Toast? = null
    private var textScale = 1f
    private var parentalUnlockedUntil = 0L
    private var previewFocusRequest: Runnable? = null
    private var previewFullscreenRequest: Runnable? = null
    private val previewWatchClock = IptvWatchClock()
    private val watchTimeWork = Executors.newSingleThreadExecutor()
    private var guideOverlay = false
    private var pendingEpisode: IptvItem? = null
    private var pendingLiveNavigation: Pair<String, String>? = null
    private var pendingSyncedRefresh = false
    private var accountSettingsSnapshot: Pair<Map<String, String>, Map<String, Map<String, String>>>? = null
    private var episodeSequence = emptyList<IptvItem>()
    private val previewMediaSources by lazy { PlayerMediaSourceFactory(this) }
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private val navLabels = mutableListOf<TextView>()
    private val navRows = mutableListOf<View>()
    private lateinit var accountNavRow: LinearLayout
    private var accountPage: FrameLayout? = null
    private var accountPageBack: (() -> Unit)? = null
    private var displayedAccounts = emptyList<IptvAccount>()
    private var displayedAccount: IptvAccount? = null
    private var accountEntry: (() -> Unit)? = null
    private var accountChosen = false
    private var accountSwitching = false
    private var avatarCatalog = emptyList<IptvAvatar>()
    private var avatarCatalogLoading = false
    private var avatarCatalogLoaded = false
    private var avatarCatalogError = ""
    private var avatarCatalogUpdated: (() -> Unit)? = null
    private val avatarFrames = mutableMapOf<FrameLayout, IptvAccount>()
    private val groupRows = mutableListOf<View>()
    private val groupRowPaints = mutableMapOf<View, () -> Unit>()
    private var sources = emptyList<IptvSource>()
    private var companionRevision = 0L
    private var section = 1
    private var sourceId = ""
    private val collapsedProviders = mutableSetOf<String>()
    private var category = ""
    private var categoryTitle = "Toutes les chaînes"
    private var visibleItems = emptyList<IptvItem>()
    private val groupCategories = ConcurrentHashMap<Pair<String, ContentType>, List<IptvCategory>>()
    private val groupCategoryLoads = mutableSetOf<Pair<String, ContentType>>()
    private var contextPanel: Dialog? = null
    private var detail: IptvItem? = null
    @Volatile private var requestVersion = 0
    private var navigationMode = 0
    private var navigationInputVersion = 0
    private var activeGroup: View? = null
    private var pendingGroupEntry: Pair<Any, () -> Boolean>? = null
    private var rememberedFocus = ""
    private var livePlayerFocus = ""
    private var restoreLiveFocus = false
    private var ready = false
    private val companionServer: IptvCompanionServer? get() = IptvCompanionRuntime.server
    private var recordingPlayback: IptvRecordingPlayback? = null
    private var pendingRecordingFolder: String? = null
    private var recordingFolderResult: Pair<String, Boolean>? = null
    private var companionError = ""
    private var sourcePage: IptvContentPage? = null
    @Volatile private var playlistUpdateRunning = false
    private val playlistWork = Executors.newSingleThreadExecutor()
    private val playlistCounts = mutableMapOf<String, Map<ContentType, Int>>()
    private var playlistUpdateScope: IptvRequestScope? = null
    private var playlistStartupPending = true
    private var playlistUpdateChecking = false
    private var playlistNextCheck = 0L
    private var enabledPlaylistIds = emptySet<String>()
    private var playlistCatalogueChanged = false
    @Volatile private var epgUpdateRunning = false
    private var updatePhoneSetup: (() -> Unit)? = null
    private var phoneContentSelection: Triple<SourceType, Boolean, Boolean>? = null
    private var searchQuery = ""
    private var searchField: EditText? = null
    private var searchKeyboardOpen = false
    private var searchReturnSection = 1
    private data class SavedSearchReturn(val type: ContentType, val history: Boolean, val focusId: String)
    private var searchReturnSaved: SavedSearchReturn? = null
    @Volatile private var searchGeneration = 0
    private var searchDebounce: Runnable? = null
    private var searchScope: IptvRequestScope? = null
    private var artworkScope = IptvRequestScope(IptvHttp.artworkClient)
    private val pendingArtwork = mutableMapOf<ImageView, () -> Unit>()
    private val artworkLoads = mutableMapOf<ImageView, () -> Unit>()
    private val artworkViewScopes = mutableMapOf<ImageView, IptvRequestScope>()
    private val artworkPlaceholders = mutableMapOf<ImageView, Pair<Int, ImageView.ScaleType>>()
    private val cardRows = mutableMapOf<FrameLayout, () -> Unit>()
    private val cardItems = mutableMapOf<FrameLayout, MutableList<IptvItem>>()
    private val searchTasks = mutableListOf<Future<*>>()
    private val searchProviders = ConcurrentHashMap<String, Pair<IptvSource, IptvProvider>>()
    private val searchTypes = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
    private val searchItems = linkedMapOf<Pair<String, ContentType>, List<IptvItem>>()
    private var searchPrograms = emptyList<Pair<IptvItem, EpgProgram>>()
    private var searchProgramsPending = false
    private var searchProgramsView: LinearLayout? = null
    private val searchPages = mutableMapOf<Pair<String, ContentType>, Int?>()
    private val searchPending = mutableSetOf<Pair<String, ContentType>>()
    private val searchFailures = mutableSetOf<Pair<String, ContentType>>()
    private val searchGroups = mutableMapOf<Pair<String, ContentType>, LinearLayout>()
    private val searchTypeGroups = linkedMapOf<ContentType, LinearLayout>()
    private val searchSelectedSources = mutableMapOf<ContentType, String>()
    private var searchEmptyView: TextView? = null
    private var focusSearchResults = false
    private var listMode = "catalogue"
    private var savedType = ContentType.LIVE
    private val syncRefresh = object : Runnable {
        override fun run() {
            if (!activityResumed || isDestroyed) return
            IptvRecordingSync.request(this@IptvActivity)
            if (!previewFullscreen) {
                checkPlaylistUpdates()
                refreshSyncedLibrary()
                IptvStore.requestSync(this@IptvActivity, Runnable { handler.post {
                    refreshSyncedLibrary(true)
                } })
            }
            handler.postDelayed(this, 15_000)
        }
    }
    private fun refreshSyncedLibrary(changed: Boolean = false) {
        if (!activityResumed || !ready || isDestroyed) return
        if (historyMutationPending) {
            pendingSyncedRefresh = pendingSyncedRefresh || changed
            return
        }
        if (previewFullscreen) {
            pendingSyncedRefresh = pendingSyncedRefresh || changed
            return
        }
        if (recordingPage != null || contextPanel != null || sourcePage != null || vodPopup?.isShowing == true) {
            pendingSyncedRefresh = pendingSyncedRefresh || changed
            return
        }
        if (accountPage != null) {
            if (displayedAccounts != store.accounts()) showAccountsPage()
            return
        }
        val account = store.selectedAccount()
        val previous = displayedAccount
        if (previous != null && store.resolveAccountId(previous.id) != account?.id) {
            accountChosen = false
            refreshAccountSidebar()
            showAccountsPage()
            return
        }
        if (account != previous) refreshAccountSidebar()
        val nextSettings = store.settingsSnapshot()
        val settingsChanged = nextSettings != accountSettingsSnapshot
        accountSettingsSnapshot = nextSettings
        if (settingsChanged) { applyTheme(); applyTextSize() }
        if (detail != null && detail?.let { store.playlistEnabled(it.sourceId) } != false) {
            pendingSyncedRefresh = pendingSyncedRefresh || changed || settingsChanged
            return
        }
        val refresh = changed || settingsChanged || pendingSyncedRefresh
        pendingSyncedRefresh = false
        val updated = store.sources()
        val enabledIds = updated.filter { store.playlistEnabled(it.id) }.map { it.id }.toSet()
        if (updated != sources || enabledIds != enabledPlaylistIds || playlistCatalogueChanged) {
            val savedMode = listMode.takeIf { it in listOf("favorites", "history") }
            val previousSavedType = savedType
            sources = updated
            enabledPlaylistIds = enabledIds; playlistCatalogueChanged = false
            invalidateProviders()
            showSection(section, true)
            if (savedMode != null && section in listOf(1, 2, 3, 5)) showSaved(previousSavedType, savedMode == "history")
        } else if (listMode in listOf("favorites", "history") && store.saved(savedType, listMode == "history") != visibleItems) {
            showSaved(savedType, listMode == "history")
        } else if (refresh && section == 0 && searchQuery.isBlank()) showSearchHistory()
        else if (refresh && section in 1..3) refreshOrganization()
    }
    private var epgVersion = 0
    private var epgRequest: Runnable? = null
    private var guideActive = false
    private var liveChannelList = false
    private var guideScope = IptvRequestScope(IptvHttp.client)
    private var guideDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private var guideOffset = 0
    private var guideHorizontal: HorizontalScrollView? = null
    private var guideNowPosition = 0
    private var remoteGuideChannel: IptvItem? = null
    private var remoteGuideProgram: EpgProgram? = null
    private var remoteGuidePage: ((Boolean) -> Boolean)? = null
    private val remoteGuidePress = IptvRemotePress(android.view.ViewConfiguration.getLongPressTimeout().toLong())
    private val remoteGuidePressTargets = mutableMapOf<Int, Triple<Long, View, Any?>>()
    private val remoteGuideNumber = IptvChannelNumberInput()
    private var remoteGuideNumberRequest: Runnable? = null
    private val guidePrograms = LruCache<String, Pair<Long, List<EpgProgram>>>(96)
    private var guideLayoutListener: View.OnLayoutChangeListener? = null
    private var recordingsSignature = ""
    private var recordingsTab = "files"
    private var recordingPage: LinearLayout? = null
    private var recordingPageBack: ((Boolean) -> Unit)? = null
    private val recordingsRefresh = object : Runnable {
        override fun run() {
            if (section == 4 && ready) {
                refreshRecordings()
                handler.postDelayed(this, 3000)
            }
        }
    }
    private var accent = Color.rgb(66, 165, 245)
    private var contrast = Color.WHITE
    private var selectedTextColor: Int? = null
    private var themeBackground = Color.rgb(18, 18, 18)
    private var themeSurface: Int? = null
    private var themeRaisedSurface: Int? = null
    private var themeForeground = Color.WHITE
    private var homeAccent = accent
    private var homeBackground = themeBackground
    private var interfaceOpacity = 127
    private var focusNeedsOutline = false
    private var themeColorVersion = 0
    private val pendingThemeColors = mutableMapOf<String, Pair<Int, String>>()
    private val titles = listOf("Rechercher", "TV", "Films", "Séries", "Enregistrements", "Ma liste", "Paramètres")
    private val icons = listOf(R.drawable.ic_header_search, R.drawable.ic_home_category_live_signal,
        R.drawable.ic_home_category_clapper, R.drawable.ic_home_category_series_bottom,
        android.R.drawable.presence_video_busy, R.drawable.ic_menu_favorite_add, R.drawable.ic_settings)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        accent = intent.getIntExtra("accent", accent)
        contrast = intent.getIntExtra("contrast", contrast)
        themeBackground = intent.getIntExtra("background", themeBackground)
        homeAccent = accent
        homeBackground = themeBackground
        themeForeground = readableThemeForeground(themeBackground)
        focusNeedsOutline = ColorUtils.calculateContrast(accent, surface(.07f)) < 1.5
        section = state?.getInt("section", 1) ?: 1
        sourceId = state?.getString("source").orEmpty()
        collapsedProviders.addAll(state?.getStringArrayList("collapsedProviders").orEmpty())
        rememberedFocus = state?.getString("rememberedFocus").orEmpty()
        livePlayerFocus = state?.getString("livePlayerFocus").orEmpty()
        pendingRecordingFolder = state?.getString("pendingRecordingFolder")
        recordingFolderResult = state?.getString("recordingFolderResult")?.let {
            it to (state?.getBoolean("recordingFolderStorage", false) ?: false)
        }
        store = IptvStore(this)
        createShell()
        val loading = text("Chargement des comptes…", 20).apply { gravity = Gravity.CENTER }
        contentHost.addView(loading, FrameLayout.LayoutParams(-1, -1))
        showNavigation(0); groupScroll.visibility = View.GONE
        previewFrame.visibility = View.GONE
        navRows.forEach { it.isEnabled = false; it.isFocusable = false }
        async({
            var step = "lecture du stockage IPTV"
            try {
                store.loadWithNasRecovery()
                val cachedAvatars = store.preloadAccountAvatars()
                step = "restauration des rappels et des enregistrements"
                IptvRecordingScheduler.restore(this, store)
                step = "préparation du guide TV"
                store.sources().also { values -> if (store.booleanSetting("epgUpdateOnStart", true)) values.forEach { store.clearEpgCache(it.id) } } to cachedAvatars
            } catch (problem: Exception) {
                // Report only types and frames: exception messages can contain provider credentials.
                val types = generateSequence<Throwable>(problem) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.name }
                val frames = problem.stackTrace.take(12).joinToString(" | ")
                android.util.Log.e("JojoHomeIptv", "Startup failed: $step; $types; $frames")
                throw IllegalStateException(if (problem is IllegalStateException && problem.cause is javax.crypto.AEADBadTagException)
                    problem.message else "Impossible de terminer la $step.", problem)
            }
        }, failure = { problem ->
            (loading.parent as? ViewGroup)?.removeView(loading)
            shell.visibility = View.VISIBLE
            message("Chargement IPTV indisponible", problem)
            results.addView(button("Réessayer") { recreate() })
            if (problem.startsWith("Le stockage IPTV local est illisible.")) {
                results.addView(button("Récupérer les données IPTV depuis le NAS") {
                    dialog("Récupérer les fournisseurs, favoris, historique et paramètres depuis le NAS ? La copie locale illisible sera conservée à part.", listOf(
                        "Annuler" to {}, "Récupérer" to {
                            async({ store.recoverFromNas() }) { recreate() }
                        }))
                })
            }
            wireVerticalFocus(results)
            firstContent()?.requestFocus()
        }) { (values, cachedAvatars) ->
            (loading.parent as? ViewGroup)?.removeView(loading)
            sources = values
            enabledPlaylistIds = values.filter { store.playlistEnabled(it.id) }.map { it.id }.toSet()
            avatarCatalog = cachedAvatars
            ready = true
            applyTheme(false)
            applyTextSize()
            refreshAccountSidebar()
            loadAccountAvatarCatalog()
            pendingEpisode = store.rememberedItem(state?.getString("pendingEpisode").orEmpty())
            navRows.forEach { it.isEnabled = true; it.isFocusable = true }
            accountEntry = entry@{
                val homeItem = if (state == null) store.rememberedItem(intent.getStringExtra("homeItemId").orEmpty()) else null
                if (homeItem != null && sources.any { it.id == homeItem.sourceId }) {
                    sourceId = homeItem.sourceId
                    homeStartFromBeginning = intent.getBooleanExtra("startFromBeginning", false)
                    homePlayerPackage = intent.getStringExtra(NuvioPlayerActivity.EXTRA_PLAYER_PACKAGE)
                    showSection(5)
                    showSaved(if (homeItem.type == ContentType.EPISODE) ContentType.SERIES else homeItem.type, false)
                    if (intent.getBooleanExtra("homeShowDetails", false) || homeItem.type == ContentType.SERIES)
                        showDetails(homeItem) else play(homeItem)
                    return@entry
                }
                if (state == null) {
                    section = 1
                    collapsedProviders.clear()
                    collapsedProviders.addAll(sources.map { it.id })
                }
                if (sources.none { it.id == sourceId }) sourceId = sources.firstOrNull()?.id.orEmpty()
                showSection(section)
                navRows.getOrNull(section)?.requestFocus()
                if (section == 1 && store.booleanSetting("playLastChannel", false)) {
                    store.saved(ContentType.LIVE, true).firstOrNull()?.let {
                        if (store.booleanSetting("previewEnabled", true)) startPreview(it, false) else play(it)
                    }
                }
                pendingLiveNavigation?.let { (id, action) ->
                    pendingLiveNavigation = null; navigateLivePlayer(id, action)
                }
                resumeRecordingFolder()
            }
            val openLastProfile = state == null && store.rememberLastProfile() && store.selectedAccount() != null
            if (state?.getBoolean("accountChosen", false) == true || openLastProfile) {
                accountChosen = true
                shell.visibility = View.VISIBLE
                accountEntry?.invoke(); accountEntry = null
            } else showAccountsPage()
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("section", section)
        out.putString("source", sourceId)
        out.putString("rememberedFocus", rememberedFocus)
        out.putString("livePlayerFocus", livePlayerFocus)
        out.putStringArrayList("collapsedProviders", ArrayList(collapsedProviders))
        out.putString("pendingEpisode", pendingEpisode?.id)
        out.putBoolean("accountChosen", accountChosen && accountPage == null)
        out.putString("pendingRecordingFolder", pendingRecordingFolder)
        out.putString("recordingFolderResult", recordingFolderResult?.first)
        out.putBoolean("recordingFolderStorage", recordingFolderResult?.second == true)
    }
    override fun onResume() {
        super.onResume()
        activityResumed = true
        val progressBridge = watchProgressBridge ?: HomeWatchProgressBridge.create(this).also { watchProgressBridge = it }
        progressBridge.startAllRawProgress { _, _ ->
            if (activityResumed && ready) {
                if (detail != null) refreshVodProgress() else refreshVodCardProgress()
            }
        }
        restoringPlaybackFocus = playbackReturnFocus != null
        handler.removeCallbacks(syncRefresh); handler.post(syncRefresh)
        IptvCompanionRuntime.start(applicationContext)
        IptvCompanionRuntime.bindActivity(
            onSubmitted = { source, handle -> handler.post { receiveCompanionSource(source, handle) } },
            onServerStarted = { handler.post { updatePhoneSetup?.invoke() } }
        )
        companionError = IptvCompanionRuntime.startError
        if (ready && companionRevision != IptvCompanionRuntime.revision) {
            companionRevision = IptvCompanionRuntime.revision
            sources = store.sources()
            invalidateProviders()
            if (section in 1..3) showSection(section, true) else refreshOrganization()
        }
        updatePhoneSetup?.invoke()
        loadVisibleArtwork()
        if (ready && guideActive && recordingPage == null && sourcePage == null && accountPage == null) renderGuide()
        if (ready && previewResumePending && recordingPage == null && sourcePage == null && accountPage == null) {
            previewResumePending = false
            previewItem?.let { startPreview(it, false) }
        }
        if (ready && detail != null) {
            refreshVodProgress()
            (vodHeroBoundItem ?: detail)?.let(::requestVodPresentation)
        }
        if (ready && detail == null && vodCards != null) refreshVodCardProgress()
        handler.post { restorePlaybackFocus() }
    }
    override fun onPause() {
        cancelGuideNumber()
        exitToast?.cancel(); exitToast = null
        super.onPause()
    }
    override fun onStop() {
        activityResumed = false
        watchProgressBridge?.stop()
        mediaRequest?.let(handler::removeCallbacks); mediaRequest = null
        mediaScope?.cancel(); mediaScope = null; mediaRequestKey = ""
        handler.removeCallbacks(syncRefresh)
        guideScope.cancel()
        previewResumePending = previewResumePending || previewPlayer != null
        stopPreview()
        IptvCompanionRuntime.unbindActivity()
        super.onStop()
    }
    override fun onDestroy() {
        watchProgressBridge?.close(); watchProgressBridge = null
        contextPanel?.dismiss(); contextPanel = null
        sourcePage?.dismiss(false); sourcePage = null
        cancelSearch()
        cancelCatalogue()
        catalogueWork.shutdownNow()
        catalogueCacheWork.shutdownNow()
        progressWork.shutdownNow()
        artworkScope.cancel()
        artworkViewScopes.values.forEach { it.cancel() }; artworkViewScopes.clear()
        vodHeroScope?.cancel()
        mediaScope?.cancel()
        mediaWork.shutdownNow()
        vodPopup?.dismiss()
        pendingArtwork.clear()
        artworkLoads.clear(); artworkPlaceholders.clear(); cardRows.clear(); cardItems.clear()
        searchWork.shutdownNow()
        guideScope.cancel()
        guideWork.shutdownNow()
        stopPreview(true)
        watchTimeWork.shutdown()
        libraryWork.shutdown()
        IptvCompanionRuntime.unbindActivity()
        recordingPlayback?.stop(); recordingPlayback = null
        requestVersion++
        handler.removeCallbacksAndMessages(null)
        work.shutdownNow()
        playlistUpdateScope?.cancel(); playlistWork.shutdownNow()
        images.shutdownNow()
        avatarFrames.clear(); avatarCatalogUpdated = null
        bitmaps.evictAll()
        super.onDestroy()
    }

    private fun createShell() {
        val root = FrameLayout(this)
        root.clipChildren = false
        root.setBackgroundColor(themeBackground)
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .20f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(18), dp(10), dp(14))
            setBackgroundColor(ColorUtils.blendARGB(themeBackground, themeForeground, .06f))
        }
        shell.addView(nav, LinearLayout.LayoutParams(dp(210), -1))
        val brand = text("JojoTV", 21, true)
        brand.setTextColor(accentTextColor())
        brand.setPadding(dp(8), 0, 0, dp(18))
        nav.addView(brand, LinearLayout.LayoutParams(-1, dp(66)))
        titles.forEachIndexed { index, title ->
            if (index == 6) {
                nav.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
                accountNavRow = command("Compte", R.drawable.ic_profile_header) {
                    requestVersion++
                    closeContentPages()
                    showAccountSelection()
                }
                navLabels += accountNavRow.getChildAt(1) as TextView
                accountNavRow.setOnFocusChangeListener { _, focused ->
                    paintCommand(accountNavRow, focused, false)
                    if (focused) showNavigation(0)
                }
                accountNavRow.setOnKeyListener { _, key, event -> handleSidebarKey(key, event) }
                nav.addView(accountNavRow, LinearLayout.LayoutParams(-1, dp(49)))
            }
            val row = command(title, icons[index]) {
                if (accountPage != null && !accountChosen) { focusContentPage(); return@command }
                val restoreSection = accountPage != null
                requestVersion++
                closeContentPages()
                if (index == 6) {
                    if (restoreSection) showSection(section, true)
                    parentalAccess("lockSettings") { showSettingsMenu() }
                }
                else { showSection(index); showNavigation(1); focusGroup() }
            }
            if (index == 4) (row.getChildAt(0) as ImageView).setImageDrawable(recordingSidebarIcon())
            val label = row.getChildAt(1) as TextView
            navLabels += label
            navRows += row
            row.setOnFocusChangeListener { _, focused ->
                paintCommand(row, focused, index == section)
                if (focused) showNavigation(0)
            }
            row.setOnKeyListener { _, key, event -> handleSidebarKey(key, event) }
            nav.addView(row, LinearLayout.LayoutParams(-1, dp(49)))
        }
        // Keep content section indices stable; focus follows the visible sidebar order.
        navRows += accountNavRow
        wireVerticalFocus(nav)
        groupScroll = ScrollView(this).apply {
            isFillViewport = true; isFocusable = false; clipToPadding = false
            isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = false
        }
        groups = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(26), dp(12), dp(16)) }
        groupScroll.addView(groups)
        shell.addView(groupScroll, LinearLayout.LayoutParams(dp(235), -1))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        contentHost = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        contentHost.addView(content, FrameLayout.LayoutParams(-1, -1))
        shell.addView(contentHost, LinearLayout.LayoutParams(0, -1, 1f))
        contentHost.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null && contentHost.hasFocus() && (sourcePage != null || recordingPage != null || accountPage != null))
                pageFocus = focused
        }
        info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        informationHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            elevation = dp(24).toFloat()
        }
        previewFrame = FrameLayout(this).apply {
            id = View.generateViewId(); isFocusable = true
            setBackground(shape(surface(.10f))); clipToOutline = true
            foreground = shape(Color.TRANSPARENT).apply { setStroke(dp(1), surface(.55f)) }
            contentDescription = "Mini lecteur IPTV"
            setOnFocusChangeListener { _, focused -> if (!previewFullscreen) foreground = shape(Color.TRANSPARENT).apply {
                setStroke(dp(if (focused) 3 else 1), if (focused) accentTextColor() else surface(.55f))
            } }
            setOnClickListener { previewItem?.let { play(it, fullscreen = true) } }
            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { firstContent()?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { showNavigation(1); focusGroup(); true }
                    else -> false
                }
            }
        }
        previewView = layoutInflater.inflate(R.layout.iptv_preview_player_view, previewFrame, false) as PlayerView
        previewView.apply {
            useController = false; isFocusable = false
            setShutterBackgroundColor(surface(.10f))
        }
        previewFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        previewPlaceholder = text("", 16).apply {
            gravity = Gravity.CENTER; maxLines = 3
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(surface(.10f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewFrame.addView(previewPlaceholder, FrameLayout.LayoutParams(-1, -1))
        previewFullscreenControls = ComposeView(this).apply {
            visibility = View.GONE
            elevation = dp(48).toFloat()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        root.addView(previewFullscreenControls, FrameLayout.LayoutParams(-1, -1))
        informationHeader.addView(previewFrame, LinearLayout.LayoutParams(dp(340), dp(191)).apply { rightMargin = dp(16) })
        informationHeader.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        informationHeader.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(dp(360), ((right - left - dp(16)) * .70f).toInt()).coerceAtLeast(1)
            if (!previewFullscreen && previewFrame.parent === informationHeader && previewFrame.layoutParams.width != width) {
                previewFrame.layoutParams = (previewFrame.layoutParams as LinearLayout.LayoutParams).apply {
                    this.width = width; height = width * 9 / 16
                }
            }
        }
        createVodHero()
        content.addView(vodHero, LinearLayout.LayoutParams(-1, dp(291)))
        content.addView(informationHeader, LinearLayout.LayoutParams(-1, dp(220)))
        status = text("", 13)
        content.addView(status, LinearLayout.LayoutParams(-1, dp(25)))
        val scroll = ScrollView(this).apply {
            isFocusable = false; clipToPadding = true
            // Bound scrolling rows without clipping the player's fullscreen transition in the header.
            addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                view.clipBounds = Rect(0, 0, right - left, bottom - top)
            }
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { loadVisibleArtwork() }
        root.viewTreeObserver.addOnScrollChangedListener { loadVisibleArtwork() }
    }

    private fun handleSidebarKey(key: Int, event: KeyEvent): Boolean {
        if (key != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // Right returns to the open page; only OK activates the focused sidebar command.
            if (sourcePage != null || recordingPage != null || accountPage != null) focusContentPage()
            else { showNavigation(1); focusGroup() }
        }
        return true
    }

    private fun showNavigation(mode: Int) {
        if (mode != 1) pendingGroupEntry = null
        navigationMode = mode
        nav.visibility = View.VISIBLE
        groupScroll.visibility = if (sourcePage != null || recordingPage != null || accountPage != null ||
            mode == 2 || section in listOf(0, 4, 6)) View.GONE else View.VISIBLE
        val navWidth = dp(if (mode == 0) 210 else 68)
        if (nav.layoutParams.width != navWidth) nav.layoutParams = (nav.layoutParams as LinearLayout.LayoutParams).apply { width = navWidth }
        navLabels.forEach { it.visibility = if (mode == 0) View.VISIBLE else View.GONE }
        (nav.getChildAt(0) as TextView).text = if (mode == 0) "JojoTV" else "TV"
    }
    private fun contentType(): ContentType = when (section) { 2 -> ContentType.MOVIE; 3 -> ContentType.SERIES; else -> ContentType.LIVE }
    private fun focusContentPage(): Boolean {
        val page = sourcePage?.view ?: recordingPage ?: accountPage ?: return false
        val target = pageFocus?.takeIf { it.isShown && it.isEnabled }
            ?: page.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown && it.isEnabled }
        if (target?.requestFocus() != true) return false
        showNavigation(1)
        return true
    }

    private fun closeContentPages() {
        sourcePage?.dismiss(false)
        recordingPageBack?.invoke(false)
        accountPage?.let { (it.parent as? ViewGroup)?.removeView(it) }; accountPage = null
        accountPageBack = null
        pageFocus = null
        content.visibility = View.VISIBLE
        showNavigation(navigationMode)
    }

    private inner class IptvContentPage {
        var view: View? = null
            private set
        var onBack: (() -> Unit)? = null
        var onDismiss: (() -> Unit)? = null
        private var origin: View? = null
        private var secure = false
        private var wasSecure = false
        val isShowing: Boolean get() = sourcePage === this

        fun show(page: View, initialFocus: View?, secure: Boolean) {
            sourcePage?.dismiss(false)
            contextPanel?.dismiss()
            origin = currentFocus
            previewResumePending = previewResumePending || previewPlayer != null
            stopPreview()
            cancelGuideNumber()
            this.secure = secure
            wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            view = page; sourcePage = this@IptvContentPage; pageFocus = null
            content.visibility = View.INVISIBLE
            contentHost.addView(page, FrameLayout.LayoutParams(-1, -1))
            showNavigation(1)
            if (initialFocus?.requestFocus() != true) focusContentPage()
        }

        fun dismiss(restoreFocus: Boolean = true, returningToParent: Boolean = false) {
            if (!isShowing) return
            val focused = view?.hasFocus() == true
            contextPanel?.dismiss()
            view?.windowToken?.let {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it, 0)
            }
            view?.let { (it.parent as? ViewGroup)?.removeView(it) }; view = null; sourcePage = null
            if (secure && !wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            content.visibility = if (recordingPage == null && accountPage == null) View.VISIBLE else View.INVISIBLE
            updatePhoneSetup = null; phoneContentSelection = null; pageFocus = null
            onDismiss?.invoke()
            showNavigation(navigationMode)
            if (restoreFocus && !returningToParent && focused) (origin?.takeIf { it.isShown } ?: navRows[section]).requestFocus()
            handler.post {
                refreshSyncedLibrary()
                if (restoreFocus && ready && activityResumed && sourcePage == null && recordingPage == null && accountPage == null && previewResumePending) {
                    previewResumePending = false
                    previewItem?.let { startPreview(it, false) }
                }
            }
        }

        fun cancel() {
            if (!isShowing) return
            val back = onBack
            dismiss(returningToParent = back != null)
            back?.invoke()
        }
    }

    private fun selectedSource(): IptvSource? = sources.firstOrNull { it.id == sourceId && store.playlistEnabled(it.id) }
    private fun orderedSources(includeDisabled: Boolean = false): List<IptvSource> = IptvOrganization.sources(
        sources.filter { includeDisabled || store.playlistEnabled(it.id) }, store.setting("sourceSort", "name"),
        IptvOrganization.ids(store.setting("sourceOrder", "[]")))
    private fun provider(source: IptvSource): IptvProvider = providers.getOrPut(source.id) {
        providerFor(store.connectionSource(source), store.intSetting("epgPastDays", 1), store.intSetting("epgRefreshHours", 24), store.setting("udpProxy", ""))
    }
    private fun invalidateProviders(id: String? = null, clearCatalogue: Boolean = true) {
        if (id == null) vodHeroDetails.evictAll()
        else vodHeroDetails.snapshot().filterValues { it.sourceId == id }.keys.forEach(vodHeroDetails::remove)
        if (clearCatalogue && (id == null || id == sourceId)) cancelCatalogue()
        if (id == null) { providers.clear(); searchProviders.clear() }
        else { providers.remove(id); searchProviders.remove(id) }
        if (clearCatalogue) {
            if (id == null) { groupCategories.clear(); cataloguePreviews.evictAll() }
            else {
                groupCategories.keys.removeAll { it.first == id }
                cataloguePreviews.snapshot().keys.filter { it.first == id }.forEach(cataloguePreviews::remove)
            }
        }
    }

    private fun epg(item: IptvItem, start: Long? = null, end: Long? = null): List<EpgProgram> {
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return emptyList()
        val offsetMinutes = store.organization(item.sourceId, item.type, item.categoryId, "epgOffsetMinutes", "0").toIntOrNull() ?: 0
        val offset = offsetMinutes.coerceIn(-720, 720) * 60_000L
        val day = start?.let { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it)) } ?: "current"
        val cached = store.epgCached(item, day)
        val programs = cached ?: (if (start != null && end != null)
            provider(source).guide(item, start - offset, end - offset) else provider(source).epg(item)).also {
            store.cacheEpg(item, day, if (store.booleanSetting("epgDescriptions", true)) it else it.map { value -> value.copy(description = "") })
            if (!epgUpdateRunning && it.isNotEmpty()) recordEpgStatus("EPG chargé")
        }
        return IptvStore.epgPrograms(programs, System.currentTimeMillis(), store.intSetting("epgPastDays", 1),
            store.booleanSetting("epgDescriptions", true), offsetMinutes)
    }

    private fun showSection(index: Int, authorized: Boolean = false) {
        if (index == 6 && !authorized) { parentalAccess("lockSettings") { showSection(index, true) }; return }
        if (accountPage != null && !accountChosen) return
        closeContentPages()
        setVodHeroVisible(false)
        content.setPadding(dp(20), dp(20), dp(20), dp(12))
        cancelCatalogue()
        if (section == 0 && index != 0) {
            searchReturnSaved = null
            rememberSearch(); cancelSearch()
            searchKeyboardOpen = false
            searchGroups.clear(); searchItems.clear(); searchPages.clear()
            searchPending.clear(); searchFailures.clear(); searchProviders.clear()
            searchField?.windowToken?.let { token ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(token, 0)
            }
        }
        if (index == 0 && section != 0) searchReturnSection = section
        if (index == 0) cancelSearch()
        resetArtworkRequests()
        searchField = null
        requestVersion++
        epgVersion++
        guideActive = false; liveChannelList = false
        if (guideOverlay) setGuideOverlay(false)
        guideScope.cancel()
        guideOffset = 0
        informationHeader.layoutParams.height = dp(if (index in listOf(1, 5)) 220 else 210)
        informationHeader.requestLayout()
        handler.removeCallbacks(recordingsRefresh)
        detail = null
        section = index
        if (index in 1..3 && selectedSource()?.includes(contentType()) != true)
            sourceId = orderedSources().firstOrNull { it.includes(contentType()) }?.id.orEmpty()
        status.visibility = View.VISIBLE
        if (index !in listOf(1, 2, 3, 5)) {
            groups.gravity = Gravity.TOP
            groups.setPadding(dp(12), dp(26), dp(12), dp(16))
        }
        if (index !in listOf(1, 5)) stopPreview(true)
        previewFrame.visibility = if (index in listOf(1, 5) && store.booleanSetting("previewEnabled", true)) View.VISIBLE else View.GONE
        listMode = "catalogue"
        category = ""
        categoryTitle = when (index) { 2 -> "Tous les films"; 3 -> "Toutes les séries"; else -> "Toutes les chaînes" }
        visibleItems = emptyList()
        info.removeAllViews()
        backdrop.setImageDrawable(null)
        backdrop.tag = ""
        navRows.forEachIndexed { rowIndex, row -> paintCommand(row as LinearLayout, row.hasFocus(), rowIndex == index) }
        showNavigation(navigationMode)
        when (index) {
            0 -> { showSearch(); if (searchQuery.isNotBlank()) runSearch(searchQuery) }
            4 -> { recordingsSignature = "initial"; message("Enregistrements", "Chargement…"); refreshRecordings(); handler.postDelayed(recordingsRefresh, 3000) }
            5 -> { buildGroups(); showSaved(ContentType.LIVE, false) }
            6 -> showSettings()
            else -> {
                buildGroups()
                if (orderedSources().none { it.includes(contentType()) }) {
                    stopPreview(true); previewFrame.visibility = View.GONE
                    message(if (sources.isEmpty()) "Bienvenue dans IPTV" else "Aucune liste de lecture pour cette rubrique",
                        "Ajoute une playlist M3U, un compte Xtream ou un portail Stalker incluant ce type de contenu.")
                    results.addView(button("Ajouter un fournisseur") { chooseSourceType() })
                } else loadCategories()
            }
        }
    }
    private fun group(label: String, selected: () -> Boolean = { false }, expanded: Boolean? = null,
                      onEnter: (() -> Unit)? = null, onClick: () -> Unit): TextView {
        return button(label, onClick).apply {
            textSize = 16f * textScale
            layoutParams = LinearLayout.LayoutParams(-1, dp((40 * textScale).toInt()))
            setPadding(dp(14), 0, dp(14), 0)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            if (expanded != null) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val path = android.graphics.Path().apply {
                    moveTo(3f, if (expanded) 11f else 5f)
                    lineTo(8f, if (expanded) 6f else 10f)
                    lineTo(13f, if (expanded) 11f else 5f)
                }
                val arrow = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.PathShape(path, 16f, 16f)).apply {
                    paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2f
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND; paint.isAntiAlias = true
                    setBounds(0, 0, dp(16), dp(16)); setTint(themeForeground)
                }
                compoundDrawablePadding = dp(12)
                setCompoundDrawablesRelative(null, null, arrow, null)
                contentDescription = "$label · ${if (expanded) "Développé" else "Réduit"}"
            }
            fun paint(focused: Boolean) {
                setBackground(shape(if (focused) accent else if (selected()) interfaceBackground(surface(.12f)) else Color.TRANSPARENT, focused))
                val foreground = selectionForeground(focused, selected())
                setTextColor(foreground)
                isSelected = focused
                compoundDrawablesRelative.filterNotNull().forEach { it.setTint(foreground) }
            }
            groupRowPaints[this] = { paint(hasFocus()) }
            paint(false)
            setOnFocusChangeListener { _, focused ->
                paint(focused)
                if (focused) {
                    if (pendingGroupEntry?.first != tag) pendingGroupEntry = null
                    showNavigation(1); activeGroup = this; centerGroupFocus(this)
                }
            }
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    pendingGroupEntry = null
                    navRows[section].requestFocus(); true
                } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (onEnter != null) onEnter() else performClick()
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_MENU && isLongClickable) {
                    pendingGroupEntry = null
                    if (event.repeatCount == 0) performLongClick()
                    true
                } else false
            }
            groupRows += this
        }
    }
    private fun refreshGroupSelection() {
        groupRows.forEach { groupRowPaints[it]?.invoke() }
    }
    private fun enterGroupContent(selected: () -> Boolean, open: () -> Unit) {
        val key = activeGroup?.tag ?: return
        pendingGroupEntry = key to selected
        if (!selected()) open()
        resumeGroupEntry()
    }
    private fun resumeGroupEntry() {
        val (key, selected) = pendingGroupEntry ?: return
        val row = activeGroup ?: return
        if (!row.isAttachedToWindow || !row.hasFocus() || row.tag != key || !row.hasWindowFocus() ||
            navigationMode != 1 || contextPanel != null || sourcePage != null || detail != null || !selected() || visibleItems.isEmpty()) return
        if (groupEntryTarget()?.requestFocus() == true) pendingGroupEntry = null
    }
    private fun groupEntryTarget(): View? {
        val channel = firstContent() ?: return null
        if (!guideActive) return channel
        val index = (channel.parent as? ViewGroup)?.indexOfChild(channel) ?: return channel
        val timeline = guideHorizontal?.getChildAt(0) as? ViewGroup ?: return channel
        val row = timeline.getChildAt(index) as? ViewGroup ?: return channel
        val cells = (0 until row.childCount).map(row::getChildAt).filter { it.isFocusable && it.isEnabled }
        fun position(view: View) = (view.layoutParams as? FrameLayout.LayoutParams)?.leftMargin ?: Int.MAX_VALUE
        val anchor = guideHorizontal?.scrollX ?: guideNowPosition
        return cells.filter { position(it) <= anchor }.maxByOrNull(::position) ?: cells.minByOrNull(::position) ?: channel
    }
    private fun centerGroupFocus(row: View) {
        groupScroll.post {
            if (row.parent !== groups || !row.hasFocus() || groupScroll.height <= 0) return@post
            val edge = ((groupScroll.height - row.height) / 2).coerceAtLeast(0)
            fun center() {
                if (row.parent === groups && row.hasFocus())
                    groupScroll.scrollTo(0, (row.top + row.height / 2 - groupScroll.height / 2).coerceAtLeast(0))
            }
            if (groups.paddingTop != edge || groups.paddingBottom != edge) {
                groups.doOnNextLayout { center() }
                groups.setPadding(dp(12), edge, dp(12), edge)
            } else center()
        }
    }
    private fun buildGroups(categories: List<IptvCategory> = groupCategories[sourceId to contentType()].orEmpty()) {
        val focusedGroup = groups.findFocus()
        val previousFocus = (focusedGroup ?: activeGroup)?.tag
        // Restoring focus after a rebuild must retain the selected group.
        var restoringGroupFocus = true
        fun contentGroup(label: String, selected: () -> Boolean, open: () -> Unit): TextView {
            val row = group(label, selected) { enterGroupContent(selected, open) }
            val paint = row.onFocusChangeListener
            var pendingFocus: Runnable? = null
            row.setOnFocusChangeListener { view, focused ->
                // Returning from a menu or PIN dialog must not reopen the group.
                val previous = activeGroup
                paint?.onFocusChange(view, focused)
                pendingFocus?.let(handler::removeCallbacks); pendingFocus = null
                if (focused && !restoringGroupFocus && previous !== view && !selected()) {
                    pendingFocus = Runnable {
                        pendingFocus = null
                        if (row.isAttachedToWindow && row.hasFocus() && row.hasWindowFocus() && !selected()) open()
                    }.also { handler.post(it) }
                }
            }
            return row
        }
        groups.removeAllViews()
        groups.gravity = Gravity.TOP
        val edge = ((groupScroll.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) - dp((40 * textScale).toInt())) / 2
        groups.setPadding(dp(12), edge.coerceAtLeast(0), dp(12), edge.coerceAtLeast(0))
        groupRows.clear()
        groupRowPaints.clear()
        activeGroup = null
        if (section == 5) {
            val savedRows = listOf("Mes chaînes" to "channels", "Mon programme TV" to "programs", "Mes rappels" to "reminders",
                "Mes films" to "movies", "Mes séries" to "series")
            savedRows.forEach { (label, key) ->
                val type = when (key) { "movies" -> ContentType.MOVIE; "series" -> ContentType.SERIES; else -> ContentType.LIVE }
                fun open() { if (key in listOf("programs", "reminders")) showSavedPrograms(key == "reminders") else showSaved(type, false) }
                val row = group(label, onEnter = {
                    if (key in listOf("programs", "reminders")) firstContent()?.requestFocus()
                    else enterGroupContent({ detail == null && savedType == type && listMode == "favorites" }) { open() }
                }) { open() }.apply { tag = key }
                val paint = row.onFocusChangeListener
                row.setOnFocusChangeListener { view, focused ->
                    paint?.onFocusChange(view, focused)
                    if (focused && (detail != null || if (key in listOf("programs", "reminders")) listMode != key else savedType != type || listMode != "favorites")) open()
                }
                groups.addView(row)
            }
            wireVerticalFocus(groups)
            if (previousFocus != null) {
                activeGroup = groupRows.firstOrNull { it.tag == previousFocus }
                if (focusedGroup != null) activeGroup?.requestFocus()
            }
            return
        }
        if (contentType() == ContentType.LIVE && store.booleanSetting("showAllPlaylistsGroup", true))
            groups.addView(contentGroup("Toutes les listes de lecture", { listMode == "allPlaylists" }) { loadAllPlaylistChannels() }.apply { tag = "allPlaylists" })
        if (store.booleanSetting("showFavoritesGroup", true))
            groups.addView(contentGroup(if (contentType() == ContentType.LIVE) "Favoris" else "Ma liste",
                { detail == null && listMode == "favorites" && savedType == contentType() }) { showSaved(contentType(), false) }.apply { tag = "favorites" })
        groups.addView(contentGroup("Historique", { detail == null && listMode == "history" && savedType == contentType() }) {
            showSaved(contentType(), true)
        }.apply { tag = "history"; setOnLongClickListener { confirmClearHistory(); true } })
        orderedSources().filter { it.includes(contentType()) }.forEach { source ->
            val type = contentType()
            val values = groupCategories[source.id to type] ?: if (source.id == sourceId) categories else emptyList()
            val expanded = source.id !in collapsedProviders
            groups.addView(group(source.name, { source.id == sourceId && listMode == "catalogue" }, expanded,
                onEnter = {
                    focusProvider(source)
                    enterGroupContent({ sourceId == source.id && listMode == "catalogue" }) { focusProvider(source) }
                }) {
                focusProvider(source)
                if (!collapsedProviders.add(source.id)) collapsedProviders.remove(source.id)
                buildGroups()
            }.apply {
                tag = source.id
                val paint = onFocusChangeListener
                var pendingFocus: Runnable? = null
                setOnFocusChangeListener { view, focused ->
                    paint?.onFocusChange(view, focused)
                    pendingFocus?.let(handler::removeCallbacks); pendingFocus = null
                    if (focused && !restoringGroupFocus) {
                        pendingFocus = Runnable {
                            pendingFocus = null
                            if (view.isAttachedToWindow && view.hasFocus() && view.hasWindowFocus()) focusProvider(source)
                        }.also { handler.post(it) }
                    }
                }
                setOnLongClickListener { providerMenu(source, contentType()); true }
            })
            if (expanded) {
                if (store.booleanSetting("showAllGroup", true)) groups.addView(contentGroup(when (section) { 2 -> "Tous les films"; 3 -> "Toutes les séries"; else -> "Toutes les chaînes" },
                    { sourceId == source.id && listMode == "catalogue" && category.isEmpty() && detail == null }) {
                    selectProviderCategory(source, type, IptvCategory("", when (section) { 2 -> "Tous les films"; 3 -> "Toutes les séries"; else -> "Toutes les chaînes" }), values, rebuildGroups = false)
                }.apply { tag = Triple(source.id, type, ""); setPadding(dp(26), 0, dp(14), 0) })
                visibleProviderGroups(source, type, values).forEach { value ->
                    groups.addView(contentGroup(groupLabel(source, type, value),
                        { sourceId == source.id && listMode == "catalogue" && category == value.id && detail == null }) {
                        selectProviderCategory(source, type, value, values, rebuildGroups = false)
                    }.apply {
                        tag = Triple(source.id, type, value.id); setPadding(dp(26), 0, dp(14), 0)
                        setOnLongClickListener { groupMenu(source, type, value); true }
                    })
                }
            }
        }
        wireVerticalFocus(groups)
        if (previousFocus != null) {
            activeGroup = groupRows.firstOrNull { it.tag == previousFocus }
                ?: groupRows.firstOrNull { it.tag == sourceId }
                ?: groupRows.firstOrNull()
            if (focusedGroup != null) activeGroup?.requestFocus()
        }
        restoringGroupFocus = false
        orderedSources().filter { it.includes(contentType()) && it.id != sourceId && it.id !in collapsedProviders }
            .forEach { loadExpandedProviderGroups(it, contentType()) }
    }
    private fun loadExpandedProviderGroups(source: IptvSource, type: ContentType) {
        val key = source.id to type
        if (groupCategories.containsKey(key) || !groupCategoryLoads.add(key)) return
        async({
            store.cachedCategories(source.id, type) ?: provider(source).categories(type).also { store.cacheCategories(source.id, type, it) }
        }, version = null, failure = {
            groupCategoryLoads.remove(key)
            if (section in 1..3 && contentType() == type && source.id !in collapsedProviders)
                toast("Les groupes de ce fournisseur sont indisponibles.")
        }) { values ->
            groupCategoryLoads.remove(key)
            if (sources.none { it == source }) return@async
            groupCategories[key] = values
            if (section in 1..3 && contentType() == type && source.id !in collapsedProviders && detail == null) {
                if (contextPanel == null && sourcePage == null) buildGroups() else pendingSyncedRefresh = true
            }
        }
    }
    private fun visibleProviderGroups(source: IptvSource, type: ContentType, values: List<IptvCategory>): List<IptvCategory> =
        IptvOrganization.groups(values, store.organization(source.id, type, null, "groupSort", "playlist"),
            IptvOrganization.ids(store.organization(source.id, type, null, "groupOrder:${type.name}", "[]"))) {
            groupLabel(source, type, it)
        }.filterNot { groupHidden(source, type, it.id) }

    private fun focusProvider(source: IptvSource) {
        if (!ready || section !in 1..3) return
        val type = contentType()
        val firstCategory = if (store.booleanSetting("showAllGroup", true)) "" else
            visibleProviderGroups(source, type, groupCategories[source.id to type].orEmpty()).firstOrNull()?.id.orEmpty()
        if (sourceId == source.id && listMode == "catalogue" && category == firstCategory && detail == null) return
        val changed = sourceId != source.id
        cancelCatalogue()
        val version = ++requestVersion
        sourceId = source.id; category = ""; detail = null; listMode = "catalogue"
        refreshGroupSelection()
        if (changed && store.booleanSetting("epgUpdateOnPlaylist", true))
            async({ store.clearEpgCache(source.id) }, version) { invalidateProviders(source.id, clearCatalogue = false); loadCategories(firstGroup = true) }
        else loadCategories(firstGroup = true)
    }
    private fun focusGroup() {
        when {
            section == 0 -> searchField?.requestFocus()
            section == 4 -> (firstContent() ?: info.findViewWithTag<View>("recording-tab:$recordingsTab"))?.requestFocus()
            groupScroll.visibility == View.VISIBLE && groupRows.isNotEmpty() ->
                (activeGroup?.takeIf { it.isAttachedToWindow } ?: groupRows.firstOrNull { it.tag == sourceId } ?: groupRows.first()).requestFocus()
            else -> (firstContent() ?: navRows.getOrNull(section))?.requestFocus()
        }
    }
    private fun loadCategories(firstGroup: Boolean = false) {
        if (!ready) return
        cancelCatalogue()
        val source = selectedSource() ?: return
        val type = contentType()
        val version = ++requestVersion
        val generation = catalogueGeneration
        fun showCategories(values: List<IptvCategory>, refresh: Boolean = false) {
            groupCategories[source.id to type] = values
            val preferred = store.organization(source.id, type, null, "category:${type.name}", "")
            val openFirst = firstGroup || groups.findFocus()?.tag == source.id
            val selected = if (openFirst) {
                if (store.booleanSetting("showAllGroup", true)) null else visibleProviderGroups(source, type, values).firstOrNull()
            } else values.firstOrNull { it.id == preferred && !groupHidden(source, type, it.id) }
            category = selected?.id.orEmpty()
            categoryTitle = selected?.let { groupLabel(source, type, it) } ?: when (type) { ContentType.MOVIE -> "Tous les films"; ContentType.SERIES -> "Toutes les séries"; else -> "Toutes les chaînes" }
            buildGroups(values)
            groupAccess(source.id, type, category) {
                loadItems()
                if (refresh) refreshCategories(source, type, values)
            }
        }
        groupCategories[source.id to type]?.let { showCategories(it, refresh = true); return }
        message("${source.name}", "Chargement des catégories…")
        val scope = IptvRequestScope(IptvHttp.client).also { catalogueScope = it }
        catalogueTask = catalogueWork.submit {
            val loaded = runCatching { IptvHttp.withScope(scope) {
                IptvHttp.checkActive()
                val cached = runCatching { store.cachedCategories(source.id, type) }.getOrNull()
                if (cached != null) cached to true
                else provider(source).categories(type).also { values ->
                    IptvHttp.checkActive()
                    runCatching { store.cacheCategories(source.id, type, values) }
                } to false
            } }
            runOnUiThread {
                if (isDestroyed || isFinishing || version != requestVersion || generation != catalogueGeneration) return@runOnUiThread
                loaded.fold({ (values, refresh) -> showCategories(values, refresh) }) { problem ->
                    message("Connexion indisponible", if (problem is IllegalStateException)
                        problem.message ?: "Connexion indisponible." else "Connexion indisponible ou réponse du fournisseur incompatible.")
                    results.addView(button("Réessayer") { loadCategories(firstGroup) })
                    results.addView(button("Modifier le fournisseur") { sourceForm(source.type, source) })
                }
            }
        }
    }
    private fun refreshCategories(source: IptvSource, type: ContentType, previous: List<IptvCategory>) {
        val generation = catalogueGeneration
        val scope = catalogueScope ?: return
        val client = provider(source)
        // Refresh after the catalogue job: cached rows appear before this extra request.
        catalogueWork.execute {
            if (generation != catalogueGeneration || providers[source.id] !== client) return@execute
            val loaded = runCatching { IptvHttp.withScope(scope) {
                IptvHttp.checkActive()
                client.categories(type).also { values ->
                    IptvHttp.checkActive()
                    store.cacheCategories(source.id, type, values)
                }
            } }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != catalogueGeneration) return@runOnUiThread
                groupCategories[source.id to type] = loaded
                if (loaded != previous && sourceId == source.id && contentType() == type && detail == null) {
                    if (contextPanel == null) buildGroups(loaded) else pendingSyncedRefresh = true
                }
            }
        }
    }
    private fun cancelCatalogue() {
        catalogueGeneration++
        catalogueScope?.cancel(); catalogueScope = null
        catalogueTask?.cancel(true); catalogueTask = null
        catalogueCacheTask?.cancel(true); catalogueCacheTask = null
        catalogueMetadataTask?.cancel(true); catalogueMetadataTask = null
    }
    private fun loadItems(selectionSettings: Map<String, String> = emptyMap()) {
        cancelCatalogue()
        guideScope.cancel()
        listMode = "catalogue"
        val source = selectedSource() ?: return
        val type = contentType()
        val selectedCategory = category
        val generation = catalogueGeneration
        val accountId = store.selectedAccount()?.id.orEmpty()
        val profileId = HomeWatchProgressBridge.currentProfileId(this)
        catalogueSelectionSettings = selectionSettings
        if (selectionSettings.isNotEmpty()) libraryWork.execute {
            if (generation == catalogueGeneration && accountId == store.selectedAccount()?.id.orEmpty()) {
                val saved = runCatching { store.setSettings(selectionSettings) }
                if (saved.isFailure) runOnUiThread {
                    if (!isDestroyed && !isFinishing && generation == catalogueGeneration)
                        toast("La s\u00e9lection n'a pas pu \u00eatre m\u00e9moris\u00e9e.")
                }
            }
        }
        val scope = IptvRequestScope(IptvHttp.client).also { catalogueScope = it }
        requestVersion++
        val previewKey = Triple(source.id, type, selectedCategory)
        val preview = cataloguePreviews.get(previewKey)
        guideActive = false; guideOffset = 0; visibleItems = preview.orEmpty()
        if (visibleItems.isEmpty()) message(categoryTitle, "Chargement…") else displayItems()
        status.text = if (visibleItems.isEmpty()) "Chargement…" else "$categoryTitle · ${visibleItems.size} contenus · Chargement…"
        // A previous provider request may still be unwinding on catalogueWork.
        // Read persisted rows independently so cold openings do not wait for it.
        val cacheRead = catalogueCacheWork.submit {
            if (generation != catalogueGeneration || Thread.currentThread().isInterrupted) return@submit
            if (preview == null) {
                val cached = runCatching { store.cached(source.id, type, selectedCategory, 0) }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) runOnUiThread {
                    if (!isDestroyed && !isFinishing && generation == catalogueGeneration && detail == null) {
                        cataloguePreviews.put(previewKey, cached.take(60))
                        visibleItems = cached
                        if (contextPanel == null) displayItems() else pendingSyncedRefresh = true
                    }
                }
            }
        }
        catalogueCacheTask = cacheRead
        catalogueTask = catalogueWork.submit {
            val collected = linkedMapOf<String, IptvItem>()
            val pending = AtomicReference<Pair<List<IptvItem>, Boolean>?>(null)
            val metadata = AtomicReference<List<IptvItem>?>(null)
            val metadataScheduled = AtomicBoolean(false)
            fun registerMetadata() {
                if (!metadataScheduled.compareAndSet(false, true)) return
                catalogueMetadataTask = progressWork.submit {
                    try {
                        while (generation == catalogueGeneration && !Thread.currentThread().isInterrupted) {
                            val items = metadata.getAndSet(null) ?: break
                            runCatching { store.registerCloudCatalogue(items, accountId, profileId) }
                        }
                    } finally {
                        metadataScheduled.set(false)
                        if (generation == catalogueGeneration && metadata.get() != null && !progressWork.isShutdown)
                            registerMetadata()
                    }
                }
            }
            var renderedCount = 0
            val publish = Runnable {
                val (all, complete) = pending.getAndSet(null) ?: return@Runnable
                if (isDestroyed || isFinishing || generation != catalogueGeneration) return@Runnable
                visibleItems = if (complete) all else {
                    val freshIds = all.mapTo(hashSetOf()) { it.id }
                    all + visibleItems.filter { it.id !in freshIds }
                }
                if (detail == null) {
                    if (contextPanel != null) {
                        pendingSyncedRefresh = true
                        return@Runnable
                    }
                    val focused = results.findFocus()?.tag as? String
                    if (renderedCount == 0) displayItems(focused.orEmpty())
                    else if (guideActive || liveChannelList) {
                        if (complete) displayItems(focused.orEmpty())
                    } else {
                        if (!complete && store.organization(source.id, type, selectedCategory, "channelSort", "playlist") == "playlist")
                            addCards(store.organizeItems(all.drop(renderedCount), source.id, type, selectedCategory, selectionSettings))
                        else displayItems(focused.orEmpty())
                    }
                    renderedCount = all.size
                    status.text = "$categoryTitle · ${visibleItems.size} contenus${if (!complete) " · Chargement…" else ""}"
                }
            }
            val loaded = runCatching { IptvHttp.withScope(scope) {
                IptvHttp.checkActive()
                // Publish local rows first; a later disk result must not replace
                // the fresh provider catalogue or race its cache write.
                cacheRead.get()
                IptvHttp.checkActive()
                provider(source).loadCatalogue(type, selectedCategory) { currentPage, batch ->
                    IptvHttp.checkActive()
                    val fresh = batch.items.filter { it.id !in collected }.distinctBy { it.id }
                    fresh.forEach { collected[it.id] = it }
                    val all = collected.values.toList()
                    if (currentPage == 0) cataloguePreviews.put(previewKey, batch.items.take(60))
                    pending.set(all to (batch.nextPage == null))
                    handler.removeCallbacks(publish)
                    handler.post(publish)
                    runCatching { store.cache(source.id, type, selectedCategory, currentPage, batch.items,
                        registerContents = false, total = batch.total) }
                    // Only one metadata task per catalogue; newer pages replace its pending snapshot.
                    metadata.set(all)
                    registerMetadata()
                    IptvHttp.checkActive()
                }
            } }
            if (loaded.isFailure) runOnUiThread {
                if (isDestroyed || isFinishing || generation != catalogueGeneration) return@runOnUiThread
                if (detail == null) {
                    status.visibility = View.VISIBLE
                    status.text = "Connexion indisponible · ${visibleItems.size} contenus chargés"
                    results.addView(button("Réessayer") { loadItems(selectionSettings) })
                }
            }
        }
    }
    private fun showSaved(type: ContentType, history: Boolean) {
        savedType = type
        cancelCatalogue()
        previewFrame.visibility = if (type == ContentType.LIVE && store.booleanSetting("previewEnabled", true)) View.VISIBLE else View.GONE
        if (type != ContentType.LIVE) stopPreview(true)
        requestVersion++
        detail = null
        listMode = if (history) "history" else "favorites"
        refreshGroupSelection()
        categoryTitle = if (history) "Historique" else "Ma liste"
        visibleItems = store.saved(type, history)
        displayItems()
        if (section == 5 && visibleItems.isEmpty()) emptySavedPage(when (type) {
            ContentType.MOVIE -> "Aucun film"; ContentType.SERIES -> "Aucune série"; else -> "Aucune chaîne"
        }, if (history) "Votre historique est vide." else "Ajoutez des contenus à votre liste depuis leur fiche.", when (type) {
            ContentType.MOVIE -> R.drawable.ic_home_category_clapper; ContentType.SERIES -> R.drawable.ic_home_category_series_bottom
            else -> R.drawable.ic_home_category_live_signal
        })
    }

    private fun savedPrograms(): List<IptvSavedProgram> = runCatching {
        iptvJson.decodeFromString<List<IptvSavedProgram>>(store.setting("savedPrograms", "[]"))
    }.getOrDefault(emptyList())

    private fun emptySavedPage(title: String, description: String, icon: Int) {
        guideActive = false; guideScope.cancel(); stopPreview(true); previewFrame.visibility = View.GONE
        info.removeAllViews(); results.removeAllViews(); backdrop.setImageDrawable(null); backdrop.tag = ""; status.text = ""
        results.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(230)
            addView(ImageView(this@IptvActivity).apply { setImageResource(icon); setColorFilter(surface(.58f)) }, LinearLayout.LayoutParams(dp(86), dp(86)))
            addView(text(title, 18).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(8)) })
            addView(text(description, 14).apply { gravity = Gravity.CENTER; setTextColor(surface(.68f)) })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showSavedPrograms(reminders: Boolean) {
        setVodHeroVisible(false)
        cancelCatalogue(); requestVersion++; epgVersion++; guideActive = false; guideScope.cancel(); detail = null
        stopPreview(true); previewFrame.visibility = View.GONE; listMode = if (reminders) "reminders" else "programs"
        visibleItems = emptyList(); backdrop.setImageDrawable(null); backdrop.tag = ""
        async({
            if (reminders) store.reminders().filter { !it.notified && it.end > System.currentTimeMillis() }
                .map { IptvSavedProgram(it.channel, EpgProgram(it.title, "", it.start, it.end)) }
            else savedPrograms().filter { value -> sources.any { it.id == value.channel.sourceId } }
        }) { values ->
            message(if (reminders) "Mes rappels" else "Mon programme TV")
            if (values.isEmpty()) emptySavedPage(if (reminders) "Aucun rappel" else "Aucun programme",
                if (reminders) "Créez un rappel depuis le guide TV." else "Ajoutez des programmes à votre liste depuis le guide TV.",
                android.R.drawable.ic_menu_agenda)
            val format = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            values.sortedBy { it.program.start }.forEach { value ->
                results.addView(button("${value.program.title}\n${value.channel.title} · ${format.format(Date(value.program.start))}") {
                    programmeActions(value.channel, value.program)
                }.apply { leftToGroups(this) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }
    }

    private fun loadAllPlaylistChannels() {
        cancelCatalogue()
        requestVersion++
        val generation = catalogueGeneration
        val scope = IptvRequestScope(IptvHttp.client).also { catalogueScope = it }
        val selected = orderedSources().filter { it.includeLive }
        listMode = "allPlaylists"; category = ""; categoryTitle = "Toutes les listes de lecture"
        refreshGroupSelection()
        guideOffset = 0; visibleItems = emptyList(); message(categoryTitle, "Chargement…")
        work.execute {
            val result = runCatching {
                IptvHttp.withScope(scope) {
                    val channels = linkedMapOf<String, IptvItem>()
                    selected.forEach { source ->
                        var page: Int? = 0
                        while (page != null) {
                            IptvHttp.checkActive()
                            val batch = provider(source).items(ContentType.LIVE, "", page)
                            store.cache(source.id, ContentType.LIVE, "", page, batch.items, total = batch.total)
                            val fresh = batch.items.filter { it.id !in channels }
                            fresh.forEach { channels[it.id] = it }
                            val all = channels.values.toList()
                            runOnUiThread {
                                if (!isDestroyed && generation == catalogueGeneration && listMode == "allPlaylists") {
                                    val focus = rememberedFocus
                                    visibleItems = all; displayItems(focus)
                                }
                            }
                            page = batch.nextPage?.takeIf { it > page!! && fresh.isNotEmpty() }
                        }
                    }
                }
            }
            if (result.isFailure) runOnUiThread {
                if (!isDestroyed && generation == catalogueGeneration && listMode == "allPlaylists") {
                    status.visibility = View.VISIBLE; status.text = "Connexion indisponible · ${visibleItems.size} chaînes chargées"
                    toast("Impossible d'actualiser toutes les listes de lecture.")
                }
            }
        }
    }

    private fun catalogueItems(): List<IptvItem> = when {
        listMode == "allPlaylists" -> store.organizeItems(visibleItems, "", ContentType.LIVE, "allPlaylists")
        listMode == "catalogue" && section in 1..3 -> store.organizeItems(visibleItems, sourceId, contentType(), category, catalogueSelectionSettings)
        listMode == "favorites" && savedType in listOf(ContentType.MOVIE, ContentType.SERIES) -> store.organizeSaved(visibleItems, savedType)
        listMode != "history" && visibleItems.all { it.type == ContentType.LIVE } -> store.organizeItems(visibleItems, "", ContentType.LIVE, "")
        else -> visibleItems
    }

    private fun displayItems(focusId: String = "") {
        if (listMode in listOf("favorites", "history")) visibleItems = store.saved(savedType, listMode == "history")
        val displayed = catalogueItems()
        val contentHadFocus = results.hasFocus()
        val restoreContentFocus = navigationMode == 2
        val restoredFocus = focusId.ifBlank {
            if (navigationMode == 2 && displayed.any { it.type != ContentType.LIVE })
                (results.findFocus()?.tag as? String)?.takeIf { tag -> displayed.any { it.id == tag } } ?: rememberedFocus
            else ""
        }
        if (updateVodCards(displayed)) {
            if (focusId.isNotBlank() && restoreContentFocus && !restoringPlaybackFocus && !vodHeroTools.hasFocus()) focusVodCard(focusId)
            results.post { restorePlaybackFocus() }
            resumeGroupEntry()
            return
        }
        resetArtworkRequests()
        detail = null
        guideActive = !liveChannelList && section in listOf(1, 5) && displayed.isNotEmpty() && displayed.all { it.type == ContentType.LIVE }
        status.visibility = if (guideActive) View.GONE else View.VISIBLE
        setVodHeroVisible(displayed.isNotEmpty() && section in listOf(2, 3, 5) &&
            displayed.all { it.type == ContentType.MOVIE || it.type == ContentType.SERIES || listMode == "history" && it.type == ContentType.EPISODE })
        if (guideActive) { renderGuide(focusId); return }
        guideScope.cancel()
        informationHeader.layoutParams.height = dp(210); informationHeader.requestLayout()
        if (section == 0) {
            showSearch()
            if (searchQuery.isNotBlank()) renderSearchResults()
            if (restoredFocus.isNotBlank()) results.findViewWithTag<View>(restoredFocus)?.requestFocus()
            results.post { restorePlaybackFocus() }
            return
        }
        results.removeAllViews()
        status.text = categoryTitle
        if (displayed.isEmpty()) {
            if (section == 0) {
                results.addView(text("Aucun résultat pour cette recherche.", 16))
                return
            }
            message(categoryTitle, if (listMode == "favorites") "Ajoute des contenus à ta liste depuis leur fiche." else "Aucun contenu dans cette sélection.")
            addHistoryClearAction()
            return
        }
        updateInfo(if (vodHero.visibility == View.VISIBLE) displayed.firstOrNull {
            it.id == restoredFocus.ifBlank { rememberedFocus }
        } ?: displayed.first() else displayed.first())
        if (displayed.all { it.type == ContentType.LIVE }) {
            val channels = displayed
            val focusedIndex = channels.indexOfFirst { it.id == focusId.ifBlank { rememberedFocus } }.coerceAtLeast(0)
            val page = IptvOrganization.channelListPage(channels.size, focusedIndex)
            remoteGuidePage = { forward ->
                val index = channels.indexOfFirst { it.id == currentFocus?.tag }.coerceAtLeast(0)
                val count = ((results.parent as? View)?.height?.div(dp(63)) ?: 1).coerceAtLeast(1)
                val target = (index + if (forward) count else -count).coerceIn(0, channels.lastIndex)
                results.findViewWithTag<View>(channels[target].id)?.requestFocus()
                    ?: run { displayItems(channels[target].id); true }
            }
            val channelLines = if (store.booleanSetting("channelTwoLines", true)) 2 else 1
            page.forEach { index ->
                val item = channels[index]
                val row = object : LinearLayout(this) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val target = when {
                                event.keyCode == KeyEvent.KEYCODE_DPAD_UP && index == page.first && index > 0 -> index - 1
                                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index == page.last && index < channels.lastIndex -> index + 1
                                else -> null
                            }
                            if (target != null) { displayItems(channels[target].id); return true }
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; id = View.generateViewId(); isFocusable = true; tag = item.id; setPadding(dp(8), dp(5), dp(8), dp(5)) }
                val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                loadImage(logo, item.artwork, item, dp(60), dp(38))
                logo.visibility = if (store.booleanSetting("showLogos", true)) View.VISIBLE else View.GONE
                row.addView(logo, LinearLayout.LayoutParams(dp(60), dp(38)))
                val label = text(listOfNotNull(if (store.booleanSetting("channelNumbers", true)) "${index + 1}" else null,
                    store.channelTitle(item).takeIf { store.booleanSetting("channelNames", true) }).joinToString("  "), 15, true)
                label.setSingleLine(channelLines == 1)
                label.maxLines = channelLines
                label.ellipsize = if (channelLines == 1) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
                label.marqueeRepeatLimit = -1
                label.visibility = if (label.text.isNotBlank()) View.VISIBLE else View.GONE
                row.addView(label, LinearLayout.LayoutParams(dp(195), -2))
                val guide = text("Programme non renseigné", 14)
                guide.maxLines = if (store.booleanSetting("programTwoLines", true)) 2 else 1
                row.addView(guide, LinearLayout.LayoutParams(0, -2, 1f))
                row.setBackground(shape(surface(.06f)))
                row.setOnFocusChangeListener { _, focused ->
                    row.setBackground(shape(if (focused) accent else if (store.booleanSetting("currentChannelColor", true) && previewItem?.id == item.id)
                        ColorUtils.blendARGB(themeBackground, accent, .20f) else surface(.06f), focused))
                    label.setTextColor(if (focused) contrast else themeForeground)
                    label.isSelected = focused && channelLines == 1
                    guide.setTextColor(if (focused) contrast else themeForeground)
                    if (logo.drawable !is android.graphics.drawable.BitmapDrawable) logo.setColorFilter(if (focused) contrast else themeForeground)
                    if (focused) {
                        showNavigation(2); updateInfo(item); rememberedFocus = item.id
                        remoteGuideChannel = item; remoteGuideProgram = null
                        loadEpg(item, guide)
                        previewFocusRequest?.let(handler::removeCallbacks)
                        if (store.booleanSetting("previewAutoPlay", false) && previewItem?.id != item.id) {
                            previewFocusRequest = Runnable { if (row.hasFocus()) previewGuideChannel(item) }.also { handler.postDelayed(it, 500) }
                        }
                    }
                }
                row.setOnClickListener {
                    watchGuideChannel(item)
                }
                row.setOnLongClickListener { channelMenu(item); true }
                leftToGroups(row)
                results.addView(row, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(3) })
            }
        } else addCards(displayed)
        addHistoryClearAction()
        if (contentHadFocus && restoreContentFocus) showNavigation(2)
        if (restoredFocus.isNotBlank() && restoreContentFocus && !restoringPlaybackFocus && !vodHeroTools.hasFocus()) {
            if (vodCards != null) focusVodCard(restoredFocus) else results.findViewWithTag<View>(restoredFocus)?.requestFocus()
        }
        results.post { restorePlaybackFocus() }
        resumeGroupEntry()
    }
    private fun addCards(items: List<IptvItem>, target: LinearLayout = results) {
        if (vodHero.visibility == View.VISIBLE && detail == null && target === results &&
            items.all { it.type == ContentType.MOVIE || it.type == ContentType.SERIES || listMode == "history" && it.type == ContentType.EPISODE }) {
            addVodCards(items)
            return
        }
        // Rows keep their measured width when the category column is shown/hidden.
        // TiviMate.mkv at 1080p: posters 246x369px, image spacing 16px.
        // The existing 2dp focus inset is included in the card width and image spacing.
        val posters = items.all { it.type == ContentType.MOVIE || it.type == ContentType.SERIES }
        val searching = section == 0 && detail == null
        val compactLive = searching && items.all { it.type == ContentType.LIVE }
        val cardWidth = if (compactLive) 118 else if (posters) 127 else 132
        val gap = if (compactLive) 8 else if (posters) 4 else 10
        val artworkWidth = dp(cardWidth - 4)
        val posterHeight = if (posters) artworkWidth * 3 / 2 else dp(184)
        val columns = if (searching) items.size.coerceAtLeast(1) else
            ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 40) / (cardWidth + gap)).toInt().coerceIn(4, 8)
        var consumed = 0
        val last = target.getChildAt(target.childCount - 1) as? FrameLayout
        val tail = last?.let(cardItems::get)
        if (last != null && tail != null && tail.size < columns && items.isNotEmpty() &&
            tail.all { it.type == ContentType.EPISODE } == items.all { it.type == ContentType.EPISODE }) {
            consumed = minOf(columns - tail.size, items.size)
            val focused = last.findFocus()?.tag as? String
            tail.addAll(items.take(consumed))
            if (last.childCount > 0) {
                last.removeAllViews(); cardRows[last]?.invoke()
                if (focused != null) last.findViewWithTag<View>(focused)?.requestFocus()
            }
        }
        items.drop(consumed).chunked(columns).forEach { values ->
            val batch = values.toMutableList()
            val holder = FrameLayout(this)
            val titleHeight = dp((if (compactLive) 19 * textScale else if (searching) 26 * textScale else 31f).toInt())
            val guideHeight = dp((19 * textScale).toInt())
            val rowHeight = if (compactLive) dp(46 + 2 + 14) + titleHeight + guideHeight else
                (if (posters) posterHeight else dp(if (batch.all { it.type == ContentType.EPISODE }) 85 else 184)) + titleHeight + dp(14)
            val populate: () -> Unit = {
                val scroll = HorizontalScrollView(this).apply { isFocusable = false; isHorizontalScrollBarEnabled = false }
                val row = LinearLayout(this)
                batch.forEachIndexed { index, item ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; id = View.generateViewId(); isFocusable = true; tag = item.id
                        clipToOutline = true
                    }
                    val artwork = ImageView(this).apply {
                        scaleType = if (compactLive) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                        cropToPadding = true
                        background = shape(surface(.10f)).apply { cornerRadius = dp(7).toFloat() }
                        clipToOutline = true
                    }
                    val artworkHeight = if (compactLive) dp(46) else if (item.type == ContentType.EPISODE) dp(85) else posterHeight
                    if (compactLive) artwork.setPadding(dp(10), dp(6), dp(10), 0)
                    loadImage(artwork, item.artwork, item, artworkWidth, artworkHeight)
                    val rating = item.rating.toFloatOrNull()?.takeIf { it > 0f }
                    if (searching && posters && rating != null) {
                        val frame = FrameLayout(this)
                        frame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
                        frame.addView(text(String.format(Locale.US, "%.1f", rating), 11, true).apply {
                            setPadding(dp(5), dp(1), dp(5), dp(1)); setTextColor(contrast)
                            background = shape(accent).apply { cornerRadius = dp(3).toFloat() }
                        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                            marginStart = dp(5); bottomMargin = dp(5)
                        })
                        card.addView(frame, LinearLayout.LayoutParams(-1, artworkHeight))
                    } else card.addView(artwork, LinearLayout.LayoutParams(-1, artworkHeight))
                    val label = text(item.title, 13)
                    label.setPadding(dp(7), dp(if (compactLive) 1 else 5), dp(7), dp(if (compactLive) 1 else 5))
                    label.setSingleLine(true)
                    label.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                    label.marqueeRepeatLimit = -1
                    card.addView(label, LinearLayout.LayoutParams(-1, titleHeight))
                    val guide = if (compactLive) text("Pas d'information", 11).apply {
                        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(7), 0, dp(7), 0)
                        card.addView(this, LinearLayout.LayoutParams(-1, guideHeight))
                    } else null
                    val progress = if (compactLive) View(this) else null
                    val track = if (progress != null) FrameLayout(this).apply {
                        setBackgroundColor(surface(.28f))
                        addView(progress, FrameLayout.LayoutParams(0, -1))
                        card.addView(this, LinearLayout.LayoutParams(-1, dp(2)).apply {
                            marginStart = dp(7); marginEnd = dp(7)
                        })
                    } else null
                    progress?.setBackgroundColor(accent)
                    if (guide != null && progress != null && track != null) loadSearchCardProgram(item, guide, progress, track)
                    card.setPadding(dp(2), dp(2), dp(2), dp(2))
                    card.setBackground(shape(surface(.10f)))
                    card.setOnFocusChangeListener { _, focused ->
                        card.setBackground(shape(if (focused) accent else surface(.10f), focused))
                        label.setTextColor(if (focused) contrast else themeForeground)
                        label.isSelected = focused
                        guide?.setTextColor(if (focused) contrast else themeForeground)
                        progress?.setBackgroundColor(if (focused) contrast else accent)
                        track?.setBackgroundColor(if (focused) ColorUtils.blendARGB(accent, contrast, .25f) else surface(.28f))
                        artwork.background = shape(if (focused) accent else surface(.10f)).apply { cornerRadius = dp(7).toFloat() }
                        if (artwork.scaleType == ImageView.ScaleType.FIT_CENTER &&
                            (!compactLive || artwork.drawable !is android.graphics.drawable.BitmapDrawable))
                            artwork.setColorFilter(if (focused) contrast else themeForeground)
                        if (focused) { showNavigation(2); updateInfo(item); if (detail == null) rememberedFocus = item.id }
                    }
                    card.setOnClickListener {
                        if (section == 0) rememberSearch()
                        if (item.type == ContentType.EPISODE || section == 0 && item.type == ContentType.LIVE) play(item)
                        else showDetails(item)
                    }
                    if (item.type != ContentType.LIVE) card.setOnLongClickListener { vodItemMenu(item, card); true }
                    else if (listMode == "history") card.setOnLongClickListener { historyItemMenu(item); true }
                    else if (item.type == ContentType.LIVE) card.setOnLongClickListener { channelMenu(item); true }
                    card.setOnKeyListener { _, key, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) false
                        else if (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                            if (section == 0 && detail == null) moveSearchFocus(holder, key, index) else {
                                val rows = orderedCardRows()
                                val neighbor = rows.getOrNull(rows.indexOf(holder) + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                                if (neighbor?.childCount == 0) cardRows[neighbor]?.invoke()
                                if (neighbor == null) false else {
                                    val cards = (neighbor.getChildAt(0) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout
                                    cards?.let { it.getChildAt(index.coerceAtMost(it.childCount - 1))?.requestFocus() } ?: false
                                }
                            }
                        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT && index == 0 && detail == null) {
                            if (section == 0) searchField?.requestFocus() else {
                                showNavigation(1)
                                (activeGroup ?: groupRows.firstOrNull() ?: navRows[section]).requestFocus()
                            }
                            true
                        } else false
                    }
                    row.addView(card, LinearLayout.LayoutParams(dp(cardWidth), -2).apply { rightMargin = dp(gap); bottomMargin = dp(10) })
                }
                scroll.addView(row)
                holder.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            }
            cardRows[holder] = populate
            cardItems[holder] = batch
            target.addView(holder, LinearLayout.LayoutParams(-1, rowHeight))
            if (batch.any { it.id == rememberedFocus }) populate()
        }
        target.post { loadVisibleArtwork(); restorePlaybackFocus() }
    }
    private fun loadSearchCardProgram(item: IptvItem, guide: TextView, progress: View, track: FrameLayout) {
        val generation = searchGeneration
        work.execute {
            if (isDestroyed || generation != searchGeneration) return@execute
            val now = System.currentTimeMillis()
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))
            val offset = (store.organization(item.sourceId, item.type, item.categoryId, "epgOffsetMinutes", "0").toIntOrNull() ?: 0)
                .coerceIn(-720, 720) * 60_000L
            val current = runCatching {
                (store.epgCached(item, "current").orEmpty() + store.epgCached(item, day).orEmpty())
                    .firstOrNull { now - offset in it.start until it.end }
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isDestroyed || section != 0 || detail != null || generation != searchGeneration || !guide.isAttachedToWindow) return@runOnUiThread
                guide.text = current.title
                val fraction = ((now - offset - current.start).toDouble() / (current.end - current.start)).coerceIn(0.0, 1.0)
                track.post {
                    progress.layoutParams = (progress.layoutParams as FrameLayout.LayoutParams).apply { width = (track.width * fraction).toInt() }
                }
            }
        }
    }
    private fun orderedCardRows(): List<FrameLayout> {
        val rows = mutableListOf<FrameLayout>()
        fun collect(parent: ViewGroup) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child is FrameLayout && child in cardRows) rows.add(child)
                else if (child is ViewGroup) collect(child)
            }
        }
        collect(results)
        return rows
    }
    private fun moveSearchFocus(origin: View, key: Int, column: Int = 0): Boolean {
        val stops = mutableListOf<View>()
        fun collect(parent: ViewGroup) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child.visibility != View.VISIBLE || !child.isEnabled) continue
                if (child is FrameLayout && child in cardRows || child.isFocusable ||
                    (child.tag as? String)?.startsWith("search-tabs:") == true) stops.add(child)
                else if (child is ViewGroup) collect(child)
            }
        }
        collect(results)
        val position = stops.indexOf(origin)
        if (position < 0) return false
        val neighbor = stops.getOrNull(position + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            ?: return if (key == KeyEvent.KEYCODE_DPAD_UP) searchField?.requestFocus() ?: false else true
        if (neighbor is FrameLayout && neighbor in cardRows) {
            if (neighbor.childCount == 0) cardRows[neighbor]?.invoke()
            val cards = (neighbor.getChildAt(0) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout
            return cards?.getChildAt(column.coerceAtMost(cards.childCount - 1))?.requestFocus() ?: false
        }
        if (neighbor is ViewGroup && (neighbor.tag as? String)?.startsWith("search-tabs:") == true) {
            val type = searchTypeGroups.keys.firstOrNull { neighbor.tag == "search-tabs:${it.name}" }
            val tab = type?.let { neighbor.findViewWithTag<View>("search-source:${it.name}:${searchSelectedSources[it]}") }
                ?: neighbor.getChildAt(0)
            return tab?.requestFocus() ?: false
        }
        return neighbor.requestFocus()
    }
    private fun loadVisibleCardRows() {
        val viewport = artworkViewport()
        val position = IntArray(2)
        val iterator = cardRows.iterator()
        while (iterator.hasNext()) {
            val (holder, populate) = iterator.next()
            if (!holder.isAttachedToWindow) { iterator.remove(); cardItems.remove(holder) }
            else if (holder.hasFocus() || isNearArtworkViewport(holder, viewport, position)) {
                if (holder.childCount == 0) populate()
            } else if (holder.childCount > 0) holder.removeAllViews()
        }
    }
    private fun createVodHero() {
        vodHero = FrameLayout(this).apply {
            visibility = View.GONE; setBackgroundColor(Color.BLACK)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN || detail == null) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { showNavigation(1); focusGroup(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { firstContent()?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
        }
        vodHeroArtwork = MainActivity.folderHeroArtworkView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        vodHero.addView(vodHeroArtwork, FrameLayout.LayoutParams(-1, -1, Gravity.RIGHT))
        vodHero.addView(View(this).apply { background = MainActivity.folderHeroEdgeFade(this@IptvActivity, Color.BLACK) },
            FrameLayout.LayoutParams(-1, -1))
        vodHeroEditorial = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(21), dp(18), dp(12), 0)
        }
        vodHeroTitle = text("", 31, true).apply {
            setTextColor(Color.WHITE); includeFontPadding = false; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        vodHeroTitleSlot = FrameLayout(this)
        vodHeroTitleSlot.addView(vodHeroTitle, FrameLayout.LayoutParams(-1, -1))
        vodHeroLogo = MainActivity.folderTitleLogoView(this).apply { visibility = View.INVISIBLE }
        vodHeroTitleSlot.addView(vodHeroLogo, FrameLayout.LayoutParams(dp(380), -1, Gravity.LEFT))
        vodHeroSeriesStatus = text("", 14).apply {
            setTextColor(Color.WHITE); visibility = View.GONE
            setSingleLine(true); includeFontPadding = false; gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = shape(Color.argb(32, 0, 0, 0)).apply {
                cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.argb(100, 255, 255, 255))
            }
        }
        vodHeroMetadata = text("", 15).apply {
            setTextColor(Color.WHITE); alpha = .82f; setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END; translationY = dp(5).toFloat()
        }
        vodHeroCredits = text("", 13).apply {
            setTextColor(Color.WHITE); alpha = .84f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        vodHeroSynopsis = text("", 15).apply {
            setTextColor(Color.WHITE); alpha = .90f; maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        vodHeroEditorial.addView(vodHeroTitleSlot, LinearLayout.LayoutParams(-1, dp((76 * textScale).toInt())))
        vodHeroEditorial.addView(vodHeroMetadata, LinearLayout.LayoutParams(-1, dp((30 * textScale).toInt())).apply { topMargin = dp(5) })
        vodHeroEditorial.addView(vodHeroCredits, LinearLayout.LayoutParams(-1, dp((36 * textScale).toInt())))
        vodHeroSynopsisBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(vodHeroSynopsis, LinearLayout.LayoutParams(-1, -2))
            addView(vodHeroSeriesStatus, LinearLayout.LayoutParams(-2, dp((26 * textScale).toInt())).apply { topMargin = dp(8) })
        }
        vodHeroEditorial.addView(vodHeroSynopsisBlock, LinearLayout.LayoutParams(0, dp((150 * textScale).toInt())))
        vodHeroEditorial.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        vodHero.addView(vodHeroEditorial, FrameLayout.LayoutParams(0, -1, Gravity.LEFT))
        vodHeroTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(2), dp(2), dp(2))
            background = shape(Color.argb(112, 30, 35, 45)).apply { cornerRadius = dp(24).toFloat() }
            clipToOutline = true
        }
        fun action(icon: Int, description: String) = ImageButton(this).apply {
            id = View.generateViewId(); isFocusable = true; contentDescription = description
            setImageResource(icon); setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false)
            setOnFocusChangeListener { _, focused ->
                if (focused) showNavigation(2)
                paintVodHeroTools()
            }
            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    KeyEvent.KEYCODE_DPAD_DOWN -> { focusVodCard(rememberedFocus); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (this === vodHeroSearch) {
                        showNavigation(1); focusGroup(); true
                    } else false
                    else -> false
                }
            }
        }
        vodHeroSearch = action(R.drawable.ic_header_search, "Rechercher").apply { setOnClickListener {
            searchReturnSaved = SavedSearchReturn(savedType, listMode == "history", rememberedFocus)
                .takeIf { listMode in listOf("favorites", "history") }
            showSection(0)
        } }
        vodHeroAction = action(R.drawable.ic_iptv_sort, "Tri").apply { setOnClickListener {
            if (listMode == "history") confirmClearHistory() else showVodSort()
        } }
        vodHeroSearch.nextFocusRightId = vodHeroAction.id
        vodHeroAction.nextFocusLeftId = vodHeroSearch.id; vodHeroAction.nextFocusRightId = vodHeroAction.id
        vodHeroTools.addView(vodHeroSearch, LinearLayout.LayoutParams(dp(40), dp(40)))
        vodHeroTools.addView(vodHeroAction, LinearLayout.LayoutParams(dp(40), dp(40)))
        vodHero.addView(vodHeroTools, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = dp(18); rightMargin = dp(18)
        })
        paintVodHeroTools()
        content.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (vodHero.visibility == View.VISIBLE && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) layoutVodHero()
        }
    }
    private fun setVodHeroVisible(visible: Boolean) {
        val wasVisible = vodHero.visibility == View.VISIBLE
        vodHero.visibility = if (visible) View.VISIBLE else View.GONE
        vodHero.isFocusable = visible && detail != null
        vodHeroAction.setImageResource(if (listMode == "history") R.drawable.ic_menu_delete else R.drawable.ic_iptv_sort)
        vodHeroAction.contentDescription = if (listMode == "history") "Effacer l’historique" else "Tri"
        vodHeroTools.visibility = if (visible && detail == null && (section in 2..3 && listMode == "catalogue" ||
            listMode in listOf("favorites", "history") && savedType in listOf(ContentType.MOVIE, ContentType.SERIES))) View.VISIBLE else View.GONE
        informationHeader.visibility = if (visible) View.GONE else View.VISIBLE
        status.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            previewFrame.visibility = View.GONE
            content.setPadding(0, 0, 0, dp(13))
            content.setBackgroundColor(Color.BLACK)
            layoutVodHero()
        } else {
            vodHeroRequest?.let(handler::removeCallbacks); vodHeroRequest = null
            vodHeroScope?.cancel(); vodHeroScope = null; vodHeroItemId = ""
            mediaRequest?.let(handler::removeCallbacks); mediaRequest = null
            mediaScope?.cancel(); mediaScope = null; mediaRequestKey = ""
            calendarRefresh?.let(handler::removeCallbacks); calendarRefresh = null
            if (wasVisible) {
                content.setPadding(dp(20), dp(20), dp(20), dp(12))
                content.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }
    private fun paintVodHeroTools() {
        if (!::vodHeroTools.isInitialized) return
        for (index in 0 until vodHeroTools.childCount) (vodHeroTools.getChildAt(index) as ImageButton).apply {
            background = shape(if (hasFocus()) accent else Color.TRANSPARENT, hasFocus()).apply { cornerRadius = dp(20).toFloat() }
            setColorFilter(if (hasFocus()) contrast else Color.WHITE)
        }
    }
    private fun showVodSort() {
        val favorites = listMode == "favorites"
        val source = if (favorites) "" else selectedSource()?.id ?: return
        val type = if (favorites) savedType else contentType()
        val group = if (favorites) "favorites" else category
        val key = IptvOrganization.key(source, type, group, "channelSort")
        val current = if (favorites) store.setting(key, "playlist") else store.organization(source, type, group, "channelSort", "playlist")
        settingsPanel("Tri") { rows, dialog ->
            listOf("playlist" to if (favorites) "Par ordre dans la liste" else "Par ordre dans la playlist", "name" to "Par nom",
                "rating" to "Par note", "added" to "Par date d'ajout").forEach { (value, label) ->
                rows.addView(settingsRadioRow(label, current == value) {
                    dialog.dismiss()
                    async({ store.setSetting(key, value) }) {
                        if (favorites) showSaved(type, false) else refreshOrganization()
                        focusVodCard(vodCardItems.firstOrNull()?.id.orEmpty())
                    }
                }.apply { tag = value }, LinearLayout.LayoutParams(-1, dp(50)))
            }
            rows.findViewWithTag<View>(current) ?: rows.getChildAt(0)
        }
    }
    private fun layoutVodHero() {
        if (content.width <= 0 || content.height <= 0) return
        val statusHeight = if (status.visibility == View.VISIBLE) dp(25) else 0
        val available = content.height - content.paddingBottom - statusHeight
        if (detail?.type == ContentType.SERIES) {
            // Same bounded hero overlap and rail offsets as Home's series folder.
            val height = dp(399)
            val overlap = minOf(-dp(40), available - height - dp(35 + 20) - seriesEpisodeRailHeight())
                .coerceAtLeast(dp(190) - height)
            vodHero.layoutParams = (vodHero.layoutParams as LinearLayout.LayoutParams).apply {
                this.height = height; bottomMargin = overlap
            }
            vodEpisodeScroll?.let { rail ->
                rail.layoutParams = (rail.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = (available - height - overlap - dp(35) - seriesEpisodeRailHeight()).coerceAtLeast(0)
                }
            }
        } else {
            var posterRailHeight = dp(if (detail == null) 186 else 190)
            if (detail == null && listMode == "favorites") {
                // Posters fill their grid span, so reserve a full 2:3 row with its margins and padding.
                val gridWidth = (content.width - dp(12) * 2).coerceAtLeast(1)
                val columns = IptvOrganization.vodGridColumns(gridWidth, dp(120))
                val posterWidth = ((gridWidth + columns - 1) / columns - dp(4) * 2).coerceAtLeast(1)
                posterRailHeight = maxOf(posterRailHeight, posterWidth * 3 / 2 + dp(6) * 4)
            }
            val railHeight = posterRailHeight +
                if (detail == null && listMode in listOf("history", "favorites")) dp((28 * textScale).toInt()) else 0
            val height = (available - railHeight).coerceAtLeast(dp(190))
            val movie = (vodHeroBoundItem?.type ?: contentType()) == ContentType.MOVIE
            val extension = if (detail == null) dp(if (movie) 20 else 10) else 0
            vodHero.layoutParams = (vodHero.layoutParams as LinearLayout.LayoutParams).apply {
                this.height = height + extension; bottomMargin = -extension
            }
        }
        // Reserve visible bounds before loading: viewport-deferred images cannot size a wrap-content view.
        // Crop within these bounds so the narrower groups view cannot letterbox the Hero.
        vodHeroArtwork.layoutParams = (vodHeroArtwork.layoutParams as FrameLayout.LayoutParams).apply {
            width = (content.width * .75f).toInt()
        }
        val editorialWidth = minOf(dp(530), content.width / 2)
        vodHeroEditorial.layoutParams = (vodHeroEditorial.layoutParams as FrameLayout.LayoutParams).apply { width = editorialWidth }
        vodHeroTitleSlot.layoutParams.height = dp((76 * textScale).toInt())
        vodHeroLogo.layoutParams.width = minOf(dp(380), editorialWidth - dp(33))
        vodHeroMetadata.layoutParams.height = dp((30 * textScale).toInt())
        vodHeroCredits.layoutParams.height = dp((36 * textScale).toInt())
        vodHeroSynopsisBlock.layoutParams = (vodHeroSynopsisBlock.layoutParams as LinearLayout.LayoutParams).apply {
            width = ((editorialWidth - dp(33)).coerceAtLeast(1) * .70f).toInt()
            this.height = dp((150 * textScale).toInt())
        }
    }
    private fun bindVodHero(item: IptvItem) {
        fun bind(value: IptvItem) {
            vodHeroBoundItem = value
            val presentation = mediaDetails.get(mediaKey(value))
            bindVodPresentation(value, presentation)
            requestVodPresentation(value)
        }
        bind(if (detail != null) item else vodHeroDetails.get(item.id) ?: item)
        backdrop.visibility = View.GONE; backdrop.tag = ""; backdrop.setImageDrawable(null)
        if (detail != null) return
        if (vodHeroItemId == item.id) return
        vodHeroItemId = item.id
        vodHeroRequest?.let(handler::removeCallbacks)
        vodHeroScope?.cancel(); vodHeroScope = null
        if (vodHeroDetails.get(item.id) != null || item.backdrop.isNotBlank() && item.description.isNotBlank()) return
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return
        val version = requestVersion
        // Only the settled selection requests its own provider details; late replies cannot replace another title.
        vodHeroRequest = Runnable {
            val scope = IptvRequestScope(IptvHttp.client).also { vodHeroScope = it }
            mediaWork.execute {
                val value = runCatching { IptvHttp.withScope(scope) { provider(source).details(item) } }.getOrNull()
                runOnUiThread {
                    if (value == null || isDestroyed || version != requestVersion || vodHeroScope !== scope ||
                        vodHero.visibility != View.VISIBLE || vodHeroItemId != item.id) return@runOnUiThread
                    vodHeroDetails.put(item.id, value)
                    bind(value)
                }
            }
        }.also { handler.postDelayed(it, 180) }
    }

    private fun mediaKey(item: IptvItem) = IptvMediaPresentation.cacheKey(item)

    private fun vodTitleLogo(item: IptvItem, presentation: IptvMediaPresentation.Details?): String =
        IptvMediaPresentation.titleLogo(item, presentation,
            OnlineAppIconLoader.cachedTitleLogo(this, if (item.type == ContentType.MOVIE) "movie" else "tv",
                presentation?.tmdbId ?: item.tmdbId)
        ).ifBlank {
            com.jojotv.home.OfficialProgrammeLogoResolver.cached(this, item.title,
                presentation?.tmdbId ?: item.tmdbId, if (item.type == ContentType.MOVIE) "movie" else "tv")
        }

    private fun bindVodPresentation(item: IptvItem, presentation: IptvMediaPresentation.Details?) {
        // Rebind artwork for persisted TMDB details as well as fresh responses.
        loadImage(vodHeroArtwork, "", item, minOf(resources.displayMetrics.widthPixels, 1920), minOf(dp(400), 1080))
        val selectedEpisode = if (detail?.id == item.id && item.type == ContentType.SERIES && focusedSeriesEpisode.isNotBlank())
            IptvMediaPresentation.mergeEpisodes(item, detailEpisodes, presentation?.episodes.orEmpty())
                .firstOrNull { it.id == focusedSeriesEpisode } else null
        val editorial = IptvMediaPresentation.editorial(item, presentation, selectedEpisode)
        val minutes = HomeMediaRules.providerRuntimeMinutes(editorial.duration)
        val runtime = if (minutes <= 0) "" else if (minutes < 60) "$minutes min"
            else String.format(Locale.ROOT, "%dh%02d", minutes / 60, minutes % 60)
        vodHeroTitle.text = IptvMediaPresentation.searchTitles(item.title).firstOrNull() ?: item.title
        vodHeroMetadata.text = listOf(editorial.genre, editorial.year.filter(Char::isDigit).take(4), runtime)
            .filter(String::isNotBlank).joinToString("  •  ")
        vodHeroCredits.text = IptvMediaPresentation.credits(editorial,
            presentation?.mediaType == "tv" && presentation.cast.any { HomeMediaRules.isTmdbPresenter(it.character) })
        vodHeroCredits.visibility = if (vodHeroCredits.text.isBlank()) View.GONE else View.VISIBLE
        vodHeroSynopsis.text = editorial.description
        val logo = vodTitleLogo(item, presentation)
        presentation?.let { OnlineAppIconLoader.rememberTitleLogo(this, it.mediaType, it.tmdbId, logo) }
        val awaitingMetadata = presentation == null && item.type in listOf(ContentType.MOVIE, ContentType.SERIES)
        val key = "${item.id}|$awaitingMetadata|$logo"
        if (key != vodHeroLogoKey) {
            vodHeroLogoKey = key
            vodHeroLogo.setImageDrawable(null); vodHeroLogo.visibility = View.INVISIBLE
            vodHeroTitle.visibility = if (logo.isBlank() && !awaitingMetadata && presentation == null) View.VISIBLE else View.INVISIBLE
            if (logo.isNotBlank()) {
                fun apply(bitmap: Bitmap?) {
                    if (bitmap == null || isDestroyed || vodHeroLogoKey != key || vodHeroBoundItem?.id != item.id) return
                    bitmaps.put(key, bitmap)
                    vodHeroLogo.setImageBitmap(bitmap); vodHeroLogo.visibility = View.VISIBLE
                    vodHeroTitle.visibility = View.INVISIBLE
                }
                (bitmaps.get(key) ?: OnlineAppIconLoader.cachedTitleLogoBitmap(logo))?.let(::apply)
                    ?: OnlineAppIconLoader.loadTitleLogoBitmap(this, logo) { bitmap ->
                        if (isDestroyed || vodHeroLogoKey != key || vodHeroBoundItem?.id != item.id) return@loadTitleLogoBitmap
                        if (bitmap == null) {
                            vodHeroLogoKey = ""
                            vodHeroTitle.visibility = View.VISIBLE
                        } else apply(bitmap)
                    }
            }
        }
        if (presentation != null && logo.isBlank()) {
            val version = requestVersion
            val account = store.selectedAccount()?.id.orEmpty()
            com.jojotv.home.OfficialProgrammeLogoResolver.resolve(this, item.title,
                presentation.tmdbId, presentation.mediaType) { found ->
                if (isDestroyed || version != requestVersion || vodHeroBoundItem?.id != item.id || vodHeroLogoKey != key ||
                    account != store.selectedAccount()?.id.orEmpty()) return@resolve
                val current = mediaDetails.get(mediaKey(item)) ?: presentation
                if (current.tmdbId != presentation.tmdbId || current.logo.isNotBlank()) return@resolve
                if (found.isBlank()) { vodHeroTitle.visibility = View.VISIBLE; return@resolve }
                val updated = current.copy(logo = found)
                mediaDetails.put(mediaKey(item), updated)
                val persistentKey = "native-media-v${HomeMediaRules.TITLE_LOGO_POLICY_VERSION}|${mediaKey(item)}"
                getSharedPreferences("home_hero_editorial_cache", MODE_PRIVATE).edit()
                    .putString(persistentKey, iptvJson.encodeToString(updated))
                    .putLong("native-media-at|$persistentKey", System.currentTimeMillis()).apply()
                bindVodPresentation(item, updated)
            }
        }
        vodHeroSeriesStatus.text = HomeMediaRules.seriesStatusBadgeText(
            IptvMediaPresentation.seriesBroadcastStatus(presentation), presentation?.seasonCount ?: 0)
        vodHeroSeriesStatus.visibility = if (item.type == ContentType.SERIES && vodHeroSeriesStatus.text.isNotBlank()) View.VISIBLE else View.GONE
        vodHeroSynopsis.maxLines = if (vodHeroSeriesStatus.visibility == View.GONE) 8 else
            ((dp((150 * textScale).toInt()) - dp((26 * textScale).toInt()) - dp(8)) / vodHeroSynopsis.lineHeight.coerceAtLeast(1)).coerceIn(1, 8)
    }

    private fun requestVodPresentation(item: IptvItem) {
        if (item.type !in listOf(ContentType.MOVIE, ContentType.SERIES)) return
        val cacheKey = mediaKey(item)
        val calendar = detailLoaded && detail?.id == item.id && item.type == ContentType.SERIES
        val key = "$cacheKey|$calendar|$requestVersion"
        if (mediaRequestKey == key) return
        mediaRequestKey = key
        mediaRequest?.let(handler::removeCallbacks); mediaRequest = null
        mediaScope?.cancel(); mediaScope = null
        val cache = getSharedPreferences("home_hero_editorial_cache", MODE_PRIVATE)
        val persistentKey = "native-media-v${HomeMediaRules.TITLE_LOGO_POLICY_VERSION}|$cacheKey"
        if (mediaDetails.get(cacheKey) == null && System.currentTimeMillis() - cache.getLong("native-media-at|$persistentKey", 0) < 86_400_000L) {
            runCatching { iptvJson.decodeFromString<IptvMediaPresentation.Details>(cache.getString(persistentKey, "").orEmpty()) }
                .getOrNull()?.let { mediaDetails.put(cacheKey, it); bindVodPresentation(item, it) }
        }
        val cached = mediaDetails.get(cacheKey)
        if (!calendar && cached != null && (item.type != ContentType.SERIES || cached.broadcastStatusLoaded)) return
        val version = requestVersion
        val account = store.selectedAccount()?.id.orEmpty()
        mediaRequest = Runnable {
            val scope = IptvRequestScope(IptvHttp.client).also { mediaScope = it }
            val lookup = item.copy(tmdbId = item.tmdbId.takeIf { it > 0 } ?: mediaDetails.get(cacheKey)?.tmdbId ?: 0,
                titleLogo = vodTitleLogo(item, mediaDetails.get(cacheKey)))
            mediaWork.execute {
                var published = false
                runCatching { IptvHttp.withScope(scope) { IptvMediaPresentation.fetch(lookup, calendar) { value ->
                    published = true
                    runOnUiThread {
                        if (isDestroyed || version != requestVersion || mediaScope !== scope || mediaRequestKey != key ||
                            vodHeroBoundItem?.id != item.id || vodHero.visibility != View.VISIBLE || account != store.selectedAccount()?.id.orEmpty()) return@runOnUiThread
                        val old = mediaDetails.get(cacheKey)
                        var assets = if (value.status == "EN COURS" && value.episodes.isEmpty() && old != null)
                            value.copy(seasons = old.seasons, episodes = old.episodes) else value
                        if (!value.broadcastStatusLoaded && old != null && old.tmdbId == value.tmdbId && old.broadcastStatusLoaded)
                            assets = assets.copy(broadcastSeason = old.broadcastSeason, broadcastStatusLoaded = true)
                        mediaDetails.put(cacheKey, assets)
                        cache.edit().putString(persistentKey, iptvJson.encodeToString(assets))
                            .putLong("native-media-at|$persistentKey", System.currentTimeMillis()).apply()
                        bindVodPresentation(item, assets)
                        if (detail?.id == item.id && detailLoaded && contextPanel == null && vodPopup?.isShowing != true) renderVodDetails()
                    }
                } } }
                if (!published) runOnUiThread {
                    if (isDestroyed || version != requestVersion || mediaScope !== scope || mediaRequestKey != key ||
                        vodHeroBoundItem?.id != item.id || vodHero.visibility != View.VISIBLE || account != store.selectedAccount()?.id.orEmpty() ||
                        mediaDetails.get(cacheKey) != null) return@runOnUiThread
                    bindVodPresentation(item, IptvMediaPresentation.Details(lookup.tmdbId,
                        if (item.type == ContentType.MOVIE) "movie" else "tv"))
                }
            }
        }.also { handler.postDelayed(it, if (detail == null) 180 else 0) }
    }

    private fun vodArtworkIdentity(item: IptvItem, hero: Boolean): String {
        val parent = if (item.type == ContentType.EPISODE) itemId(item.sourceId, ContentType.SERIES, item.seriesId) else item.id
        // Home shares an episode choice across all seasons independently from the series poster.
        return "external-playback|" + if (item.type == ContentType.EPISODE && !hero) "$parent|episodes"
            else store.playbackContentId(parent)
    }

    private fun selectedVodArtwork(item: IptvItem, hero: Boolean = false): String {
        val identity = vodArtworkIdentity(item, hero)
        return MainActivity.selectedExternalArtwork(this, if (hero) MainActivity.externalHeroArtworkKey(identity)
            else MainActivity.externalThumbnailArtworkKey(identity))
    }

    private fun renderVodDetails() {
        val item = detail ?: return
        val presentation = mediaDetails.get(mediaKey(item))
        val episodes = if (item.type == ContentType.SERIES) IptvMediaPresentation.mergeEpisodes(item, detailEpisodes, presentation?.episodes.orEmpty()) else emptyList()
        detailActions(item, episodes, loading = !detailLoaded)
    }

    private fun refreshVodProgress() {
        val item = detail ?: return
        if (item.type !in listOf(ContentType.MOVIE, ContentType.SERIES)) return
        val version = requestVersion
        val account = store.selectedAccount()?.id.orEmpty()
        val profile = HomeWatchProgressBridge.currentProfileId(this)
        val generation = ++detailProgressGeneration
        detailProgressTask?.cancel(true)
        detailProgressTask = progressWork.submit {
            if (generation != detailProgressGeneration || version != requestVersion) return@submit
            val progress = runCatching { store.vodProgress(item) }.getOrNull() ?: return@submit
            runOnUiThread {
                if (isDestroyed || generation != detailProgressGeneration || version != requestVersion || detail?.id != item.id ||
                    account != store.selectedAccount()?.id.orEmpty() || profile != HomeWatchProgressBridge.currentProfileId(this)) return@runOnUiThread
                if (detailProgress == progress) return@runOnUiThread
                detailProgress = progress
                if (item.type == ContentType.SERIES) updateSeriesEpisodeProgress()
                else if (contextPanel == null && vodPopup?.isShowing != true) renderVodDetails()
            }
        }
    }

    private fun addVodCast(item: IptvItem) {
        val cast = mediaDetails.get(mediaKey(item))?.cast.orEmpty()
        if (cast.isEmpty()) return
        results.addView(text(if (cast.any { HomeMediaRules.isTmdbPresenter(it.character) }) "Pr\u00e9sentation" else "Acteurs", 18, true)
            .apply { setTextColor(Color.WHITE); setPadding(dp(21), dp(12), 0, 0) })
        val row = LinearLayout(this)
        cast.forEach { person ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; id = View.generateViewId()
                tag = "cast:${person.id}"; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(5), dp(4), dp(5), dp(4))
            }
            val frame = FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(47, 63, 91)) }
                clipToOutline = true
            }
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(image, FrameLayout.LayoutParams(-1, -1)); tile.addView(frame, LinearLayout.LayoutParams(dp(57), dp(57)))
            loadImage(image, person.artwork, targetWidth = dp(57), targetHeight = dp(57))
            val label = text(person.name, 11).apply { gravity = Gravity.CENTER; maxLines = 2; setTextColor(Color.WHITE) }
            tile.addView(label, LinearLayout.LayoutParams(-1, dp(34)))
            tile.contentDescription = "${person.name} • ${person.character}"
            tile.setOnFocusChangeListener { _, focused ->
                tile.background = shape(if (focused) accent else Color.TRANSPARENT, focused)
                label.setTextColor(if (focused) contrast else Color.WHITE)
            }
            row.addView(tile, LinearLayout.LayoutParams(dp(82), -2).apply { rightMargin = dp(14) })
        }
        wireSeriesHorizontalFocus(row)
        val first = row.getChildAt(0)
        val episodes = results.findViewWithTag<LinearLayout>("vod_episode_row")
        val previous = results.findViewWithTag<View>(focusedSeriesEpisode)?.id ?: episodes?.getChildAt(0)?.id
            ?: results.findViewWithTag<View>("play:${item.id}")?.id
        for (index in 0 until row.childCount) {
            row.getChildAt(index).nextFocusUpId = previous ?: row.getChildAt(index).id
            row.getChildAt(index).nextFocusDownId = row.getChildAt(index).id
        }
        if (episodes != null) for (index in 0 until episodes.childCount) episodes.getChildAt(index).nextFocusDownId = first.id
        else results.findViewWithTag<View>("play:${item.id}")?.nextFocusDownId = first.id
        results.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; setPadding(dp(21), dp(8), dp(21), dp(12)); addView(row)
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun vodProgressBadge(watched: Boolean = false): TextView = text(if (watched) "\u2713" else "En cours", if (watched) 12 else 9, true).apply {
        setTextColor(contrast); gravity = Gravity.CENTER; setSingleLine(true); includeFontPadding = false
        contentDescription = if (watched) "\u00c9pisode vu" else "En cours de lecture"
        background = GradientDrawable().apply {
            setColor(accent); setStroke(dp(1), Color.rgb(235, 244, 255))
            if (watched) shape = GradientDrawable.OVAL else cornerRadius = dp(7).toFloat()
        }
    }

    private fun updateSeriesEpisodeProgress() {
        seriesEpisodeProgressFrames.forEach { (episode, frame) ->
            frame.findViewWithTag<View>("series_episode_progress")?.let(frame::removeView)
            if (frame.findViewWithTag<View>("series_episode_air_date") != null) return@forEach
            val progress = detailProgress.firstOrNull { it.season == episode.first && it.episode == episode.second }
            val watched = progress?.isCompleted() == true
            if (watched || progress != null && progress.position > 0) {
                val badge = vodProgressBadge(watched).apply { tag = "series_episode_progress" }
                frame.addView(badge, 1, FrameLayout.LayoutParams(dp(if (watched) 22 else 58), dp(22), Gravity.TOP or Gravity.RIGHT)
                    .apply { setMargins(0, dp(5), dp(5), 0) })
            }
        }
    }

    private fun refreshVodCardProgress() {
        val rail = vodCards ?: return
        val generation = ++vodCardProgressGeneration
        val version = requestVersion
        val account = store.selectedAccount()?.id.orEmpty()
        vodCardProgressTask?.cancel(true)
        vodCardProgressTask = progressWork.submit {
            if (generation != vodCardProgressGeneration || version != requestVersion) return@submit
            val snapshot = runCatching { store.vodProgress().groupBy { it.contentType to it.contentId } }.getOrNull() ?: return@submit
            runOnUiThread {
                if (isDestroyed || generation != vodCardProgressGeneration || version != requestVersion ||
                    rail !== vodCards || account != store.selectedAccount()?.id.orEmpty()) return@runOnUiThread
                vodCardProgress.clear()
                vodCardProgress.putAll(snapshot)
                rail.adapter?.notifyItemRangeChanged(0, vodCardItems.size, "progress")
            }
        }
    }

    private fun vodCardsKey() = listOf(section, listMode, sourceId, category, savedType,
        accent, contrast, themeForeground, themeBackground, textScale)

    private fun updateVodCards(items: List<IptvItem>): Boolean {
        val rail = vodCards ?: return false
        if (detail != null || rail.parent !== results || rail.tag != vodCardsKey() ||
            items.isEmpty() || items.any { it.type !in listOf(ContentType.MOVIE, ContentType.SERIES) &&
                !(listMode == "history" && it.type == ContentType.EPISODE) }) return false
        val previous = vodCardItems.toList()
        val focusedId = rail.findFocus()?.tag as? String
        val focusedIndex = previous.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        if (previous != items) {
            val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = items.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].id == items[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == items[newItemPosition]
            })
            vodCardItems.clear(); vodCardItems.addAll(items)
            rail.adapter?.let(changes::dispatchUpdatesTo)
            if (focusedId != null) {
                showNavigation(2)
                focusVodCard(items.firstOrNull { it.id == focusedId }?.id ?: items[focusedIndex.coerceAtMost(items.lastIndex)].id)
            }
        }
        val selected = items.firstOrNull { it.id == focusedId.orEmpty().ifBlank { rememberedFocus } } ?: items.first()
        results.findViewWithTag<TextView>("vod-position")?.text = "${items.indexOf(selected) + 1} / ${items.size}"
        status.text = categoryTitle
        updateInfo(selected)
        refreshVodCardProgress()
        return true
    }

    private fun addVodCards(items: List<IptvItem>) {
        if (items.isEmpty()) return
        val start = vodCardItems.size
        val known = vodCardItems.mapTo(hashSetOf()) { it.id }
        vodCardItems.addAll(items.filter { known.add(it.id) })
        val existing = vodCards
        if (existing != null) {
            existing.adapter?.notifyItemRangeInserted(start, vodCardItems.size - start)
            existing.post { restorePlaybackFocus() }
            return
        }
        class PosterHolder(val card: FrameLayout, val artwork: ImageView, val border: View, val badge: TextView) : RecyclerView.ViewHolder(card) {
            var item: IptvItem? = null
        }
        val posterWidth = dp(120)
        val posterHeight = dp(180)
        val savedPosition = if (listMode in listOf("history", "favorites")) text(
            "${vodCardItems.indexOfFirst { it.id == rememberedFocus }.coerceAtLeast(0) + 1} / ${vodCardItems.size}", 15
        ).apply { tag = "vod-position"; setTextColor(Color.WHITE); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL } else null
        val savedHeaderHeight = if (savedPosition != null) dp((28 * textScale).toInt()) else 0
        val rail = object : RecyclerView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                // Span count must be ready before RecyclerView measures or binds its first poster.
                val columns = IptvOrganization.vodGridColumns(
                    View.MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight, posterWidth)
                (layoutManager as? GridLayoutManager)?.let { if (it.spanCount != columns) it.spanCount = columns }
                super.onMeasure(widthSpec, heightSpec)
            }
        }.apply {
            tag = vodCardsKey()
            layoutManager = GridLayoutManager(this@IptvActivity,
                IptvOrganization.vodGridColumns(content.width - dp(24), posterWidth))
            isFocusable = false; clipToPadding = false; clipChildren = false
            isNestedScrollingEnabled = false
            setPadding(dp(12), dp(6), dp(12), dp(6))
            itemAnimator = null
        }
        rail.adapter = object : RecyclerView.Adapter<PosterHolder>() {
            override fun getItemCount() = vodCardItems.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterHolder {
                val card = object : FrameLayout(this@IptvActivity) {
                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        val height = View.MeasureSpec.getSize(widthSpec) * 3 / 2
                        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    }
                }.apply {
                    id = View.generateViewId(); isFocusable = true
                    if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false)
                    background = shape(Color.TRANSPARENT).apply { cornerRadius = dp(12).toFloat() }
                    clipToOutline = true
                    // Fill each span; fixed-width posters leave an extra gap after every column.
                    layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                        leftMargin = dp(4); rightMargin = dp(4)
                        topMargin = dp(6); bottomMargin = dp(6)
                    }
                }
                val artwork = ImageView(this@IptvActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                val border = View(this@IptvActivity).apply {
                    alpha = 0f
                    background = shape(Color.TRANSPARENT).apply {
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(2), if (ColorUtils.calculateContrast(accent, Color.BLACK) < 1.5) Color.WHITE else accent)
                    }
                }
                card.addView(artwork, FrameLayout.LayoutParams(-1, -1))
                val badge = vodProgressBadge().apply { visibility = View.GONE }
                card.addView(badge, FrameLayout.LayoutParams(dp(58), dp(22), Gravity.TOP or Gravity.LEFT).apply {
                    setMargins(dp(5), dp(5), 0, 0)
                })
                card.addView(border, FrameLayout.LayoutParams(-1, -1))
                val holder = PosterHolder(card, artwork, border, badge)
                card.setOnFocusChangeListener { _, focused ->
                    border.animate().alpha(if (focused) 1f else 0f).setDuration(120).start()
                    val scale = if (focused) 1.04f else 1f
                    artwork.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                    if (artwork.drawable !is android.graphics.drawable.BitmapDrawable) {
                        artwork.setBackgroundColor(if (focused) accent else Color.TRANSPARENT)
                        artwork.setColorFilter(if (focused) contrast else Color.WHITE)
                    }
                    if (focused && rail === vodCards) holder.item?.let {
                        showNavigation(2); rememberedFocus = it.id; updateInfo(it)
                        savedPosition?.text = "${holder.bindingAdapterPosition + 1} / ${vodCardItems.size}"
                    }
                }
                card.setOnClickListener { holder.item?.let { item ->
                    val selected = vodHeroBoundItem?.takeIf { it.id == item.id } ?: item
                    if (item.type == ContentType.EPISODE) play(selected) else showDetails(selected)
                } }
                card.setOnLongClickListener { holder.item?.let { vodItemMenu(it, card) }; true }
                card.setOnKeyListener { _, key, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_MENU) {
                        if (event.repeatCount == 0) card.performLongClick()
                        true
                    } else if (event.action == KeyEvent.ACTION_DOWN && key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                        val position = holder.bindingAdapterPosition
                        val columns = (rail.layoutManager as GridLayoutManager).spanCount
                        if (position == RecyclerView.NO_POSITION) true
                        else if (key == KeyEvent.KEYCODE_DPAD_LEFT && position % columns == 0) {
                            showNavigation(1); focusGroup(); true
                        } else if (key == KeyEvent.KEYCODE_DPAD_UP && position < columns && vodHeroTools.visibility == View.VISIBLE) {
                            vodHeroSearch.requestFocus(); true
                        } else {
                            val direction = when (key) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                                KeyEvent.KEYCODE_DPAD_UP -> "up"; else -> "down"
                            }
                            val target = IptvOrganization.vodGridTarget(position, vodCardItems.size, columns, direction)
                            if (target != position && target >= 0) focusVodCard(vodCardItems[target].id)
                            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) results.findViewWithTag<View>("history-clear")?.requestFocus()
                            true
                        }
                    } else false
                }
                return holder
            }
            private fun bindProgress(holder: PosterHolder, item: IptvItem) {
                val contentId = store.playbackContentId(IptvStore.homeContentId(item))
                val progress = vodCardProgress[(if (item.type == ContentType.MOVIE) "movie" else "series") to contentId].orEmpty()
                    .let { records -> if (item.type == ContentType.EPISODE) records.filter {
                        it.season == item.season && it.episode == item.episode
                    } else records }
                val started = if (item.type == ContentType.SERIES) IptvStore.homeFavoriteSeriesStarted(contentId, progress)
                    else IptvStore.homeFavoritePlaybackProgress(contentId, if (item.type == ContentType.MOVIE) "movies" else "series", progress)
                        ?.let { it.position > 0 && !it.isCompleted() } == true
                holder.badge.visibility = if (started) View.VISIBLE else View.GONE
                (holder.badge.layoutParams as FrameLayout.LayoutParams).apply {
                    gravity = Gravity.TOP or if (item.type == ContentType.SERIES) Gravity.LEFT else Gravity.RIGHT
                    leftMargin = if (item.type == ContentType.SERIES) dp(5) else 0
                    rightMargin = if (item.type == ContentType.SERIES) 0 else dp(5)
                    holder.badge.layoutParams = this
                }
            }
            override fun onBindViewHolder(holder: PosterHolder, position: Int, payloads: MutableList<Any>) {
                if (payloads.isNotEmpty() && payloads.all { it == "progress" }) bindProgress(holder, vodCardItems[position])
                else onBindViewHolder(holder, position)
            }
            override fun onBindViewHolder(holder: PosterHolder, position: Int) {
                val item = vodCardItems[position]
                holder.border.animate().cancel(); holder.artwork.animate().cancel()
                holder.item = item; holder.card.tag = item.id; holder.card.contentDescription = item.title
                bindProgress(holder, item)
                holder.border.alpha = if (holder.card.hasFocus()) 1f else 0f
                holder.artwork.scaleX = if (holder.card.hasFocus()) 1.04f else 1f
                holder.artwork.scaleY = holder.artwork.scaleX
                holder.artwork.setBackgroundColor(Color.TRANSPARENT)
                loadImage(holder.artwork, selectedVodArtwork(item).ifBlank { item.artwork }, item, posterWidth, posterHeight)
                if (holder.artwork.drawable !is android.graphics.drawable.BitmapDrawable) {
                    holder.artwork.setBackgroundColor(if (holder.card.hasFocus()) accent else Color.TRANSPARENT)
                    holder.artwork.setColorFilter(if (holder.card.hasFocus()) contrast else Color.WHITE)
                }
            }
            override fun onViewAttachedToWindow(holder: PosterHolder) {
                if (holder.artwork !in artworkLoads) holder.item?.let {
                    loadImage(holder.artwork, selectedVodArtwork(it).ifBlank { it.artwork }, it, posterWidth, posterHeight)
                }
            }
            override fun onViewRecycled(holder: PosterHolder) {
                artworkViewScopes.remove(holder.artwork)?.cancel()
                pendingArtwork.remove(holder.artwork); artworkLoads.remove(holder.artwork); artworkPlaceholders.remove(holder.artwork)
                holder.artwork.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.artwork.tag = ""; holder.artwork.setImageDrawable(null); holder.item = null
            }
        }
        vodCards = rail
        fun resizeGrid() {
            val viewport = results.parent as? View ?: return
            if (rail.width <= 0 || viewport.height <= 0 || rail !== vodCards) return
            val height = (viewport.height - savedHeaderHeight).coerceAtLeast(1)
            if (rail.layoutParams.height != height) {
                rail.layoutParams = (rail.layoutParams as LinearLayout.LayoutParams).apply { this.height = height }
            }
        }
        val viewport = results.parent as? View
        val resizeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            resizeGrid()
            if (rail === vodCards) resumeGroupEntry()
        }
        rail.addOnLayoutChangeListener(resizeListener)
        rail.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { viewport?.addOnLayoutChangeListener(resizeListener) }
            override fun onViewDetachedFromWindow(view: View) { viewport?.removeOnLayoutChangeListener(resizeListener) }
        })
        if (savedPosition != null) results.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0)
            addView(text(categoryTitle, 16, true).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(savedPosition, LinearLayout.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(-1, savedHeaderHeight))
        results.addView(rail, LinearLayout.LayoutParams(-1, dp(186)))
        rail.post { resizeGrid() }
        refreshVodCardProgress()
        val focusInput = navigationInputVersion
        rail.post {
            if (focusInput == navigationInputVersion && rail === vodCards && detail == null && navigationMode == 2 &&
                !rail.hasFocus() && !vodHeroTools.hasFocus()) {
                if (restoringPlaybackFocus) restorePlaybackFocus() else focusVodCard(rememberedFocus)
            }
        }
    }
    private fun focusVodCard(id: String) {
        val request = ++vodFocusRequest
        if (navigationMode != 2 || detail != null) return
        val rail = vodCards ?: return
        val position = vodCardItems.indexOfFirst { it.id == id }.coerceAtLeast(0)
        if (vodCardItems.isEmpty()) return
        val targetId = vodCardItems[position].id
        val focusInput = navigationInputVersion
        rail.findViewHolderForAdapterPosition(position)?.itemView?.let { it.requestFocus(); return }
        rail.scrollToPosition(position)
        rail.doOnNextLayout {
            if (request == vodFocusRequest && focusInput == navigationInputVersion && rail === vodCards && detail == null &&
                navigationMode == 2 && contextPanel == null && sourcePage == null && accountPage == null && recordingPage == null) {
                val currentPosition = vodCardItems.indexOfFirst { it.id == targetId }
                if (currentPosition >= 0) rail.findViewHolderForAdapterPosition(currentPosition)?.itemView?.requestFocus()
            }
        }
    }
    private fun rememberPlaybackFocus(tag: String) {
        playbackReturnFocus = PlaybackFocus(tag, store.selectedAccount()?.id.orEmpty(), section, detail?.id)
        restoringPlaybackFocus = false
    }

    private fun restorePlaybackFocus(): Boolean {
        val selection = playbackReturnFocus ?: return false
        if (!restoringPlaybackFocus || !activityResumed || !ready || contextPanel != null ||
            sourcePage != null || vodPopup?.isShowing == true || accountPage != null || recordingPage != null) return false
        if (selection.accountId != store.selectedAccount()?.id.orEmpty() || selection.section != section || selection.detailId != detail?.id) {
            playbackReturnFocus = null; restoringPlaybackFocus = false
            return false
        }
        val rail = vodCards
        val target = if (rail != null) {
            val position = vodCardItems.indexOfFirst { it.id == selection.tag }
            if (position < 0) return false
            val card = rail.findViewHolderForAdapterPosition(position)?.itemView
            if (card == null) {
                rail.scrollToPosition(position)
                rail.doOnNextLayout {
                    if (rail === vodCards && selection == playbackReturnFocus) restorePlaybackFocus()
                }
                return false
            }
            card
        } else {
            // Search rows may be recycled while the player owns the screen.
            cardItems.entries.firstOrNull { (_, items) -> items.any { it.id == selection.tag } }?.key?.let {
                if (it.childCount == 0) cardRows[it]?.invoke()
            }
            results.findViewWithTag<View>(selection.tag) ?: return false
        }
        if (!target.isShown || !target.isFocusable || !target.isEnabled) return false
        showNavigation(2)
        if (!target.requestFocus()) return false
        playbackReturnFocus = null; restoringPlaybackFocus = false
        target.post {
            if (target.isAttachedToWindow && target.hasFocus())
                target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), false)
        }
        return true
    }
    private fun updateInfo(item: IptvItem) {
        if (section == 0 && detail == null) return
        if (vodHero.visibility == View.VISIBLE) {
            val series = detail?.takeIf { it.type == ContentType.SERIES }
            bindVodHero(if (series != null && item.type == ContentType.EPISODE)
                series.copy(duration = item.duration.ifBlank { series.duration }) else item)
            return
        }
        info.removeAllViews(); info.tag = null
        info.addView(text(item.title, 28, true).apply {
            if (item.type == ContentType.LIVE) {
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
            }
        })
        val runtimeMinutes = HomeMediaRules.providerRuntimeMinutes(item.duration)
        val runtimeLabel = when {
            runtimeMinutes <= 0 -> ""
            runtimeMinutes < 60 -> "$runtimeMinutes min"
            else -> String.format(Locale.ROOT, "%dh%02d", runtimeMinutes / 60, runtimeMinutes % 60)
        }
        info.addView(text(listOf(item.rating, item.year, runtimeLabel, item.genre).filter(String::isNotBlank).joinToString("  ·  "), 14).apply {
            translationY = if (detail != null) dp(5).toFloat() else 0f
        })
        if (item.cast.isNotBlank()) info.addView(text("Acteurs : ${item.cast}", 13).apply { maxLines = 1 })
        if (item.director.isNotBlank()) info.addView(text("Réalisateur : ${item.director}", 13).apply { maxLines = 1 })
        info.addView(text(item.description, 14).apply { maxLines = 4; setPadding(0, dp(10), 0, 0) })
        backdrop.visibility = if (detail == null) View.GONE else View.VISIBLE
        if (detail != null) loadImage(backdrop, item.backdrop.ifBlank { item.artwork }, item)
        else { backdrop.tag = ""; backdrop.setImageDrawable(null) }
    }
    private fun loadEpg(item: IptvItem, guide: TextView) {
        epgRequest?.let(handler::removeCallbacks)
        val version = ++epgVersion
        val request = Runnable {
            val source = sources.firstOrNull { it.id == item.sourceId } ?: return@Runnable
            val screen = requestVersion
            async({ epg(item) }, screen, {}) { programs ->
                if (version != epgVersion || detail != null) return@async
                val now = System.currentTimeMillis()
                val current = programs.firstOrNull { now in it.start until it.end }
                val next = programs.firstOrNull { it.start > now }
                if (remoteGuideChannel?.id == item.id) remoteGuideProgram = current
                val format = SimpleDateFormat("HH:mm", Locale.FRANCE)
                guide.text = listOfNotNull(current?.let { "${format.format(Date(it.start))}  ${it.title}" },
                    next?.let { "${format.format(Date(it.start))}  ${it.title}" }).joinToString("\n").ifBlank { "Programme non renseigné" }
                if (current != null) updateInfo(item.copy(description = current.description, title = "${item.title} · ${current.title}"))
            }
        }
        epgRequest = request
        handler.postDelayed(request, 250)
    }
    private fun showDetails(item: IptvItem, authorized: Boolean = false) {
        if (!store.playlistEnabled(item.sourceId)) { toast("Cette liste de lecture est désactivée."); return }
        if (!authorized) { groupAccess(item.sourceId, item.type, item.categoryId) { showDetails(item, true) }; return }
        setVodHeroVisible(false)
        if (section == 0) {
            content.setPadding(dp(20), dp(20), dp(20), dp(12))
            informationHeader.layoutParams.height = dp(210); informationHeader.requestLayout()
        }
        guideActive = false
        guideScope.cancel()
        status.visibility = View.VISIBLE
        resetArtworkRequests()
        searchGroups.clear()
        if (detail?.id != item.id) { selectedSeriesSeason = null; focusedSeriesEpisode = "" }
        detail = item
        detailEpisodes = emptyList(); detailProgress = emptyList(); detailLoaded = false
        seriesEpisodeProgressFrames.clear()
        val version = ++requestVersion
        if (item.type in listOf(ContentType.MOVIE, ContentType.SERIES)) setVodHeroVisible(true)
        rememberedFocus = item.id
        showNavigation(2)
        if (vodHero.visibility == View.VISIBLE) vodHero.requestFocus()
        updateInfo(item)
        results.removeAllViews()
        status.text = "Chargement des informations…"
        detailActions(item, emptyList(), loading = true)
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return
        async({ provider(source).details(item) to if (item.type == ContentType.SERIES) provider(source).episodes(item) else emptyList() }, version,
            { status.text = it; toast(it); detailLoaded = true; renderVodDetails() }) { (enriched, episodes) ->
            detail = enriched
            detailLoaded = true
            detailEpisodes = episodes.map { it.copy(categoryId = item.categoryId, categoryName = item.categoryName) }
            updateInfo(enriched)
            status.text = ""
            renderVodDetails()
            refreshVodProgress()
        }
    }
    private fun detailActions(item: IptvItem, episodes: List<IptvItem>, loading: Boolean = false) {
        if (item.type == ContentType.SERIES) episodeSequence = episodes.filter { !it.scheduledEpisode }
        val focusedAction = results.findFocus()?.tag
        val restoreFocus = results.hasFocus() || vodHero.hasFocus() || currentFocus == null
        val episodeScrollX = vodEpisodeScroll?.scrollX ?: 0
        val seasonScrollX = vodSeasonScroll?.scrollX ?: 0
        releaseArtworkIn(results)
        // Keep focus inside the detail page while its seasons and episodes are rebuilt.
        if (restoreFocus && vodHero.visibility == View.VISIBLE) vodHero.requestFocus()
        results.removeAllViews()
        vodSeasonScroll = null; vodEpisodeScroll = null
        val actions = LinearLayout(this)
        if (item.type != ContentType.SERIES) actions.addView(button(if (detailProgress.any { it.position > 0 && !it.isCompleted() }) "▶  Reprendre" else "▶  Regarder") { play(item) }
            .apply { tag = "play:${item.id}"; if (item.type != ContentType.LIVE) setOnLongClickListener { vodItemMenu(item, this); true } })
        if (item.type != ContentType.SERIES) {
            lateinit var favorite: TextView
            favorite = button(if (store.favorite(item)) "Retirer de ma liste" else "Ajouter à ma liste") {
                changeFavorite(item, favorite)
            }.apply {
                tag = "favorite:${item.id}"
                isEnabled = favoriteChangeKey(item) !in pendingFavoriteChanges
                if (!isEnabled) text = "Enregistrement…"
                if (item.type != ContentType.LIVE) setOnLongClickListener { vodItemMenu(item, this); true }
            }
            actions.addView(favorite)
        }
        if (item.type == ContentType.LIVE) {
            actions.addView(button("Enregistrer") { recordingOptions(item) })
            actions.addView(button("Guide TV") { detail = null; openGuide() })
        }
        if (item.type != ContentType.SERIES) results.addView(actions, LinearLayout.LayoutParams(-1,
            if (item.type != ContentType.LIVE) dp(50) else -2))
        if (listMode == "history") actions.addView(button("Supprimer de l’historique") { changeHistory(item) })
        if (item.type != ContentType.LIVE) {
            actions.setPadding(dp(21), 0, dp(21), 0)
            for (index in 0 until actions.childCount) styleSeriesControl(actions.getChildAt(index) as TextView)
        }
        if (item.type == ContentType.SERIES) {
            if (episodes.isEmpty()) results.addView(text(if (loading) "Chargement des épisodes…" else "Aucun épisode disponible pour cette série.", 16).apply {
                setTextColor(Color.WHITE); setPadding(dp(21), dp(12), dp(21), 0)
            })
            if (episodes.isNotEmpty()) addSeriesEpisodes(item, episodes)
            if (calendarRefresh == null) {
                calendarRefresh = Runnable {
                    calendarRefresh = null
                    if (detail?.id == item.id) renderVodDetails()
                }.also {
                    val next = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    handler.postDelayed(it, (next - System.currentTimeMillis()).coerceAtLeast(1000))
                }
            }
        }
        if (item.type == ContentType.MOVIE) addVodCast(item)
        if (vodHero.visibility == View.VISIBLE) layoutVodHero()
        val focus = focusedAction ?: focusedSeriesEpisode.takeIf { item.type == ContentType.SERIES && it.isNotBlank() }
        if (restoreFocus && !restoringPlaybackFocus) (focus?.let { results.findViewWithTag<View>(it) }
            ?: if (item.type == ContentType.SERIES) results.findViewWithTag<View>("season:$selectedSeriesSeason") else actions.getChildAt(0))?.requestFocus()
        vodSeasonScroll?.let { rail -> rail.post { if (vodSeasonScroll === rail) rail.scrollTo(seasonScrollX, 0) } }
        vodEpisodeScroll?.let { rail -> rail.post { if (vodEpisodeScroll === rail) rail.scrollTo(episodeScrollX, 0) } }
        results.post { restorePlaybackFocus() }
    }

    private fun releaseArtworkIn(group: ViewGroup) {
        (artworkLoads.keys + pendingArtwork.keys + artworkPlaceholders.keys).toSet().filter { image ->
            var parent = image.parent
            while (parent is View && parent !== group) parent = parent.parent
            parent === group
        }.forEach { image ->
            artworkViewScopes.remove(image)?.cancel()
            pendingArtwork.remove(image); artworkLoads.remove(image); artworkPlaceholders.remove(image); image.tag = ""
        }
    }

    private fun seriesEpisodeArtworkWidth() = Math.round(dp(250) * .81f)
    private fun seriesEpisodeArtworkHeight() = Math.round(dp(146) * .81f)
    private fun seriesEpisodeRailHeight() = seriesEpisodeArtworkHeight() + dp(14) + dp((28 * textScale).toInt())

    private fun styleSeriesControl(control: TextView) {
        control.setTextColor(Color.WHITE)
        fun background(focused: Boolean) = shape(if (focused) accent else Color.TRANSPARENT, focused).apply {
            cornerRadius = dp(10).toFloat()
            if (!focused) setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 100))
        }
        control.background = background(false)
        control.setOnFocusChangeListener { _, focused ->
            control.background = background(focused)
            control.setTextColor(if (focused) contrast else Color.WHITE)
        }
    }

    private fun addSeriesEpisodes(series: IptvItem, episodes: List<IptvItem>) {
        val seasons = episodes.groupBy { it.season }.toSortedMap()
        selectedSeriesSeason = selectedSeriesSeason?.takeIf(seasons::containsKey) ?: seasons.firstKey()
        val inset = Math.round(minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * .05f)
            .coerceIn(dp(24), dp(48))
        val seasonRow = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        val seasonScroll = HorizontalScrollView(this).apply {
            isFocusable = false; isHorizontalScrollBarEnabled = false
            clipChildren = false; clipToPadding = false
            setPadding(inset, 0, inset, 0); translationY = dp(40).toFloat()
            addView(seasonRow)
        }
        vodSeasonScroll = seasonScroll
        results.addView(seasonScroll, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = -dp(25) })
        val episodeRow = LinearLayout(this).apply { tag = "vod_episode_row"; clipChildren = false; clipToPadding = false }
        val episodeScroll = HorizontalScrollView(this).apply {
            isFocusable = false; isHorizontalScrollBarEnabled = false
            clipChildren = false; clipToPadding = false
            setPadding(inset, 0, inset, 0); translationY = dp(18).toFloat()
            addView(episodeRow)
        }
        vodEpisodeScroll = episodeScroll
        results.addView(episodeScroll, LinearLayout.LayoutParams(-1, seriesEpisodeRailHeight()).apply { topMargin = dp(10) })
        val seasonButtons = linkedMapOf<Int, TextView>()
        val artworks = mutableListOf<ImageView>()
        fun renderSeason() {
            seriesEpisodeProgressFrames.clear()
            artworks.forEach { artwork ->
                artworkViewScopes.remove(artwork)?.cancel()
                pendingArtwork.remove(artwork); artworkLoads.remove(artwork); artworkPlaceholders.remove(artwork)
                artwork.tag = ""
            }
            artworks.clear(); episodeRow.removeAllViews()
            val season = selectedSeriesSeason ?: return
            val seasonButton = seasonButtons[season] ?: return
            seasonButtons.forEach { (number, control) -> control.isSelected = number == season }
            val today = java.time.LocalDate.now()
            seasons.getValue(season).sortedBy { it.episode }.forEachIndexed { index, episode ->
                // Home's 250x146dp landscape artwork uses the same .81 episode scale.
                val number = episode.episode.takeIf { it > 0 } ?: index + 1
                val code = String.format(Locale.ROOT, "S%02dE%02d", season, number)
                val airDate = HomeMediaRules.seriesEpisodeDateBadge(episode.airDate, today)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; id = View.generateViewId(); isFocusable = true; tag = episode.id
                    clipChildren = false; clipToPadding = false
                    contentDescription = "$code • ${episode.title}" + if (airDate.isBlank()) "" else " • Diffusion prévue le $airDate"
                    if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false)
                    nextFocusUpId = seasonButton.id; nextFocusDownId = id
                    translationY = dp(5).toFloat()
                }
                val artworkFrame = FrameLayout(this).apply {
                    tag = "series_episode_artwork"
                    background = shape(Color.TRANSPARENT).apply { cornerRadius = dp(12).toFloat() }
                    clipToOutline = true
                }
                val artwork = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                val border = View(this).apply {
                    alpha = 0f
                    background = shape(Color.TRANSPARENT).apply {
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(2), if (ColorUtils.calculateContrast(accent, Color.BLACK) < 1.5) Color.WHITE else accent)
                    }
                }
                artworkFrame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
                if (airDate.isNotBlank()) artworkFrame.addView(vodProgressBadge().apply {
                    text = airDate; tag = "series_episode_air_date"
                    contentDescription = "Diffusion prévue le $airDate"; setPadding(dp(5), 0, dp(5), 0)
                }, FrameLayout.LayoutParams(-2, dp(22), Gravity.TOP or Gravity.RIGHT).apply {
                    setMargins(0, dp(5), dp(5), 0)
                })
                seriesEpisodeProgressFrames[season to number] = artworkFrame
                artworkFrame.addView(border, FrameLayout.LayoutParams(-1, -1))
                artworkFrame.translationY = dp(2).toFloat()
                card.addView(artworkFrame, LinearLayout.LayoutParams(-1, seriesEpisodeArtworkHeight()).apply { bottomMargin = dp(2) })
                val label = text(code, 15, true).apply {
                    setTextColor(Color.WHITE); includeFontPadding = false
                    setPadding(dp(2), 0, dp(2), 0); setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1
                    translationY = dp(1).toFloat()
                }
                card.addView(label, LinearLayout.LayoutParams(-1, dp((28 * textScale).toInt())))
                loadImage(artwork, selectedVodArtwork(episode).ifBlank { episode.artwork.ifBlank { episode.backdrop.ifBlank { series.artwork } } }, episode,
                    seriesEpisodeArtworkWidth(), seriesEpisodeArtworkHeight())
                if (artwork.drawable !is android.graphics.drawable.BitmapDrawable) artwork.setColorFilter(Color.WHITE)
                artworks.add(artwork)
                card.setOnFocusChangeListener { _, focused ->
                    border.animate().alpha(if (focused) 1f else 0f).setDuration(120).start()
                    val scale = if (focused) 1.04f else 1f
                    artwork.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                    label.isSelected = focused
                    if (artwork.drawable !is android.graphics.drawable.BitmapDrawable) {
                        artwork.setBackgroundColor(if (focused) accent else Color.TRANSPARENT)
                        artwork.setColorFilter(if (focused) contrast else Color.WHITE)
                    }
                    if (focused) { focusedSeriesEpisode = episode.id; updateInfo(episode) }
                }
                card.setOnClickListener { play(episode) }
                card.setOnLongClickListener { vodItemMenu(episode, card); true }
                episodeRow.addView(card, LinearLayout.LayoutParams(seriesEpisodeArtworkWidth(), seriesEpisodeRailHeight() - dp(6)).apply {
                    rightMargin = dp(13)
                })
            }
            updateSeriesEpisodeProgress()
            wireSeriesHorizontalFocus(episodeRow)
            val firstEpisode = episodeRow.getChildAt(0)
            seasonButtons.values.forEach { it.nextFocusDownId = firstEpisode?.id ?: it.id }
            episodeScroll.scrollTo(0, 0)
            if (focusedSeriesEpisode.isBlank()) updateInfo(series)
        }
        seasons.keys.forEach { season ->
            val calendar = mediaDetails.get(mediaKey(series))?.seasons?.firstOrNull { it.number == season }
            val status = calendar?.let { HomeMediaRules.seasonStatusLabel(it.count, it.dates, java.time.LocalDate.now()) }.orEmpty()
            val control = button("Saison $season") {
                selectedSeriesSeason = season; focusedSeriesEpisode = ""
                renderSeason()
            }.apply {
                tag = "season:$season"; gravity = Gravity.CENTER; nextFocusUpId = id
                contentDescription = "Saison $season" + if (status.isBlank()) "" else " • $status"
                setOnLongClickListener { vodItemMenu(series, this); true }
                styleSeriesControl(this)
            }
            seasonButtons[season] = control
            seasonRow.addView(control, LinearLayout.LayoutParams(dp(150), dp(50)).apply { rightMargin = dp(12) })
        }
        wireSeriesHorizontalFocus(seasonRow)
        renderSeason()
    }

    private fun wireSeriesHorizontalFocus(row: LinearLayout) {
        for (index in 0 until row.childCount) {
            val card = row.getChildAt(index)
            card.nextFocusLeftId = row.getChildAt((index + row.childCount - 1) % row.childCount).id
            card.nextFocusRightId = row.getChildAt((index + 1) % row.childCount).id
            card.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else if (key == KeyEvent.KEYCODE_MENU && card.isLongClickable) {
                    if (event.repeatCount == 0) card.performLongClick()
                    true
                } else {
                    val target = when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> card.nextFocusLeftId
                        KeyEvent.KEYCODE_DPAD_RIGHT -> card.nextFocusRightId
                        KeyEvent.KEYCODE_DPAD_UP -> card.nextFocusUpId
                        KeyEvent.KEYCODE_DPAD_DOWN -> card.nextFocusDownId
                        else -> View.NO_ID
                    }
                    if (target == View.NO_ID) false else row.rootView.findViewById<View>(target)?.requestFocus() == true
                }
            }
        }
    }

    private fun favoriteChangeKey(item: IptvItem) = "${store.selectedAccount()?.id.orEmpty()}|${item.id}"

    private fun changeFavorite(item: IptvItem, control: TextView? = null, changed: (Boolean) -> Unit = {}) {
        val accountId = store.selectedAccount()?.id.orEmpty()
        val key = "$accountId|${item.id}"
        if (!pendingFavoriteChanges.add(key)) return
        val added = !store.favorite(item)
        val version = requestVersion
        control?.apply { isEnabled = false; text = "Enregistrement…" }
        libraryWork.execute {
            val result = runCatching { store.setFavorite(item, added, accountId) }
            runOnUiThread {
                pendingFavoriteChanges.remove(key)
                if (isDestroyed || isFinishing || store.resolveAccountId(accountId) != store.selectedAccount()?.id.orEmpty()) return@runOnUiThread
                val current = results.findViewWithTag<TextView>("favorite:${item.id}")
                current?.apply {
                    isEnabled = true
                    text = if (store.favorite(item)) "Retirer de ma liste" else "Ajouter à ma liste"
                }
                result.fold({ saved ->
                    if (control == null) toast(if (saved) "Ajouté aux Favoris." else "Retiré des Favoris.")
                    refreshVodCardProgress()
                    if (version == requestVersion) changed(saved) else refreshSyncedLibrary(true)
                }, { toast("Modification des favoris impossible. Réessayez.") })
            }
        }
    }

    private fun addHistoryClearAction() {
        if (listMode != "history" || savedType != ContentType.LIVE || results.findViewWithTag<View>("history-clear") != null) return
        results.addView(button("Vider tout l’historique") { confirmClearHistory() }.apply {
            tag = "history-clear"
            leftToGroups(this)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
    }

    private fun vodItemMenu(item: IptvItem, anchor: View) {
        vodPopup?.dismiss()
        val entries = mutableListOf<Triple<Int, String, () -> Unit>>()
        fun entry(icon: Int, title: String, action: () -> Unit) { entries += Triple(icon, title, action) }
        val available = IptvMediaPresentation.playable(item)
        if (item.type == ContentType.MOVIE) entry(R.drawable.ic_menu_info, "Informations") { showDetails(item) }
        if (available && item.type == ContentType.EPISODE) entry(R.drawable.ic_refresh, "Marquer comme vu") { changeVodWatched(item, true) }
        if (available) entry(R.drawable.ic_reset_arrow, "Marquer comme non vu") { changeVodWatched(item, false) }
        if (available && item.type != ContentType.SERIES) entry(R.drawable.ic_reset_arrow, "Reprendre depuis le début") { changeVodWatched(item, false, restart = true) }
        entry(R.drawable.ic_menu_wallpaper, "Changer de vignette") { chooseVodArtwork(item, anchor, false) }
        entry(R.drawable.ic_menu_wallpaper, "Changer la vignette du Hero") { chooseVodArtwork(item, anchor, true) }
        if (available && item.type != ContentType.SERIES) entry(R.drawable.ic_menu_open, "Ouvrir avec") { chooseVodPlayer(item) }
        if (item.type == ContentType.SERIES) entry(R.drawable.ic_menu_open, "Ouvrir") { showDetails(item) }
        if (item.type in listOf(ContentType.MOVIE, ContentType.SERIES))
            entry(R.drawable.ic_menu_delete, if (store.favorite(item)) "Retirer des favoris" else "Ajouter aux favoris") {
                changeFavorite(item) {
                    if (detail?.id == item.id) renderVodDetails()
                    else if (detail == null && listMode == "favorites") showSaved(savedType, false)
                }
            }
        if (listMode == "history" && available) entry(R.drawable.ic_menu_delete, "Supprimer de l’historique") { changeHistory(item) }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val background = shape(interfaceBackground(surface(.12f))).apply {
            cornerRadius = dp(12).toFloat(); setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 150))
        }
        val bounds = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }.apply { inset(dp(12), dp(12)) }
        val menuWidth = minOf(dp(310), bounds.width())
        val content = ScrollView(this).apply {
            isFocusable = false; isVerticalScrollBarEnabled = false
            addView(rows, ViewGroup.LayoutParams(-1, -2))
        }
        val popup = android.widget.PopupWindow(content, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(background); isOutsideTouchable = true; elevation = dp(12).toFloat()
        }
        entries.forEach { (iconId, title, action) ->
            val row = LinearLayout(this).apply {
                id = View.generateViewId(); isFocusable = true; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val icon = ImageView(this).apply { setImageResource(iconId); setColorFilter(themeForeground) }
            val label = text(title, 16)
            row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            row.setOnFocusChangeListener { _, focused ->
                row.background = shape(if (focused) accent else Color.TRANSPARENT, focused)
                val foreground = if (focused) contrast else themeForeground
                icon.setColorFilter(foreground); label.setTextColor(foreground)
            }
            row.setOnClickListener { popup.dismiss(); action() }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        wireVerticalFocus(rows)
        popup.setOnDismissListener {
            if (vodPopup === popup) vodPopup = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
            if (detail != null) renderVodDetails()
            handler.post { if (pendingSyncedRefresh) refreshSyncedLibrary() }
        }
        vodPopup = popup
        // Posters sit at the bottom of the screen. Measure the menu before
        // positioning it above them instead of letting the dropdown collapse.
        rows.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        popup.height = minOf(rows.measuredHeight, bounds.height())
        val location = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val x = location[0].coerceIn(bounds.left, bounds.right - menuWidth)
        val below = location[1] + anchor.height + dp(4)
        val y = if (below + popup.height <= bounds.bottom) below else location[1] - popup.height - dp(4)
        popup.showAtLocation(window.decorView, Gravity.TOP or Gravity.LEFT,
            x, y.coerceIn(bounds.top, bounds.bottom - popup.height))
        rows.getChildAt(0)?.requestFocus()
    }

    private fun changeVodWatched(item: IptvItem, watched: Boolean, restart: Boolean = false) {
        val version = requestVersion
        val account = store.selectedAccount()?.id.orEmpty()
        libraryWork.execute {
            if (account != store.selectedAccount()?.id.orEmpty()) return@execute
            val result = runCatching { store.setVodWatched(item, watched) }
            runOnUiThread {
                if (isDestroyed || account != store.selectedAccount()?.id.orEmpty()) return@runOnUiThread
                result.fold({
                    if (restart && version == requestVersion) play(item, startFromBeginning = true)
                    else {
                        refreshVodProgress(); refreshVodCardProgress()
                        toast(if (watched) "Marqué comme vu." else "Marqué comme non vu.")
                    }
                }, { toast("Impossible de modifier l’état de lecture.") })
            }
        }
    }

    private fun chooseVodPlayer(item: IptvItem) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("https://localhost/video.mp4"), "video/*")
        val players = packageManager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
        dialog("Ouvrir avec", listOf("Lecteur intégré" to { play(item, playerPackageOverride = "") }) + players.map { player ->
            player.loadLabel(packageManager).toString() to { play(item, playerPackageOverride = player.activityInfo.packageName) }
        })
    }

    private fun chooseVodArtwork(item: IptvItem, anchor: View, hero: Boolean) {
        val parent = if (item.type == ContentType.EPISODE) {
            val seriesId = itemId(item.sourceId, ContentType.SERIES, item.seriesId)
            detail?.takeIf { it.id == seriesId } ?: store.rememberedItem(seriesId) ?: item
        } else item
        val version = requestVersion
        val account = store.selectedAccount()?.id.orEmpty()
        val identity = vodArtworkIdentity(item, hero)
        val landscape = hero || item.type == ContentType.EPISODE
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(20))
            background = shape(interfaceBackground(surface(.12f)))
        }
        panel.addView(text(if (hero) "Changer la vignette du Hero" else "Changer de vignette", 24, true))
        val search = EditText(this).apply {
            setText(IptvMediaPresentation.searchTitle(parent.title)); setTextColor(themeForeground); setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        val message = text("", 16)
        fun apply(url: String) {
            if (account != store.selectedAccount()?.id.orEmpty()) return
            if (!MainActivity.saveExternalArtworkSelection(this, identity, url, hero)) { toast("Impossible de sauvegarder la vignette."); return }
            dialog.dismiss()
            if (version != requestVersion) return
            vodHeroBoundItem?.let(::bindVodHero)
            if (detail != null) renderVodDetails()
            else if (section == 0) renderSearchResults()
            else vodCards?.adapter?.notifyDataSetChanged()
        }
        fun render(assets: IptvMediaPresentation.Details?) {
            releaseArtworkIn(row)
            row.removeAllViews()
            val original = if (hero) parent.backdrop.takeUnless { it == parent.artwork }.orEmpty() else item.artwork
            val urls = (listOf(original) + if (landscape) assets?.backdrops.orEmpty() else assets?.posters.orEmpty()).filter(String::isNotBlank).distinct()
            message.text = if (urls.isEmpty()) "Aucune image disponible. Tu peux modifier la recherche." else ""
            urls.forEachIndexed { index, url ->
                val width = dp(if (landscape) 250 else 120); val height = dp(if (landscape) 146 else 180)
                val card = LinearLayout(this).apply {
                    id = View.generateViewId(); orientation = LinearLayout.VERTICAL; isFocusable = true
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                }
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(Color.BLACK).apply { cornerRadius = dp(12).toFloat() }
                    clipToOutline = true
                }
                card.addView(image, LinearLayout.LayoutParams(width, height))
                loadImage(image, url, targetWidth = width, targetHeight = height)
                val label = text(if (url == original) "Image du fournisseur" else "Image ${index + 1}", 14).apply { gravity = Gravity.CENTER }
                card.addView(label, LinearLayout.LayoutParams(-1, dp(32)))
                card.setOnFocusChangeListener { _, focused ->
                    card.background = shape(if (focused) accent else Color.TRANSPARENT, focused)
                    label.setTextColor(if (focused) contrast else themeForeground)
                }
                card.setOnClickListener { apply(url) }
                row.addView(card, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(14) })
            }
            wireSeriesHorizontalFocus(row)
            if (dialog.isShowing) row.getChildAt(0)?.requestFocus()
        }
        var generation = 0
        val scope = IptvRequestScope(IptvHttp.client)
        val searchButton = button("Rechercher") {
            val query = search.text.toString().trim()
            if (query.isBlank()) return@button
            val current = ++generation
            message.text = "Recherche des images…"
            mediaWork.execute {
                val result = runCatching { IptvHttp.withScope(scope) {
                    val lookup = if (query == IptvMediaPresentation.searchTitle(parent.title)) parent
                        else parent.copy(title = query, year = "", tmdbId = 0)
                    IptvMediaPresentation.fetch(if (lookup.type == ContentType.EPISODE)
                        lookup.copy(type = ContentType.SERIES, tmdbId = 0) else lookup) { assets ->
                        runOnUiThread { if (dialog.isShowing && current == generation) { message.text = ""; render(assets) } }
                    }
                } }
                runOnUiThread { if (dialog.isShowing && current == generation && (result.isFailure || message.text.isNotBlank()))
                    message.text = "Aucune image trouvée pour cette recherche." }
            }
        }
        panel.addView(searchButton)
        panel.addView(message); panel.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        panel.addView(button("Annuler") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        render(mediaDetails.get(mediaKey(parent)))
        contextPanel?.dismiss(); contextPanel = dialog
        val viewport = ScrollView(this).apply { isFillViewport = true; addView(panel) }
        viewport.viewTreeObserver.addOnGlobalLayoutListener { loadVisibleArtwork() }
        scroll.viewTreeObserver.addOnScrollChangedListener { loadVisibleArtwork() }
        dialog.setContentView(viewport)
        dialog.setOnDismissListener {
            scope.cancel()
            releaseArtworkIn(panel)
            if (contextPanel === dialog) contextPanel = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
            if (detail != null) renderVodDetails()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            attributes = attributes.apply { x = dp(16) }
            setLayout((contentHost.width - dp(32)).coerceAtLeast(1), -2)
        }
        row.getChildAt(0)?.requestFocus() ?: search.requestFocus()
        if (mediaDetails.get(mediaKey(parent)) == null) searchButton.performClick()
    }

    private fun historyItemMenu(item: IptvItem) {
        dialog(item.title, listOf(
            "Regarder" to { if (item.type == ContentType.SERIES) showDetails(item) else play(item) },
            "Supprimer de l’historique" to { changeHistory(item) },
            "Vider tout l’historique" to { confirmClearHistory() }
        ))
    }

    private fun confirmClearHistory() {
        val accountId = store.selectedAccount()?.id.orEmpty()
        confirmClearHistory { changeHistory(null, accountId) }
    }

    private fun confirmClearHistory(clearHistory: () -> Unit) {
        contextPanel?.dismiss()
        val origin = currentFocus
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(16), dp(14))
            background = shape(surface(.16f)).apply { cornerRadius = dp(12).toFloat() }
        }
        panel.addView(text("Effacer l’historique ?", 21, true), LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        fun action(label: String, clicked: () -> Unit) = button(label, clicked).apply {
            gravity = Gravity.CENTER; minimumWidth = dp(86); setSingleLine(true)
            if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            fun paint(focused: Boolean) {
                background = shape(if (focused) accent else Color.TRANSPARENT, focused).apply { cornerRadius = dp(24).toFloat() }
                setTextColor(if (focused) contrast else themeForeground)
            }
            paint(false); setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnKeyListener { _, key, event -> event.action == KeyEvent.ACTION_DOWN &&
                key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN) }
        }
        val cancel = action("Annuler") { dialog.dismiss() }
        val clear = action("Effacer") { dialog.dismiss(); clearHistory() }
        cancel.nextFocusLeftId = cancel.id; cancel.nextFocusRightId = clear.id
        clear.nextFocusLeftId = cancel.id; clear.nextFocusRightId = clear.id
        actions.addView(cancel, LinearLayout.LayoutParams(-2, -2))
        actions.addView(clear, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        dialog.setContentView(panel)
        dialog.setOnDismissListener {
            if (contextPanel === dialog) {
                contextPanel = null
                if (!isDestroyed && !isFinishing && origin?.isShown == true) origin.requestFocus()
            }
            handler.post { if (!isDestroyed && !isFinishing && pendingSyncedRefresh) refreshSyncedLibrary() }
        }
        contextPanel = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            setDimAmount(.55f); setGravity(Gravity.CENTER)
            setLayout(minOf(dp(560), resources.displayMetrics.widthPixels - dp(64)).coerceAtLeast(1), -2)
        }
        clear.requestFocus()
    }

    private fun changeHistory(item: IptvItem?, accountId: String = store.selectedAccount()?.id.orEmpty()) {
        if (historyMutationPending) return
        historyMutationPending = true
        libraryWork.execute {
            val result = runCatching { store.removeHistory(item, accountId) }
            runOnUiThread {
                historyMutationPending = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.fold({
                    if (store.resolveAccountId(accountId) == store.selectedAccount()?.id.orEmpty() && listMode == "history") {
                        showSaved(savedType, true)
                        if (visibleItems.isEmpty() && savedType != ContentType.LIVE) { showNavigation(1); focusGroup() }
                    }
                    toast(if (item == null) "Historique effacé." else "Contenu retiré de l’historique.")
                }, { toast("Suppression de l’historique impossible. Réessayez.") })
                if (pendingSyncedRefresh) refreshSyncedLibrary()
            }
        }
    }
    private fun play(item: IptvItem, authorized: Boolean = false, fullscreen: Boolean = false, external: Boolean = false,
        playerPackageOverride: String? = null, startFromBeginning: Boolean = homeStartFromBeginning) {
        if (!store.playlistEnabled(item.sourceId)) { toast("Cette liste de lecture est désactivée."); return }
        if (!IptvMediaPresentation.playable(item)) {
            toast(if (item.scheduledEpisode) "Cet épisode n’est pas encore disponible chez le fournisseur." else
                HomeMediaRules.seriesEpisodeLabel(item.season, item.episode, item.airDate, java.time.LocalDate.now()))
            return
        }
        if (!authorized) { playbackAccess(item) { play(item, true, fullscreen, external, playerPackageOverride, startFromBeginning) }; return }
        if (!fullscreen && section == 0 && item.type == ContentType.LIVE && store.booleanSetting("searchStay", false)) {
            detail = null
            showSearch(); renderSearchResults()
            informationHeader.layoutParams.height = dp((210 * textScale).toInt()); informationHeader.requestLayout()
            startPreview(item, explicitPlayback = true, authorized = true)
            return
        }
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return
        val externalMode = store.organization(item.sourceId, item.type, item.categoryId, "externalPlayerMode", "never")
        val preferredPackage = playerPackageOverride ?: if (external) store.setting("externalPlayerPackage", "") else homePlayerPackage ?: if (externalMode == "all" || externalMode == "vod" && item.type != ContentType.LIVE)
            store.setting("externalPlayerPackage", "") else ""
        val activeMiniPlayer = item.type == ContentType.LIVE && previewItem?.id == item.id &&
            (previewPlayer != null || previewPreparing)
        if (!external && item.type == ContentType.LIVE &&
            (activeMiniPlayer || preferredPackage.isBlank() && store.booleanSetting("previewEnabled", true))) {
            if (previewFullscreen) {
                if (previewItem?.id != item.id) changePreviewChannel(item, authorized = true)
                return
            }
            if (guideOverlay) setGuideOverlay(false)
            previewFrame.visibility = View.VISIBLE
            if (previewPlayer != null && previewItem?.id == item.id) enterPreviewFullscreen()
            else {
                previewFullscreenPendingItemId = item.id
                if (!previewPreparing || previewItem?.id != item.id) startPreview(item, explicitPlayback = true, authorized = true)
            }
            return
        }
        val version = ++requestVersion
        val playbackAccountId = store.selectedAccount()?.id.orEmpty()
        status.text = "Préparation de la lecture…"
        async({ provider(source).resolve(item) }, version,
            failure = { if (vodHero.visibility == View.VISIBLE) toast(it) else status.text = it }) { stream ->
            val player = Intent(this, NuvioPlayerActivity::class.java)
                .putExtra(NuvioPlayerActivity.EXTRA_STREAM_URL, stream.url)
                .putExtra(NuvioPlayerActivity.EXTRA_STREAM_HEADERS, iptvJson.encodeToString(stream.headers))
                .putExtra(NuvioPlayerActivity.EXTRA_IPTV_DNS, stream.dns?.let { iptvJson.encodeToString(it) })
                .putExtra(NuvioPlayerActivity.EXTRA_PLAYER_PACKAGE, preferredPackage)
                .putExtra(NuvioPlayerActivity.EXTRA_TITLE, item.title)
                .putExtra(NuvioPlayerActivity.EXTRA_CONTENT_ID, store.playbackContentId(if (item.type == ContentType.EPISODE)
                    itemId(item.sourceId, ContentType.SERIES, item.seriesId) else item.id))
                .putExtra(NuvioPlayerActivity.EXTRA_CONTENT_TYPE, when (item.type) {
                    ContentType.LIVE -> "iptv"; ContentType.MOVIE -> "movie"; else -> "series"
                })
                .putExtra(NuvioPlayerActivity.EXTRA_DESCRIPTION, item.description)
                .putExtra(NuvioPlayerActivity.EXTRA_POSTER, item.artwork)
                .putExtra(NuvioPlayerActivity.EXTRA_BACKDROP, item.backdrop)
                .putExtra(NuvioPlayerActivity.EXTRA_PLAYER_ACCENT, accent)
                .putExtra(NuvioPlayerActivity.EXTRA_SEASON, item.season)
                .putExtra(NuvioPlayerActivity.EXTRA_EPISODE, item.episode)
                .putExtra(NuvioPlayerActivity.EXTRA_EPISODE_TITLE, item.title)
            if (item.type != ContentType.LIVE) {
                val presentationItem = if (item.type == ContentType.EPISODE) {
                    val seriesId = itemId(item.sourceId, ContentType.SERIES, item.seriesId)
                    detail?.takeIf { it.id == seriesId } ?: store.rememberedItem(seriesId)
                } else item
                val presentation = presentationItem?.let { mediaDetails.get(mediaKey(it)) }
                val logoId = IptvStore.playbackTmdbId(item, presentationItem).takeIf { it > 0 } ?: presentation?.tmdbId ?: 0
                player.putExtra(NuvioPlayerActivity.EXTRA_TITLE_LOGO,
                    IptvMediaPresentation.titleLogo(presentationItem ?: item, presentation,
                        OnlineAppIconLoader.cachedTitleLogo(this, if (item.type == ContentType.MOVIE) "movie" else "tv", logoId))
                        .ifBlank { item.titleLogo })
                player.putExtra(NuvioPlayerActivity.EXTRA_TMDB_ID,
                    IptvStore.playbackTmdbId(item, presentationItem).takeIf { it > 0 } ?: presentation?.tmdbId ?: 0)
                player.putExtra(NuvioPlayerActivity.EXTRA_TMDB_MEDIA_TYPE,
                    if (item.type == ContentType.MOVIE) "movie" else "tv")
                player.putExtra(NuvioPlayerActivity.EXTRA_MEDIA_PRESENTATION,
                    IptvMediaPresentation.playerPresentation(item, presentation,
                        presentationItem?.takeIf { item.type == ContentType.EPISODE }).toString())
            }
            if (startFromBeginning) player.putExtra("startFromBeginning", true)
            previewResumePending = previewPlayer != null
            if (guideOverlay) setGuideOverlay(false)
            stopPreview()
            if (item.type in listOf(ContentType.MOVIE, ContentType.EPISODE)) {
                rememberedFocus = detail?.id ?: item.id
                if (item.type == ContentType.EPISODE && detail?.id == itemId(item.sourceId, ContentType.SERIES, item.seriesId)) {
                    selectedSeriesSeason = item.season; focusedSeriesEpisode = item.id
                }
                rememberPlaybackFocus(if (detail?.id == item.id) "play:${item.id}" else item.id)
            }
            if (item.type == ContentType.EPISODE) {
                pendingEpisode = item
                player.putExtra(NuvioPlayerActivity.EXTRA_IPTV_RESULT, true)
                startActivityForResult(player, 8094)
            } else if (item.type == ContentType.LIVE) {
                rememberedFocus = item.id
                livePlayerFocus = item.id
                player.putExtra(NuvioPlayerActivity.EXTRA_IPTV_NAVIGATION_ENABLED, true)
                startActivityForResult(player, 8095)
            } else startActivity(player)
            homeStartFromBeginning = false
            status.text = ""
            // Browsing never adds an item to favorites/history. Only this explicit play does.
            async({ store.recordPlayback(item, playbackAccountId) }, version) {}
        }
    }

    private fun openGuide(focusId: String = rememberedFocus) {
        if (visibleItems.isEmpty()) { toast("Choisis d’abord une catégorie de chaînes."); return }
        guideActive = true; liveChannelList = false
        detail = null
        val channels = catalogueItems()
        guideOffset = (channels.indexOfFirst { it.id == focusId }.coerceAtLeast(0) / 12) * 12
        showNavigation(2)
        renderGuide(focusId, channels)
        results.findViewWithTag<View>(focusId)?.requestFocus()
    }

    private fun openChannelList(focusId: String = rememberedFocus) {
        liveChannelList = true; detail = null
        showNavigation(2); displayItems(focusId)
    }

    private fun startPreview(item: IptvItem, explicitPlayback: Boolean, authorized: Boolean = false) {
        if (!store.playlistEnabled(item.sourceId)) return
        if (previewFullscreen) return
        if (!authorized) { playbackAccess(item) { startPreview(item, explicitPlayback, true) }; return }
        if (!activityResumed || !store.booleanSetting("previewEnabled", true) && !guideOverlay &&
            !(section == 0 && explicitPlayback && store.booleanSetting("searchStay", false))) return
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return
        stopPreview()
        if (previewFullscreenPendingItemId != item.id) previewFullscreenPendingItemId = null
        previewItem = item
        previewPreparing = true
        previewFrame.visibility = if (guideOverlay) View.GONE else View.VISIBLE
        if (guideActive) { status.text = ""; status.visibility = View.GONE }
        val generation = previewGeneration
        work.execute {
            val resolved = runCatching { provider(source).resolve(item) }
            runOnUiThread {
                if (!activityResumed || isDestroyed || generation != previewGeneration) return@runOnUiThread
                previewPreparing = false
                resolved.fold({ stream ->
                    val mediaSource = try {
                        previewMediaSources.dns = stream.dns
                        previewMediaSources.createMediaSource(this, stream.url, stream.headers)
                    }
                    catch (_: Exception) {
                        status.visibility = View.VISIBLE
                        status.text = if (stream.url.startsWith("rtsp:", true) && !PlayerMediaSourceFactory.isFreeboxRtspUrl(stream.url)) "Le lecteur interne ne prend pas encore en charge ce flux RTSP."
                            else "Aperçu indisponible. Ouvre la chaîne dans le lecteur."
                        if (previewFullscreenPendingItemId == item.id) previewFullscreenPendingItemId = null
                        return@fold
                    }
                    val settings = IptvStore.applyPlayerSettings(this, item.id, PlayerSettings())
                    val buffer = settings.bufferSettings
                    val renderers = object : DefaultRenderersFactory(this) {
                        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                            enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                            val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                .setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
                            return com.nuvio.tv.ui.screens.player.PlaybackSpeedAwareAudioSink(sink, store.booleanSetting("audioPassthrough", false))
                        }
                    }.setExtensionRendererMode(settings.decoderPriority)
                    val tracks = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
                        setParameters(buildUponParameters().setMaxAudioChannelCount(if (store.booleanSetting("preferSurround", false)) Int.MAX_VALUE else 2))
                        val languages = com.nuvio.tv.ui.screens.player.resolvePreferredAudioLanguages(
                            settings.preferredAudioLanguage, settings.secondaryPreferredAudioLanguage,
                            com.nuvio.tv.ui.screens.player.resolveDeviceAudioLanguages())
                        setParameters(buildUponParameters().setPreferredAudioLanguages(*languages.toTypedArray()))
                    }
                    val player = ExoPlayer.Builder(this, renderers).setTrackSelector(tracks)
                        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(buffer.minBufferMs, buffer.maxBufferMs,
                            buffer.bufferForPlaybackMs, buffer.bufferForPlaybackAfterRebufferMs).build()).build()
                    previewPlayer = player
                    previewTrackSelector = tracks
                    previewStreamUrl = stream.url
                    previewStreamHeaders = stream.headers
                    previewView.player = player
                    player.setAudioAttributes(AudioAttributes.DEFAULT, true)
                    player.volume = if (store.booleanSetting("previewSound", true)) 1f else 0f
                    attachPreviewListener(player, item, generation, explicitPlayback)
                    player.setMediaSource(mediaSource)
                    player.prepare()
                    player.play()
                }, {
                    if (previewFullscreenPendingItemId == item.id) previewFullscreenPendingItemId = null
                    status.visibility = View.VISIBLE; status.text = "Aperçu indisponible. Réessaie ou ouvre le lecteur."
                })
            }
        }
    }

    private fun stopPreview(clearItem: Boolean = false) {
        previewFullscreenRequest?.let(handler::removeCallbacks); previewFullscreenRequest = null
        if (previewFullscreen) {
            previewFrame.animate().cancel()
            previewFullscreenClosing = false
            previewAfterFullscreen = null
            completePreviewFullscreenCollapse()
        }
        val elapsed = previewWatchClock.update(false, android.os.SystemClock.elapsedRealtime())
        val id = previewItem?.id
        val watchAccountId = store.selectedAccount()?.id.orEmpty()
        if (elapsed > 0 && id != null) watchTimeWork.execute { runCatching { store.recordWatchTime(id, elapsed, watchAccountId) } }
        if (clearItem && guideOverlay) setGuideOverlay(false)
        if (::previewView.isInitialized) { previewView.animate().cancel(); previewView.alpha = 1f }
        previewListener?.let { listener -> previewPlayer?.removeListener(listener) }
        previewListener = null
        previewHistoryRequest?.let(handler::removeCallbacks); previewHistoryRequest = null
        if (::previewPlaceholder.isInitialized) {
            previewPlaceholder.visibility = View.VISIBLE
        }
        previewFocusRequest?.let(handler::removeCallbacks); previewFocusRequest = null
        previewGeneration++
        if (::previewView.isInitialized) previewView.player = null
        previewPlayer?.release()
        previewPlayer = null
        previewTrackSelector = null
        previewStreamUrl = ""
        previewStreamHeaders = emptyMap()
        previewPreparing = false
        previewPendingItem = null
        if (clearItem) {
            previewItem = null
            previewPendingItem = null
            previewFullscreenPendingItemId = null
            previewResumePending = false
        }
    }

    private fun showPreviewFullscreenControls() {
        val player = previewPlayer ?: return
        val tracks = previewTrackSelector ?: return
        val item = previewItem ?: return
        // The guide owns the player and its attached view. The native screen
        // borrows that session for controls only, including on exit/disposal.
        val token = IptvLivePlayerHandoff.register(IptvLivePlayerHandoff.Session(
            player, tracks, previewStreamUrl, previewStreamHeaders, { _, _ -> true }))
        previewControlsToken = token
        val arguments = mapOf("streamUrl" to previewStreamUrl,
            "contentId" to store.playbackContentId(item.id), "contentType" to "iptv",
            "title" to android.net.Uri.encode(item.title), "logo" to android.net.Uri.encode(item.artwork),
            "streamDescription" to android.net.Uri.encode(item.description),
            "headers" to android.net.Uri.encode(iptvJson.encodeToString(previewStreamHeaders)),
            "liveHandoffToken" to token)
        previewFullscreenControls.setContent {
            val playerAccent = androidx.compose.ui.graphics.Color(accent)
            NuvioTheme(customAccent = playerAccent) {
                val navigation = rememberNavController()
                SideEffect { previewControlsNavigation = navigation }
                NavHost(navController = navigation, startDestination = "iptv-preview-player") {
                    composable("iptv-preview-player", arguments = arguments.map { (name, value) ->
                        navArgument(name) { type = NavType.StringType; defaultValue = value }
                    }) {
                        val model = hiltViewModel<PlayerViewModel>()
                        SideEffect { previewControlsViewModel = model }
                        PlayerScreen(viewModel = model, errorAccentColor = playerAccent,
                            attachedPlayerView = previewView,
                            onBackPress = { _, _, _, _, _ -> exitPreviewFullscreen() },
                            onPlaybackErrorBack = { exitPreviewFullscreen() },
                            onIptvNavigation = { action ->
                                when (action) {
                                    "next", "previous" -> switchPreviewChannel(action == "next")
                                    "remote:RECENT" -> store.saved(ContentType.LIVE, true)
                                        .firstOrNull { it.id != previewItem?.id }?.let { changePreviewChannel(it) }
                                    "remote:FAVORITE" -> previewItem?.let { current ->
                                        changeFavorite(current)
                                    }
                                    "remote:RECORD" -> guideRemoteAction(IptvRemoteAction.RECORD, previewItem, null)
                                    "remote:INFO_NEXT", "remote:INFO_PREVIOUS" ->
                                        showAdjacentPlayerInfo(action == "remote:INFO_NEXT")
                                    "guide", "remote:GUIDE_OVERLAY", "remote:GUIDE_PREVIEW" ->
                                        exitPreviewFullscreen {
                                            setGuideOverlay(action != "remote:GUIDE_PREVIEW"); openGuide(previewItem?.id ?: rememberedFocus)
                                        }
                                    "remote:CHANNELS_OVERLAY", "remote:CHANNELS_PREVIEW" ->
                                        exitPreviewFullscreen {
                                            setGuideOverlay(action == "remote:CHANNELS_OVERLAY"); openChannelList(previewItem?.id ?: rememberedFocus)
                                        }
                                    "remote:GROUPS_OVERLAY", "remote:GROUPS_PREVIEW" ->
                                        exitPreviewFullscreen {
                                            setGuideOverlay(action == "remote:GROUPS_OVERLAY"); openGuide(previewItem?.id ?: rememberedFocus); showNavigation(1); focusGroup()
                                        }
                                    "remote:STOP", "remote:EXIT" -> exitPreviewFullscreen { stopPreview() }
                                    else -> exitPreviewFullscreen {
                                        val remote = action.removePrefix("remote:")
                                        IptvRemoteAction.entries.firstOrNull { it.name == remote }
                                            ?.let { guideRemoteAction(it, previewItem, null) }
                                    }
                                }
                            })
                    }
                }
            }
        }
        previewFullscreenControls.visibility = View.VISIBLE
        previewFullscreenControls.requestFocus()
    }

    private fun clearPreviewFullscreenControls() {
        previewControlsViewModel?.stopAndRelease()
        previewControlsViewModel = null
        previewControlsNavigation?.popBackStack()
        previewControlsNavigation = null
        previewFullscreenControls.disposeComposition()
        previewControlsToken?.let(IptvLivePlayerHandoff::reclaim)
        previewControlsToken = null
    }

    private fun showAdjacentPlayerInfo(forward: Boolean, current: IptvItem? = previewItem) {
        val item = current?.let { IptvOrganization.adjacentChannel(catalogueItems(), it.id, forward) }
        if (item == null) toast("Choisis une chaîne dans le Guide TV.") else showPlayerChannelInfo(item)
    }

    private fun showPlayerChannelInfo(item: IptvItem) {
        contextPanel?.dismiss()
        val dialog = Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(ColorUtils.setAlphaComponent(themeBackground, 232))
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            loadImage(this, item.artwork, item, dp(64), dp(48))
        }, LinearLayout.LayoutParams(dp(64), dp(48)))
        heading.addView(text(store.channelTitle(item), 20, true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(heading)
        val programme = text("Pas d’information", 16, true).apply { setPadding(0, dp(12), 0, dp(6)); maxLines = 2 }
        val description = text(item.description, 14).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        panel.addView(programme); panel.addView(description)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        fun browseButton(forward: Boolean) = ImageButton(this).apply {
            val label = if (forward) "Informations de la chaîne suivante" else "Informations de la chaîne précédente"
            contentDescription = label; androidx.core.view.ViewCompat.setTooltipText(this, label)
            setImageResource(if (forward) android.R.drawable.ic_media_next else android.R.drawable.ic_media_previous)
            isFocusable = true; id = View.generateViewId(); setPadding(dp(8), dp(8), dp(8), dp(8))
            fun paint(focused: Boolean) {
                background = shape(if (focused) accent else surface(.12f), focused)
                setColorFilter(if (focused) contrast else themeForeground)
            }
            paint(false); setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnClickListener { showAdjacentPlayerInfo(forward, item) }
        }
        actions.addView(browseButton(false), LinearLayout.LayoutParams(dp(44), dp(44)))
        val watch = button("Regarder") {
            dialog.dismiss()
            if (previewFullscreen) changePreviewChannel(item) else play(item, fullscreen = true)
        }
        actions.addView(watch, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(12); rightMargin = dp(12) })
        actions.addView(browseButton(true), LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(actions)
        dialog.setContentView(panel)
        dialog.setOnDismissListener {
            if (contextPanel === dialog) contextPanel = null
            if (previewFullscreen) previewFullscreenControls.requestFocus()
        }
        dialog.setOnKeyListener { _, code, event ->
            val key = IptvRemote.player.firstOrNull { it.keyCode == IptvRemote.key(code) && !it.long }
            val action = key?.let { IptvRemote.action(it, true, store.setting(it.setting(true), it.action.name)) }
            if (action !in listOf(IptvRemoteAction.INFO_NEXT, IptvRemoteAction.INFO_PREVIOUS, IptvRemoteAction.INFO)) false
            else {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (action) {
                    IptvRemoteAction.INFO_NEXT -> showAdjacentPlayerInfo(true, item)
                    IptvRemoteAction.INFO_PREVIOUS -> showAdjacentPlayerInfo(false, item)
                    else -> previewItem?.let(::showPlayerChannelInfo)
                }
                true
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            setLayout((resources.displayMetrics.widthPixels * .9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        contextPanel = dialog; watch.requestFocus()
        async({ epg(item) }, failure = {}) { programs ->
            if (contextPanel !== dialog || !dialog.isShowing) return@async
            val now = System.currentTimeMillis()
            programs.firstOrNull { now in it.start until it.end }?.let { current ->
                val format = SimpleDateFormat("HH:mm", Locale.FRANCE)
                programme.text = "${format.format(Date(current.start))} – ${format.format(Date(current.end))}  ${current.title}"
                description.text = current.description
            }
        }
    }

    private fun enterPreviewFullscreen() {
        if (previewFullscreen || previewFullscreenClosing || previewPlayer == null) return
        val root = shell.parent as? FrameLayout ?: return
        val parent = previewFrame.parent as? ViewGroup ?: return
        if (parent === root || root.width <= 0 || root.height <= 0) return

        val rootLocation = IntArray(2)
        val frameLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        previewFrame.getLocationOnScreen(frameLocation)
        val bounds = Rect(frameLocation[0] - rootLocation[0], frameLocation[1] - rootLocation[1],
            frameLocation[0] - rootLocation[0] + previewFrame.width,
            frameLocation[1] - rootLocation[1] + previewFrame.height)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        // Transform the attached frame in place so PlayerView keeps its video surface and player.
        previewMiniBounds = bounds
        previewMiniLayoutParams = LinearLayout.LayoutParams(previewFrame.layoutParams as LinearLayout.LayoutParams)
        previewFullscreenInfoVisibility = info.visibility
        info.visibility = View.GONE
        previewFullscreenRequest?.let(handler::removeCallbacks); previewFullscreenRequest = null
        previewFocusRequest?.let(handler::removeCallbacks); previewFocusRequest = null
        guideScope.cancel()
        previewFrame.pivotX = 0f
        previewFrame.pivotY = 0f
        previewFrame.scaleX = bounds.width().toFloat() / root.width
        previewFrame.scaleY = bounds.height().toFloat() / root.height
        previewFrame.translationX = 0f
        previewFrame.translationY = 0f
        previewFrame.clipToOutline = false
        previewFrame.setBackgroundColor(Color.BLACK)
        previewFrame.foreground = null
        previewFullscreen = true
        previewFullscreenClosing = false
        previewFullscreenPendingItemId = null
        status.text = ""
        previewFrame.animate().cancel()
        // Lay out the same view at full resolution before animating its visual
        // bounds. Scaling the small video buffer itself made fullscreen blurry.
        previewFrame.doOnNextLayout {
            if (previewFullscreen && !previewFullscreenClosing) {
                previewFrame.animate()
                    .translationX(-bounds.left.toFloat()).translationY(-bounds.top.toFloat())
                    .scaleX(1f).scaleY(1f)
                    .setDuration(280).setInterpolator(DecelerateInterpolator()).withEndAction {
                        if (previewFullscreen && !previewFullscreenClosing) showPreviewFullscreenControls()
                    }.start()
            }
        }
        previewFrame.layoutParams = LinearLayout.LayoutParams(previewMiniLayoutParams!!).apply {
            width = root.width; height = root.height
        }
        val item = previewItem
        if (item != null) {
            val accountId = store.selectedAccount()?.id.orEmpty()
            async({ store.recordPlayback(item, accountId) }) { }
        }
    }

    private fun exitPreviewFullscreen(afterCollapse: (() -> Unit)? = null) {
        if (!previewFullscreen || previewFullscreenClosing) return
        previewAfterFullscreen = afterCollapse
        previewFullscreenControls.visibility = View.GONE
        val root = shell.parent as? FrameLayout ?: return completePreviewFullscreenCollapse()
        val bounds = previewMiniBounds ?: return completePreviewFullscreenCollapse()
        if (root.width <= 0 || root.height <= 0) return completePreviewFullscreenCollapse()

        previewFullscreenClosing = true
        previewFrame.animate().cancel()
        previewFrame.animate()
            .translationX(0f).translationY(0f)
            .scaleX(bounds.width().toFloat() / previewFrame.layoutParams.width)
            .scaleY(bounds.height().toFloat() / previewFrame.layoutParams.height)
            .setDuration(240).setInterpolator(DecelerateInterpolator())
            .withEndAction { completePreviewFullscreenCollapse() }.start()
    }

    private fun completePreviewFullscreenCollapse() {
        if (!previewFullscreen) return
        previewFrame.translationX = 0f; previewFrame.translationY = 0f
        previewFrame.scaleX = 1f; previewFrame.scaleY = 1f
        previewMiniLayoutParams?.let { previewFrame.layoutParams = it }
        previewMiniLayoutParams = null
        previewFrame.clipToOutline = true
        previewFrame.background = shape(surface(.10f))
        previewFrame.foreground = shape(Color.TRANSPARENT).apply {
            setStroke(dp(if (previewFrame.hasFocus()) 3 else 1), if (previewFrame.hasFocus()) accentTextColor() else surface(.55f))
        }
        previewFullscreenControls.visibility = View.GONE
        clearPreviewFullscreenControls()
        previewFullscreen = false
        previewFullscreenClosing = false
        if (activityResumed) {
            handler.removeCallbacks(syncRefresh); handler.post(syncRefresh)
        }
        previewMiniBounds = null
        info.visibility = previewFullscreenInfoVisibility
        previewFrame.requestFocus()
        val afterCollapse = previewAfterFullscreen
        previewAfterFullscreen = null
        if (afterCollapse != null && activityResumed) { afterCollapse(); return }
        if (activityResumed && guideActive && section == 1) {
            val focusId = previewItem?.id ?: rememberedFocus
            handler.post { if (!previewFullscreen && activityResumed && guideActive) renderGuide(focusId) }
        }
    }

    private fun attachPreviewListener(player: ExoPlayer, item: IptvItem, generation: Int, explicitPlayback: Boolean) {
        previewHistoryRequest?.let(handler::removeCallbacks); previewHistoryRequest = null
        var recorded = false
        val watchAccountId = store.selectedAccount()?.id.orEmpty()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != previewGeneration || previewPlayer !== player) return
                val elapsed = previewWatchClock.update(isPlaying, android.os.SystemClock.elapsedRealtime())
                if (elapsed > 0) watchTimeWork.execute { runCatching { store.recordWatchTime(item.id, elapsed, watchAccountId) } }
                if (isPlaying && !guideOverlay && !previewFullscreen) schedulePreviewFullscreen()
                else { previewFullscreenRequest?.let(handler::removeCallbacks); previewFullscreenRequest = null }
                previewHistoryRequest?.let(handler::removeCallbacks)
                if (isPlaying && explicitPlayback && !recorded) {
                    previewHistoryRequest = Runnable {
                        if (generation == previewGeneration && player.isPlaying && !recorded) {
                            recorded = true
                            work.execute { runCatching { store.recordPlayback(item, watchAccountId) } }
                        }
                    }.also { handler.postDelayed(it, store.intSetting("historyDelay", 10).coerceIn(0, 120) * 1000L) }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (generation != previewGeneration || previewPlayer !== player) return
                previewPlaceholder.visibility = View.VISIBLE
                status.visibility = View.VISIBLE
                status.text = "Aperçu indisponible. Ouvre la chaîne dans le lecteur."
            }
            override fun onRenderedFirstFrame() {
                if (generation != previewGeneration || previewPlayer !== player) return
                previewPlaceholder.visibility = View.GONE
                previewView.animate().cancel()
                if (store.booleanSetting("previewTransition", true)) {
                    previewView.alpha = 0f; previewView.animate().alpha(1f).setDuration(180).start()
                } else previewView.alpha = 1f
                if (previewFullscreenPendingItemId == item.id && previewItem?.id == item.id) {
                    previewFullscreenPendingItemId = null
                    enterPreviewFullscreen()
                }
            }
        }
        previewListener = listener
        player.addListener(listener)
    }

    private fun switchPreviewChannel(next: Boolean) {
        if (previewFullscreenClosing) return
        val current = previewPendingItem ?: previewItem ?: return
        val item = IptvOrganization.adjacentChannel(catalogueItems(), current.id, next)
        if (item == null) { toast("Aucune autre chaîne disponible dans cette liste."); return }
        changePreviewChannel(item)
    }

    private fun changePreviewChannel(item: IptvItem, authorized: Boolean = false) {
        if (previewFullscreenClosing) return
        if (!authorized) { playbackAccess(item) { changePreviewChannel(item, true) }; return }
        val player = previewPlayer ?: return
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return
        val previous = previewItem ?: return
        val accountId = store.selectedAccount()?.id.orEmpty()
        val elapsed = previewWatchClock.update(false, android.os.SystemClock.elapsedRealtime())
        if (elapsed > 0) watchTimeWork.execute { runCatching { store.recordWatchTime(previous.id, elapsed, accountId) } }
        previewListener?.let(player::removeListener); previewListener = null
        previewHistoryRequest?.let(handler::removeCallbacks); previewHistoryRequest = null
        previewFullscreenRequest?.let(handler::removeCallbacks); previewFullscreenRequest = null
        val generation = ++previewGeneration
        previewPendingItem = item
        previewPlaceholder.text = item.title
        previewPlaceholder.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = "Chargement de ${item.title}…"
        work.execute {
            val resolved = runCatching { provider(source).resolve(item) }
            runOnUiThread {
                if (isDestroyed || generation != previewGeneration || previewPlayer !== player) return@runOnUiThread
                resolved.fold({ stream ->
                    val mediaSource = runCatching { previewMediaSources.createMediaSource(this, stream.url, stream.headers) }
                        .getOrElse {
                            previewPendingItem = null
                            status.text = "Cette chaîne ne peut pas être lue."
                            attachPreviewListener(player, previous, generation, false)
                            previewWatchClock.update(player.isPlaying, android.os.SystemClock.elapsedRealtime())
                            return@fold
                        }
                    previewItem = item
                    previewPendingItem = null
                    rememberedFocus = item.id
                    livePlayerFocus = item.id
                    previewStreamUrl = stream.url
                    previewStreamHeaders = stream.headers
                    attachPreviewListener(player, item, generation, true)
                    status.text = ""
                    player.setMediaSource(mediaSource)
                    player.prepare()
                    player.play()
                    previewControlsViewModel?.updateLiveChannel(store.playbackContentId(item.id),
                        item.title, item.description, item.artwork, stream.url, stream.headers)
                    if (!previewFullscreen && activityResumed && guideActive && section == 1)
                        renderGuide(item.id)
                }, {
                    previewPendingItem = null
                    status.text = "Impossible de charger cette chaîne."
                    attachPreviewListener(player, previous, generation, false)
                    previewWatchClock.update(player.isPlaying, android.os.SystemClock.elapsedRealtime())
                })
            }
        }
    }

    private fun setGuideOverlay(enabled: Boolean) {
        if (guideOverlay == enabled) return
        guideOverlay = enabled
        (previewView.parent as? ViewGroup)?.removeView(previewView)
        if (enabled) {
            (shell.parent as FrameLayout).addView(previewView, 0, FrameLayout.LayoutParams(-1, -1))
            previewFrame.visibility = View.GONE
            content.setBackgroundColor(interfaceBackground(themeBackground))
        } else {
            previewFrame.addView(previewView, 0, FrameLayout.LayoutParams(-1, -1))
            content.setBackgroundColor(Color.TRANSPARENT)
            previewFrame.visibility = if (store.booleanSetting("previewEnabled", true) && section in listOf(1, 5)) View.VISIBLE else View.GONE
        }
        nav.setBackgroundColor(interfaceBackground(surface(.06f)))
        navRows.forEachIndexed { index, row -> paintCommand(row as LinearLayout, row.hasFocus(), index == section) }
        if (enabled && guideActive) {
            val version = requestVersion
            val scope = guideScope
            val scrollX = guideHorizontal?.scrollX
            handler.post {
                if (activityResumed && guideOverlay && guideActive && version == requestVersion && scope === guideScope)
                    renderGuide(rememberedFocus, preservedScrollX = scrollX)
            }
        }
    }

    private fun schedulePreviewFullscreen() {
        previewFullscreenRequest?.let(handler::removeCallbacks); previewFullscreenRequest = null
        val delay = store.intSetting("previewFullscreenDelay", 120).coerceIn(0, 3600)
        if (delay == 0 || guideOverlay || previewFullscreen || previewPlayer?.isPlaying != true) return
        val item = previewItem ?: return
        val generation = previewGeneration
        val request = object : Runnable {
            override fun run() {
                if (!activityResumed || generation != previewGeneration || previewPlayer?.isPlaying != true || previewItem?.id != item.id) return
                if (contextPanel != null || sourcePage != null || !window.decorView.hasWindowFocus()) handler.postDelayed(this, 1000)
                else { previewFullscreenRequest = null; enterPreviewFullscreen() }
            }
        }
        previewFullscreenRequest = request; handler.postDelayed(request, delay * 1000L)
    }

    private fun previewGuideChannel(item: IptvItem) {
        if ((guideActive || liveChannelList) && !store.booleanSetting(if (guideOverlay) "guideOverlayStay" else "previewStayGuide", !guideOverlay)) play(item)
        else startPreview(item, true)
    }

    private fun watchGuideChannel(item: IptvItem) {
        if (guideOverlay) {
            if (store.booleanSetting("guideOverlayStay", false)) startPreview(item, true) else play(item)
        } else if (store.booleanSetting("previewEnabled", true) && store.setting("channelClick", "preview") == "preview") {
            if (!store.booleanSetting("previewStayGuide", true) || previewItem?.id == item.id && previewPlayer != null) play(item)
            else startPreview(item, true)
        } else if (!store.booleanSetting("previewEnabled", true) && store.booleanSetting("guideOverlayStay", false)) {
            setGuideOverlay(true); startPreview(item, true)
        } else play(item)
    }

    private fun renderGuide(focusId: String = "", ordered: List<IptvItem>? = null, preservedScrollX: Int? = null) {
        setVodHeroVisible(false)
        if (previewFullscreen) return
        (results.parent as? ScrollView)?.isSmoothScrollingEnabled = store.booleanSetting("animatedChannelScroll", true)
        val previousFocus = results.findFocus()?.tag as? String
        val restoreContentFocus = navigationMode == 2
        val focusInput = navigationInputVersion
        val restorePlayerFocus = restoreLiveFocus
        val restoreFocus = focusId.ifBlank { if (restorePlayerFocus) rememberedFocus else previousFocus.orEmpty() }
        resetArtworkRequests()
        guideScope.cancel()
        val scope = IptvRequestScope(IptvHttp.client).also { guideScope = it }
        val version = requestVersion
        epgVersion++
        if (informationHeader.layoutParams.height != dp(220)) {
            informationHeader.layoutParams.height = dp(220); informationHeader.requestLayout()
        }
        backdrop.visibility = View.GONE
        backdrop.tag = ""
        backdrop.setImageDrawable(null)
        status.visibility = View.GONE
        fun control(label: String, action: () -> Unit) = button(label, action).apply { textSize = 13f * textScale }
        fun previousDay() {
            val oldest = Calendar.getInstance().apply {
                add(Calendar.DATE, -store.intSetting("epgPastDays", 1))
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (guideDay.timeInMillis > oldest.timeInMillis) {
                guideDay.add(Calendar.DATE, -1); renderGuide()
            }
        }
        val date = control("${SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(guideDay.time)} à ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())}") {
            dialog("Date du guide", listOf(
                "Jour précédent" to { previousDay() },
                "Aujourd’hui" to {
                    guideDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                    renderGuide()
                },
                "Jour suivant" to { guideDay.add(Calendar.DATE, 1); renderGuide() }
            ))
        }.apply {
            tag = "guide-date"
            setTextColor(accentTextColor()); setPadding(0, 0, dp(6), 0); setSingleLine(true)
            val focus = onFocusChangeListener
            setOnFocusChangeListener { view, focused -> focus?.onFocusChange(view, focused); setTextColor(if (focused) contrast else accentTextColor()) }
        }
        leftToGroups(date)
        val start = guideDay.timeInMillis
        val end = (guideDay.clone() as Calendar).apply { add(Calendar.DATE, 1) }.timeInMillis
        val pixelsPerHour = dp(300)
        fun position(time: Long) = (((time - start).toDouble() / 3_600_000) * pixelsPerHour).toInt()
        val width = position(end)
        val height = dp(44)
        val showNumbers = store.booleanSetting("channelNumbers", true)
        val showLogos = store.booleanSetting("showLogos", true)
        val showNames = store.booleanSetting("channelNames", true)
        val showReplay = store.booleanSetting("showCatchupIcon", true)
        val channelLines = if (store.booleanSetting("channelTwoLines", true)) 2 else 1
        val programLines = if (store.booleanSetting("programTwoLines", true)) 2 else 1
        val colorCurrentChannel = store.booleanSetting("currentChannelColor", true)
        val colorCurrentProgram = store.booleanSetting("currentProgramColor", true)
        val autoPreview = store.booleanSetting("previewAutoPlay", false)
        val progressOnly = store.booleanSetting("currentProgramProgressOnly", true)
        val showNow = store.booleanSetting("currentTimeIndicator", true)
        val overlayAlpha = interfaceOpacity
        val nameSettings = listOf("channelNameEditor", "channelNamePrefixes", "channelNameSuffixes").associateWith { store.setting(it, "") }
        val epgPastDays = store.setting("epgPastDays", "1")
        val epgDescriptions = store.setting("epgDescriptions", "true")
        val epgRefreshHours = store.setting("epgRefreshHours", "24")
        fun paint(view: View, color: Int) {
            val drawable = (view.background as? GradientDrawable) ?: shape(color).also(view::setBackground)
            drawable.setColor(color)
            drawable.setStroke(dp(if (view.hasFocus() && focusNeedsOutline) 1 else 0), focusOutlineColor())
        }
        val sorted = ordered ?: catalogueItems()
        if (restorePlayerFocus) sorted.indexOfFirst { it.id == rememberedFocus }.takeIf { it >= 0 }?.let {
            guideOffset = (it / 12) * 12
        }
        guideOffset = if (sorted.isEmpty()) 0 else guideOffset.coerceAtMost((sorted.lastIndex / 12) * 12)
        val channels = sorted.drop(guideOffset).take(12)
        val itemsAtRender = visibleItems
        fun moveChannels(forward: Boolean): Boolean {
            val available = if (visibleItems === itemsAtRender) sorted else catalogueItems()
            val offset = if (forward) guideOffset + channels.size else (guideOffset - 12).coerceAtLeast(0)
            if (offset == guideOffset || offset !in available.indices) return false
            val target = available[if (forward) offset else (guideOffset - 1).coerceAtLeast(0)]
            val scrollX = guideHorizontal?.scrollX ?: 0
            guideOffset = offset
            renderGuide(target.id, available, scrollX)
            if (store.booleanSetting("animatedChannelScroll", true)) {
                results.translationY = dp(if (forward) 18 else -18).toFloat()
                results.animate().translationY(0f).setDuration(180).start()
            } else { results.animate().cancel(); results.translationY = 0f }
            return true
        }
        remoteGuidePage = ::moveChannels
        var selectedChannel = channels.firstOrNull { it.id == rememberedFocus } ?: channels.firstOrNull()
        val heading = "guide-information:$textScale:$themeForeground:progress"
        if (info.tag != heading || info.childCount != 3) {
            info.removeAllViews(); info.tag = heading
            info.addView(text("", 22, true).apply { maxLines = 3 })
            info.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
                addView(text("", 15).apply { setSingleLine(true) })
                addView(LinearLayout(this@IptvActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(FrameLayout(this@IptvActivity).apply {
                        background = shape(ColorUtils.setAlphaComponent(themeForeground, 64))
                        addView(View(this@IptvActivity).apply { background = shape(themeForeground) }, FrameLayout.LayoutParams(0, -1))
                    }, LinearLayout.LayoutParams(dp(52), dp(3)).apply { rightMargin = dp(14) })
                    addView(text("", 15).apply { setSingleLine(true) })
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
                addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    val times = getChildAt(0) as TextView
                    val progress = getChildAt(1) as LinearLayout
                    val remaining = progress.getChildAt(1) as TextView
                    val requiredWidth = times.paint.measureText(times.text.toString()) +
                        remaining.paint.measureText(remaining.text.toString()) + dp(80)
                    val compact = progress.visibility == View.VISIBLE && right - left < requiredWidth
                    val layoutOrientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    if (orientation != layoutOrientation) {
                        orientation = layoutOrientation
                        progress.layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                            leftMargin = if (compact) 0 else dp(14); topMargin = if (compact) dp(6) else 0
                        }
                    }
                }
            })
            info.addView(text("", 14).apply { maxLines = 3; setPadding(0, dp(8), 0, 0) })
        }
        val programTitle = info.getChildAt(0) as TextView
        val programTiming = info.getChildAt(1) as LinearLayout
        val programTime = programTiming.getChildAt(0) as TextView
        val programProgressInfo = programTiming.getChildAt(1) as LinearLayout
        val programProgress = programProgressInfo.getChildAt(0) as FrameLayout
        val programRemaining = programProgressInfo.getChildAt(1) as TextView
        val programDescription = info.getChildAt(2) as TextView
        val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
        fun describe(channel: IptvItem, program: EpgProgram?) {
            selectedChannel = channel
            remoteGuideChannel = channel; remoteGuideProgram = program
            val title = program?.title ?: "Pas d’information"
            val times = program?.let { "${timeFormat.format(Date(it.start))} – ${timeFormat.format(Date(it.end))}" }.orEmpty()
            if (programTitle.text.toString() != title) programTitle.text = title
            if (programTime.text.toString() != times) programTime.text = times
            if (programDescription.text.toString() != program?.description.orEmpty()) programDescription.text = program?.description.orEmpty()
            programTiming.visibility = if (program == null) View.GONE else View.VISIBLE
            val now = System.currentTimeMillis()
            val currentProgram = program?.takeIf { now in it.start until it.end }
            programProgressInfo.visibility = if (currentProgram == null) View.GONE else View.VISIBLE
            currentProgram?.let {
                val elapsed = (now - it.start).toDouble() / (it.end - it.start)
                programProgress.getChildAt(0).layoutParams = FrameLayout.LayoutParams((elapsed * dp(52)).toInt(), -1)
                programRemaining.text = "${(it.end - now + 59_999) / 60_000} min"
            }
            programDescription.visibility = if (program == null) View.GONE else View.VISIBLE
        }
        selectedChannel?.let { describe(it, null) }
        fun watch(channel: IptvItem) {
            watchGuideChannel(channel)
        }
        fun focusChannel(channel: IptvItem, view: View, program: EpgProgram?) {
            showNavigation(2); rememberedFocus = channel.id; describe(channel, program)
            previewFocusRequest?.let(handler::removeCallbacks)
            if (autoPreview && previewItem?.id != channel.id) {
                previewFocusRequest = Runnable { if (view.hasFocus()) previewGuideChannel(channel) }.also { handler.postDelayed(it, 500) }
            }
        }
        val gridTag = "guide-grid:$start:$end:$textScale:$themeForeground:$themeBackground:$accent:$overlayAlpha:$guideOverlay"
        val reusable = (results.getChildAt(0) as? LinearLayout)?.takeIf { it.tag == gridTag }
        val grid = reusable ?: LinearLayout(this).apply { tag = gridTag }
        val names = (reusable?.getChildAt(0) as? LinearLayout) ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val previousNames = (1 until names.childCount).mapNotNull { names.getChildAt(it) as? LinearLayout }
        if (names.childCount > 0) names.removeViewAt(0)
        names.addView(date, 0, LinearLayout.LayoutParams(-1, dp(30)))
        val horizontal = (reusable?.getChildAt(1) as? HorizontalScrollView) ?: HorizontalScrollView(this).apply { isFocusable = false }
        val timeline = (horizontal.getChildAt(0) as? LinearLayout) ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val previousRows = (1 until timeline.childCount).mapNotNull { timeline.getChildAt(it) as? FrameLayout }
        if (reusable == null) {
            val times = FrameLayout(this)
            var tick = start
            while (tick < end) {
                times.addView(text(timeFormat.format(Date(tick)), 14), FrameLayout.LayoutParams(pixelsPerHour / 2, dp(30)).apply { leftMargin = position(tick) })
                tick += 1_800_000
            }
            timeline.addView(times, LinearLayout.LayoutParams(width, dp(30)))
            horizontal.addView(timeline, ViewGroup.LayoutParams(width, -2))
            grid.addView(names, LinearLayout.LayoutParams(dp(240), -2))
            grid.addView(horizontal, LinearLayout.LayoutParams(0, -2, 1f))
            results.removeAllViews(); results.addView(grid)
        }
        addHistoryClearAction()
        timeline.foreground = if (showNow) object : android.graphics.drawable.Drawable() {
            private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(themeForeground, 170); strokeWidth = 1f
            }
            private val point = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = themeForeground }
            override fun draw(canvas: android.graphics.Canvas) {
                val now = System.currentTimeMillis()
                if (now !in start until end) return
                val x = position(now) + .5f
                val y = dp(30).toFloat()
                canvas.drawLine(x, y, x, bounds.bottom.toFloat(), line)
                canvas.drawCircle(x, y, dp(2).toFloat(), point)
            }
            override fun setAlpha(alpha: Int) { line.alpha = alpha; point.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { line.colorFilter = filter; point.colorFilter = filter; invalidateSelf() }
            @Deprecated("Drawable opacity") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        } else null
        guideLayoutListener?.let { guideHorizontal?.removeOnLayoutChangeListener(it) }
        guideHorizontal = horizontal
        val viewportWidth = horizontal.width.takeIf { it > 0 } ?: (content.width - dp(240)).coerceAtLeast(dp(300))
        val initialScrollX = preservedScrollX ?: restoreFocus.substringAfterLast('|', "").toLongOrNull()?.let(::position)
            ?: position(System.currentTimeMillis() - System.currentTimeMillis() % 1_800_000)
        var renderScrollX = initialScrollX.coerceIn(0, (width - viewportWidth).coerceAtLeast(0))
        val refreshRows = mutableListOf<() -> Unit>()
        val rows = mutableListOf<Pair<FrameLayout, MutableList<Pair<Long, View>>>>()
        channels.forEachIndexed { index, channel ->
            var channelPrograms = emptyList<EpgProgram>()
            val name = (previousNames.getOrNull(index) ?: LinearLayout(this)).apply {
                gravity = Gravity.CENTER_VERTICAL; isFocusable = true; tag = channel.id
                if (id == View.NO_ID) id = View.generateViewId()
                setPadding(dp(4), dp(4), dp(8), dp(4))
                paint(this, if (hasFocus()) accent else if (colorCurrentChannel && previewItem?.id == channel.id)
                    interfaceBackground(ColorUtils.blendARGB(themeBackground, accent, .20f)) else Color.TRANSPARENT)
            }
            if (name.childCount != 4) {
                name.removeAllViews()
                name.addView(text("", 14).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(26), -1))
                name.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(48), dp(36)).apply { rightMargin = dp(8) })
                name.addView(text("", 14, true), LinearLayout.LayoutParams(0, -2, 1f))
                name.addView(text("↶", 20).apply { contentDescription = "Replay disponible" }, LinearLayout.LayoutParams(dp(22), -2))
            }
            val number = (name.getChildAt(0) as TextView).apply {
                text = "${guideOffset + index + 1}"; visibility = if (showNumbers) View.VISIBLE else View.GONE
                setTextColor(if (name.hasFocus()) contrast else themeForeground)
            }
            val logo = (name.getChildAt(1) as ImageView).apply { visibility = if (showLogos) View.VISIBLE else View.GONE }
            if (showLogos) {
                loadImage(logo, channel.artwork, channel, dp(48), dp(36))
            }
            val label = (name.getChildAt(2) as TextView).apply {
                text = IptvOrganization.channelTitle(channel.title, nameSettings)
                setSingleLine(channelLines == 1)
                maxLines = channelLines
                ellipsize = if (channelLines == 1) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
                marqueeRepeatLimit = -1
                visibility = if (showNames) View.VISIBLE else View.GONE
                setTextColor(if (name.hasFocus()) contrast else themeForeground)
                isSelected = name.hasFocus() && channelLines == 1
            }
            val replay = (name.getChildAt(3) as TextView).apply {
                visibility = if (channel.catchupDays > 0 && showReplay) View.VISIBLE else View.GONE
                setTextColor(if (name.hasFocus()) contrast else themeForeground)
            }
            name.setOnClickListener { watch(channel) }
            name.setOnLongClickListener { channelMenu(channel); true }
            name.setOnFocusChangeListener { _, focused ->
                paint(name, if (focused) accent else if (colorCurrentChannel && previewItem?.id == channel.id)
                    interfaceBackground(ColorUtils.blendARGB(themeBackground, accent, .20f)) else Color.TRANSPARENT)
                number.setTextColor(if (focused) contrast else themeForeground)
                label.setTextColor(if (focused) contrast else themeForeground)
                label.isSelected = focused && channelLines == 1
                replay.setTextColor(if (focused) contrast else themeForeground)
                if (logo.drawable !is android.graphics.drawable.BitmapDrawable) logo.setColorFilter(if (focused) contrast else themeForeground)
                if (focused) {
                    focusChannel(channel, name, channelPrograms.firstOrNull { System.currentTimeMillis() in it.start until it.end })
                }
            }
            if (name.parent == null) names.addView(name, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = dp(2) })
            val row = previousRows.getOrNull(index) ?: FrameLayout(this)
            val recycledCells = java.util.ArrayDeque<TextView>()
            // Keep the measured widgets; only their channel/program bindings change between pages.
            for (childIndex in 0 until row.childCount) {
                val child = row.getChildAt(childIndex)
                if (child is TextView) recycledCells.add(child)
            }
            for (childIndex in row.childCount - 1 downTo 0) if (row.getChildAt(childIndex) !is TextView) row.removeViewAt(childIndex)
            if (row.parent == null) timeline.addView(row, LinearLayout.LayoutParams(width, height).apply { bottomMargin = dp(2) })
            val cells = mutableListOf<Pair<Long, View>>()
            rows += row to cells
            val cellData = mutableMapOf<Long, Triple<Long, EpgProgram?, Boolean>>()
            val segments = mutableListOf<Long>()
            var renderedRange: LongRange? = null
            var renderedPrograms: List<EpgProgram>? = null
            fun fill(programs: List<EpgProgram>, missing: String, focusTime: Long? = null) {
                val range = IptvGuideViewport.range(start, end, renderScrollX, horizontal.width.takeIf { it > 0 } ?: viewportWidth, pixelsPerHour)
                if (range == renderedRange && programs == renderedPrograms && (focusTime == null || cells.any { it.first == focusTime })) return
                val focusedTime = cells.firstOrNull { it.second.hasFocus() }?.first
                    ?: recycledCells.firstOrNull { it.hasFocus() && it.tag?.toString()?.startsWith("${channel.id}|") == true }
                        ?.tag?.toString()?.substringAfterLast('|')?.toLongOrNull()
                channelPrograms = programs
                renderedRange = range; renderedPrograms = programs
                val previous = cells.toMap()
                val kept = mutableListOf<Pair<Long, View>>()
                segments.clear()
                var cursor = start
                fun cell(from: Long, to: Long, program: EpgProgram?) {
                    if (to <= from) return
                    segments += from
                    if ((to <= range.first || from > range.last) && (focusedTime == null || focusedTime !in from until to) &&
                        (focusTime == null || focusTime !in from until to)) return
                    val now = System.currentTimeMillis()
                    val current = program != null && now in program.start until program.end
                    val highlighted = current && colorCurrentProgram
                    val normal = (if (highlighted && !progressOnly) ColorUtils.blendARGB(themeBackground, accent, .20f) else surface(.08f)).let {
                        if (guideOverlay) ColorUtils.setAlphaComponent(it, overlayAlpha) else it
                    }
                    val data = Triple(to, program, highlighted)
                    val existing = previous[from]?.takeIf { cellData[from] == data }
                    val recycled = recycledCells.firstOrNull { it.tag == "${channel.id}|$from" }
                        ?: recycledCells.firstOrNull { !it.hasFocus() }
                    val view = existing ?: ((previous[from] as? TextView) ?: recycled?.also { recycledCells.remove(it) } ?: button("", {})).apply {
                        val title = program?.title ?: missing
                        if (text.toString() != title) text = title
                        setOnClickListener {
                            if (program == null || System.currentTimeMillis() in program.start until program.end) watch(channel) else programmeActions(channel, program)
                        }
                        tag = "${channel.id}|$from"
                        textSize = 13f * textScale; maxLines = programLines; setPadding(dp(5), dp(3), dp(5), dp(3))
                        paint(this, if (hasFocus()) accent else normal)
                        setTextColor(if (hasFocus()) contrast else themeForeground)
                        setOnFocusChangeListener { view, focused ->
                            paint(view, if (focused) accent else normal)
                            setTextColor(if (focused) contrast else themeForeground)
                            if (focused) focusChannel(channel, view, program)
                        }
                        setOnLongClickListener { if (program == null) recordingOptions(channel) else programmeActions(channel, program); true }
                        setOnKeyListener { _, key, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) false
                            else if (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                                val rowIndex = rows.indexOfFirst { it.first == row }
                                val anchor = maxOf(from, start + horizontal.scrollX.toLong() * 3_600_000 / pixelsPerHour)
                                val target = rows.getOrNull(rowIndex + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)?.second
                                    ?.lastOrNull { it.first <= anchor }?.second
                                if (target != null) { target.requestFocus(); true }
                                else if (key == KeyEvent.KEYCODE_DPAD_DOWN && moveChannels(true)) true
                                else if (key == KeyEvent.KEYCODE_DPAD_UP && rowIndex == 0 && moveChannels(false)) true
                                else if (key == KeyEvent.KEYCODE_DPAD_UP && rowIndex == 0) {
                                    (previewFrame.takeIf { it.visibility == View.VISIBLE } ?: date).requestFocus(); true
                                } else false
                            } else if (key == KeyEvent.KEYCODE_DPAD_LEFT && position(from) <=
                                (if (System.currentTimeMillis() in start until end) guideNowPosition else horizontal.scrollX)) {
                                showNavigation(1); focusGroup(); true
                            } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_LEFT) {
                                val neighbor = segments.getOrNull(segments.indexOf(from) + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                                if (neighbor == null) true else {
                                    fill(channelPrograms, missing, neighbor)
                                    cells.firstOrNull { it.first == neighbor }?.second?.requestFocus() ?: true
                                }
                            } else false
                        }
                    }
                    if (existing == null) {
                        val layout = FrameLayout.LayoutParams((position(to) - position(from) - dp(2)).coerceAtLeast(dp(2)), height).apply { leftMargin = position(from) }
                        if (view.parent == null) row.addView(view, layout) else view.layoutParams = layout
                        cellData[from] = data
                    }
                    val padding = dp(5) + (renderScrollX - position(from)).coerceIn(0, (view.layoutParams.width - dp(40)).coerceAtLeast(0))
                    if (view.paddingLeft != padding) view.setPadding(padding, dp(3), dp(5), dp(3))
                    kept += from to view
                }
                programs.forEach { program ->
                    val from = maxOf(start, program.start, cursor)
                    val to = minOf(end, program.end)
                    if (to > from) {
                        while (cursor < from) { val gapEnd = minOf(from, cursor + 1_800_000); cell(cursor, gapEnd, null); cursor = gapEnd }
                        cell(from, to, program); cursor = to
                    }
                }
                while (cursor < end) { val gapEnd = minOf(end, cursor + 1_800_000); cell(cursor, gapEnd, null); cursor = gapEnd }
                val retained = kept.map { it.first }.toSet()
                previous.filterKeys { it !in retained }.forEach { (from, view) ->
                    row.removeView(view); cellData.remove(from); recycledCells.add(view as TextView)
                }
                recycledCells.forEach { if (it.parent === row) row.removeView(it) }
                cells.clear(); cells.addAll(kept)
                val now = System.currentTimeMillis()
                if (focusedTime != null) {
                    val focusedCell = cells.firstOrNull { it.second.hasFocus() }
                    if (focusedCell == null) cells.lastOrNull { it.first <= focusedTime }?.second?.requestFocus()
                    else describe(channel, cellData[focusedCell.first]?.second)
                }
                else if (selectedChannel?.id == channel.id) describe(channel, programs.firstOrNull { now in it.start until it.end })
            }
            val cacheKey = "${channel.id}:$start:${sources.firstOrNull { it.id == channel.sourceId }.hashCode()}:" +
                "${store.organization(channel.sourceId, channel.type, channel.categoryId, "epgOffsetMinutes", "0")}:" +
                "$epgPastDays:$epgDescriptions:$epgRefreshHours"
            val cached = guidePrograms.get(cacheKey)
            // Keep the last programs visible while an expired entry is refreshed in the background.
            fill(cached?.second.orEmpty(), "Pas d’information")
            refreshRows += { fill(channelPrograms, "Pas d’information") }
            name.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { showNavigation(1); (activeGroup ?: groupRows.firstOrNull() ?: navRows[section]).requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        cells.lastOrNull { position(it.first) <= horizontal.scrollX }?.second?.requestFocus() ?: false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (index == 0 && moveChannels(false)) true else {
                            (if (index == 0) previewFrame.takeIf { it.visibility == View.VISIBLE } ?: date else names.getChildAt(index)).requestFocus(); true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> names.getChildAt(index + 2)?.requestFocus() ?: moveChannels(true)
                    else -> false
                }
            }
            if (cached == null || System.currentTimeMillis() - cached.first >= 60_000) guideWork.execute {
                val programs = runCatching { IptvHttp.withScope(scope) { epg(channel, start, end) } }
                runOnUiThread {
                    if (!isDestroyed && activityResumed && guideActive && detail == null && version == requestVersion && scope === guideScope) {
                        programs.getOrNull()?.let { guidePrograms.put(cacheKey, System.currentTimeMillis() to it) }
                        fill(programs.getOrDefault(emptyList()), "Pas d’information")
                    }
                }
            }
        }
        while (names.childCount > channels.size + 1) names.removeViewAt(names.childCount - 1)
        while (timeline.childCount > channels.size + 1) timeline.removeViewAt(timeline.childCount - 1)
        horizontal.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (scope !== guideScope) return@setOnScrollChangeListener
            renderScrollX = scrollX
            refreshRows.forEach { it() }
            rows.forEach { (_, cells) -> cells.forEach { (from, view) ->
                val padding = dp(5) + (scrollX - position(from)).coerceIn(0, (view.layoutParams.width - dp(40)).coerceAtLeast(0))
                if (view.paddingLeft != padding) view.setPadding(padding, dp(3), dp(5), dp(3))
            } }
        }
        guideLayoutListener = View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && scope === guideScope) refreshRows.forEach { it() }
        }.also(horizontal::addOnLayoutChangeListener)
        horizontal.post {
            if (isDestroyed || version != requestVersion || scope !== guideScope) return@post
            val now = System.currentTimeMillis()
            guideNowPosition = position(now - now % 1_800_000).coerceIn(0, (width - horizontal.width).coerceAtLeast(0))
            val restoredScrollX = initialScrollX.coerceIn(0, (width - horizontal.width).coerceAtLeast(0))
            val refreshWithoutScroll = horizontal.scrollX == restoredScrollX && renderScrollX != restoredScrollX
            renderScrollX = restoredScrollX
            horizontal.scrollTo(renderScrollX, 0)
            // Position/size listeners already refill the viewport; only an unreported clamp needs a pass here.
            if (refreshWithoutScroll) refreshRows.forEach { it() }
            if (restoreContentFocus && focusInput == navigationInputVersion && contextPanel == null && sourcePage == null &&
                accountPage == null && recordingPage == null && hasWindowFocus() && restoreFocus.isNotBlank()) {
                val target = results.findViewWithTag<View>(restoreFocus)
                    ?: results.findFocus()?.takeIf { it.tag?.toString()?.substringBefore('|') == restoreFocus.substringBefore('|') }
                    ?: names.findViewWithTag<View>(rememberedFocus)
                if (target != null) { showNavigation(2); if (!target.hasFocus()) target.requestFocus() }
            }
            if (restorePlayerFocus) restoreLiveFocus = false
            resumeGroupEntry()
        }
    }

    private fun programmeActions(channel: IptvItem, program: EpgProgram) {
        val now = System.currentTimeMillis()
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Rechercher" to { guideRemoteAction(IptvRemoteAction.SEARCH, channel, program) },
            "Paramètres" to { showSettingsMenu() },
            "Regarder la chaîne" to { play(channel) })
        if (program.end > now) actions += (if (program.start > now) "Programmer l’enregistrement" else "Enregistrer jusqu’à la fin") to {
            playbackAccess(channel) {
                if (program.start > now) showRecordingForm(RecordingDraft(channel.sourceId, channel, title = program.title,
                    start = program.start, end = program.end,
                    beforeMinutes = store.intSetting("recordingBefore", 0).coerceIn(0, 60),
                    afterMinutes = store.intSetting("recordingAfter", 0).coerceIn(0, 60)))
                else scheduleRecording(channel, program.title, program.start, program.end,
                    beforeMinutes = store.intSetting("recordingBefore", 0).coerceIn(0, 60),
                    afterMinutes = store.intSetting("recordingAfter", 0).coerceIn(0, 60))
            }
        }
        actions += "Enregistrement personnalisé" to { customRecordingOptions(channel, program) }
        actions += "Description du programme" to { guideRemoteAction(IptvRemoteAction.DESCRIPTION, channel, program) }
        if (now in program.start until program.end)
            actions += "Regarder la chaîne dans un lecteur externe" to { guideRemoteAction(IptvRemoteAction.EXTERNAL, channel, null) }
        actions += (if (store.favorite(channel)) "Retirer la chaîne des Favoris" else "Ajouter la chaîne aux Favoris") to {
            guideRemoteAction(IptvRemoteAction.FAVORITE, channel, program)
        }
        actions += "Options de Chaîne" to { channelMenu(channel) }
        if (program.start > now) actions += "Créer un rappel" to {
            async({ IptvRecordingScheduler.scheduleReminder(this, store, channel, program) }, failure = { toast(it) }) { toast("Rappel programmé.") }
        }
        store.reminders().firstOrNull { it.channel.id == channel.id && it.start == program.start && !it.notified }?.let { reminder ->
            actions.removeAll { it.first == "Créer un rappel" }
            actions += "Annuler le rappel" to {
                async({ IptvRecordingScheduler.cancelReminder(this, store, reminder) }) {
                    toast("Rappel annulé."); if (section == 5 && listMode == "reminders") showSavedPrograms(true)
                }
            }
        }
        val saved = savedPrograms().any { it.channel.id == channel.id && it.program.start == program.start }
        actions += (if (saved) "Retirer de Mon programme TV" else "Ajouter à Mon programme TV") to {
            async({
                val values = savedPrograms().filterNot { it.channel.id == channel.id && it.program.start == program.start }
                store.setSetting("savedPrograms", iptvJson.encodeToString(if (saved) values else values + IptvSavedProgram(channel, program)))
            }) { if (section == 5 && listMode == "programs") showSavedPrograms(false) else toast(if (saved) "Programme retiré." else "Programme ajouté.") }
        }
        actions += "Retour" to {}
        dialog("${channel.title} · ${program.title}", actions)
    }
    private fun customRecordingOptions(channel: IptvItem, program: EpgProgram) {
        settingsPanel("Enregistrement personnalisé") { rows, panel ->
            rows.addView(settingsPanelRow("Nouvel enregistrement") {
                playbackAccess(channel) {
                    if (panel.isShowing) {
                        val start = maxOf(System.currentTimeMillis() + 60_000L, program.start)
                        panel.dismiss()
                        showRecordingForm(RecordingDraft(channel.sourceId, channel, title = program.title,
                            start = start, end = program.end.takeIf { it > start } ?: (start + 60 * 60_000L),
                            beforeMinutes = store.intSetting("recordingBefore", 0).coerceIn(0, 60),
                            afterMinutes = store.intSetting("recordingAfter", 0).coerceIn(0, 60)))
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(62)))
            rows.addView(settingsPanelRow("Tous les enregistrements") {
                panel.dismiss(); recordingsTab = "files"; showSection(4)
                navRows[4].requestFocus(); showNavigation(1)
            }, LinearLayout.LayoutParams(-1, dp(62)))
            rows.getChildAt(0)
        }
    }
    private fun recordingOptions(channel: IptvItem) {
        dialog("Enregistrer · ${channel.title}", listOf(
            "Démarrer maintenant" to { recordingDuration(channel, System.currentTimeMillis()) },
            "Programmer un enregistrement" to { showRecordingForm(RecordingDraft(channel.sourceId, channel,
                title = channel.title, beforeMinutes = store.intSetting("recordingBefore", 0).coerceIn(0, 60),
                afterMinutes = store.intSetting("recordingAfter", 0).coerceIn(0, 60)), "startTime") }, "Annuler" to {}))
    }
    private fun recordingDuration(channel: IptvItem, start: Long) {
        val immediate = start <= System.currentTimeMillis() + 1000
        dialog("Durée de l’enregistrement", listOf(1, 15, 30, 60, 120).map { minutes ->
            "$minutes min" to {
                val actualStart = if (immediate) System.currentTimeMillis() else start
                scheduleRecording(channel, channel.title, actualStart, actualStart + minutes * 60_000L)
            }
        } + ("Annuler" to {}))
    }
    private fun scheduleRecording(channel: IptvItem, title: String, start: Long, end: Long,
        beforeMinutes: Int = 0, afterMinutes: Int = 0, recordingId: String? = null,
        repeatDays: List<Int> = emptyList(), remoteRevision: Long? = null,
        failure: (String) -> Unit = { toast(it) }, scheduled: () -> Unit = {}) {
        val remote = recordingId != null && IptvRecordingSync.remote(this, store, recordingId)
        async({
            if (recordingId == null) IptvRecordingScheduler.schedule(this, store, channel, title, start, end, beforeMinutes, afterMinutes, repeatDays)
            else if (remote) IptvRecordingSync.edit(this, store, recordingId,
                checkNotNull(remoteRevision) { "Rouvre la programmation avant de la modifier." },
                channel, title, start, end, beforeMinutes, afterMinutes, repeatDays)
            else IptvRecordingScheduler.reschedule(this, store, recordingId, channel, title, start, end, beforeMinutes, afterMinutes, repeatDays)
        }, version = null, failure = failure) { saved ->
            toast(if (remote) "Demande de modification enregistrée. En attente de l’appareil créateur."
                else if (recordingId != null) "Programmation modifiée." else if (saved.start > System.currentTimeMillis() + 1000)
                "Enregistrement programmé." else "Enregistrement démarré.")
            scheduled()
            if (section == 4) {
                recordingsTab = if (saved.start > System.currentTimeMillis() + 1000) "planned" else "files"
                recordingsSignature = ""; refreshRecordings(saved.id)
            }
        }
    }
    private data class RecordingDraft(
        var sourceId: String,
        var channel: IptvItem? = null,
        var group: IptvCategory? = null,
        var title: String = "",
        var start: Long = (System.currentTimeMillis() + 5 * 60_000L) / 60_000L * 60_000L,
        var end: Long = start + 60 * 60_000L,
        var beforeMinutes: Int = 0,
        var afterMinutes: Int = 0,
        var repeatDays: List<Int> = emptyList(),
        val recordingId: String? = null,
        val directory: String = "",
        val remoteRevision: Long? = null,
    )

    private fun showRecordingForm(draft: RecordingDraft = RecordingDraft(
        (selectedSource()?.takeIf { it.includes(ContentType.LIVE) }
            ?: orderedSources().firstOrNull { it.includes(ContentType.LIVE) })?.id.orEmpty(),
        beforeMinutes = store.intSetting("recordingBefore", 0).coerceIn(0, 60),
        afterMinutes = store.intSetting("recordingAfter", 0).coerceIn(0, 60)),
        focusKey: String = "channel") {
        if (recordingPage != null) return
        contextPanel?.dismiss()
        val origin = currentFocus
        val resumePreview = previewPlayer != null
        stopPreview()
        cancelGuideNumber()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeBackground)
            val metrics = resources.displayMetrics
            setPadding((metrics.widthPixels * .05f).toInt(), (metrics.heightPixels * .05f).toInt(),
                (metrics.widthPixels * .05f).toInt(), (metrics.heightPixels * .05f).toInt())
        }
        recordingPage = page
        pageFocus = null
        val parent = contentHost
        fun close(restorePreview: Boolean = true) {
            if (recordingPage !== page) return
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(page.windowToken, 0)
            val focused = page.hasFocus()
            parent.removeView(page); recordingPage = null; recordingPageBack = null; pageFocus = null
            content.visibility = if (sourcePage == null && accountPage == null) View.VISIBLE else View.INVISIBLE
            showNavigation(navigationMode)
            if (focused) (origin?.takeIf { it.isShown } ?: navRows[section]).requestFocus()
            if (restorePreview && resumePreview && activityResumed) previewItem?.let { startPreview(it, false) }
            if (section == 4) refreshRecordings()
            handler.post { refreshSyncedLibrary() }
        }
        recordingPageBack = { restorePreview -> close(restorePreview) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = button("Retour") { close() }
        heading.addView(back, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(20) })
        heading.addView(text(if (draft.recordingId == null) "Programmer un enregistrement" else "Modifier la programmation", 24, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val body = LinearLayout(this)
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(8), dp(12)) }
        val scroll = ScrollView(this).apply { isFocusable = false; addView(rows) }
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, .64f).apply { marginEnd = dp(20) })
        val recap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(surface(.06f))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val recapScroll = ScrollView(this).apply {
            id = View.generateViewId(); isFocusable = true; addView(recap)
            contentDescription = "Récapitulatif de l’enregistrement"
            setOnFocusChangeListener { _, focused ->
                recap.background = shape(surface(.06f)).apply { if (focused) setStroke(dp(2), accentTextColor()) }
            }
        }
        body.addView(recapScroll, LinearLayout.LayoutParams(0, -1, .36f))
        recap.addView(text("Récapitulatif", 16, true))
        val summaryTitle = text("", 20, true).apply { setPadding(0, dp(16), 0, dp(12)); maxLines = 3 }
        recap.addView(summaryTitle)
        val summary = text("", 14)
        recap.addView(summary)
        val recurrence = text("", 13).apply { setPadding(0, dp(16), 0, dp(16)); setTextColor(surface(.68f)) }
        recap.addView(recurrence)
        recap.addView(text("Dossier d’enregistrement", 13, true))
        recap.addView(text(if (draft.remoteRevision != null) "Dossier conservé sur l’appareil créateur"
            else draft.directory.ifBlank { IptvRecordingScheduler.rootPath(this) }, 12).apply {
            setTextColor(surface(.68f)); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        })
        val error = text("", 14).apply {
            visibility = View.GONE; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = shape(surface(.14f)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        page.addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT }
        page.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        var saving = false
        lateinit var save: TextView
        lateinit var updateFields: (String) -> Unit
        fun selectedProvider() = sources.firstOrNull { it.id == draft.sourceId && it.includes(ContentType.LIVE) }
        fun selectedGroupName() = draft.group?.let { group ->
            selectedProvider()?.let { groupLabel(it, ContentType.LIVE, group).ifBlank { "Sans groupe" } }
        } ?: "Tous les groupes"
        val dateFormat = SimpleDateFormat("EEE dd/MM/yyyy", Locale.FRANCE)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val format = SimpleDateFormat("EEE dd/MM · HH:mm", Locale.FRANCE)
        val values = mutableMapOf<String, TextView>()
        fun label(title: String) = rows.addView(text(title, 14, true).apply { setPadding(dp(16), dp(12), dp(16), dp(4)) })
        fun row(key: String, title: String, value: String, action: () -> Unit) =
            settingsPanelRow(title, value) { if (!saving) action() }.apply {
                tag = key; minimumHeight = dp(58)
                setOnKeyListener(null)
                val labels = getChildAt(0) as LinearLayout
                for (index in 0 until labels.childCount) (labels.getChildAt(index) as TextView).apply {
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                }
                (labels.getChildAt(1) as? TextView)?.let { values[key] = it }
                rows.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        label("Chaîne et programme")
        row("provider", "Fournisseur", selectedProvider()?.name ?: "Choisir un fournisseur") {
            showRecordingProviders(draft) { updateFields("group") }
        }
        row("group", "Groupe", selectedGroupName()) {
            if (selectedProvider() == null) showRecordingProviders(draft) { updateFields("group") }
            else showRecordingGroups(draft) { updateFields("channel") }
        }
        row("channel", "Chaîne", draft.channel?.title ?: "Choisir une chaîne") {
            if (selectedProvider() == null) showRecordingProviders(draft) { updateFields("group") }
            else showRecordingChannels(draft) { updateFields("startTime") }
        }
        label("Nom de l’enregistrement")
        val name = settingsInput().apply {
            id = View.generateViewId(); tag = "name"; setSingleLine(true); textSize = 16 * textScale
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
            hint = "Nom de la chaîne par défaut"; setHintTextColor(surface(.68f)); setText(draft.title)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draft.title = s?.toString().orEmpty()
                    summaryTitle.text = draft.title.trim().ifBlank { draft.channel?.title ?: "Choisis une chaîne" }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        rows.addView(name, LinearLayout.LayoutParams(-1, dp(46)).apply { marginStart = dp(16); marginEnd = dp(16) })
        fun changeStart(value: Long, key: String) {
            val window = IptvRecordingScheduler.moveStart(draft.start, draft.end, value)
            draft.start = window.start; draft.end = window.end; updateFields(key)
        }
        label("Horaires")
        row("startDate", "Date de début", dateFormat.format(Date(draft.start))) {
            pickRecordingDate(draft.start) { changeStart(it, "startDate") }
        }
        row("startTime", "Heure de début", timeFormat.format(Date(draft.start))) {
            pickRecordingTime("Heure de début", draft.start) { changeStart(it, "startTime") }
        }
        row("endDate", "Date de fin", dateFormat.format(Date(draft.end))) {
            pickRecordingDate(draft.end) { value -> draft.end = value; updateFields("endDate") }
        }
        row("endTime", "Heure de fin", timeFormat.format(Date(draft.end))) {
            pickRecordingTime("Heure de fin", draft.end) { value -> draft.end = value; updateFields("endTime") }
        }
        label("Options")
        row("before", "Marge avant", "${draft.beforeMinutes} min") {
            pickRecordingMargin("Marge avant", draft.beforeMinutes) { value -> draft.beforeMinutes = value; updateFields("before") }
        }
        row("after", "Marge après", "${draft.afterMinutes} min") {
            pickRecordingMargin("Marge après", draft.afterMinutes) { value -> draft.afterMinutes = value; updateFields("after") }
        }
        row("repeat", "Répéter", recordingRepeatLabel(draft.repeatDays)) {
            pickRecordingRepeat(draft) { updateFields("repeat") }
        }
        fun problem(message: String, key: String = "") {
            error.text = message; error.visibility = View.VISIBLE
            if (key.isNotBlank()) rows.findViewWithTag<View>(key)?.requestFocus()
        }
        val permission = button("Autoriser les alarmes") {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: android.content.ActivityNotFoundException) {
                problem("Ouvre les paramètres Android, puis autorise les alarmes et rappels pour JoJoHome.")
            }
        }.apply { visibility = View.GONE }
        page.addView(permission, page.indexOfChild(actions), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val cancel = button("Annuler") { close() }
        actions.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        val saveLabel = if (draft.recordingId == null) "Programmer" else "Enregistrer les modifications"
        save = button(saveLabel) {
            if (!saving) {
                val source = selectedProvider()
                val channel = draft.channel
                val timing = runCatching { IptvRecordingScheduler.plan(draft.start, draft.end, draft.beforeMinutes, draft.afterMinutes, draft.repeatDays) }
                when {
                    source == null -> problem("Choisis un fournisseur avec des chaînes TV.", "provider")
                    channel == null || channel.sourceId != source.id -> problem("Choisis une chaîne.", "channel")
                    groupHidden(source, ContentType.LIVE, channel.categoryId) -> problem("Choisis une chaîne dans un groupe visible.", "channel")
                    draft.start <= System.currentTimeMillis() -> problem("L’heure de début est passée. Choisis un horaire à venir.", "startTime")
                    draft.end <= draft.start -> problem("La fin doit être postérieure au début.", "endTime")
                    timing.isFailure -> problem(timing.exceptionOrNull()?.message ?: "Horaires ou récurrence invalides.", "repeat")
                    draft.remoteRevision == null && android.os.Build.VERSION.SDK_INT >= 31 &&
                        (draft.repeatDays.isNotEmpty() || timing.getOrThrow().capture.start > System.currentTimeMillis() + 1000) &&
                        !getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() -> {
                        problem("Autorise les alarmes et rappels, puis valide à nouveau la programmation.")
                        permission.visibility = View.VISIBLE; permission.requestFocus()
                    }
                    else -> playbackAccess(channel) {
                        if (!saving && recordingPage === page) {
                            saving = true; save.text = "Programmation en cours…"; name.isEnabled = false; error.visibility = View.GONE
                            scheduleRecording(channel, draft.title.trim().ifBlank { channel.title }, draft.start, draft.end,
                                draft.beforeMinutes, draft.afterMinutes, draft.recordingId, draft.repeatDays,
                                remoteRevision = draft.remoteRevision,
                                failure = { message ->
                                    saving = false; save.text = saveLabel; name.isEnabled = true
                                    if (recordingPage === page) { problem(message); save.requestFocus() } else toast(message)
                                }, scheduled = {
                                    if (recordingPage === page) {
                                        close(false); recordingsTab = "planned"; showSection(4); showNavigation(2)
                                    }
                                })
                        }
                    }
                }
            }
        }.apply { tag = "save"; gravity = Gravity.CENTER; minimumWidth = dp(180) }
        actions.addView(save, LinearLayout.LayoutParams(-2, -2))
        updateFields = { key ->
            if (recordingPage === page) {
                fun value(field: String, title: String, content: String) {
                    values[field]?.text = content
                    rows.findViewWithTag<View>(field)?.contentDescription = "$title · $content"
                }
                value("provider", "Fournisseur", selectedProvider()?.name ?: "Choisir un fournisseur")
                value("group", "Groupe", selectedGroupName())
                value("channel", "Chaîne", draft.channel?.title ?: "Choisir une chaîne")
                value("startDate", "Date de début", dateFormat.format(Date(draft.start)))
                value("startTime", "Heure de début", timeFormat.format(Date(draft.start)))
                value("endDate", "Date de fin", dateFormat.format(Date(draft.end)))
                value("endTime", "Heure de fin", timeFormat.format(Date(draft.end)))
                value("before", "Marge avant", "${draft.beforeMinutes} min")
                value("after", "Marge après", "${draft.afterMinutes} min")
                value("repeat", "Répéter", recordingRepeatLabel(draft.repeatDays))
                if (name.text.toString() != draft.title) name.setText(draft.title)
                error.visibility = View.GONE
                summaryTitle.text = draft.title.trim().ifBlank { draft.channel?.title ?: "Choisis une chaîne" }
                summary.text = runCatching { IptvRecordingScheduler.plan(draft.start, draft.end, draft.beforeMinutes, draft.afterMinutes, draft.repeatDays) }
                    .fold({ (program, window) -> "${if (draft.repeatDays.isEmpty()) "Programme" else "Première occurrence"}\n${format.format(Date(program.start))}\n→ ${format.format(Date(program.end))}\n\n" +
                        "Enregistrement avec marges\n${format.format(Date(window.start))}\n→ ${format.format(Date(window.end))}\nDurée : ${(window.end - window.start) / 60_000L} min" },
                        { it.message ?: "Horaires invalides" })
                recurrence.text = if (draft.repeatDays.isEmpty()) "Enregistrement unique" else "Répéter : ${recordingRepeatLabel(draft.repeatDays)}"
                if (key.isNotBlank()) rows.findViewWithTag<View>(key)?.requestFocus()
            }
        }
        fun pair(first: String, second: String) {
            val left = rows.findViewWithTag<View>(first)
            val right = rows.findViewWithTag<View>(second)
            val index = rows.indexOfChild(left)
            rows.removeView(left); rows.removeView(right)
            rows.addView(LinearLayout(this).apply {
                addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(right, LinearLayout.LayoutParams(0, -2, 1f))
            }, index, LinearLayout.LayoutParams(-1, -2))
        }
        listOf("provider" to "group", "startDate" to "startTime", "endDate" to "endTime", "before" to "after")
            .forEach { (left, right) -> pair(left, right) }
        val keys = listOf(listOf("provider", "group"), listOf("channel"), listOf("name"),
            listOf("startDate", "startTime"), listOf("endDate", "endTime"), listOf("before", "after"), listOf("repeat"))
        val fields = keys.map { row -> row.mapNotNull { rows.findViewWithTag<View>(it) } }
        var recapOrigin: View = save
        recapScroll.nextFocusUpId = back.id; recapScroll.nextFocusDownId = save.id
        recapScroll.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { recapOrigin.requestFocus(); true } else false
        }
        (fields.flatten() + listOf(back, permission, cancel, save)).forEach { view ->
            view.setOnKeyListener { _, code, event ->
                val grid = listOf(listOf(back)) + fields.map { row -> row.filter { it.isEnabled } }.filter { it.isNotEmpty() } +
                    (if (permission.visibility == View.VISIBLE) listOf(listOf(permission)) else emptyList()) + listOf(listOf(cancel, save))
                val index = grid.indexOfFirst { view in it }
                if (event.action != KeyEvent.ACTION_DOWN || code !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) ||
                    view === name && code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
                else {
                    val column = grid[index].indexOf(view)
                    val target = when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> if (index == 0) save else grid[index - 1].let { it[column.coerceAtMost(it.lastIndex)] }
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (index == grid.lastIndex - 1) save else grid[(index + 1) % grid.size].let { it[column.coerceAtMost(it.lastIndex)] }
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (column == 0) navRows[section] else grid[index][column - 1]
                        else -> if (view in fields.flatten() && column == grid[index].lastIndex) {
                            recapOrigin = view; recapScroll
                        } else grid[index][(column + 1).coerceAtMost(grid[index].lastIndex)]
                    }
                    target.requestFocus(); target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true); true
                }
            }
        }
        name.setOnEditorActionListener { _, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(name.windowToken, 0)
            rows.findViewWithTag<View>("startDate")?.requestFocus(); true
        }
        content.visibility = View.INVISIBLE
        parent.addView(page, FrameLayout.LayoutParams(-1, -1))
        showNavigation(1)
        updateFields(focusKey)
    }

    private fun pickRecordingDate(value: Long, chosen: (Long) -> Unit) {
        val date = Calendar.getInstance().apply { timeInMillis = value }
        DatePickerDialog(this, { _, year, month, day ->
            date.set(year, month, day)
            date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0)
            chosen(date.timeInMillis)
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickRecordingTime(title: String, value: Long, chosen: (Long) -> Unit): Unit =
        settingsPanel(title) { rows, panel ->
            val date = Calendar.getInstance().apply { timeInMillis = value }
            var hour = date.get(Calendar.HOUR_OF_DAY)
            var minute = date.get(Calendar.MINUTE)
            val fields = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(16)) }
            fun number(label: String, initial: Int, count: Int, changed: (Int) -> Unit) = LinearLayout(this).apply {
                id = View.generateViewId(); orientation = LinearLayout.VERTICAL; isFocusable = true
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                var selected = initial
                val labels = List(3) { index ->
                    text("", if (index == 1) 28 else 20, index == 1).apply {
                        gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
                    }
                }
                fun paint() {
                    contentDescription = "$label : ${String.format(Locale.ROOT, "%02d", selected)}"
                    labels.forEachIndexed { index, number ->
                        number.text = String.format(Locale.ROOT, "%02d", (selected + index - 1 + count) % count)
                        number.setTextColor(if (index != 1) surface(.55f) else if (isFocused) contrast else themeForeground)
                    }
                    labels[1].background = shape(if (isFocused) accent else surface(.16f), isFocused)
                }
                paint()
                setOnFocusChangeListener { _, _ -> paint() }
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN || code !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false
                    else {
                        selected = (selected + if (code == KeyEvent.KEYCODE_DPAD_UP) count - 1 else 1) % count
                        changed(selected); paint(); true
                    }
                }
            }
            val hours = number("Heures", hour, 24) { hour = it }
            val minutes = number("Minutes", minute, 60) { minute = it }
            val wheelHeight = dp((168 * textScale).toInt())
            fields.addView(hours, LinearLayout.LayoutParams(dp((88 * textScale).toInt()), wheelHeight))
            fields.addView(text(":", 28).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp((24 * textScale).toInt()), wheelHeight))
            fields.addView(minutes, LinearLayout.LayoutParams(dp((88 * textScale).toInt()), wheelHeight))
            rows.addView(fields)
            rows.addView(text("↑/↓ : régler · ←/→ : changer de colonne · OK : suivant", 12).apply {
                gravity = Gravity.CENTER; setTextColor(surface(.68f)); setPadding(dp(16), 0, dp(16), dp(16))
            })
            fun save() {
                if (!panel.isShowing) return
                date.set(Calendar.HOUR_OF_DAY, hour); date.set(Calendar.MINUTE, minute)
                date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0)
                panel.dismiss(); chosen(date.timeInMillis)
            }
            hours.nextFocusRightId = minutes.id; minutes.nextFocusLeftId = hours.id
            val confirm = settingsPanelRow("Valider") { save() }
            rows.addView(confirm, LinearLayout.LayoutParams(-1, dp(50)))
            val cancel = settingsPanelRow("Annuler") { panel.dismiss() }
            rows.addView(cancel, LinearLayout.LayoutParams(-1, dp(50)))
            hours.setOnClickListener { minutes.requestFocus() }
            minutes.setOnClickListener { confirm.requestFocus() }
            panel.setOnShowListener {
                panel.setOnKeyListener { _, code, event ->
                    val focused = panel.currentFocus
                    if (event.action != KeyEvent.ACTION_DOWN || code !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
                    else when (focused) {
                        hours, minutes -> when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> hours.requestFocus()
                            KeyEvent.KEYCODE_DPAD_RIGHT -> (if (focused === hours) minutes else confirm).requestFocus()
                            else -> false
                        }
                        confirm -> when (code) {
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> minutes.requestFocus()
                            KeyEvent.KEYCODE_DPAD_DOWN -> cancel.requestFocus()
                            else -> false
                        }
                        cancel -> when (code) {
                            KeyEvent.KEYCODE_DPAD_UP -> confirm.requestFocus()
                            KeyEvent.KEYCODE_DPAD_DOWN -> hours.requestFocus()
                            KeyEvent.KEYCODE_DPAD_LEFT -> minutes.requestFocus()
                            else -> false
                        }
                        else -> false
                    }
                }
            }
            hours
        }

    private fun pickRecordingMargin(title: String, value: Int, chosen: (Int) -> Unit): Unit =
        settingsPanel(title) { rows, panel ->
            val input = settingsInput().apply {
                id = View.generateViewId(); contentDescription = "Minutes"
                inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(2))
                setSingleLine(true); setText(value.toString()); selectAll(); textSize = 20 * textScale
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            rows.addView(input, LinearLayout.LayoutParams(-1, dp(60)).apply { marginStart = dp(16); marginEnd = dp(16) })
            val error = text("", 12).apply { visibility = View.GONE; setPadding(dp(16), 0, dp(16), dp(12)) }
            rows.addView(error)
            fun save() {
                if (!panel.isShowing) return
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 0..60) {
                    error.text = "Marge invalide : 0 à 60 minutes."; error.visibility = View.VISIBLE; input.requestFocus()
                } else {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                    panel.dismiss(); chosen(minutes)
                }
            }
            input.setOnEditorActionListener { _, _, _ -> save(); true }
            val confirm = settingsPanelRow("Valider") { save() }
            val cancel = settingsPanelRow("Annuler") { panel.dismiss() }
            rows.addView(confirm, LinearLayout.LayoutParams(-1, dp(50)))
            rows.addView(cancel, LinearLayout.LayoutParams(-1, dp(50)))
            panel.setOnShowListener {
                panel.setOnKeyListener { _, code, event ->
                    val fields = listOf(input, confirm, cancel)
                    val focused = panel.currentFocus ?: return@setOnKeyListener false
                    val index = fields.indexOf(focused)
                    if (event.action != KeyEvent.ACTION_DOWN || index < 0 ||
                        code !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false
                    else fields[(index + if (code == KeyEvent.KEYCODE_DPAD_UP) fields.size - 1 else 1) % fields.size].requestFocus()
                }
            }
            input
        }

    private val recordingDays = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private fun recordingRepeatLabel(days: List<Int>): String = when {
        days.isEmpty() -> "Off"
        days.distinct().size == 7 -> "Tous les jours"
        else -> days.distinct().sorted().mapNotNull { recordingDays.getOrNull(it - 1)?.take(3) }.joinToString(", ")
    }
    private fun pickRecordingRepeat(draft: RecordingDraft, chosen: () -> Unit): Unit =
        settingsPanel("Répéter", onDismiss = chosen) { rows, panel ->
            recordingDays.forEachIndexed { index, label ->
                val day = index + 1
                val toggle = Switch(this).apply {
                    isChecked = day in draft.repeatDays; isFocusable = false; isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                lateinit var row: LinearLayout
                row = settingsPanelRow(label) {
                    toggle.isChecked = !toggle.isChecked
                    draft.repeatDays = if (toggle.isChecked) (draft.repeatDays + day).distinct().sorted() else draft.repeatDays - day
                    row.contentDescription = "$label · ${if (toggle.isChecked) "Activé" else "Désactivé"}"
                    tintSettingsControls(row, row.hasFocus())
                }.apply {
                    contentDescription = "$label · ${if (toggle.isChecked) "Activé" else "Désactivé"}"
                    addView(toggle, LinearLayout.LayoutParams(-2, -2))
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, node: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, node)
                            node.className = Switch::class.java.name; node.isCheckable = true; node.isChecked = toggle.isChecked
                        }
                    }
                }
                rows.addView(row, LinearLayout.LayoutParams(-1, dp(50)))
            }
            rows.addView(settingsPanelRow("Enregistrement unique") { draft.repeatDays = emptyList(); panel.dismiss() },
                LinearLayout.LayoutParams(-1, dp(50)))
            rows.addView(settingsPanelRow("Valider") { panel.dismiss() }, LinearLayout.LayoutParams(-1, dp(50)))
            rows.getChildAt(0)
        }

    private fun showRecordingProviders(draft: RecordingDraft, chosen: () -> Unit): Unit =
        settingsPanel("Fournisseur") { rows, panel ->
            val available = orderedSources().filter { it.includes(ContentType.LIVE) }
            if (available.isEmpty()) rows.addView(text("Ajoute une liste de lecture contenant des chaînes TV dans les paramètres IPTV.", 14).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
            available.forEach { source ->
                rows.addView(settingsPanelRow(source.name) {
                    if (draft.sourceId != source.id) {
                        if (draft.title == draft.channel?.title) draft.title = ""
                        draft.sourceId = source.id; draft.group = null; draft.channel = null
                    }
                    panel.dismiss(); chosen()
                }, LinearLayout.LayoutParams(-1, dp(62)))
            }
            rows.addView(settingsPanelRow("Retour") { panel.dismiss() }, LinearLayout.LayoutParams(-1, dp(62)))
            rows.getChildAt(0).takeIf { it.isFocusable } ?: rows.getChildAt(rows.childCount - 1)
        }

    private fun showRecordingGroups(draft: RecordingDraft, chosen: () -> Unit) {
        val source = sources.firstOrNull { it.id == draft.sourceId && it.includes(ContentType.LIVE) } ?: return
        settingsPanel("Choisir un groupe") { rows, panel ->
            val scope = IptvRequestScope(IptvHttp.client)
            rows.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) { scope.cancel() }
            })
            fun select(group: IptvCategory?) {
                if (group != null && draft.channel?.categoryId != group.id) {
                    if (draft.title == draft.channel?.title) draft.title = ""
                    draft.channel = null
                }
                draft.group = group; panel.dismiss(); chosen()
            }
            val all = settingsPanelRow("Tous les groupes", if (draft.group == null) "Sélectionné" else "") { select(null) }
            rows.addView(all, LinearLayout.LayoutParams(-1, dp(62)))
            val loading = text("Chargement des groupes…", 14).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
            rows.addView(loading)
            val back = settingsPanelRow("Retour") { panel.dismiss() }
            rows.addView(back, LinearLayout.LayoutParams(-1, dp(62)))
            handler.post {
                if (panel.isShowing) async({ groupCategories[source.id to ContentType.LIVE]
                    ?: IptvHttp.withScope(scope) { provider(source).categories(ContentType.LIVE) }
                }, failure = { message ->
                    if (panel.isShowing) {
                        loading.text = message
                        rows.addView(settingsPanelRow("Réessayer") {
                            panel.dismiss(); showRecordingGroups(draft, chosen)
                        }, rows.indexOfChild(back), LinearLayout.LayoutParams(-1, dp(62)))
                        wireVerticalFocus(rows)
                    }
                }) { categories ->
                    if (!panel.isShowing) return@async
                    groupCategories[source.id to ContentType.LIVE] = categories
                    val visible = visibleProviderGroups(source, ContentType.LIVE, categories)
                    if (visible.isEmpty()) loading.text = "Aucun groupe visible pour ce fournisseur."
                    else rows.removeView(loading)
                    var selected: View = all
                    visible.forEach { group ->
                        val entry = settingsPanelRow(groupLabel(source, ContentType.LIVE, group).ifBlank { "Sans groupe" },
                            if (group.id == draft.group?.id) "Sélectionné" else "") { select(group) }
                        rows.addView(entry, rows.indexOfChild(back), LinearLayout.LayoutParams(-1, dp(62)))
                        if (group.id == draft.group?.id) selected = entry
                    }
                    wireVerticalFocus(rows)
                    if (all.hasFocus()) selected.requestFocus()
                }
            }
            all
        }
    }

    private fun showRecordingChannels(draft: RecordingDraft, query: String = "", page: Int = 0,
        chosen: () -> Unit) {
        val source = sources.firstOrNull { it.id == draft.sourceId && it.includes(ContentType.LIVE) }
            ?: return
        val group = draft.group
        settingsPanel("Choisir une chaîne") { rows, panel ->
            val scope = IptvRequestScope(if (query.isBlank()) IptvHttp.client else IptvHttp.searchClient)
            var task: Future<*>? = null
            rows.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) { scope.cancel(); task?.cancel(true) }
            })
            val search = settingsInput().apply {
                id = View.generateViewId(); setSingleLine(true); textSize = 16 * textScale
                hint = "Rechercher une chaîne"; setHintTextColor(surface(.68f)); setText(query)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            fun find() { val value = search.text.toString().trim(); panel.dismiss(); showRecordingChannels(draft, value, chosen = chosen) }
            search.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { find(); true } else false
            }
            rows.addView(search, LinearLayout.LayoutParams(-1, dp(60)).apply { marginStart = dp(16); marginEnd = dp(16) })
            rows.addView(settingsPanelRow("Rechercher") { find() }, LinearLayout.LayoutParams(-1, dp(49)))
            val loading = text("Chargement des chaînes…", 14).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
            rows.addView(loading)
            var shown: IptvPage? = null
            fun render(batch: IptvPage) {
                if (!panel.isShowing || isDestroyed || isFinishing || batch == shown) return
                val focused = rows.findFocus()?.tag
                rows.removeViews(3, rows.childCount - 3)
                val hidden = mutableMapOf<String, Boolean>()
                val channels = store.organizeItems(batch.items.filter { item ->
                    item.sourceId == source.id && item.type == ContentType.LIVE && (group == null || item.categoryId == group.id)
                        && !hidden.getOrPut(item.categoryId) { groupHidden(source, ContentType.LIVE, item.categoryId) }
                }, source.id, ContentType.LIVE, group?.id.orEmpty()).distinctBy { it.id }
                val selection = group?.let { groupLabel(source, ContentType.LIVE, it) } ?: source.name
                loading.text = if (channels.isEmpty()) "Aucune chaîne dans cette sélection." else "$selection · Page ${page + 1}"
                channels.forEach { channel ->
                    rows.addView(settingsPanelRow(channel.title) {
                        groupAccess(channel.sourceId, ContentType.LIVE, channel.categoryId) {
                            if (panel.isShowing) {
                                if (draft.title.isBlank() || draft.title == draft.channel?.title) draft.title = channel.title
                                draft.channel = channel; panel.dismiss(); chosen()
                            }
                        }
                    }.apply { tag = "channel:${channel.id}" }, LinearLayout.LayoutParams(-1, dp(62)))
                }
                if (page > 0) rows.addView(settingsPanelRow("Page précédente") {
                    panel.dismiss(); showRecordingChannels(draft, query, page - 1, chosen)
                }.apply { tag = "previous" }, LinearLayout.LayoutParams(-1, dp(62)))
                batch.nextPage?.takeIf { it > page }?.let { next ->
                    rows.addView(settingsPanelRow("Page suivante") { panel.dismiss(); showRecordingChannels(draft, query, next, chosen) }
                        .apply { tag = "next" }, LinearLayout.LayoutParams(-1, dp(62)))
                }
                rows.addView(settingsPanelRow("Retour") { panel.dismiss() }.apply { tag = "back" }, LinearLayout.LayoutParams(-1, dp(62)))
                wireVerticalFocus(rows)
                shown = batch
                if (focused != null) (rows.findViewWithTag<View>(focused) ?: rows.getChildAt(1)).requestFocus()
            }
            handler.post {
                if (panel.isShowing) task = searchWork.submit {
                    runCatching { IptvHttp.withScope(scope) {
                        loadRecordingChannelPage(source, group, query, page) { batch -> runOnUiThread { render(batch) } }
                    } }.onFailure { problem -> runOnUiThread {
                        if (!panel.isShowing || isDestroyed || isFinishing) return@runOnUiThread
                        if (shown == null) loading.text = if (problem is IllegalStateException)
                            problem.message ?: "Connexion indisponible." else "Connexion indisponible ou réponse du fournisseur incompatible."
                        rows.addView(settingsPanelRow("Réessayer") { panel.dismiss(); showRecordingChannels(draft, query, page, chosen) },
                            LinearLayout.LayoutParams(-1, dp(62)))
                        wireVerticalFocus(rows)
                    } }
                }
            }
            rows.getChildAt(1)
        }
    }
    private fun loadRecordingChannelPage(source: IptvSource, group: IptvCategory?, query: String, page: Int,
        publish: (IptvPage) -> Unit) {
        IptvHttp.checkActive()
        val category = group?.id.orEmpty()
        if (query.isBlank()) {
            val cached = runCatching { store.cached(source.id, ContentType.LIVE, category, page) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) publish(IptvPage(cached, page + 1))
            var selected: IptvPage? = null
            provider(source).loadCatalogue(ContentType.LIVE, category) { index, batch ->
                IptvHttp.checkActive()
                if (index == page) { selected = batch; publish(batch) }
                if (index == page + 1 && batch.items.isEmpty() && selected?.nextPage == index) {
                    selected = selected?.copy(nextPage = null)
                    selected?.let(publish)
                }
                runCatching { store.cache(source.id, ContentType.LIVE, category, index, batch.items,
                    registerContents = false, total = batch.total) }
            }
            if (selected == null) {
                val empty = IptvPage(emptyList())
                publish(empty)
                runCatching { store.cache(source.id, ContentType.LIVE, category, page, empty.items, registerContents = false) }
            }
        } else {
            val connection = provider(source)
            val batch = if (group == null) connection.search(query, ContentType.LIVE, page) else {
                val channels = mutableListOf<IptvItem>()
                var current: Int? = 0
                while (current != null) {
                    IptvHttp.checkActive()
                    val index = current
                    val batch = connection.items(ContentType.LIVE, group.id, index)
                    channels += batch.items.filter { it.categoryId == group.id }
                    current = batch.nextPage?.takeIf { it > index }
                }
                searchPage(channels, query, page)
            }
            publish(batch)
        }
    }
    private fun refreshRecordings(focusId: String = "") {
        if (recordingPage != null || contextPanel != null || sourcePage != null) return
        async({ IptvRecordingSync.visible(this, store).sortedByDescending { it.start } }) { values ->
            if (recordingPage != null || contextPanel != null || sourcePage != null) return@async
            val signature = "$recordingsTab:$accent:$contrast:$themeBackground:$themeForeground:$textScale:${IptvRecordingSync.failureMessage(this)}" + values.joinToString {
                "${it.id}:${it.state}:${it.bytes}:${it.title}:${it.start}:${it.end}:${it.beforeMinutes}:${it.afterMinutes}:${it.repeatDays}:${IptvRecordingScheduler.repeating(this, it)}:${it.error}:${IptvRecordingSync.status(this, store, it.id)}"
            }
            if (recordingsSignature == signature && results.childCount > 0) {
                if (focusId.isNotBlank()) results.findViewWithTag<View>(focusId)?.requestFocus()
                return@async
            }
            recordingsSignature = signature
            val focus = focusId.takeIf(String::isNotBlank) ?: if (content.hasFocus()) currentFocus?.tag as? String else null
            info.removeAllViews(); results.removeAllViews(); resetArtworkRequests()
            backdrop.setImageDrawable(null); status.visibility = View.GONE
            previewFrame.visibility = View.GONE
            informationHeader.layoutParams.height = dp((96 * textScale).toInt()); informationHeader.requestLayout()
            val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            fun command(icon: Int, label: String, key: String, action: () -> Unit) = ImageButton(this).apply {
                id = View.generateViewId(); tag = key; contentDescription = label; isFocusable = true
                setImageResource(icon); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(9), dp(9), dp(9), dp(9))
                fun paint(focused: Boolean) {
                    background = shape(if (focused) accent else Color.TRANSPARENT, focused)
                    setColorFilter(if (focused) contrast else themeForeground)
                }
                paint(false); setOnFocusChangeListener { _, focused ->
                    paint(focused); if (focused) showNavigation(2)
                }; setOnClickListener { action() }
                setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT && key == "recording-schedule") {
                        showNavigation(1); navRows[4].requestFocus(); true
                    } else false
                }
            }
            val add = command(android.R.drawable.ic_input_add, "Programmer un enregistrement", "recording-schedule") { showRecordingForm() }
            val settings = command(R.drawable.ic_settings, "Paramètres d’enregistrement", "recording-settings") {
                parentalAccess("lockSettings") { showRecordingSettings() }
            }
            heading.addView(add, LinearLayout.LayoutParams(dp(40), dp(40)))
            heading.addView(text("Enregistrements", 26).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
            heading.addView(settings, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
            info.addView(heading, LinearLayout.LayoutParams(-1, dp((44 * textScale).toInt())))
            val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val selected = values.filter { if (recordingsTab == "planned") it.state == RecordingState.PLANNED else it.state != RecordingState.PLANNED }
                .let { if (recordingsTab == "planned") it.sortedBy(IptvRecording::start) else it }
            listOf("files" to "Enregistrements", "planned" to "Programmations").forEach { (key, label) ->
                val count = values.count { if (key == "planned") it.state == RecordingState.PLANNED else it.state != RecordingState.PLANNED }
                val tab = text("$label ($count)", 14).apply {
                    id = View.generateViewId(); tag = "recording-tab:$key"; isFocusable = true; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                }
                fun paint(focused: Boolean) {
                    tab.background = shape(if (focused) accent else if (key == recordingsTab) surface(.16f) else Color.TRANSPARENT, focused)
                    tab.setTextColor(if (focused) contrast else if (key == recordingsTab) themeForeground else surface(.68f))
                }
                fun select() {
                    if (recordingsTab != key) { recordingsTab = key; recordingsSignature = ""; refreshRecordings() }
                }
                paint(false)
                tab.setOnFocusChangeListener { _, focused ->
                    paint(focused); if (focused) { showNavigation(2); select() }
                }
                tab.setOnClickListener { select(); firstContent()?.requestFocus() }
                tab.setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        (firstContent() ?: add).requestFocus(); true
                    } else if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP) {
                        (if (key == "planned") add else settings).requestFocus(); true
                    } else if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT && key == "files") {
                        showNavigation(1); navRows[4].requestFocus(); true
                    } else false
                }
                tabs.addView(tab, LinearLayout.LayoutParams(-2, dp((32 * textScale).toInt())).apply { marginEnd = dp(8) })
            }
            info.addView(tabs, LinearLayout.LayoutParams(-1, dp((40 * textScale).toInt())))
            val format = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            selected.forEach { recording ->
                val state = when (recording.state) {
                    RecordingState.PLANNED -> "Programmé"; RecordingState.RUNNING -> "En cours"; RecordingState.COMPLETE -> "Terminé"
                    RecordingState.PARTIAL -> "Partiel"; RecordingState.FAILED -> "Échec"; RecordingState.CANCELLED -> "Annulé"
                }
                val description = ("${recording.channel.title} · $state\n${format.format(Date(recording.start))} → ${format.format(Date(recording.end))} · " +
                    if (recording.state == RecordingState.PLANNED) "${(recording.end - recording.start) / 60_000L} min · Marges ${recording.beforeMinutes}/${recording.afterMinutes} min · ${recordingRepeatLabel(recording.repeatDays)}"
                    else if (IptvRecordingSync.remote(this, store, recording.id)) "État sur l’appareil créateur"
                    else "${recording.bytes / (1024 * 1024)} Mo" + if (recording.error.isBlank()) "" else "\n${recording.error}") +
                    IptvRecordingSync.status(this, store, recording.id).let { if (it.isBlank()) "" else "\n$it" }
                val row = settingsPanelRow(recording.title, description) {
                    if (recording.state == RecordingState.PLANNED) editRecording(recording) else recordingActions(recording)
                }.apply { tag = recording.id; minimumHeight = dp((70 * textScale).toInt()) }
                val paint = row.onFocusChangeListener
                row.setOnFocusChangeListener { view, focused ->
                    paint?.onFocusChange(view, focused); if (focused) showNavigation(2)
                }
                row.setOnKeyListener { view, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MENU) { recordingActions(recording); true }
                    else if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        showNavigation(1); navRows[4].requestFocus(); true
                    } else if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP && results.indexOfChild(view) == 0) {
                        tabs.findViewWithTag<View>("recording-tab:$recordingsTab")?.requestFocus() ?: false
                    } else false
                }
                row.setOnLongClickListener { recordingActions(recording); true }
                results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
            if (selected.isEmpty()) results.addView(text(if (recordingsTab == "planned")
                IptvRecordingSync.failureMessage(this).ifBlank { "Aucune programmation" } else "Aucun enregistrement", 16).apply {
                gravity = Gravity.CENTER; setTextColor(surface(.68f))
            }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels - content.paddingTop - content.paddingBottom
                - informationHeader.layoutParams.height).coerceAtLeast(dp(160))))
            add.nextFocusDownId = tabs.getChildAt(1).id; settings.nextFocusDownId = tabs.getChildAt(0).id
            if (focus != null) (results.findViewWithTag<View>(focus) ?: info.findViewWithTag<View>(focus)
                ?: tabs.findViewWithTag<View>("recording-tab:$recordingsTab"))?.requestFocus()
            else if (navigationMode != 0 && navRows[4].hasFocus()) focusGroup()
            results.post { restorePlaybackFocus() }
        }
    }
    private fun editRecording(recording: IptvRecording) {
        async({ IptvRecordingSync.visible(this, store).firstOrNull { it.id == recording.id } }) { current ->
            if (current?.state != RecordingState.PLANNED) { toast("Cette programmation n’est plus modifiable."); recordingsSignature = ""; refreshRecordings() }
            else showRecordingForm(RecordingDraft(current.channel.sourceId, current.channel, title = current.title,
                start = current.programStart, end = current.programEnd, beforeMinutes = current.beforeMinutes,
                afterMinutes = current.afterMinutes, repeatDays = current.repeatDays,
                recordingId = current.id, directory = current.directory,
                remoteRevision = IptvRecordingSync.plan(this, store, current.id)
                    ?.takeIf { it.owner != store.recordingDeviceId() }?.revision), "startTime")
        }
    }
    private fun recordingActions(recording: IptvRecording) {
        if (IptvRecordingSync.remote(this, store, recording.id)) {
            val remoteActions = mutableListOf<Pair<String, () -> Unit>>()
            if (recording.state == RecordingState.PLANNED) {
                remoteActions += "Modifier la programmation" to { editRecording(recording) }
                remoteActions += "Demander l’annulation" to {
                    async({ IptvRecordingSync.cancel(this, store, recording.id) }, failure = { toast(it) }) {
                        recordingsSignature = ""; refreshRecordings()
                        toast("Demande d’annulation enregistrée. En attente de l’appareil créateur.")
                    }
                }
            }
            remoteActions += "Retour" to {}
            dialog(recording.title, remoteActions)
            return
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val repeating = IptvRecordingScheduler.repeating(this, recording)
        if (recording.state == RecordingState.PLANNED) actions += "Modifier la programmation" to { editRecording(recording) }
        if (repeating && recording.state != RecordingState.PLANNED) actions += "Désactiver la récurrence" to {
            async({ IptvRecordingScheduler.cancelRecurrence(this, store, recording) }, failure = { toast(it) }) {
                recordingsSignature = ""; refreshRecordings(); toast("Récurrence désactivée. L’enregistrement en cours est conservé.")
            }
        }
        if (recording.state in setOf(RecordingState.COMPLETE, RecordingState.PARTIAL) &&
            (IptvRecordingScheduler.isNetwork(recording.file) || IptvRecordingScheduler.isDocument(recording.file) || File(recording.file).isFile)) {
            actions += "Regarder" to { playRecording(recording) }
        }
        if (recording.state in setOf(RecordingState.PLANNED, RecordingState.RUNNING)) actions +=
            (if (recording.state == RecordingState.RUNNING) "Arrêter et conserver" else if (repeating) "Annuler la récurrence" else "Annuler la programmation") to {
                async({ IptvRecordingScheduler.cancel(this, store, recording) }, failure = { toast(it) }) { recordingsSignature = ""; refreshRecordings() }
            }
        else actions += "Supprimer" to {
            dialog("Supprimer cet enregistrement ?", listOf("Supprimer" to {
                async({
                    IptvRecordingScheduler.storage(this, recording).use { it.delete() }
                    store.removeRecording(recording.id)
                }) { recordingsSignature = ""; refreshRecordings() }
            }, "Conserver" to {}))
        }
        actions += "Retour" to {}
        dialog(recording.title, actions)
    }

    private fun playRecording(recording: IptvRecording) {
        val version = requestVersion
        work.execute {
            val result = runCatching {
                if (IptvRecordingScheduler.isNetwork(recording.file) || IptvRecordingScheduler.isDocument(recording.file)) {
                    val storage = IptvRecordingScheduler.storage(this, recording)
                    try {
                        val name = checkNotNull(IptvRecordingScheduler.playable(storage)) { "Enregistrement indisponible." }
                        val playback = IptvRecordingPlayback(storage).also { it.start(8000, true) }
                        playback.url(name) to playback
                    } catch (failure: Exception) { storage.close(); throw failure }
                } else android.net.Uri.fromFile(File(recording.file)).toString() to null
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || version != requestVersion) { result.getOrNull()?.second?.stop(); return@runOnUiThread }
                result.fold({ (url, playback) ->
                    recordingPlayback?.stop(); recordingPlayback = playback
                    rememberPlaybackFocus(recording.id)
                    startActivityForResult(Intent(this, NuvioPlayerActivity::class.java)
                        .putExtra(NuvioPlayerActivity.EXTRA_STREAM_URL, url)
                        .putExtra(NuvioPlayerActivity.EXTRA_TITLE, recording.title)
                        .putExtra(NuvioPlayerActivity.EXTRA_CONTENT_ID, "iptv:recording:${recording.id}")
                        .putExtra(NuvioPlayerActivity.EXTRA_CONTENT_TYPE, "movie")
                        .putExtra(NuvioPlayerActivity.EXTRA_PLAYER_PACKAGE, "")
                        .putExtra(NuvioPlayerActivity.EXTRA_PLAYER_ACCENT, accent), 8097)
                }, { toast("Enregistrement indisponible. Vérifiez le stockage et la connexion réseau.") })
            }
        }
    }

    private fun showSearch(openKeyboard: Boolean = true) {
        setVodHeroVisible(false)
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels / metrics.density
        val screenHeight = metrics.heightPixels / metrics.density
        content.setPadding(dp(24), dp((screenHeight * .05f).toInt()), dp(24), dp(8))
        showNavigation(2)
        previewFrame.visibility = View.GONE
        informationHeader.layoutParams.height = dp(((if (searchQuery.isBlank()) 94 else 74) * textScale).toInt())
        informationHeader.requestLayout()
        status.visibility = View.GONE
        searchKeyboardOpen = false
        val voiceAvailable = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(packageManager) != null
        info.removeAllViews()
        val input = object : EditText(this) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && searchKeyboardOpen) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                        searchKeyboardOpen = false
                        showSoftInputOnFocus = false
                        rememberSearch()
                    }
                    return true
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            id = View.generateViewId()
            hint = "Rechercher"; setText(searchQuery); setSelection(text.length)
            textSize = 18f * textScale; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0); setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        searchField = input
        fun paintInput(focused: Boolean) {
            val color = if (focused) surface(.76f) else surface(.14f)
            val foreground = if (ColorUtils.calculateLuminance(color) > .45) Color.rgb(16, 25, 43) else Color.WHITE
            input.background = shape(color).apply { if (focused) setStroke(dp(1), ColorUtils.blendARGB(color, foreground, .25f)) }
            input.setTextColor(foreground); input.setHintTextColor(ColorUtils.blendARGB(color, foreground, .60f))
        }
        paintInput(false)
        input.setOnFocusChangeListener { _, focused -> paintInput(focused) }
        fun searchButton(icon: Int, description: String) = ImageButton(this).apply {
            id = View.generateViewId(); isFocusable = true
            contentDescription = description
            setImageResource(icon); setPadding(dp(9), dp(9), dp(9), dp(9))
            scaleType = ImageView.ScaleType.FIT_CENTER; clipToOutline = true
            fun paint(focused: Boolean) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(if (focused) accent else surface(.10f))
                    if (focused && focusNeedsOutline) setStroke(dp(1), focusOutlineColor())
                }
                setColorFilter(if (focused) contrast else themeForeground)
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
        }
        val voice = searchButton(R.drawable.ic_iptv_microphone, "Recherche vocale").apply {
            visibility = if (voiceAvailable) View.VISIBLE else View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            nextFocusLeftId = navRows[0].id
            nextFocusRightId = input.id
            setOnClickListener {
                searchKeyboardOpen = false
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                voiceSearch()
            }
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN)
                    focusSearchContents()
                else false
            }
        }
        val options = searchButton(R.drawable.ic_settings, "Réglages de la recherche").apply {
            tag = "search-settings"
            setOnClickListener {
                searchKeyboardOpen = false
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                parentalAccess("lockSettings") {
                    showSearchSettings(onBack = {
                        handler.post {
                            if (!isDestroyed && section == 0 && detail == null && accountPage == null) {
                                cancelSearch(); showSearch(openKeyboard = false)
                                if (searchQuery.isNotBlank()) runSearch(searchQuery)
                                searchField?.windowToken?.let { token ->
                                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                                        .hideSoftInputFromWindow(token, 0)
                                }
                                info.findViewWithTag<View>("search-settings")?.requestFocus()
                            }
                        }
                    })
                }
            }
            nextFocusLeftId = input.id
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN)
                    focusSearchContents()
                else false
            }
        }
        input.nextFocusLeftId = if (voiceAvailable) voice.id else navRows[0].id
        input.nextFocusRightId = options.id
        val searchBar = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val rowWidth = View.MeasureSpec.getSize(widthMeasureSpec)
                val sideWidth = dp(66)
                val fieldWidth = minOf(dp((screenWidth * .625f).toInt()), (rowWidth - 2 * sideWidth).coerceAtLeast(1))
                // Size before measuring children; centering must not change padding after layout.
                (input.layoutParams as FrameLayout.LayoutParams).width = fieldWidth
                (voice.layoutParams as FrameLayout.LayoutParams).leftMargin =
                    ((rowWidth - fieldWidth) / 2 - sideWidth).coerceAtLeast(0)
                (options.layoutParams as FrameLayout.LayoutParams).rightMargin =
                    ((rowWidth - fieldWidth) / 2 - dp(58)).coerceAtLeast(0)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            addView(input, FrameLayout.LayoutParams(0, -1, Gravity.CENTER))
            addView(voice, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.LEFT or Gravity.CENTER_VERTICAL))
            addView(options, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        }
        info.addView(searchBar, LinearLayout.LayoutParams(-1, dp((56 * textScale).toInt()).coerceAtLeast(dp(48))))
        input.setOnEditorActionListener { _, _, _ ->
            searchKeyboardOpen = false
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            rememberSearch(); runSearch(input.text.toString(), focusResults = true)
            true
        }
        input.setOnKeyListener { _, key, event ->
            if (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                (event.flags and KeyEvent.FLAG_EDITOR_ACTION) == 0) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    input.performClick()
                }
                true
            } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusSearchContents()
            } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                options.requestFocus()
            } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                (if (voiceAvailable) voice else navRows[0]).requestFocus()
            } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (section == 0 && detail == null && searchField === input) runSearch(s.toString(), debounce = true)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.showSoftInputOnFocus = false
        input.setOnClickListener { showSearchKeyboard() }
        if (searchQuery.isBlank()) showSearchHistory()
        input.requestFocus()
        if (openKeyboard && searchQuery.isBlank() && (!voiceAvailable || !store.booleanSetting("searchVoice", true))) showSearchKeyboard()
    }
    private fun showSearchKeyboard() {
        val input = searchField ?: return
        searchKeyboardOpen = true
        input.showSoftInputOnFocus = true
        input.requestFocus()
        input.post {
            if (!isDestroyed && section == 0 && detail == null && searchField === input && input.hasFocus()) {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private fun voiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Rechercher")
        try { startActivityForResult(intent, 8096) }
        catch (_: android.content.ActivityNotFoundException) {
            showSearchKeyboard()
            toast("Recherche vocale indisponible sur cet appareil.")
        }
    }
    private fun rememberSearch() {
        val query = searchField?.text?.toString()?.trim() ?: searchQuery
        if (query.isNotBlank()) work.execute { store.recordSearch(query) }
    }
    private fun cancelSearch() {
        searchGeneration++
        searchPrograms = emptyList(); searchProgramsView = null; searchProgramsPending = false
        searchDebounce?.let(handler::removeCallbacks); searchDebounce = null
        searchScope?.cancel(); searchScope = null
        searchTasks.forEach { it.cancel(true) }; searchTasks.clear()
    }
    private fun showSearchHistory() {
        results.removeAllViews(); searchGroups.clear(); searchTypeGroups.clear(); searchEmptyView = null
        status.text = ""; status.visibility = View.GONE
        if (!store.booleanSetting("searchHistory", true)) return
        val history = store.searches()
        val screenWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val historyWidth = dp((screenWidth * .625f).toInt())
        val historyMargin = dp((screenWidth * .16f).toInt())
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Historique des recherches", 14, true).apply {
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = ImageButton(this).apply {
            id = View.generateViewId(); isFocusable = true
            contentDescription = "Effacer l’historique des recherches"
            setImageResource(R.drawable.ic_menu_delete); setPadding(dp(9), dp(9), dp(9), dp(9))
            scaleType = ImageView.ScaleType.FIT_CENTER; clipToOutline = true
            visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
            fun paint(focused: Boolean) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(if (focused) accent else surface(.10f))
                    if (focused && focusNeedsOutline) setStroke(dp(1), focusOutlineColor())
                }
                setColorFilter(if (focused) contrast else themeForeground)
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnClickListener {
                searchKeyboardOpen = false
                searchField?.windowToken?.let { token ->
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(token, 0)
                }
                confirmClearHistory { changeSearchHistory { store.clearSearches() } }
            }
        }
        header.addView(clear, LinearLayout.LayoutParams(dp(36), dp(36)))
        results.addView(header, LinearLayout.LayoutParams(historyWidth, dp((32 * textScale).toInt()).coerceAtLeast(dp(40))).apply {
            marginStart = historyMargin; bottomMargin = dp(14)
        })
        val columns = LinearLayout(this)
        val chips = history.mapIndexed { index, query ->
            text(query, 16).apply {
                id = View.generateViewId(); isFocusable = true; gravity = Gravity.CENTER_VERTICAL
                tag = if (index == 0) "search-history-first" else "search-history:$query"
                contentDescription = "$query · Appui long pour supprimer"
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(12), 0)
                fun paint(focused: Boolean) {
                    background = shape(if (focused) accent else surface(.10f), focused).apply { cornerRadius = dp((20 * textScale).toInt()).toFloat() }
                    setTextColor(if (focused) contrast else themeForeground)
                }
                paint(false)
                setOnFocusChangeListener { _, focused -> paint(focused) }
                setOnClickListener {
                    searchField?.setText(query)
                    searchField?.setSelection(query.length)
                    rememberSearch(); runSearch(query, focusResults = true)
                }
                setOnLongClickListener {
                    dialog("Historique des recherches", listOf("Supprimer « $query »" to { changeSearchHistory { store.removeSearch(query) } }))
                    true
                }
            }
        }
        val lists = listOf(chips.filterIndexed { index, _ -> index % 2 == 0 }, chips.filterIndexed { index, _ -> index % 2 == 1 })
        lists.forEachIndexed { columnIndex, entries ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            entries.forEachIndexed { rowIndex, chip ->
                chip.nextFocusUpId = entries.getOrNull(rowIndex - 1)?.id ?: searchField?.id ?: View.NO_ID
                chip.nextFocusDownId = entries.getOrNull(rowIndex + 1)?.id ?: chip.id
                chip.nextFocusLeftId = if (columnIndex == 1) lists[0].getOrNull(rowIndex)?.id ?: chip.id else chip.id
                chip.nextFocusRightId = if (columnIndex == 0) lists[1].getOrNull(rowIndex.coerceAtMost(lists[1].lastIndex))?.id ?: clear.id else clear.id
                column.addView(chip, LinearLayout.LayoutParams(-2, dp((32 * textScale).toInt())).apply { bottomMargin = dp(16) })
            }
            columns.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { if (columnIndex == 0) marginEnd = dp(16) })
        }
        results.addView(columns, LinearLayout.LayoutParams(historyWidth, -2).apply { marginStart = historyMargin })
        clear.nextFocusUpId = info.findViewWithTag<View>("search-settings")?.id ?: searchField?.id ?: View.NO_ID
        clear.nextFocusDownId = lists[1].firstOrNull()?.id ?: chips.firstOrNull()?.id ?: searchField?.id ?: View.NO_ID
        clear.nextFocusLeftId = clear.nextFocusDownId
        (results.parent as? ScrollView)?.scrollTo(0, 0)
    }
    private fun changeSearchHistory(change: () -> Unit) {
        work.execute {
            change()
            runOnUiThread {
                if (!isDestroyed && section == 0 && detail == null && searchQuery.isBlank()) {
                    showSearchHistory(); searchField?.requestFocus()
                }
            }
        }
    }
    private fun runSearch(text: String, debounce: Boolean = false, focusResults: Boolean = false) {
        if (section != 0 || detail != null) return
        val query = text.trim()
        if (debounce && query == searchQuery) return
        if (!debounce && query == searchQuery && searchScope != null) {
            searchDebounce?.let { handler.removeCallbacks(it); searchDebounce = null; it.run() }
            focusSearchResults = focusResults
            if (focusResults && (visibleItems.isNotEmpty() || searchPrograms.isNotEmpty())) {
                focusSearchResults = false; firstContent()?.requestFocus()
            }
            return
        }
        val previous = searchItems.mapValues { (_, items) -> items.filter { it.title.contains(query, true) } }
        cancelSearch()
        resetArtworkRequests()
        searchQuery = query
        informationHeader.layoutParams.height = dp(((if (query.isBlank()) 94 else 74) * textScale).toInt())
        informationHeader.requestLayout()
        listMode = "search"
        categoryTitle = "Résultats pour « $query »"
        focusSearchResults = focusResults
        searchItems.clear(); searchPages.clear(); searchPending.clear(); searchFailures.clear()
        visibleItems = emptyList()
        if (query.isBlank()) { showSearchHistory(); return }
        status.visibility = View.GONE
        searchProgramsPending = true
        sources.filter { store.playlistEnabled(it.id) }.forEach { source -> searchTypes.filter(source::includes).forEach { type ->
            val key = source.id to type
            searchItems[key] = previous[key].orEmpty(); searchPending.add(key)
        } }
        renderSearchResults()
        (results.parent as? ScrollView)?.scrollTo(0, 0)
        val generation = searchGeneration
        val scope = IptvRequestScope(IptvHttp.searchClient).also { searchScope = it }
        val start = Runnable {
            searchDebounce = null
            searchTasks.add(searchWork.submit {
                val programs = runCatching { store.searchEpg(query) }.getOrDefault(emptyList())
                runOnUiThread {
                    if (!isDestroyed && section == 0 && generation == searchGeneration) {
                        searchPrograms = programs
                        searchProgramsPending = false
                        if (detail == null) {
                            renderSearchPrograms(); updateSearchStatus()
                            if (focusSearchResults && searchPrograms.isNotEmpty()) {
                                focusSearchResults = false; firstContent()?.requestFocus()
                            }
                        }
                    }
                }
            })
            sources.toList().forEach { source ->
                searchTasks.add(searchWork.submit {
                    // One portal session at a time per provider; other providers search in parallel.
                    val configured = store.connectionSource(source)
                    val connection = searchProviders.compute(source.id) { _, cached ->
                        cached?.takeIf { it.first == configured } ?: (configured to providerFor(configured))
                    }!!.second
                    synchronized(connection) {
                        val firstType = searchReturnSaved?.type ?: when (searchReturnSection) { 2 -> ContentType.MOVIE; 3 -> ContentType.SERIES; else -> ContentType.LIVE }
                        (listOf(firstType) + searchTypes).distinct().filter(source::includes).forEach { type ->
                            if (generation != searchGeneration) return@submit
                            searchBatch(source, connection, type, 0, query, generation, scope)
                        }
                    }
                })
            }
        }
        if (debounce) { searchDebounce = start; handler.postDelayed(start, 250) } else start.run()
    }
    private fun searchBatch(source: IptvSource, connection: IptvProvider, type: ContentType, page: Int,
        query: String, generation: Int, scope: IptvRequestScope) {
        val batch = runCatching { IptvHttp.withScope(scope) { connection.search(query, type, page) } }
        runOnUiThread {
            if (isDestroyed || section != 0 || generation != searchGeneration) return@runOnUiThread
            val key = source.id to type
            searchPending.remove(key)
            batch.onSuccess {
                searchFailures.remove(key)
                val previousSize = searchItems[key].orEmpty().size
                searchItems[key] = (if (page == 0) it.items else searchItems[key].orEmpty() + it.items).distinctBy { item -> item.id }
                searchPages[key] = it.nextPage?.takeIf { next -> next > page && it.items.isNotEmpty() && (page == 0 || searchItems[key].orEmpty().size > previousSize) }
            }.onFailure { searchFailures.add(key) }
            if (batch.isSuccess) searchPages[key]?.let { next -> loadSearchPage(key, next, render = false) }
            visibleItems = searchItems.keys.flatMap(::searchResultItems)
            if (detail == null) {
                renderSearchGroup(key); updateSearchStatus()
                if (focusSearchResults && visibleItems.isNotEmpty()) { focusSearchResults = false; firstContent()?.requestFocus() }
            }
        }
    }
    private fun renderSearchResults() {
        results.removeAllViews(); searchGroups.clear(); searchTypeGroups.clear()
        searchProgramsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results.addView(searchProgramsView)
        renderSearchPrograms()
        visibleItems = searchItems.keys.flatMap(::searchResultItems)
        listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE).forEach { type ->
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(22)) }
            searchTypeGroups[type] = group
            results.addView(group)
            renderSearchGroup("" to type)
        }
        searchEmptyView = text("", 16).apply { gravity = Gravity.CENTER }.also {
            val height = (resources.displayMetrics.heightPixels - content.paddingTop - content.paddingBottom
                - informationHeader.layoutParams.height).coerceAtLeast(dp(80))
            results.addView(it, LinearLayout.LayoutParams(-1, height))
        }
        updateSearchStatus()
    }

    private fun searchResultItems(key: Pair<String, ContentType>): List<IptvItem> {
        val items = store.organizeItems(searchItems[key].orEmpty(), key.first, key.second, "")
        return if (key.second == ContentType.LIVE && store.booleanSetting("searchFavoritesFirst", true))
            items.sortedByDescending { store.favorite(it) } else items
    }
    private fun renderSearchPrograms() {
        val group = searchProgramsView ?: return
        val focused = group.findFocus()?.tag as? String
        group.removeAllViews()
        if (searchPrograms.isNotEmpty()) {
            group.addView(text("Programmes · ${searchPrograms.size}", 16, true))
            val programs = if (store.booleanSetting("searchFavoritesFirst", true)) searchPrograms.sortedByDescending { store.favorite(it.first) } else searchPrograms
            val date = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            programs.forEach { (channel, program) ->
                group.addView(button("${program.title}\n${store.channelTitle(channel)} · ${date.format(Date(program.start))}") {
                    rememberSearch()
                    groupAccess(channel.sourceId, channel.type, channel.categoryId) { programmeActions(channel, program) }
                }.apply { tag = "program:${channel.id}:${program.start}"; leftToGroups(this) })
            }
        }
        focused?.let { group.findViewWithTag<View>(it)?.requestFocus() }
    }
    private fun renderSearchGroup(key: Pair<String, ContentType>) {
        val type = key.second
        val group = searchTypeGroups[type] ?: return
        val focused = group.findFocus()?.tag as? String
        val hadFocus = group.hasFocus()
        val available = orderedSources().filter { source ->
            source.includes(type) && (searchResultItems(source.id to type).isNotEmpty() || (source.id to type) in searchFailures)
        }
        searchGroups.keys.removeAll { it.second == type }
        group.removeAllViews()
        group.visibility = if (available.isEmpty()) View.GONE else View.VISIBLE
        if (available.isEmpty()) {
            if (hadFocus) searchField?.requestFocus()
            return
        }
        if (available.none { it.id == searchSelectedSources[type] }) searchSelectedSources[type] = available.first().id
        val title = when (type) { ContentType.LIVE -> "Chaînes"; ContentType.MOVIE -> "Films"; else -> "Séries" }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "search-tabs:${type.name}" }
        val paints = mutableListOf<() -> Unit>()
        val tabViews = available.map { source ->
            val tab = text(source.name, 16).apply {
                id = View.generateViewId(); isFocusable = true
                tag = "search-source:${type.name}:${source.id}"
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            }
            val paint: () -> Unit = {
                val selected = searchSelectedSources[type] == source.id
                tab.background = shape(if (tab.hasFocus()) accent else surface(.10f), tab.hasFocus()).apply {
                    cornerRadius = dp((16 * textScale).toInt()).toFloat()
                    if (selected && !tab.hasFocus()) setStroke(dp(1), surface(.58f))
                }
                tab.setTextColor(if (tab.hasFocus()) contrast else if (selected) themeForeground else surface(.62f))
            }
            paints.add(paint)
            tab.setOnFocusChangeListener { _, focused ->
                if (focused && searchSelectedSources[type] != source.id) {
                    searchSelectedSources[type] = source.id
                    renderSearchSource(type, body)
                }
                paints.forEach { it() }
            }
            tab.setOnClickListener {
                if (searchSelectedSources[type] != source.id) {
                    searchSelectedSources[type] = source.id; renderSearchSource(type, body); paints.forEach { it() }
                }
                firstSearchResult(body)?.requestFocus()
            }
            tab.setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                    moveSearchFocus(tabs, code) else false
            }
            tabs.addView(tab, LinearLayout.LayoutParams(-2, dp((32 * textScale).toInt())).apply { marginEnd = dp(12) })
            paint()
            tab
        }
        tabViews.forEachIndexed { index, tab ->
            tab.nextFocusLeftId = tabViews.getOrNull(index - 1)?.id ?: searchField?.id ?: View.NO_ID
            tab.nextFocusRightId = tabViews.getOrNull(index + 1)?.id ?: tab.id
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(title, 14, true).apply { setSingleLine(true); gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(dp((76 * textScale).toInt()), -1))
        header.addView(HorizontalScrollView(this).apply {
            isFocusable = false; isHorizontalScrollBarEnabled = false; addView(tabs)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        group.addView(header, LinearLayout.LayoutParams(-1, dp((52 * textScale).toInt())))
        group.addView(body)
        renderSearchSource(type, body)
        if (hadFocus) (focused?.let { group.findViewWithTag<View>(it) }
            ?: firstSearchResult(body) ?: tabViews.firstOrNull())?.requestFocus()
    }

    private fun renderSearchSource(type: ContentType, group: LinearLayout) {
        val source = searchSelectedSources[type] ?: return
        val key = source to type
        val focused = group.findFocus()?.tag as? String
        val hadFocus = group.hasFocus()
        searchGroups.keys.removeAll { it.second == type }
        searchGroups[key] = group
        group.removeAllViews()
        val items = searchResultItems(key)
        if (items.isNotEmpty()) addCards(items, group)
        if (key in searchPending) group.addView(text("Recherche…", 14))
        else if (key in searchFailures) group.addView(button("Réessayer") { loadSearchPage(key, searchPages[key] ?: 0) }.apply {
            tag = "search-retry:${key.first}:${key.second.name}"; leftToGroups(this)
        })
        else if (items.isEmpty()) group.addView(text("Aucun résultat", 14))
        if (hadFocus) (focused?.let { group.findViewWithTag<View>(it) }
            ?: firstSearchResult(group)
            ?: firstContent() ?: searchField)?.requestFocus()
        group.post { restorePlaybackFocus() }
    }

    private fun firstSearchResult(group: ViewGroup): View? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.visibility != View.VISIBLE || !child.isEnabled) continue
            if (child is FrameLayout && child in cardRows && child.childCount == 0) cardRows[child]?.invoke()
            if (child.isFocusable) return child
            if (child is ViewGroup) firstSearchResult(child)?.let { return it }
        }
        return null
    }

    private fun focusSearchContents(): Boolean {
        val program = searchProgramsView?.let(::firstSearchResult)
        val tab = searchTypeGroups.keys.firstNotNullOfOrNull { type ->
            searchTypeGroups[type]?.findViewWithTag<View>("search-source:${type.name}:${searchSelectedSources[type]}")
        }
        return (results.findViewWithTag<View>("search-history-first") ?: program ?: tab ?: firstContent() ?: searchField)?.requestFocus() ?: false
    }
    private fun loadSearchPage(key: Pair<String, ContentType>, page: Int, render: Boolean = true) {
        if (key in searchPending) return
        val source = sources.firstOrNull { it.id == key.first } ?: return
        val connection = searchProviders[key.first]?.second ?: return
        val scope = searchScope ?: return
        val generation = searchGeneration
        val query = searchQuery
        searchPending.add(key)
        if (render && detail == null) renderSearchGroup(key)
        if (detail == null) updateSearchStatus()
        searchTasks.add(searchWork.submit { synchronized(connection) { searchBatch(source, connection, key.second, page, query, generation, scope) } })
    }
    private fun updateSearchStatus() {
        val count = visibleItems.size + searchPrograms.size
        val pending = searchPending.isNotEmpty() || searchProgramsPending
        status.visibility = View.GONE
        searchEmptyView?.apply {
            visibility = if (count == 0 && searchFailures.isEmpty()) View.VISIBLE else View.GONE
            text = when {
                sources.isEmpty() -> "Ajoute un fournisseur pour rechercher."
                pending -> "Recherche…"
                else -> "Aucun Résultat"
            }
        }
    }

    private fun showSources() {
        info.removeAllViews()
        info.addView(text("Fournisseurs IPTV", 28, true))
        results.removeAllViews()
        status.text = ""
        orderedSources().forEach { source ->
            results.addView(button("${source.name} · ${source.type.name}") { sourceActions(source) })
        }
        results.addView(button("Ajouter un fournisseur") { chooseSourceType() })
        val nas = getSharedPreferences("home_settings", MODE_PRIVATE)
        results.addView(text(if (nas.getString("nas_token", "").isNullOrBlank())
            "Connecte le même compte NAS sur tes appareils pour synchroniser les fournisseurs et favoris IPTV."
            else nas.getString("nas_iptv_sync_error", null) ?: "Synchronisation automatique des fournisseurs et favoris via ton compte NAS.", 14))
        results.addView(text("Companion : IP ${iptvLanAddress() ?: "indisponible"} · port 8093\nGarde ce module ouvert pour recevoir un fournisseur.", 14))
    }
    private fun chooseSourceType(onCancel: (() -> Unit)? = null, selected: SourceType = SourceType.M3U) {
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = IptvContentPage()
        SourceType.entries.forEach { type ->
            val label = when (type) {
                SourceType.M3U -> "Liste de lecture M3U"; SourceType.XTREAM -> "Xtream Codes"; SourceType.STALKER -> "Portail Stalker"
            }
            choices.addView(settingsPanelRow(label) {
                page.dismiss()
                sourceForm(type, null, onBack = { chooseSourceType(onCancel, type) },
                    onSaved = onCancel?.let { { showPlaylistsSettings("add") } })
            }.apply { tag = type }, LinearLayout.LayoutParams(-1, dp((52 * textScale).toInt())).apply { bottomMargin = dp(8) })
        }
        actions.addView(settingsPanelRow("Annuler") { page.cancel() }, LinearLayout.LayoutParams(-1, dp((52 * textScale).toInt())))
        showPlaylistWizard(page, "Type de liste\nde lecture", R.drawable.ic_file_playlist, choices, actions, onCancel,
            choices.findViewWithTag(selected), typeSelection = true)
    }
    private fun sourceTypeLabel(type: SourceType): String = when (type) {
        SourceType.M3U -> "M3U"; SourceType.XTREAM -> "Xtream"; SourceType.STALKER -> "Portail Stalker"
    }

    private fun showPlaylistWizard(page: IptvContentPage, title: String, icon: Int, fields: View, actions: View,
        onBack: (() -> Unit)?, initialFocus: View?, description: String = "", secure: Boolean = icon == R.drawable.ic_menu_open,
        typeSelection: Boolean = false) {
        val layout = LinearLayout(this).apply {
            orientation = if (typeSelection) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(themeBackground)
            if (!typeSelection) setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (typeSelection) setPadding(dp(24), dp(20), dp(24), dp(20)) else setPadding(0, 0, 0, dp(16))
            setBackgroundColor(surface(if (typeSelection) .16f else .07f))
            addView(ImageView(this@IptvActivity).apply {
                setImageDrawable(object : android.graphics.drawable.Drawable() {
                    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = themeForeground }
                    override fun draw(canvas: android.graphics.Canvas) {
                        val saved = canvas.save()
                        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                        canvas.scale(bounds.width() / 120f, bounds.height() / 120f)
                        if (icon == R.drawable.ic_file_playlist) {
                            canvas.drawRect(0f, 30f, 72f, 42f, paint)
                            canvas.drawRect(0f, 54f, 72f, 66f, paint)
                            canvas.drawRect(0f, 78f, 48f, 90f, paint)
                            canvas.drawRect(84f, 54f, 96f, 114f, paint)
                            canvas.drawRect(60f, 78f, 120f, 90f, paint)
                        } else if (icon == android.R.drawable.ic_menu_agenda) {
                            canvas.drawRoundRect(0f, 25f, 120f, 115f, 12f, 12f, paint)
                            canvas.drawPath(android.graphics.Path().apply {
                                moveTo(0f, 40f); lineTo(0f, 30f); quadTo(0f, 20f, 10f, 20f)
                                lineTo(40f, 20f); lineTo(60f, 35f); lineTo(110f, 35f)
                                lineTo(110f, 50f); close()
                            }, paint)
                        } else if (icon == android.R.drawable.ic_menu_share) {
                            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 12f
                            canvas.drawArc(5f, 25f, 75f, 95f, 90f, 180f, false, paint)
                            canvas.drawArc(45f, 25f, 115f, 95f, -90f, 180f, false, paint)
                            canvas.drawLine(40f, 25f, 55f, 25f, paint)
                            canvas.drawLine(40f, 95f, 55f, 95f, paint)
                            canvas.drawLine(65f, 25f, 80f, 25f, paint)
                            canvas.drawLine(65f, 95f, 80f, 95f, paint)
                            canvas.drawLine(35f, 60f, 85f, 60f, paint)
                        } else {
                            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 11f
                            canvas.drawRoundRect(35f, 5f, 108f, 115f, 8f, 8f, paint)
                            paint.style = android.graphics.Paint.Style.FILL
                            canvas.drawPath(android.graphics.Path().apply {
                                moveTo(0f, 48f); lineTo(42f, 48f); lineTo(42f, 30f)
                                lineTo(75f, 60f); lineTo(42f, 90f); lineTo(42f, 72f)
                                lineTo(0f, 72f); close()
                            }, paint)
                        }
                        canvas.restoreToCount(saved)
                    }
                    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
                    @Deprecated("Drawable opacity") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(if (typeSelection) 64 else 48), dp(if (typeSelection) 64 else 48)))
            addView(LinearLayout(this@IptvActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(text(if (typeSelection) title else title.replace("\n", " "), if (typeSelection) 30 else 24).apply {
                    setTextColor(themeForeground); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    maxLines = if (typeSelection) 3 else 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (description.isNotBlank()) addView(text(description, 14).apply {
                    setTextColor(surface(.58f)); setPadding(0, dp(8), dp(20), 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        layout.addView(heading, if (typeSelection) LinearLayout.LayoutParams(0, -1, .45f) else LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this)
        layout.addView(body, if (typeSelection) LinearLayout.LayoutParams(0, -1, .55f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val fieldsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (typeSelection) gravity = Gravity.CENTER_VERTICAL
            setPadding(if (typeSelection) dp(16) else 0, dp(8), dp(16), dp(16))
            addView(fields, LinearLayout.LayoutParams(-1, -2))
        }
        if (typeSelection) fieldsColumn.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val first = (fields as? ViewGroup)?.getChildAt(0)
            if (first != null) {
                // Align the first choice with the title and Cancel while keeping the whole list in view.
                val offset = (fields.height - first.height) / 2
                val room = (bottom - top - fieldsColumn.paddingTop - fieldsColumn.paddingBottom - fields.height) / 2
                fields.translationY = minOf(offset, room).coerceAtLeast(0).toFloat()
            }
        }
        body.addView(ScrollView(this).apply {
            isFocusable = false; isFillViewport = typeSelection
            if (typeSelection) isVerticalScrollBarEnabled = false
            addView(fieldsColumn)
        },
            LinearLayout.LayoutParams(0, -1, .68f))
        body.addView(View(this).apply { setBackgroundColor(surface(.16f)) }, LinearLayout.LayoutParams(dp(1), -1))
        val commands = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (typeSelection) gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), 0, dp(16)); addView(actions, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(ScrollView(this).apply {
            isFocusable = false; isFillViewport = typeSelection
            if (typeSelection) { isVerticalScrollBarEnabled = false; setPadding(0, 0, dp(16), 0) }
            addView(commands)
        },
            LinearLayout.LayoutParams(0, -1, .32f))
        page.onBack = onBack
        page.show(layout, initialFocus, secure)
        if (fields is ViewGroup) wireVerticalFocus(fields)
        if (actions is ViewGroup) wireVerticalFocus(actions)
        var lastField = fields.findFocus() ?: fields.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isEnabled }
        if (fields is ViewGroup) for (index in 0 until fields.childCount) {
            val row = fields.getChildAt(index)
            if (!row.isFocusable) continue
            val paint = row.onFocusChangeListener
            row.setOnFocusChangeListener { view, focused ->
                paint?.onFocusChange(view, focused)
                if (focused) lastField = row
            }
            row.nextFocusLeftId = navRows[section].id
            if (row !is EditText) row.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { navRows[section].requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        (actions as? ViewGroup)?.let { parent ->
                            (0 until parent.childCount).map(parent::getChildAt).firstOrNull { it.isFocusable && it.isEnabled }?.requestFocus()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        if (actions is ViewGroup) {
            for (index in 0 until actions.childCount) actions.getChildAt(index).setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    (lastField?.takeIf { it.isShown && it.isEnabled } ?: navRows[section]).requestFocus(); true
                }
                else false
            }
        }
        initialFocus?.requestFocus()
    }
    private data class ContextEntry(val title: String, val subtitle: String = "", val checked: Boolean? = null, val action: () -> Unit)

    private fun sidePanel(title: String, entries: List<ContextEntry>, focusTitle: String = "", onBack: (() -> Unit)? = null) {
        contextPanel?.dismiss()
        val origin = currentFocus
        var returningToParent = false
        val dialog = object : Dialog(this) {
            override fun cancel() {
                if (onBack == null) super.cancel()
                else { returningToParent = true; onBack() }
            }
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(surface(.07f)) }
        panel.addView(text(title, 20).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(24), dp(16), dp(24), dp(16)); setBackgroundColor(surface(.16f))
        }, LinearLayout.LayoutParams(-1, dp(72)))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(16)) }
        panel.addView(ScrollView(this).apply { isFocusable = false; addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; isFocusable = true; id = View.generateViewId()
                setPadding(dp(16), dp(8), dp(16), dp(8)); contentDescription = listOf(entry.title, entry.subtitle).filter(String::isNotBlank).joinToString(" · ")
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(entry.title, 14))
            if (entry.subtitle.isNotBlank()) labels.addView(text(entry.subtitle, 12).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = entry.checked?.let { checked -> Switch(this).apply {
                isChecked = checked; isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                row.addView(this, LinearLayout.LayoutParams(-2, -2))
            } }
            fun paint(focused: Boolean) {
                row.background = shape(if (focused) accent else Color.TRANSPARENT, focused)
                val foreground = if (focused) contrast else themeForeground
                for (index in 0 until labels.childCount) (labels.getChildAt(index) as TextView).setTextColor(foreground)
                toggle?.let { switch ->
                    switch.thumbTintList = android.content.res.ColorStateList.valueOf(foreground)
                    switch.trackTintList = android.content.res.ColorStateList.valueOf(
                        if (focused) ColorUtils.blendARGB(accent, contrast, .45f) else if (switch.isChecked) accent else surface(.32f))
                }
            }
            paint(false)
            row.setOnFocusChangeListener { _, focused -> paint(focused) }
            row.setOnClickListener { dialog.dismiss(); entry.action() }
            row.setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) { dialog.cancel(); true } else false
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        }
        wireVerticalFocus(rows)
        dialog.setContentView(panel)
        dialog.setOnDismissListener {
            if (contextPanel === dialog) contextPanel = null
            if (!returningToParent && contextPanel == null && origin?.isAttachedToWindow == true) origin.requestFocus()
            if (!returningToParent) handler.post { refreshSyncedLibrary() }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.55f); setGravity(Gravity.RIGHT)
            setLayout((resources.displayMetrics.widthPixels * .375f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        contextPanel = dialog
        (entries.indexOfFirst { it.title == focusTitle }.takeIf { it >= 0 }?.let(rows::getChildAt) ?: rows.getChildAt(0))?.requestFocus()
    }

    private fun groupLabel(source: IptvSource, type: ContentType, group: IptvCategory): String =
        store.setting(IptvOrganization.key(source.id, type, group.id, "groupName"), "").ifBlank { group.name }

    private fun groupHidden(source: IptvSource, type: ContentType, id: String): Boolean = IptvOrganization.groupHidden(id,
        store.setting(IptvOrganization.key(source.id, type, id, "hidden"), ""),
        store.setting(IptvOrganization.key(source.id, type, null, "newGroupsVisible:${type.name}"), "true"),
        store.setting(IptvOrganization.key(source.id, type, null, "knownGroups:${type.name}"), "[]"))

    private fun groupAccess(source: String, type: ContentType, group: String, action: () -> Unit) {
        val blocked = store.setting(IptvOrganization.key(source, type, group, "blocked"), "false") == "true"
        if (blocked && android.os.SystemClock.elapsedRealtime() >= parentalUnlockedUntil) parentalAccess("lockPlayback", true, action)
        else action()
    }

    private fun playbackAccess(item: IptvItem, action: () -> Unit) {
        parentalAccess("lockPlayback", store.groupBlocked(item) && android.os.SystemClock.elapsedRealtime() >= parentalUnlockedUntil, action)
    }

    private fun refreshOrganization() {
        if (previewItem?.let(store::groupBlocked) == true && android.os.SystemClock.elapsedRealtime() >= parentalUnlockedUntil) stopPreview(true)
        if (section in 1..3) {
            buildGroups()
            groupCategories[sourceId to contentType()]?.firstOrNull { it.id == category }?.let {
                selectedSource()?.let { source -> categoryTitle = groupLabel(source, contentType(), it) }
            }
        }
        if (section in listOf(1, 2, 3, 5) && detail == null) displayItems(rememberedFocus)
    }

    private fun saveOrganization(source: IptvSource, type: ContentType, group: String?, name: String, value: String, done: () -> Unit) {
        async({ store.setSetting(IptvOrganization.key(source.id, type, group, name), value)
            if (name == "epgOffsetMinutes") store.clearEpgCache(source.id)
        }) { refreshOrganization(); done() }
    }

    private fun categoriesFor(source: IptvSource, type: ContentType, done: (List<IptvCategory>) -> Unit) {
        groupCategories[source.id to type]?.let { done(it); return }
        async({ provider(source).categories(type) }, failure = { toast(it) }) { values ->
            groupCategories[source.id to type] = values; done(values)
        }
    }

    private fun sortLabel(source: IptvSource, type: ContentType, group: String?): String = when (
        store.organization(source.id, type, group, "channelSort", "playlist")) {
        "name" -> "Par nom (A–Z)"; "nameDescending" -> "Par nom (Z–A)"; "manual" -> "Ordre personnalisé"
        "added" -> "Par date d'ajout"; "rating" -> "Par note"; "watchTime" -> "Par temps de visionnage"; else -> "Par ordre dans la playlist"
    }

    private fun chooseSort(source: IptvSource, type: ContentType, group: String?, done: () -> Unit) {
        sidePanel("Tri des Chaînes", listOf("playlist" to "Par ordre dans la playlist", "name" to "Par nom (A–Z)",
            "nameDescending" to "Par nom (Z–A)", "added" to "Par date d'ajout", "watchTime" to "Par temps de visionnage", "manual" to "Ordre personnalisé").map { (value, label) ->
            ContextEntry(label) { saveOrganization(source, type, group, "channelSort", value, done) }
        })
    }

    private fun externalLabel(source: IptvSource, type: ContentType, group: String?): String = when (
        store.organization(source.id, type, group, "externalPlayerMode", "never")) {
        "all" -> "Toujours"; "vod" -> "Pour la VOD uniquement"; else -> "Jamais"
    }

    private fun chooseExternal(source: IptvSource, type: ContentType, group: String?, done: () -> Unit) {
        fun selectPlayer(mode: String) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("https://example.invalid/video.mp4"), "video/*")
            val players = packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
            if (players.isEmpty()) { toast("Aucun lecteur externe installé sur cette TV."); done(); return }
            sidePanel("Lecteur externe", players.map { player -> ContextEntry(player.loadLabel(packageManager).toString()) {
                async({ store.setSetting("externalPlayerPackage", player.activityInfo.packageName)
                    store.setSetting(IptvOrganization.key(source.id, type, group, "externalPlayerMode"), mode) }) { done() }
            } })
        }
        sidePanel("Utiliser un lecteur externe", listOf("never" to "Jamais", "vod" to "Pour la VOD uniquement", "all" to "Toujours").map { (value, label) ->
            ContextEntry(label) {
                if (value != "never" && store.setting("externalPlayerPackage", "").isBlank()) selectPlayer(value)
                else saveOrganization(source, type, group, "externalPlayerMode", value, done)
            }
        } + ContextEntry("Choisir le lecteur externe") { selectPlayer(store.organization(source.id, type, group, "externalPlayerMode", "vod").takeUnless { it == "never" } ?: "vod") })
    }

    private fun providerMenu(source: IptvSource, type: ContentType, focus: String = "") {
        val showHidden = store.organization(source.id, type, null, "showHiddenChannels", "false") == "true"
        val selected = if (source.id == sourceId) category else store.organization(source.id, type, null, "category:${type.name}", "")
        val label = if (selected.isBlank() && store.organization(source.id, type, null, "favoritesOnly", "false") == "true") "Favoris"
            else groupCategories[source.id to type]?.firstOrNull { it.id == selected }?.let { groupLabel(source, type, it) }
                ?: when (type) { ContentType.MOVIE -> "Tous les films"; ContentType.SERIES -> "Toutes les séries"; else -> "Toutes les Chaînes" }
        sidePanel(source.name, listOf(
            ContextEntry("Catégorie de Chaînes", label) { chooseCategory(source, type) },
            ContextEntry("Tri des Chaînes", sortLabel(source, type, null)) { chooseSort(source, type, null) { providerMenu(source, type, "Tri des Chaînes") } },
            ContextEntry("Réorganiser les chaînes") { reorderChannels(source, type, null) { providerMenu(source, type, "Réorganiser les chaînes") } },
            ContextEntry("Afficher les chaînes de groupes masqués", checked = showHidden) {
                saveOrganization(source, type, null, "showHiddenChannels", (!showHidden).toString()) { providerMenu(source, type, "Afficher les chaînes de groupes masqués") }
            },
            ContextEntry("Utiliser un lecteur externe", externalLabel(source, type, null)) { chooseExternal(source, type, null) { providerMenu(source, type, "Utiliser un lecteur externe") } },
            ContextEntry("Gérer les Groupes") { manageGroups(source, type) }
        ) + if (type == ContentType.LIVE) listOf(
            ContextEntry("Gérer la visibilité des chaînes") { manageChannelOrganization(source, blocking = false) },
            ContextEntry("Gérer le blocage des chaînes") { manageChannelOrganization(source, blocking = true) }
        ) else emptyList(), focus)
    }

    private fun chooseCategory(source: IptvSource, type: ContentType) {
        categoriesFor(source, type) { values ->
            val all = when (type) { ContentType.MOVIE -> "Tous les films"; ContentType.SERIES -> "Toutes les séries"; else -> "Toutes les chaînes" }
            fun select(value: IptvCategory, favorites: Boolean) {
                selectProviderCategory(source, type, value, values, favorites)
            }
            sidePanel("Catégorie de Chaînes", listOf(
                ContextEntry(all) { select(IptvCategory("", all), false) },
                ContextEntry("Favoris") { select(IptvCategory("", all), true) }
            ) + values.filterNot { groupHidden(source, type, it.id) }.map { value ->
                ContextEntry(groupLabel(source, type, value)) { select(value, false) }
            })
        }
    }

    private fun selectProviderCategory(source: IptvSource, type: ContentType, value: IptvCategory, values: List<IptvCategory>, favorites: Boolean = false, rebuildGroups: Boolean = true) {
        groupAccess(source.id, type, value.id) {
            cancelCatalogue(); guideScope.cancel(); requestVersion++
            sourceId = source.id; category = value.id; categoryTitle = if (favorites) "Favoris" else groupLabel(source, type, value)
            listMode = "catalogue"; detail = null; guideActive = false; visibleItems = emptyList()
            collapsedProviders.remove(source.id)
            if (rebuildGroups) buildGroups(values)
            else refreshGroupSelection()
            loadItems(mapOf(IptvOrganization.key(source.id, type, null, "category:${type.name}") to value.id,
                IptvOrganization.key(source.id, type, null, "favoritesOnly") to favorites.toString()))
        }
    }

    private fun groupMenu(source: IptvSource, type: ContentType, value: IptvCategory, focus: String = "") {
        val favoritesOnly = store.organization(source.id, type, value.id, "favoritesOnly", "false") == "true"
        val blocked = store.setting(IptvOrganization.key(source.id, type, value.id, "blocked"), "false") == "true"
        val offset = store.organization(source.id, type, value.id, "epgOffsetMinutes", "0").toIntOrNull() ?: 0
        fun reopen(label: String) = groupMenu(source, type, value, label)
        sidePanel("${source.name} · ${groupLabel(source, type, value)}", listOf(
            ContextEntry("Nom du Groupe", groupLabel(source, type, value)) {
                editOrganizationText("Nom du Groupe", groupLabel(source, type, value)) { name ->
                    if (name.isBlank()) false else { saveOrganization(source, type, value.id, "groupName", name) { reopen("Nom du Groupe") }; true }
                }
            },
            ContextEntry("Restaurer le nom du Groupe", value.name) { saveOrganization(source, type, value.id, "groupName", "") { reopen("Restaurer le nom du Groupe") } },
            ContextEntry("Tri des Chaînes", sortLabel(source, type, value.id)) { chooseSort(source, type, value.id) { reopen("Tri des Chaînes") } },
            ContextEntry("Réorganiser les chaînes") { reorderChannels(source, type, value) { reopen("Réorganiser les chaînes") } },
            ContextEntry("Afficher uniquement les Favoris", checked = favoritesOnly) {
                saveOrganization(source, type, value.id, "favoritesOnly", (!favoritesOnly).toString()) { reopen("Afficher uniquement les Favoris") }
            },
            ContextEntry("Utiliser un lecteur externe", externalLabel(source, type, value.id)) { chooseExternal(source, type, value.id) { reopen("Utiliser un lecteur externe") } },
            ContextEntry("Décalage temporel EPG, h:min", offsetLabel(offset)) {
                editOrganizationText("Décalage EPG de −12:00 à 12:00", offsetLabel(offset)) { text ->
                    val minutes = IptvOrganization.offsetMinutes(text)
                    if (minutes == null) false else { saveOrganization(source, type, value.id, "epgOffsetMinutes", minutes.toString()) { reopen("Décalage temporel EPG, h:min") }; true }
                }
            },
            ContextEntry(if (blocked) "Débloquer le Groupe" else "Bloquer le Groupe") {
                val save = {
                    if (!blocked && previewItem?.let { it.sourceId == source.id && it.categoryId == value.id } == true) stopPreview(true)
                    saveOrganization(source, type, value.id, "blocked", (!blocked).toString()) { reopen(if (blocked) "Bloquer le Groupe" else "Débloquer le Groupe") }
                }
                if (store.setting("pinHash", "").isBlank()) editPin(save) else parentalAccess("lockSettings", true, save)
            }
        ), focus)
    }

    private fun offsetLabel(minutes: Int): String = "${if (minutes < 0) "−" else ""}${kotlin.math.abs(minutes) / 60}:${(kotlin.math.abs(minutes) % 60).toString().padStart(2, '0')}"

    private fun editOrganizationText(title: String, value: String, submit: (String) -> Boolean) {
        val field = EditText(this).apply { setText(value); setSingleLine(true); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (submit(field.text.toString().trim())) dialog.dismiss() else field.error = "Valeur invalide"
        } }
        dialog.show()
    }

    private fun manageGroups(source: IptvSource, type: ContentType, focus: String = "", onBack: (() -> Unit)? = null) {
        categoriesFor(source, type) { values ->
            val sort = store.organization(source.id, type, null, "groupSort", "playlist")
            val ordered = IptvOrganization.groups(values, sort,
                IptvOrganization.ids(store.organization(source.id, type, null, "groupOrder:${type.name}", "[]"))) { groupLabel(source, type, it) }
            val newGroupsKey = IptvOrganization.key(source.id, type, null, "newGroupsVisible:${type.name}")
            val knownGroupsKey = IptvOrganization.key(source.id, type, null, "knownGroups:${type.name}")
            val categoryKey = IptvOrganization.key(source.id, type, null, "category:${type.name}")
            fun hiddenKey(id: String) = IptvOrganization.key(source.id, type, id, "hidden")
            val draft = buildMap {
                put(newGroupsKey, store.setting(newGroupsKey, "true"))
                put(knownGroupsKey, store.setting(knownGroupsKey, "[]"))
                put(categoryKey, category)
                values.forEach { put(hiddenKey(it.id), store.setting(hiddenKey(it.id), "")) }
            }.toMutableMap()
            var closed: () -> Unit = {}
            settingsPanel("Gérer les groupes · ${when (type) { ContentType.MOVIE -> "Films"; ContentType.SERIES -> "Séries"; else -> "TV" }}",
                onBack = { onBack?.invoke() ?: providerMenu(source, type) }, onDismiss = { closed() }) { rows, _ ->
                val groupViews = mutableMapOf<String, LinearLayout>()
                var newGroupsRow: LinearLayout? = null
                val confirmed = draft.toMutableMap()
                val pending = mutableMapOf<String, String>()
                val revisions = mutableMapOf<String, Long>()
                var revision = 0L
                var saving = false
                var menuClosed = false
                var organizationChanged = false
                fun visible(id: String) = !IptvOrganization.groupHidden(id, draft[hiddenKey(id)].orEmpty(),
                    draft[newGroupsKey].orEmpty(), draft[knownGroupsKey].orEmpty())
                fun refreshVisibility(keys: Set<String>) {
                    if (newGroupsKey in keys) newGroupsRow?.let { row ->
                        (row.getChildAt(1) as Switch).isChecked = draft[newGroupsKey] == "true"
                        tintSettingsControls(row, row.hasFocus())
                    }
                    groupViews.forEach { (id, row) ->
                        if (hiddenKey(id) !in keys && newGroupsKey !in keys && knownGroupsKey !in keys) return@forEach
                        val visible = visible(id)
                        (row.getChildAt(1) as Switch).isChecked = visible
                        val labels = row.getChildAt(0) as LinearLayout
                        val subtitle = if (visible) "Groupe visible" else "Groupe masqué"
                        (labels.getChildAt(1) as TextView).text = subtitle
                        row.contentDescription = "${(labels.getChildAt(0) as TextView).text} · $subtitle"
                        tintSettingsControls(row, row.hasFocus())
                    }
                }
                fun finishChanges() {
                    if (!menuClosed || !organizationChanged) return
                    organizationChanged = false
                    val clearSelection = source.id == sourceId && category.isNotBlank() && confirmed[categoryKey] == ""
                    if (clearSelection) {
                        category = ""; categoryTitle = when (type) { ContentType.MOVIE -> "Tous les films"; ContentType.SERIES -> "Toutes les séries"; else -> "Toutes les chaînes" }
                    }
                    if (contextPanel == null) refreshOrganization() else pendingSyncedRefresh = true
                    if (clearSelection) loadItems()
                }
                fun saveChanges() {
                    if (saving) return
                    if (pending.isEmpty()) { finishChanges(); return }
                    val changes = pending.toMap()
                    val sentRevisions = changes.keys.associateWith { revisions[it] }
                    pending.clear(); saving = true
                    async({ store.setSettings(changes) }, version = null, failure = { message ->
                        saving = false
                        val reverted = changes.keys.filter { revisions[it] == sentRevisions[it] }.toSet()
                        reverted.forEach { draft[it] = confirmed.getValue(it) }
                        if (rows.isAttachedToWindow) refreshVisibility(reverted)
                        toast(message); saveChanges()
                    }) {
                        confirmed.putAll(changes); organizationChanged = true
                        saving = false; saveChanges()
                    }
                }
                fun changeVisibility(changes: Map<String, String>) {
                    revision++
                    changes.keys.forEach { revisions[it] = revision }
                    draft.putAll(changes); pending.putAll(changes)
                    refreshVisibility(changes.keys); saveChanges()
                }
                fun visibility(visible: Boolean) {
                    changeVisibility(buildMap {
                        values.forEach { put(hiddenKey(it.id), (!visible).toString()) }
                        if (!visible && source.id == sourceId && category.isNotBlank()) put(categoryKey, "")
                    })
                }
                fun toggle(key: String, title: String, checked: Boolean, subtitle: String = "",
                    changes: (Boolean) -> Map<String, String>): LinearLayout {
                    val row = settingsPanelToggle(rows, key, title, checked, checked = checked, subtitle = subtitle)
                    row.setOnClickListener { changeVisibility(changes(!(row.getChildAt(1) as Switch).isChecked)) }
                    return row
                }
                closed = { menuClosed = true; saveChanges() }
                fun command(title: String, subtitle: String = "", action: () -> Unit) {
                    rows.addView(settingsPanelRow(title, subtitle, action).apply { tag = title }, LinearLayout.LayoutParams(-1, -2))
                }
                command("Tri des groupes", when (sort) { "name" -> "Par nom"; "manual" -> "Ordre personnalisé"; else -> "Par ordre dans la playlist" }) {
                    settingsPanel("Tri des groupes", onBack = { manageGroups(source, type, "Tri des groupes", onBack) }) { rows, dialog ->
                        listOf("playlist" to "Par ordre dans la playlist", "name" to "Par nom", "manual" to "Ordre personnalisé").forEach { (value, label) ->
                            rows.addView(settingsRadioRow(label, sort == value) {
                                dialog.dismiss(); saveOrganization(source, type, null, "groupSort", value) { manageGroups(source, type, "Tri des groupes", onBack) }
                            }, LinearLayout.LayoutParams(-1, dp(45)))
                        }
                        rows.getChildAt(0)
                    }
                }
                command("Réorganiser les groupes") {
                    reorderPanel("Réorganiser les groupes", ordered.map { it.id to groupLabel(source, type, it) },
                        onCancel = { manageGroups(source, type, "Réorganiser les groupes", onBack) }) { ids ->
                        async({ store.setSetting(IptvOrganization.key(source.id, type, null, "groupOrder:${type.name}"), iptvJson.encodeToString(ids))
                            store.setSetting(IptvOrganization.key(source.id, type, null, "groupSort"), "manual") }) { refreshOrganization(); manageGroups(source, type, "Réorganiser les groupes", onBack) }
                    }
                }
                command("Restaurer l’ordre des groupes") {
                    async({ store.setSetting(IptvOrganization.key(source.id, type, null, "groupOrder:${type.name}"), "[]")
                        store.setSetting(IptvOrganization.key(source.id, type, null, "groupSort"), "playlist") }) { refreshOrganization(); manageGroups(source, type, onBack = onBack) }
                }
                command("Afficher tous les groupes") { visibility(true) }
                command("Masquer tous les groupes") { visibility(false) }
                newGroupsRow = toggle(newGroupsKey, "Afficher les groupes nouvellement ajoutés", draft[newGroupsKey] == "true") { visible ->
                    mapOf(newGroupsKey to visible.toString(), knownGroupsKey to iptvJson.encodeToString(values.map { it.id }))
                }.apply { tag = "Afficher les groupes nouvellement ajoutés" }
                ordered.forEach { value ->
                    val visible = visible(value.id)
                    val name = groupLabel(source, type, value)
                    groupViews[value.id] = toggle(hiddenKey(value.id), name, visible,
                        subtitle = if (visible) "Groupe visible" else "Groupe masqué") { show ->
                        buildMap {
                            put(hiddenKey(value.id), (!show).toString())
                            if (!show && source.id == sourceId && category == value.id) put(categoryKey, "")
                        }
                    }.apply { tag = name }
                }
                rows.findViewWithTag<View>(focus) ?: rows.getChildAt(0)
            }
        }
    }

    private fun reorderChannels(source: IptvSource, type: ContentType, group: IptvCategory?, done: () -> Unit) {
        toast("Chargement des chaînes à réorganiser…")
        async({
            val items = linkedMapOf<String, IptvItem>()
            var page = 0
            while (true) {
                val batch = provider(source).items(type, group?.id.orEmpty(), page)
                val fresh = batch.items.filter { it.id !in items }
                fresh.forEach { items[it.id] = it }
                val next = batch.nextPage?.takeIf { it > page && fresh.isNotEmpty() } ?: break
                page = next
            }
            store.organizeItems(items.values.toList(), source.id, type, group?.id.orEmpty())
        }, failure = { toast(it); done() }) { items ->
            reorderPanel("Réorganiser les chaînes", items.map { it.id to it.title }) { ids ->
                async({ store.setSetting(IptvOrganization.key(source.id, type, group?.id, "itemOrder"), iptvJson.encodeToString(ids))
                    store.setSetting(IptvOrganization.key(source.id, type, group?.id, "channelSort"), "manual") }) { refreshOrganization(); done() }
            }
        }
    }

    private fun reorderPanel(title: String, initial: List<Pair<String, String>>, onCancel: (() -> Unit)? = null, save: (List<String>) -> Unit) {
        if (initial.isEmpty()) { toast("Aucun contenu à réorganiser."); return }
        contextPanel?.dismiss()
        val values = initial.toMutableList()
        var moving = false
        var returningToParent = false
        val dialog = object : Dialog(this) {
            override fun cancel() {
                if (onCancel == null) super.cancel()
                else { returningToParent = true; onCancel() }
            }
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); setBackgroundColor(surface(.07f)) }
        panel.addView(text(title, 23, true))
        val help = text("OK : choisir · ↑/↓ : déplacer · OK : déposer", 14).apply { setPadding(0, dp(10), 0, dp(12)) }
        panel.addView(help)
        val list = ListView(this).apply { isFocusable = true; divider = null; selector = shape(Color.TRANSPARENT) }
        fun paint(view: TextView, position: Int) {
            val focused = list.hasFocus() && position == list.selectedItemPosition
            view.setTextColor(if (focused) contrast else themeForeground)
            view.background = shape(if (focused) accent else Color.TRANSPARENT, focused)
        }
        fun paintRows() {
            for (index in 0 until list.childCount) (list.getChildAt(index) as? TextView)?.let { paint(it, list.firstVisiblePosition + index) }
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = values.size
            override fun getItem(position: Int) = values[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = (convertView as? TextView ?: text("", 16)).apply {
                text = "${position + 1}. ${values[position].second}"; paint(this, position)
                setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = false
            }
        }
        list.adapter = adapter
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { paintRows() }
            override fun onNothingSelected(parent: AdapterView<*>?) { paintRows() }
        }
        list.setOnFocusChangeListener { _, _ -> paintRows() }
        list.setOnItemClickListener { _, _, _, _ -> moving = !moving; help.text = if (moving) "↑/↓ : déplacer · OK : déposer" else "OK : choisir · ↑/↓ : déplacer · OK : déposer" }
        list.setOnKeyListener { _, key, event ->
            if (!moving || event.action != KeyEvent.ACTION_DOWN || key !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false
            else {
                val from = list.selectedItemPosition
                val to = (from + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1).coerceIn(0, values.lastIndex)
                if (from >= 0 && from != to) { val item = values.removeAt(from); values.add(to, item); adapter.notifyDataSetChanged(); list.setSelection(to) }
                true
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(button("Enregistrer l’ordre") { dialog.dismiss(); save(values.map { it.first }) })
        panel.addView(button("Annuler") { dialog.cancel() })
        wireVerticalFocus(panel)
        dialog.setContentView(panel)
        dialog.setOnDismissListener {
            if (contextPanel === dialog) contextPanel = null
            if (!returningToParent) handler.post { refreshSyncedLibrary() }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.55f); setGravity(Gravity.RIGHT); setLayout((resources.displayMetrics.widthPixels * .41f).toInt(), -1)
        }
        contextPanel = dialog; list.requestFocus(); list.setSelection(0)
    }

    private fun channelMenu(item: IptvItem, focus: String = "") {
        val source = sources.firstOrNull { it.id == item.sourceId } ?: return
        val original = store.originalItem(visibleItems.firstOrNull { it.id == item.id } ?: item)
        val title = store.setting(IptvOrganization.channelNameKey(item), "").trim().ifBlank { original.title }
        val current = item.copy(title = title)
        val hidden = store.booleanSetting(IptvOrganization.channelHiddenKey(item), false)
        val blocked = store.booleanSetting(IptvOrganization.channelBlockedKey(item), false)
        fun save(key: String, value: String, label: String) {
            async({ store.setSetting(key, value) }) { refreshOrganization(); channelMenu(original, label) }
        }
        sidePanel(title, listOfNotNull(
            ContextEntry("Regarder") { play(current) },
            if (listMode == "history") ContextEntry("Supprimer de l’historique") { changeHistory(item) } else null,
            if (listMode == "history") ContextEntry("Vider tout l’historique") { confirmClearHistory() } else null,
            ContextEntry(if (store.favorite(item)) "Retirer des Favoris" else "Ajouter aux Favoris") {
                changeFavorite(original) { refreshOrganization(); channelMenu(original) }
            },
            ContextEntry("Enregistrer") { playbackAccess(current) { recordingOptions(current) } },
            ContextEntry("Informations") { showDetails(current) },
            ContextEntry("Renommer la chaîne", title) {
                editOrganizationText("Nom de la chaîne", title) { name ->
                    if (name.isBlank()) false else {
                        async({ store.setSettings(mapOf(IptvOrganization.channelOriginalNameKey(item) to original.title,
                            IptvOrganization.channelNameKey(item) to name)) }) {
                            refreshOrganization(); channelMenu(original, "Renommer la chaîne")
                        }
                        true
                    }
                }
            },
            ContextEntry("Restaurer le nom de la chaîne", original.title) {
                save(IptvOrganization.channelNameKey(item), "", "Restaurer le nom de la chaîne")
            },
            ContextEntry(if (hidden) "Afficher la chaîne" else "Masquer la chaîne") {
                save(IptvOrganization.channelHiddenKey(item), (!hidden).toString(), if (hidden) "Masquer la chaîne" else "Afficher la chaîne")
            },
            ContextEntry(if (blocked) "Débloquer la chaîne" else "Bloquer la chaîne") {
                val update = {
                    if (!blocked && previewItem?.id == item.id) stopPreview(true)
                    save(IptvOrganization.channelBlockedKey(item), (!blocked).toString(), if (blocked) "Bloquer la chaîne" else "Débloquer la chaîne")
                }
                if (store.setting("pinHash", "").isBlank()) editPin(update) else parentalAccess("lockSettings", true, update)
            },
            ContextEntry("Gérer la visibilité des chaînes") { manageChannelOrganization(source, item.categoryId, false) },
            ContextEntry("Gérer le blocage des chaînes") { manageChannelOrganization(source, item.categoryId, true) },
            ContextEntry("Réglages du Groupe", item.categoryName) {
                categoriesFor(source, item.type) { values ->
                    val value = values.firstOrNull { it.id == item.categoryId }
                    if (value == null) providerMenu(source, item.type) else groupMenu(source, item.type, value)
                }
            },
            ContextEntry("Réglages du fournisseur", source.name) { providerMenu(source, item.type) }
        ), focus)
    }

    private fun manageChannelOrganization(source: IptvSource,
        group: String = category.takeIf { source.id == sourceId }.orEmpty(), blocking: Boolean, focus: String = "") {
        fun show(items: List<IptvItem>, focused: String) {
            val title = if (blocking) "Gérer le blocage des chaînes" else "Gérer la visibilité des chaînes"
            val entries = items.mapIndexed { index, item ->
                val key = if (blocking) IptvOrganization.channelBlockedKey(item) else IptvOrganization.channelHiddenKey(item)
                val stored = store.booleanSetting(key, false)
                val name = store.setting(IptvOrganization.channelNameKey(item), "").trim().ifBlank { item.title }
                val label = "${index + 1}. $name"
                ContextEntry(label, item.categoryName, checked = if (blocking) stored else !stored) {
                    val update = {
                        if (blocking && !stored && previewItem?.id == item.id) stopPreview(true)
                        async({ store.setSetting(key, (!stored).toString()) }) { refreshOrganization(); show(items, label) }
                    }
                    if (!blocking) update()
                    else if (store.setting("pinHash", "").isBlank()) editPin(update)
                    else parentalAccess("lockSettings", true, update)
                }
            }
            if (entries.isEmpty()) toast("Aucune chaîne dans cette sélection.")
            else sidePanel(title, entries, focused, onBack = { providerMenu(source, ContentType.LIVE) })
        }
        async({
            val values = linkedMapOf<String, IptvItem>()
            provider(source).loadCatalogue(ContentType.LIVE, group) { _, page -> page.items.forEach { values[it.id] = it } }
            values.values.toList()
        }, failure = { toast(it) }) { show(it, focus) }
    }

    private fun sourceActions(source: IptvSource) {
        showPlaylistSettings(source)
    }
    private fun showPlaylistSettings(source: IptvSource, focusKey: String = "enabled"): Unit =
        settingsPanel(source.name, onBack = { showPlaylistsSettings(source.id) }) { rows, panel ->
            fun row(key: String, title: String, subtitle: String = "", action: () -> Unit): LinearLayout =
                settingsPanelRow(title, subtitle, action).apply {
                    tag = key; minimumHeight = dp(if (subtitle.isBlank()) 48 else 68)
                    rows.addView(this, LinearLayout.LayoutParams(-1, -2))
                }
            fun reopen(key: String) = sources.firstOrNull { it.id == source.id }?.let { showPlaylistSettings(it, key) }
            fun setting(name: String, default: String = "") = store.setting(IptvPlaylistSettings.key(source.id, name), default)
            fun saveSetting(name: String, value: String) {
                async({ store.setSetting(IptvPlaylistSettings.key(source.id, name), value) }, version = null,
                    failure = { toast(it); reopen(name) }) { reopen(name) }
            }
            settingsPanelToggle(rows, IptvPlaylistSettings.key(source.id, "enabled"), "Activer la playlist", true,
                checked = store.playlistEnabled(source.id), save = { enabled ->
                    store.setSetting(IptvPlaylistSettings.key(source.id, "enabled"), enabled.toString())
                }) {
                if (!store.playlistEnabled(sourceId)) sourceId = orderedSources().firstOrNull()?.id.orEmpty()
                pendingSyncedRefresh = true
                if (previewItem?.sourceId == source.id && !store.playlistEnabled(source.id)) stopPreview(true)
            }.tag = "enabled"
            row("name", "Nom de la liste de lecture", source.name) {
                showSettingsTextInput("Nom de la liste de lecture", source.name) { value ->
                    if (value.isBlank()) { toast("Donne un nom à la liste de lecture."); reopen("name") }
                    else savePlaylistSource(source.id, "name") { it.copy(name = value) }
                }
            }
            row("epg", "Sources EPG", if (source.type == SourceType.M3U && source.epgUrl.isBlank()) "Source fournie par la liste de lecture" else "1 source") {
                showPlaylistEpgSources(source)
            }
            row("logos", "Priorité aux logos", "Préférer les logos de la liste de lecture") {
                settingsPanel("Priorité aux logos", onBack = { reopen("logos") }) { choices, _ ->
                    playbackSettingsNote(choices, "Les logos fournis par la liste de lecture sont utilisés. Les logos des sources EPG ne sont pas encore pris en charge.")
                    choices.addView(settingsPanelRow("Retour") { reopen("logos") }, LinearLayout.LayoutParams(-1, dp(48)))
                    choices.getChildAt(choices.childCount - 1)
                }
            }
            row("replay", "Replay", "Lecture Replay indisponible") {
                settingsPanel("Replay", onBack = { reopen("replay") }) { choices, _ ->
                    playbackSettingsNote(choices, "Les disponibilités Replay du fournisseur sont affichées dans le guide. La lecture Replay n’est pas encore disponible dans ce module.")
                    choices.addView(settingsPanelRow("Retour") { reopen("replay") }, LinearLayout.LayoutParams(-1, dp(48)))
                    choices.getChildAt(choices.childCount - 1)
                }
            }
            val userAgent = source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
            row("agent", "User-Agent", userAgent.ifBlank { "Non définie" }) {
                showSettingsTextInput("User-Agent", userAgent) { value ->
                    if ('\r' in value || '\n' in value) { toast("Le User-Agent doit tenir sur une ligne."); reopen("agent") }
                    else savePlaylistSource(source.id, "agent") { current ->
                        current.copy(headers = current.headers.filterKeys { !it.equals("User-Agent", true) } +
                            if (value.isBlank()) emptyMap() else mapOf("User-Agent" to value))
                    }
                }
            }
            row("connection", when (source.type) {
                SourceType.STALKER -> "Paramètres du Portal Stalker"
                SourceType.XTREAM -> "Paramètres Xtream Codes"
                else -> "Paramètres de la liste de lecture M3U"
            }) { panel.dismiss(); sourceForm(source.type, source,
                onBack = { reopen("connection") }, onSaved = { reopen("connection") }) }
            row("groups", "Gérer les Groupes") { showPlaylistGroups(source) }
            playbackSettingsNote(rows, "Options de mise à jour")
            row("updateHours", "Intervalles de mise à jour en heures", setting("updateHours", "24").let { if (it == "0") "Jamais" else it }) {
                settingsPanel("Intervalles de mise à jour en heures", onBack = { reopen("updateHours") }) { choices, _ ->
                    listOf(0, 2, 4, 6, 12, 24, 48, 72, 168).forEach { hours ->
                        choices.addView(settingsRadioRow(if (hours == 0) "Jamais" else hours.toString(),
                            setting("updateHours", "24") == hours.toString()) { saveSetting("updateHours", hours.toString()) },
                            LinearLayout.LayoutParams(-1, dp(48)))
                    }
                    choices.getChildAt(listOf(0, 2, 4, 6, 12, 24, 48, 72, 168).indexOf(setting("updateHours", "24").toIntOrNull()).coerceAtLeast(0))
                }
            }
            settingsPanelToggle(rows, IptvPlaylistSettings.key(source.id, "updateOnStart"),
                "Mise à jour au démarrage de l’application", false).tag = "updateOnStart"
            row("update", if (playlistUpdateRunning) "Mise à jour en cours…" else "Mettre à jour la liste de lecture") {
                updatePlaylists(listOf(source), source.id)
            }.apply { isEnabled = !playlistUpdateRunning; isFocusable = isEnabled }
            row("delete", "Supprimer la liste de lecture") {
                settingsPanel("Supprimer « ${source.name} » ?", onBack = { reopen("delete") }) { choices, _ ->
                    val cancel = settingsPanelRow("Annuler") { reopen("delete") }
                    choices.addView(cancel, LinearLayout.LayoutParams(-1, dp(48)))
                    choices.addView(settingsPanelRow("Supprimer la liste de lecture") {
                        async({ store.remove(source); store.sources() }, version = null, failure = { toast(it); reopen("delete") }) { values ->
                            sources = values; playlistCounts.remove(source.id); invalidateProviders(source.id)
                            if (sourceId == source.id) sourceId = orderedSources().firstOrNull()?.id.orEmpty()
                            pendingSyncedRefresh = true; showPlaylistsSettings()
                        }
                    }, LinearLayout.LayoutParams(-1, dp(48)))
                    cancel
                }
            }
            rows.findViewWithTag<View>(focusKey)?.takeIf { it.isFocusable } ?: rows.getChildAt(0)
        }

    private fun savePlaylistSource(id: String, focus: String, update: (IptvSource) -> IptvSource) {
        async({
            val current = store.sources().firstOrNull { it.id == id } ?: return@async null
            update(current).also { store.save(it) }
        }, version = null, failure = { toast(it); sources.firstOrNull { it.id == id }?.let { showPlaylistSettings(it, focus) } }) { saved ->
            sources = store.sources(); playlistCounts.remove(id); invalidateProviders(id)
            pendingSyncedRefresh = true; playlistCatalogueChanged = true
            saved?.let { showPlaylistSettings(it, focus) } ?: showPlaylistsSettings()
        }
    }

    private fun showPlaylistGroups(source: IptvSource, focusType: ContentType = ContentType.LIVE): Unit =
        settingsPanel("Gérer les Groupes", onBack = { showPlaylistSettings(source, "groups") }) { rows, _ ->
            listOf(ContentType.LIVE to "Chaînes", ContentType.MOVIE to "Films", ContentType.SERIES to "Séries")
                .filter { source.includes(it.first) }.forEach { (type, label) ->
                    rows.addView(settingsPanelRow(label) {
                        manageGroups(source, type, onBack = { showPlaylistGroups(source, type) })
                    }.apply { tag = type }, LinearLayout.LayoutParams(-1, dp(48)))
                }
            rows.findViewWithTag<View>(focusType) ?: rows.getChildAt(0)
        }
    private fun testSource(source: IptvSource) {
        status.text = "Test de la connexion…"
        async({ providerFor(store.connectionSource(source)).testConnection() }) { result -> status.text = result; toast(result) }
    }

    private fun settingsPanel(title: String, onBack: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null,
        parentPanel: Dialog? = null,
        build: (LinearLayout, Dialog) -> View?) {
        if (contextPanel !== parentPanel) contextPanel?.dismiss()
        val origin = parentPanel?.currentFocus ?: currentFocus
        var returningToParent = false
        val dialog = object : Dialog(this) {
            override fun cancel() {
                // Keep this window focused until the previous settings panel is ready.
                if (onBack == null) super.cancel()
                else { returningToParent = true; onBack() }
            }
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface(.07f))
        }
        panel.addView(text(title, 20).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            setTextColor(themeForeground)
            setBackgroundColor(surface(.16f))
        }, LinearLayout.LayoutParams(-1, dp(72)))
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            addView(rows)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val initialFocus = build(rows, dialog)
        for (index in 0 until rows.childCount) (rows.getChildAt(index) as? ViewGroup)?.let {
            tintSettingsControls(it, false)
        }
        wireVerticalFocus(rows)
        dialog.setContentView(panel)
        dialog.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN || code !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false
            else {
                val available = (0 until rows.childCount).map(rows::getChildAt)
                    .filter { it.isFocusable && it.isEnabled && it.visibility == View.VISIBLE }
                val target = when {
                    code == KeyEvent.KEYCODE_DPAD_UP && dialog.currentFocus === available.firstOrNull() -> available.lastOrNull()
                    code == KeyEvent.KEYCODE_DPAD_DOWN && dialog.currentFocus === available.lastOrNull() -> available.firstOrNull()
                    else -> null
                }
                if (target == null) false else {
                    // ScrollView can scroll toward a distant cyclic target without moving focus.
                    scroll.scrollTo(0, if (target === available.firstOrNull()) 0 else rows.height)
                    target.requestFocus()
                }
            }
        }
        dialog.setOnDismissListener {
            if (isFinishing || isDestroyed) parentPanel?.dismiss()
            if (contextPanel === dialog) {
                contextPanel = parentPanel?.takeIf { it.isShowing }
                if (!returningToParent) {
                    val restoredFocus = contextPanel?.currentFocus ?: origin
                    if (restoredFocus?.isAttachedToWindow == true) restoredFocus.requestFocus()
                    else firstContent()?.requestFocus()
                }
            }
            onDismiss?.invoke()
            if (!returningToParent) handler.post { refreshSyncedLibrary() }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            setDimAmount(.55f)
            setGravity(Gravity.RIGHT)
            setLayout((resources.displayMetrics.widthPixels * .375f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        contextPanel = dialog
        initialFocus?.requestFocus()
    }

    private fun showSettingsMenu(focusKey: String = "account"): Unit = settingsPanel("Paramètres") { rows, dialog ->
        listOf("account" to "Compte", "general" to "Général", "playlists" to "Listes de lecture", "epg" to "EPG (guide des programmes TV)",
            "appearance" to "Apparence", "playback" to "Lecteur", "remote" to "Télécommande",
            "parental" to "Contrôle Parental", "advanced" to "Paramètres avancés", "about" to "À propos").forEach { (key, title) ->
            val row = text(title, 14).apply {
                id = View.generateViewId()
                tag = key
                isFocusable = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(themeForeground)
                setOnFocusChangeListener { _, focused ->
                    background = GradientDrawable().apply {
                        setColor(if (focused) accent else Color.TRANSPARENT)
                        cornerRadius = dp(6).toFloat()
                        if (focused && focusNeedsOutline) setStroke(dp(1), focusOutlineColor())
                    }
                    setTextColor(if (focused) contrast else themeForeground)
                }
                setOnClickListener {
                    if (key == "account") showAccountSettings()
                    else if (key == "general") showGeneralSettings()
                    else if (key == "playlists") parentalAccess("lockPlaylists") { showPlaylistsSettings() }
                    else if (key == "epg") parentalAccess("lockEpg") { showEpgSettings() }
                    else if (key == "appearance") showAppearanceSettings()
                    else if (key == "playback") showPlaybackSettings()
                    else if (key == "parental") showParentalSettings()
                    else if (key == "advanced") showAdvancedSettings()
                    else if (key == "remote") showRemoteSettings()
                    else if (key == "about") showAboutSettings()
                }
                setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        dialog.dismiss(); true
                    } else false
                }
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, dp(45)))
        }
        for (index in 0 until rows.childCount) {
            val row = rows.getChildAt(index)
            row.nextFocusUpId = rows.getChildAt((index + rows.childCount - 1) % rows.childCount).id
            row.nextFocusDownId = rows.getChildAt((index + 1) % rows.childCount).id
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
        }
        rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
    }

    private fun accountSettingsRow(account: IptvAccount, subtitle: String = "", avatarName: String = account.name,
        action: () -> Unit): LinearLayout {
        val row = settingsPanelRow(account.name, subtitle, action)
        val avatar = accountAvatarView(account.copy(name = avatarName), 40)
        row.addView(avatar, 0, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val listener = row.onFocusChangeListener
        row.setOnFocusChangeListener { view, focused ->
            listener?.onFocusChange(view, focused)
            styleAccountAvatar(avatar, focused)
        }
        return row
    }

    private fun accountColorRow(title: String, color: Int, subtitle: String = "", action: () -> Unit): LinearLayout {
        val row = settingsPanelRow(title, subtitle, action)
        val swatch = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun style(focused: Boolean) {
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(color)
                setStroke(dp(if (focused) 2 else 1), if (focused) focusOutlineColor()
                    else ColorUtils.setAlphaComponent(MainActivity.contrastingThemeTextColor(color), 200))
            }
        }
        row.addView(swatch, 0, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val listener = row.onFocusChangeListener
        row.setOnFocusChangeListener { view, focused -> listener?.onFocusChange(view, focused); style(focused) }
        style(false)
        return row
    }

    private fun accountAvatarView(account: IptvAccount, size: Int): FrameLayout = FrameLayout(this).apply {
        tag = "account-avatar"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToOutline = true
        addView(text("", maxOf(12, size / 2)).apply {
            tag = "avatar-label"; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        addView(ImageView(this@IptvActivity).apply {
            tag = "avatar-image"; visibility = View.GONE; scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        avatarFrames[this] = account
        val frame = this
        val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener { loadAccountAvatarImage(frame) }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                avatarFrames[frame] = account
                view.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            }
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                avatarFrames.remove(frame)
            }
        })
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> loadAccountAvatarImage(frame) }
        bindAccountAvatar(this, account)
        styleAccountAvatar(this, false)
    }

    private fun bindAccountAvatar(frame: FrameLayout, account: IptvAccount) {
        val entry = avatarCatalog.firstOrNull { it.id == account.avatar || it.emoji.isNotBlank() && it.emoji == account.avatar }
        frame.findViewWithTag<TextView>("avatar-label").apply {
            text = entry?.emoji?.takeIf(String::isNotBlank) ?: if (account.name.isEmpty()) "?"
                else account.name.substring(0, account.name.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
            visibility = if (entry?.image?.isNotBlank() == true
                || entry == null && account.avatar.isNotBlank() && !account.avatar.startsWith("initial:")) View.GONE else View.VISIBLE
        }
        frame.findViewWithTag<ImageView>("avatar-image").apply {
            visibility = View.GONE; setImageDrawable(null); contentDescription = entry?.id
        }
        styleAccountAvatar(frame, (frame.parent as? View)?.hasFocus() == true)
        loadAccountAvatarImage(frame)
    }

    private fun loadAccountAvatarImage(frame: FrameLayout) {
        val account = avatarFrames[frame] ?: return
        val entry = avatarCatalog.firstOrNull { it.id == account.avatar }?.takeIf { it.image.isNotBlank() } ?: return
        val image = frame.findViewWithTag<ImageView>("avatar-image")
        if (image.contentDescription == "loading:${entry.id}" || image.visibility == View.VISIBLE) return
        store.cachedAvatarBitmap(entry)?.let {
            image.setImageBitmap(it); image.visibility = View.VISIBLE
            frame.findViewWithTag<TextView>("avatar-label").visibility = View.GONE
            return
        }
        if (!frame.isAttachedToWindow || !frame.getLocalVisibleRect(android.graphics.Rect())) return
        image.contentDescription = "loading:${entry.id}"
        images.execute {
            val bitmap = runCatching { store.avatarBitmap(entry) }.getOrNull()
            runOnUiThread {
                if (!isDestroyed && frame.isAttachedToWindow && image.contentDescription == "loading:${entry.id}") {
                    image.contentDescription = entry.id
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap); image.visibility = View.VISIBLE
                        frame.findViewWithTag<TextView>("avatar-label").visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun styleAccountAvatar(frame: FrameLayout, focused: Boolean) {
        val account = avatarFrames[frame]
        val initialColor = avatarCatalog.firstOrNull { it.id == account?.avatar }?.initialColor()
        val foreground = initialColor?.let(MainActivity::contrastingThemeTextColor) ?: themeForeground
        frame.background = shape(initialColor ?: surface(.16f)).apply {
            cornerRadius = dp(100).toFloat()
            if (focused) setStroke(dp(1), focusOutlineColor())
            else if (initialColor != null) setStroke(dp(1), ColorUtils.setAlphaComponent(foreground, 72))
        }
        frame.foreground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
            val ornament = account?.ornamentColorValue() ?: themeForeground
            setStroke(dp(if ((frame.layoutParams?.width ?: frame.width) >= dp(88)) 2 else 1),
                if (focused) focusOutlineColor() else ColorUtils.blendARGB(ornament, Color.WHITE, .30f))
        }
        frame.findViewWithTag<TextView>("avatar-label").setTextColor(foreground)
    }

    private fun refreshAccountSidebar() {
        accountNavRow.removeViewAt(0)
        displayedAccount = store.selectedAccount()
        val account = displayedAccount ?: IptvAccount("", "Compte")
        accountNavRow.addView(accountAvatarView(account, 24), 0, LinearLayout.LayoutParams(dp(24), dp(24)))
        accountNavRow.contentDescription = listOf("Compte", account.name).distinct().joinToString(" · ")
        paintCommand(accountNavRow, accountNavRow.hasFocus(), false)
    }

    private fun loadAccountAvatarCatalog() {
        if (avatarCatalogLoading) return
        avatarCatalogLoading = true; avatarCatalogError = ""
        images.execute {
            val cached = store.cachedAvatars()
            fun publish(entries: List<IptvAvatar>) = runOnUiThread {
                if (!isDestroyed && entries != avatarCatalog) {
                    avatarCatalog = entries
                    avatarFrames.toList().forEach { (frame, account) -> if (frame.isAttachedToWindow) bindAccountAvatar(frame, account) }
                    avatarCatalogUpdated?.invoke()
                }
            }
            if (cached.isNotEmpty()) publish(cached)
            val fresh = runCatching { store.fetchAvatars() }
            fresh.getOrNull()?.let { entries -> store.preloadAccountAvatars(); publish(entries) }
            runOnUiThread {
                if (!isDestroyed) {
                    avatarCatalogLoading = false; avatarCatalogLoaded = true
                    avatarCatalogError = if (fresh.isFailure && avatarCatalog.isEmpty()) "Catalogue NAS indisponible. Réessaie après avoir vérifié la connexion au NAS." else ""
                    avatarCatalogUpdated?.invoke()
                }
            }
        }
    }

    private fun accountPageLogo(): ImageView = ImageView(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setImageDrawable(object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(239, 190, 121); style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val saved = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 64f, bounds.height() / 64f)
                canvas.drawLine(21f, 9f, 32f, 19f, paint); canvas.drawLine(43f, 9f, 32f, 19f, paint)
                canvas.drawRoundRect(android.graphics.RectF(9f, 19f, 55f, 54f), 9f, 9f, paint)
                val play = android.graphics.Path().apply { moveTo(25f, 29f); lineTo(40f, 37f); lineTo(25f, 45f); close() }
                canvas.drawPath(play, paint)
                canvas.restoreToCount(saved)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        })
    }

    private fun accountAddIcon(): ImageView = ImageView(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setImageDrawable(object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; strokeWidth = 10f
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val saved = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 104f, bounds.height() / 104f)
                canvas.drawLine(18f, 52f, 86f, 52f, paint)
                canvas.drawLine(52f, 18f, 52f, 86f, paint)
                canvas.restoreToCount(saved)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        })
    }

    private fun accountCardBackground(color: Int, hasAvatar: Boolean): android.graphics.drawable.Drawable =
        object : android.graphics.drawable.Drawable() {
            private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            private val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 6f
                this.color = Color.WHITE
            }
            private var texture: Bitmap? = null
            private var highlighted = false
            private var drawableAlpha = 255

            private fun renderTexture(): Bitmap {
                // Draw the soft halos once on a software canvas, including on older Fire TV devices.
                // No inset layers: the visible card remains 344 x 290 inside its 416 x 362 halo.
                val scale = minOf(bounds.width() / 416f, bounds.height() / 362f, 1.5f)
                val image = Bitmap.createBitmap((416 * scale).toInt().coerceAtLeast(1),
                    (362 * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(image)
                canvas.scale(image.width / 416f, image.height / 362f)
                val rect = android.graphics.RectF(36f, 36f, 380f, 326f)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val rim = ColorUtils.blendARGB(color, Color.WHITE, if (hasAvatar) .40f else .26f)
                paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = if (hasAvatar) 20f else 12f
                paint.color = ColorUtils.setAlphaComponent(rim, if (hasAvatar) 210 else 100)
                paint.maskFilter = android.graphics.BlurMaskFilter(24f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, 40f, 40f, paint)
                paint.maskFilter = null; paint.color = rim
                paint.style = android.graphics.Paint.Style.FILL
                val base = Color.rgb(15, 19, 29)
                paint.shader = android.graphics.LinearGradient(0f, 36f, 0f, 326f, intArrayOf(
                    ColorUtils.blendARGB(base, color, if (hasAvatar) .48f else .24f),
                    ColorUtils.blendARGB(base, color, if (hasAvatar) .12f else .06f),
                    ColorUtils.blendARGB(base, color, .03f)), floatArrayOf(0f, .52f, 1f), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, 40f, 40f, paint)
                paint.shader = android.graphics.LinearGradient(0f, 36f, 0f, 180f,
                    ColorUtils.setAlphaComponent(Color.WHITE, if (hasAvatar) 24 else 8), Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, 40f, 40f, paint)
                paint.shader = null; paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = if (hasAvatar) 14f else 10f
                paint.color = ColorUtils.setAlphaComponent(rim, if (hasAvatar) 180 else 70)
                paint.maskFilter = android.graphics.BlurMaskFilter(if (hasAvatar) 16f else 12f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, 40f, 40f, paint)
                paint.maskFilter = null; paint.strokeWidth = 1.2f; paint.color = rim
                canvas.drawRoundRect(rect, 40f, 40f, paint)
                if (hasAvatar) {
                    paint.strokeWidth = 10f; paint.color = ColorUtils.setAlphaComponent(rim, 230)
                    paint.maskFilter = android.graphics.BlurMaskFilter(16f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    canvas.drawCircle(208f, 137f, 73f, paint)
                    paint.maskFilter = null; paint.strokeWidth = 2f; paint.color = rim
                    canvas.drawCircle(208f, 137f, 73f, paint)
                }
                return image
            }

            override fun onBoundsChange(bounds: android.graphics.Rect) { texture = null }
            override fun isStateful(): Boolean = true
            override fun onStateChange(states: IntArray): Boolean {
                val next = states.any { it == android.R.attr.state_focused || it == android.R.attr.state_pressed }
                if (next == highlighted) return false
                highlighted = next; invalidateSelf(); return true
            }
            override fun draw(canvas: android.graphics.Canvas) {
                if (bounds.width() <= 0 || bounds.height() <= 0) return
                val image = texture ?: renderTexture().also { texture = it }
                bitmapPaint.alpha = if (highlighted) drawableAlpha else drawableAlpha / 2
                canvas.drawBitmap(image, null, bounds, bitmapPaint)
                if (highlighted) {
                    val saved = canvas.save()
                    canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                    canvas.scale(bounds.width() / 416f, bounds.height() / 362f)
                    canvas.drawRoundRect(android.graphics.RectF(36f, 36f, 380f, 326f), 40f, 40f, outlinePaint)
                    canvas.restoreToCount(saved)
                }
            }
            override fun setAlpha(alpha: Int) { drawableAlpha = alpha; outlinePaint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) {
                bitmapPaint.colorFilter = filter; outlinePaint.colorFilter = filter; invalidateSelf()
            }
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }

    private fun showAccountsPage(focusAccountId: String? = null) {
        val previousFocus = focusAccountId ?: accountPage?.findFocus()?.tag
        if (accountPage == null) {
            stopPreview(true); cancelSearch(); cancelCatalogue(); guideScope.cancel()
            resetArtworkRequests()
            guideActive = false; requestVersion++; parentalUnlockedUntil = 0L
            handler.removeCallbacks(recordingsRefresh)
        }
        accountPage?.let { (it.parent as? ViewGroup)?.removeView(it) }
        content.visibility = View.INVISIBLE
        val page = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(Color.rgb(25, 31, 43), Color.rgb(7, 10, 15))
                setGradientCenter(.5f, .55f); gradientRadius = resources.displayMetrics.widthPixels * .60f
            }
        }
        accountPage = page
        pageFocus = null
        showNavigation(1)
        val logo = accountPageLogo()
        val brand = text("JOJOTV", 24).apply {
            gravity = Gravity.CENTER; includeFontPadding = false; letterSpacing = .24f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setTextColor(Color.rgb(239, 190, 121))
        }
        val title = text("Choisir un compte", 38).apply {
            gravity = Gravity.CENTER; includeFontPadding = false; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        page.addView(logo); page.addView(brand); page.addView(title)
        val accounts = store.accounts()
        displayedAccounts = accounts
        val cards = mutableListOf<FrameLayout>()
        val columns = minOf(accounts.size + 1, 3)
        val body = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        for (index in 0..accounts.size) {
            val account = accounts.getOrNull(index)
            val ornament = account?.ornamentColorValue() ?: Color.rgb(164, 175, 195)
            val card = FrameLayout(this).apply {
                id = View.generateViewId(); isFocusable = true; tag = account?.id ?: "add"
                contentDescription = account?.let { "${it.name} · Appui long pour modifier le compte" } ?: "Ajouter un compte"
                background = accountCardBackground(ornament, account != null)
            }
            card.addView(account?.let { accountAvatarView(it, 108) } ?: accountAddIcon())
            card.addView(text(account?.name ?: "Ajouter un compte", if (account == null) 24 else 28).apply {
                gravity = Gravity.CENTER; includeFontPadding = false; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            card.setOnFocusChangeListener { view, focused ->
                val scale = if (focused) 1.04f else 1f
                view.scaleX = scale; view.scaleY = scale
            }
            card.setOnClickListener { if (account == null) showAccountForm() else enterAccount(account.id) }
            if (account != null) card.setOnLongClickListener { showStartupAccountMenu(account.id); true }
            cards += card; body.addView(card)
        }
        cards.forEachIndexed { index, card ->
            val rowStart = index / columns * columns
            val rowEnd = minOf(rowStart + columns, cards.size) - 1
            card.nextFocusLeftId = if (index == rowStart && accountChosen) accountNavRow.id
                else cards[if (index > rowStart) index - 1 else rowEnd].id
            card.nextFocusRightId = cards[if (index < rowEnd) index + 1 else rowStart].id
            card.nextFocusUpId = cards[if (index >= columns) index - columns else (cards.lastIndex / columns * columns + index).coerceAtMost(cards.lastIndex)].id
            card.nextFocusDownId = cards[if (index + columns < cards.size) index + columns else index % columns].id
        }
        val scroll = ScrollView(this).apply {
            isFocusable = false; isVerticalScrollBarEnabled = false
            clipChildren = false; clipToPadding = false; addView(body)
        }
        page.addView(scroll)
        fun layoutAccountsPage(width: Int, height: Int) {
            // Scale the supplied 1280 x 720 reference as one composition, independent of TV density.
            val scale = minOf(width / 1280f, height / 720f)
            if (scale <= 0f) return
            fun px(value: Int) = (value * scale + .5f).toInt()
            val top = ((height - 720 * scale) / 2).toInt()
            logo.layoutParams = FrameLayout.LayoutParams(px(96), px(96), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = top + px(36)
            }
            brand.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 34 * scale)
            brand.layoutParams = FrameLayout.LayoutParams(-1, px(48)).apply { topMargin = top + px(128) }
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 53 * scale)
            title.layoutParams = FrameLayout.LayoutParams(-1, px(78)).apply { topMargin = top + px(196) }
            val scrollTop = top + px(275)
            scroll.layoutParams = FrameLayout.LayoutParams(-1, (height - scrollTop).coerceAtLeast(1)).apply { topMargin = scrollTop }
            body.layoutParams = FrameLayout.LayoutParams(-1, px((cards.size + columns - 1) / columns * 362))
            cards.forEachIndexed { index, card ->
                val rowStart = index / columns * columns
                val rowCount = minOf(columns, cards.size - rowStart)
                val rowWidth = px(416 + (rowCount - 1) * 384)
                card.layoutParams = FrameLayout.LayoutParams(px(416), px(362)).apply {
                    leftMargin = (width - rowWidth) / 2 + px(index % columns * 384)
                    topMargin = px(index / columns * 362)
                }
                val illustration = card.getChildAt(0)
                val avatar = accounts.getOrNull(index) != null
                illustration.layoutParams = FrameLayout.LayoutParams(px(if (avatar) 144 else 104), px(if (avatar) 144 else 104)).apply {
                    leftMargin = px(if (avatar) 136 else 156); topMargin = px(if (avatar) 65 else 90)
                }
                if (illustration is FrameLayout) illustration.findViewWithTag<TextView>("avatar-label")
                    .setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 64 * scale)
                (card.getChildAt(1) as TextView).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (if (avatar && text.length <= 20) 37 else 32) * scale)
                    layoutParams = FrameLayout.LayoutParams(px(312), px(88)).apply { leftMargin = px(52); topMargin = px(208) }
                }
            }
        }
        layoutAccountsPage(contentHost.width, contentHost.height)
        page.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                layoutAccountsPage(right - left, bottom - top)
        }
        contentHost.addView(page, FrameLayout.LayoutParams(-1, -1))
        (cards.firstOrNull { it.tag == previousFocus } ?: cards.firstOrNull { it.tag == store.selectedAccount()?.id }
            ?: cards.firstOrNull())?.requestFocus()
    }

    private fun enterAccount(id: String) {
        if (accountSwitching) return
        accountSwitching = true
        stopPreview(true)
        // Catalogue/page refreshes must not discard completion and leave the selector locked.
        async({ store.selectAccount(id) }, version = null,
            failure = { accountSwitching = false; toast(it) }) { displaySelectedAccount() }
    }

    private fun displaySelectedAccount() {
        accountSwitching = false
        accountPage?.let { (it.parent as? ViewGroup)?.removeView(it) }; accountPage = null
        accountPageBack = null
        content.visibility = View.VISIBLE; accountChosen = true; pageFocus = null
        providers.clear(); searchProviders.clear(); groupCategories.clear(); guidePrograms.evictAll()
        collapsedProviders.clear(); collapsedProviders.addAll(sources.map { it.id })
        rememberedFocus = ""; livePlayerFocus = ""; detail = null; pendingEpisode = null
        playbackReturnFocus = null; restoringPlaybackFocus = false
        parentalUnlockedUntil = 0L; pendingSyncedRefresh = false
        applyTheme(false); applyTextSize(); refreshAccountSidebar()
        val entry = accountEntry; accountEntry = null
        if (entry != null) entry() else {
            section = 1; showSection(1, true); showNavigation(0); navRows[1].requestFocus()
        }
    }

    private fun showAccountSettings(focusKey: String = "selected"): Unit =
        settingsPanel("Compte", onBack = { showSettingsMenu("account") }) { rows, dialog ->
            val account = store.selectedAccount()
            if (account != null) rows.addView(accountSettingsRow(account, "Compte sélectionné") {
                dialog.dismiss(); showAccountSelection()
            }.apply { tag = "selected" }, LinearLayout.LayoutParams(-1, dp(70)))
            else rows.addView(text("Aucun compte", 14).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12)); setTextColor(surface(.68f))
            })
            settingsPanelToggle(rows, "rememberLastProfile", "Se souvenir du dernier profil", false,
                checked = store.rememberLastProfile(),
                subtitle = "Se souvenir du dernier profil sélectionné au démarrage",
                save = store::setRememberLastProfile)
            fun row(key: String, title: String, needsAccount: Boolean = false, dismiss: Boolean = true, action: () -> Unit) {
                val enabled = !needsAccount || account != null
                rows.addView(settingsPanelRow(title) { if (dismiss) dialog.dismiss(); action() }.apply {
                    tag = key; isEnabled = enabled; isFocusable = enabled; isClickable = enabled
                    if (!enabled) alpha = .45f
                }, LinearLayout.LayoutParams(-1, dp(49)))
            }
            row("add", "Ajouter un compte") { showAccountForm() }
            row("supabase", "Supabase · sauvegarde des profils et paramètres", dismiss = false) {
                startActivity(Intent(this, com.jojotv.home.progresspilot.SupabaseAccountActivity::class.java))
            }
            row("edit", "Éditer le compte", true) { account?.let { showAccountForm(it) } }
            row("delete", "Supprimer le compte", true) { account?.let { showAccountDeletion(it) } }
            row("avatar", "Choisir un avatar", true) { account?.let { showAccountAvatars(it) } }
            rows.findViewWithTag<View>(focusKey)?.takeIf { it.isFocusable } ?: rows.findViewWithTag<View>("add")
        }

    private fun showAccountSelection() {
        showAccountsPage()
        accountPageBack = {
            closeContentPages(); showSection(section, true); showAccountSettings("selected")
        }
    }

    private fun showStartupAccountMenu(id: String, focusKey: String = "edit") {
        val account = store.accounts().firstOrNull { it.id == store.resolveAccountId(id) } ?: return
        settingsPanel("Compte · ${account.name}", onBack = { showAccountsPage(account.id) }) { rows, dialog ->
            fun row(key: String, title: String, action: () -> Unit) {
                rows.addView(settingsPanelRow(title) { dialog.dismiss(); action() }.apply { tag = key },
                    LinearLayout.LayoutParams(-1, dp(49)))
            }
            row("edit", "Modifier le nom") { showAccountForm(account) { showStartupAccountMenu(account.id, "edit") } }
            row("avatar", "Choisir un avatar") { showAccountAvatars(account) { showStartupAccountMenu(account.id, "avatar") } }
            rows.addView(accountColorRow("Couleur d’ornement", account.ornamentColorValue(), accountOrnamentLabel(account)) {
                dialog.dismiss(); showAccountOrnaments(account) { showStartupAccountMenu(account.id, "ornament") }
            }.apply { tag = "ornament" }, LinearLayout.LayoutParams(-1, dp(62)))
            row("delete", "Supprimer le compte") { showAccountDeletion(account) { showStartupAccountMenu(account.id, "delete") } }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }
    }

    private fun showAccountForm(account: IptvAccount? = null, onBack: (() -> Unit)? = null): Unit =
        settingsPanel(if (account == null) "Ajouter un compte" else "Éditer le compte",
            onBack = { if (onBack != null) onBack() else if (accountPage != null) showAccountsPage(account?.id)
                else showAccountSettings(if (account == null) "add" else "edit") }) { rows, dialog ->
            rows.addView(text("Nom du compte", 14).apply { setPadding(dp(16), dp(12), dp(16), 0) })
            val field = settingsInput().apply {
                id = View.generateViewId(); setSingleLine(true); textSize = 16 * textScale
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                filters = arrayOf(android.text.InputFilter.LengthFilter(40))
                setText(account?.name.orEmpty()); setSelection(text.length)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            rows.addView(field, LinearLayout.LayoutParams(-1, dp(60)).apply {
                marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(12)
            })
            val error = text("", 12).apply {
                visibility = View.GONE; setPadding(dp(16), 0, dp(16), dp(12))
            }
            rows.addView(error)
            var saving = false
            lateinit var saveRow: LinearLayout
            fun save() {
                if (saving) return
                val current = account?.let { store.accounts().firstOrNull { value -> value.id == store.resolveAccountId(it.id) } ?: it }
                val edited = (current ?: IptvAccount(UUID.randomUUID().toString(), "")).copy(name = field.text.toString())
                if (account == null) stopPreview(true)
                saving = true; saveRow.isEnabled = false
                async({ store.saveAccount(edited) }, version = null, failure = { message ->
                    saving = false; saveRow.isEnabled = true
                    error.text = message; error.visibility = View.VISIBLE
                }) {
                    if (dialog.isShowing) {
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .hideSoftInputFromWindow(field.windowToken, 0)
                        dialog.dismiss()
                        refreshAccountSidebar()
                        if (accountPage != null) {
                            showAccountsPage(edited.id)
                            if (onBack != null) onBack()
                            else if (account == null) showAccountAvatars(store.selectedAccount()!!)
                        } else if (account == null) {
                            displaySelectedAccount()
                            showAccountSettings("avatar")
                        } else showAccountSettings("edit")
                    }
                }
            }
            saveRow = settingsPanelRow("Enregistrer") { save() }
            rows.addView(saveRow, LinearLayout.LayoutParams(-1, dp(49)))
            rows.addView(settingsPanelRow("Annuler") {
                dialog.cancel()
            }, LinearLayout.LayoutParams(-1, dp(49)))
            field.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { save(); true } else false
            }
            field
        }

    private fun showAccountDeletion(account: IptvAccount, onBack: (() -> Unit)? = null): Unit =
        settingsPanel("Supprimer le compte", onBack = { if (onBack != null) onBack() else showAccountSettings("delete") }) { rows, dialog ->
            rows.addView(text("Supprimer le compte « ${account.name} » ?", 14).apply {
                setPadding(dp(16), dp(12), dp(16), dp(20))
            })
            val cancel = settingsPanelRow("Annuler") { dialog.cancel() }
            rows.addView(cancel, LinearLayout.LayoutParams(-1, dp(49)))
            rows.addView(settingsPanelRow("Supprimer") {
                updateAccount(dialog) { store.deleteAccount(account.id) }
            }, LinearLayout.LayoutParams(-1, dp(49)))
            cancel
        }

    private fun showAccountAvatars(account: IptvAccount, focusKey: String = "ornament", onBack: (() -> Unit)? = null): Unit =
        settingsPanel("Choisir un avatar", onBack = {
            if (onBack != null) onBack() else if (accountPage != null) showAccountsPage(account.id) else showAccountSettings("avatar")
        }) { rows, dialog ->
            rows.addView(accountColorRow("Couleur d’ornement", account.ornamentColorValue(), accountOrnamentLabel(account)) {
                dialog.dismiss(); showAccountOrnaments(account) {
                    val current = store.accounts().firstOrNull { it.id == store.resolveAccountId(account.id) }
                    if (current != null) showAccountAvatars(current, onBack = onBack)
                }
            }.apply { tag = "ornament" }, LinearLayout.LayoutParams(-1, dp(62)))
            listOf("avatar" to "Avatar : initiale et couleur", "smileys" to "Smileys", "famille" to "Famille", "animaux" to "Animaux").forEach { (group, label) ->
                rows.addView(settingsPanelRow(label) {
                    dialog.dismiss(); showAccountAvatarGroup(account, group, label, onBack)
                }.apply { tag = group }, LinearLayout.LayoutParams(-1, dp(49)))
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun accountOrnamentLabel(account: IptvAccount): String {
        val colors = MainActivity.themeColorChoices()
        return colors.indices.firstOrNull { MainActivity.themeColor(it) == account.ornamentColorValue() }
            ?.let { colors[it] } ?: "Jaune moutarde"
    }

    private fun showAccountOrnaments(account: IptvAccount, onBack: (() -> Unit)? = null): Unit =
        settingsPanel("Couleur d’ornement", onBack = {
            if (onBack != null) onBack() else showAccountAvatars(account)
        }) { rows, dialog ->
            MainActivity.themeColorChoices().forEachIndexed { index, label ->
                val color = MainActivity.themeColor(index)
                val hex = String.format(Locale.ROOT, "#%06X", color and 0xffffff)
                val row = accountColorRow(label, color, if (account.ornamentColorValue() == color) "Sélectionnée" else "") {
                    updateAccount(dialog, "avatar", onSaved = {
                        val current = store.accounts().firstOrNull { it.id == store.resolveAccountId(account.id) }
                        if (current != null) showAccountOrnaments(current, onBack)
                    }) {
                        val current = store.accounts().firstOrNull { it.id == store.resolveAccountId(account.id) }
                        check(current != null) { "Ce compte n’existe plus." }
                        store.saveAccount(current.copy(ornamentColor = hex))
                    }
                }.apply { tag = hex }
                rows.addView(row, LinearLayout.LayoutParams(-1, dp(62)))
            }
            rows.findViewWithTag<View>(account.ornamentColor.uppercase(Locale.ROOT)) ?: rows.getChildAt(0)
        }

    private fun showAccountAvatarGroup(account: IptvAccount, group: String, title: String, onBack: (() -> Unit)? = null): Unit =
        settingsPanel(title, onBack = { showAccountAvatars(account, group, onBack) }) { rows, dialog ->
            val entries = avatarCatalog.filter { it.group == group }
            if (entries.isEmpty()) {
                rows.addView(text(if (avatarCatalogLoading || !avatarCatalogLoaded) "Chargement des avatars du NAS…"
                    else avatarCatalogError.ifBlank { "Aucun avatar dans ce groupe." }, 14).apply {
                    setPadding(dp(16), dp(12), dp(16), dp(16))
                })
                val retry = settingsPanelRow("Réessayer") { loadAccountAvatarCatalog() }
                rows.addView(retry, LinearLayout.LayoutParams(-1, dp(49)))
                avatarCatalogUpdated = { if (dialog.isShowing) { dialog.dismiss(); showAccountAvatarGroup(account, group, title, onBack) } }
                if (!avatarCatalogLoading && !avatarCatalogLoaded) loadAccountAvatarCatalog()
                retry
            } else {
                entries.forEach { entry ->
                    val row = accountSettingsRow(account.copy(name = entry.name, avatar = entry.id),
                        if (account.avatar == entry.id || entry.emoji.isNotBlank() && account.avatar == entry.emoji) "Sélectionné" else "",
                        avatarName = if (entry.initialColor() != null) account.name else entry.name) {
                        updateAccount(dialog, "avatar", onSaved = if (onBack == null) null else ({
                            val current = store.accounts().firstOrNull { it.id == store.resolveAccountId(account.id) }
                            if (current != null) showAccountAvatars(current, group, onBack)
                        })) {
                            val current = store.accounts().firstOrNull { it.id == store.resolveAccountId(account.id) }
                            check(current != null) { "Ce compte n’existe plus." }
                            store.saveAccount(current.copy(avatar = entry.id))
                        }
                    }.apply { tag = entry.id }
                    rows.addView(row, LinearLayout.LayoutParams(-1, dp(62)))
                }
                rows.findViewWithTag<View>(avatarCatalog.firstOrNull { it.id == account.avatar || it.emoji.isNotBlank() && it.emoji == account.avatar }?.id)
                    ?: rows.getChildAt(0)
            }
        }

    private fun updateAccount(dialog: Dialog, focusKey: String = "selected", onSaved: (() -> Unit)? = null, action: () -> Unit) {
        val previous = store.selectedAccount()?.id
        if (focusKey != "avatar") stopPreview(true)
        async(action, version = null, failure = { if (dialog.isShowing) toast(it) }) {
            if (dialog.isShowing) {
                dialog.dismiss(); refreshAccountSidebar()
                if (accountPage != null || store.selectedAccount() == null) {
                    showAccountsPage()
                    onSaved?.invoke()
                }
                else {
                    if (store.selectedAccount()?.id != previous) displaySelectedAccount()
                    if (onSaved != null) onSaved() else showAccountSettings(focusKey)
                }
            }
        }
    }

    private fun settingsPanelRow(title: String, subtitle: String = "", action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            contentDescription = listOf(title, subtitle).filter(String::isNotBlank).joinToString(" · ")
            val labels = LinearLayout(this@IptvActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 14).apply { setTextColor(themeForeground) })
                if (subtitle.isNotBlank()) addView(text(subtitle, 12).apply {
                    setTextColor(surface(.68f))
                    setPadding(0, dp(4), 0, 0)
                })
            }
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply {
                    setColor(if (focused) accent else Color.TRANSPARENT)
                    cornerRadius = dp(6).toFloat()
                    if (focused && focusNeedsOutline) setStroke(dp(1), focusOutlineColor())
                }
                for (index in 0 until labels.childCount) (labels.getChildAt(index) as TextView)
                    .setTextColor(if (focused) contrast else if (index == 0) themeForeground else surface(.68f))
                tintSettingsControls(this, focused)
            }
            setOnClickListener { action() }
            setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (contextPanel != null) contextPanel?.cancel() else sourcePage?.cancel()
                    true
                } else false
            }
        }

    private fun tintSettingsControls(row: ViewGroup, focused: Boolean) {
        val foreground = if (focused) contrast else themeForeground
        val choices = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(if (focused) contrast else accentTextColor(), if (focused) contrast else surface(.68f)))
        for (index in 0 until row.childCount) when (val child = row.getChildAt(index)) {
            is Switch -> {
                child.thumbTintList = choices
                child.trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ColorUtils.blendARGB(if (focused) accent else themeBackground,
                        if (focused) contrast else accentTextColor(), .45f),
                        if (focused) ColorUtils.blendARGB(accent, contrast, .25f) else surface(.32f)))
            }
            is RadioButton -> child.buttonTintList = choices
            is ImageView -> child.setColorFilter(foreground)
            is TextView -> if (child.tag == "playlist-status") {
                child.setTextColor(if (focused) contrast else if (child.isActivated) this.contrast else themeForeground)
                child.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (child.isActivated && !focused) accent else Color.TRANSPARENT)
                    setStroke(dp(1), if (focused) contrast else if (child.isActivated) accent else surface(.68f))
                }
            }
            else -> if (child.tag == "settings-menu-icon") {
                child.background?.colorFilter = android.graphics.PorterDuffColorFilter(foreground, android.graphics.PorterDuff.Mode.SRC_IN)
                child.invalidate()
            }
        }
    }

    private fun settingsInput() = EditText(this).apply {
        setTextColor(themeForeground)
        backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        highlightColor = ColorUtils.setAlphaComponent(accent, 85)
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = GradientDrawable().apply {
            setColor(accent); setSize(dp(2), dp(26))
        }
    }

    private fun showPlaylistsSettings(focusKey: String = ""): Unit {
        val scope = IptvRequestScope(IptvHttp.client)
        settingsPanel("Listes de lecture", onBack = { showSettingsMenu("playlists") }, onDismiss = { scope.cancel() }) { rows, dialog ->
            val countLabels = mutableMapOf<String, Pair<LinearLayout, TextView>>()
            orderedSources(includeDisabled = true).forEach { source ->
                val title = playlistCountLabel(source, playlistCounts[source.id].orEmpty())
                val row = settingsPanelRow(source.name, title) { dialog.dismiss(); sourceActions(source) }.apply {
                    tag = source.id; minimumHeight = dp(72)
                }
                val labels = row.getChildAt(0) as LinearLayout
                countLabels[source.id] = row to (labels.getChildAt(1) as TextView)
                row.addView(text(if (store.playlistEnabled(source.id)) "✓" else "", 18, true).apply {
                    tag = "playlist-status"; gravity = Gravity.CENTER; isActivated = store.playlistEnabled(source.id)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, 0, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) })
                row.contentDescription = "${source.name} · ${if (store.playlistEnabled(source.id)) "Activée" else "Désactivée"} · $title"
                rows.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            rows.addView(settingsPanelRow("Ajouter une liste de lecture") {
                dialog.dismiss(); chooseSourceType(onCancel = { showPlaylistsSettings("add") })
            }.apply { tag = "add" }, LinearLayout.LayoutParams(-1, dp(45)))
            rows.addView(settingsPanelRow(if (playlistUpdateRunning) "Mise à jour des listes de lecture…" else "Mettre à jour toutes les listes de lecture") {
                updatePlaylists()
            }.apply {
                tag = "update"
                isEnabled = orderedSources().isNotEmpty() && !playlistUpdateRunning; isFocusable = isEnabled
                if (!isEnabled) ((getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(surface(.4f))
            }, LinearLayout.LayoutParams(-1, dp(62)))
            rows.addView(settingsPanelRow("Tri des listes de lecture",
                if (store.setting("sourceSort", "name") == "manual") "Manuellement" else "Par nom") {
                dialog.dismiss(); showPlaylistSort()
            }.apply { tag = "sort" }, LinearLayout.LayoutParams(-1, dp(62)))
            loadPlaylistCounts(scope, dialog, countLabels)
            rows.findViewWithTag<View>(focusKey)?.takeIf { it.isFocusable } ?: rows.getChildAt(0)
        }
    }

    private fun playlistCountLabel(source: IptvSource, counts: Map<ContentType, Int>): String =
        listOf(ContentType.LIVE to "Chaînes", ContentType.MOVIE to "films", ContentType.SERIES to "séries")
            .filter { source.includes(it.first) }.joinToString(", ") { (type, label) -> "$label : ${counts[type]?.toString() ?: "—"}" }

    private fun loadPlaylistCounts(scope: IptvRequestScope, panel: Dialog, labels: Map<String, Pair<LinearLayout, TextView>>) {
        val snapshot = orderedSources(includeDisabled = true)
        fun publish(source: IptvSource, counts: Map<ContentType, Int>) = handler.post {
            if (!panel.isShowing || isDestroyed || sources.none { it == source }) return@post
            playlistCounts[source.id] = counts
            labels[source.id]?.let { (row, label) ->
                label.text = playlistCountLabel(source, counts)
                row.contentDescription = "${source.name} · ${if (store.playlistEnabled(source.id)) "Activée" else "Désactivée"} · ${label.text}"
            }
        }
        playlistWork.execute {
            runCatching { IptvHttp.withScope(scope) {
                // Publish stored totals for every list before asking any provider for missing totals.
                val cached = snapshot.associate { source -> source.id to store.playlistCounts(source.id).also { publish(source, it) } }
                if (playlistUpdateRunning) return@withScope
                snapshot.filter { store.playlistEnabled(it.id) }.forEach { source ->
                    scope.checkActive()
                    val counts = cached[source.id].orEmpty().toMutableMap()
                    val missing = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).filter { source.includes(it) && it !in counts }
                    if (missing.isNotEmpty()) {
                        val connection = providerFor(store.connectionSource(source))
                        missing.forEach { type ->
                            scope.checkActive()
                            runCatching { connection.items(type, "", 0) }.getOrNull()?.let { page ->
                                val total = page.total ?: page.items.size.takeIf { page.nextPage == null }
                                if (total != null) {
                                    counts[type] = total; store.cachePlaylistCount(source.id, type, total)
                                    publish(source, counts.toMap())
                                }
                            }
                        }
                    }
                }
            } }
        }
    }

    private fun showPlaylistSort(focusKey: String? = null): Unit =
        settingsPanel("Tri des listes de lecture", onBack = { showPlaylistsSettings() }) { rows, dialog ->
            val mode = store.setting("sourceSort", "name")
            listOf("name" to "Par nom", "manual" to "Manuellement").forEach { (key, label) ->
                val row = settingsPanelRow(label) {
                    async({ store.setSetting("sourceSort", key) }) {
                        if (section in 1..3) buildGroups()
                        showPlaylistSort(key)
                    }
                }.apply { tag = key }
                row.addView(RadioButton(this).apply {
                    isChecked = mode == key; isClickable = false; isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    buttonTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accentTextColor(), surface(.68f)))
                }, 0, LinearLayout.LayoutParams(dp(40), dp(40)))
                rows.addView(row, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.addView(settingsPanelRow("Réorganiser les listes de lecture") {
                dialog.dismiss()
                reorderPanel("Réorganiser les listes de lecture", orderedSources(includeDisabled = true).map { it.id to it.name },
                    onCancel = { showPlaylistSort("reorder") }) { ids ->
                    async({ store.setSetting("sourceOrder", iptvJson.encodeToString(ids)) }) {
                        if (section in 1..3) buildGroups()
                        showPlaylistSort("reorder")
                    }
                }
            }.apply {
                tag = "reorder"
                isEnabled = mode == "manual" && sources.size > 1; isFocusable = isEnabled
                if (!isEnabled) ((getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(surface(.4f))
            }, LinearLayout.LayoutParams(-1, dp(45)))
            rows.findViewWithTag<View>(focusKey ?: mode)?.takeIf { it.isFocusable } ?: rows.getChildAt(0)
        }

    private fun checkPlaylistUpdates() {
        if (!ready || !accountChosen || playlistUpdateRunning || playlistUpdateChecking || System.currentTimeMillis() < playlistNextCheck) return
        playlistUpdateChecking = true
        val startup = playlistStartupPending
        val now = System.currentTimeMillis()
        playlistStartupPending = false; playlistNextCheck = now + 60_000
        async({
            val settings = store.settingsSnapshot().first
            store.sources().filter { source ->
                if (!IptvPlaylistSettings.enabled(settings, source.id)) false else {
                    val lastUpdate = store.playlistUpdatedAt(source.id)
                    // Existing installations begin their interval here, without downloading every list on opening IPTV.
                    if (lastUpdate == 0L) store.markPlaylistUpdated(source.id)
                    IptvPlaylistSettings.updateDue(settings, source.id, lastUpdate.takeIf { it > 0 } ?: now, now, startup)
                }
            }
        }, version = null, failure = { playlistUpdateChecking = false }) { due ->
            playlistUpdateChecking = false
            if (activityResumed && due.isNotEmpty()) updatePlaylists(due, automatic = true)
        }
    }

    private fun updatePlaylists(values: List<IptvSource> = orderedSources(), selectedId: String? = null, automatic: Boolean = false) {
        if (playlistUpdateRunning || values.isEmpty()) return
        playlistUpdateRunning = true
        val snapshot = values.toList()
        val scope = IptvRequestScope(IptvHttp.client)
        playlistUpdateScope = scope
        if (!automatic) {
            if (selectedId == null) showPlaylistsSettings("update")
            else sources.firstOrNull { it.id == selectedId }?.let { showPlaylistSettings(it, "update") }
        }
        val origin = contextPanel
        playlistWork.execute {
            val completed = mutableListOf<String>()
            var failed = 0
            try { runCatching {
                IptvHttp.withScope(scope) { snapshot.forEach { source ->
                    scope.checkActive()
                    if (store.sources().none { it == source } || automatic && !store.playlistEnabled(source.id)) return@forEach
                    runCatching {
                        val connection = providerFor(store.connectionSource(source))
                        listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).filter(source::includes).forEach { type ->
                            store.cacheCategories(source.id, type, connection.categories(type))
                            val seen = hashSetOf<String>()
                            var total: Int? = null
                            connection.loadCatalogue(type, "") { page, batch ->
                                scope.checkActive()
                                check(store.sources().any { it == source }) { "Les paramètres de la liste de lecture ont changé." }
                                seen.addAll(batch.items.map { it.id }); batch.total?.let { total = it }
                                store.cache(source.id, type, "", page, batch.items, total = batch.total)
                            }
                            store.cachePlaylistCount(source.id, type, total ?: seen.size)
                        }
                        store.clearEpgCache(source.id); store.markPlaylistUpdated(source.id); completed += source.id
                    }.onFailure { failed++ }
                } }
            }.onFailure { if (!isDestroyed) failed++ }
            } finally {
                handler.post {
                    playlistUpdateRunning = false; playlistUpdateScope = null
                    if (!isDestroyed && !isFinishing) {
                        completed.forEach { playlistCounts.remove(it); invalidateProviders(it) }
                        if (completed.isNotEmpty()) { pendingSyncedRefresh = true; playlistCatalogueChanged = true }
                        if (failed > 0) playlistNextCheck = System.currentTimeMillis() + 5 * 60_000
                        if (!automatic || failed > 0) toast(if (failed > 0) "$failed liste(s) n’ont pas pu être mises à jour." else "Listes de lecture mises à jour.")
                        if (!automatic && contextPanel === origin && origin?.isShowing == true) {
                            if (selectedId == null) showPlaylistsSettings("update")
                            else sources.firstOrNull { it.id == selectedId }?.let { showPlaylistSettings(it, "update") } ?: showPlaylistsSettings()
                        }
                    }
                }
            }
        }
    }

    private fun settingsPanelToggle(rows: LinearLayout, key: String, title: String, default: Boolean,
        checked: Boolean? = null, subtitle: String = "", save: ((Boolean) -> Unit)? = null,
        changed: (Boolean) -> Unit = {}): LinearLayout {
        val control = Switch(this).apply {
            isChecked = checked ?: store.booleanSetting(key, default); isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentTextColor(), surface(.74f)))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentTextColor(), surface(.32f)))
        }
        var saving = false
        lateinit var row: LinearLayout
        row = settingsPanelRow(title, subtitle) {
            if (saving) return@settingsPanelRow
            val checked = !control.isChecked
            // Keep the focused row enabled while preventing duplicate saves.
            saving = true
            // Group saves remain valid when hiding the current group reloads the catalogue.
            async({ if (save != null) save(checked) else store.setSetting(key, checked.toString()) },
                version = if (save != null) null else requestVersion, failure = {
                    saving = false; if (row.isAttachedToWindow) toast(it)
                }) {
                saving = false
                if (row.isAttachedToWindow) {
                    control.isChecked = checked
                    tintSettingsControls(row, row.hasFocus()); changed(checked)
                }
            }
        }.apply {
            tag = key; addView(control, LinearLayout.LayoutParams(-2, -2))
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, node: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, node)
                    node.className = Switch::class.java.name; node.isCheckable = true; node.isChecked = control.isChecked
                }
            }
        }
        row.minimumHeight = dp(if (title.length > 95) 78 else if (title.length > 55) 62 else 45)
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun settingsRadioRow(title: String, checked: Boolean, action: () -> Unit) = settingsPanelRow(title, action = action).apply {
        addView(RadioButton(this@IptvActivity).apply {
            isChecked = checked; isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentTextColor(), surface(.68f)))
        }, 0, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun showAppearanceSettings(focusKey: String = "guide"): Unit =
        settingsPanel("Apparence", onBack = { showSettingsMenu("appearance") }) { rows, dialog ->
            fun row(key: String, title: String, subtitle: String = "", action: (() -> Unit)? = null) {
                rows.addView(settingsPanelRow(title, subtitle) { action?.invoke() }.apply {
                    tag = key; isFocusable = action != null; isClickable = action != null
                }, LinearLayout.LayoutParams(-1, dp(if (subtitle.isBlank()) 45 else 62)))
            }
            row("guide", "Guide TV") { dialog.dismiss(); showGuideSettings() }
            row("player", "Lecteur") { dialog.dismiss(); showPlayerAppearance() }
            row("groups", "Groupes") { dialog.dismiss(); showGroupAppearance() }
            row("logos", "Logos") { dialog.dismiss(); showLogoSettings() }
            row("language", "Langue", "Système")
            val textChoices = listOf("small" to "Petite", "medium" to "Moyenne", "large" to "Grande", "extraLarge" to "Très Grande")
            row("textSize", "Taille du texte", textChoices.firstOrNull { it.first == store.setting("textSize", "medium") }?.second ?: "Moyenne") {
                settingsPanel("Taille du texte", onBack = { showAppearanceSettings("textSize") }) { options, choiceDialog ->
                    textChoices.forEach { (value, label) ->
                        options.addView(settingsRadioRow(label, store.setting("textSize", "medium") == value) {
                            async({ store.setSetting("textSize", value) }) {
                                applyTextSize(); choiceDialog.dismiss(); showAppearanceSettings("textSize")
                            }
                        }.apply { tag = value }, LinearLayout.LayoutParams(-1, dp(45)))
                    }
                    options.findViewWithTag<View>(store.setting("textSize", "medium"))
                }
            }
            row("colors", "Couleurs du thème", "${themeColorLabel("themeBackground")} • ${themeColorLabel("themeAccent")}") {
                dialog.dismiss(); showThemeColors()
            }
            row("transparency", "Transparence de l'interface utilisateur, %", "${store.intSetting("interfaceTransparency", 50).coerceIn(0, 100)} %") {
                dialog.dismiss(); showInterfaceTransparency()
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private data class ThemeColorChoice(val key: String, val label: String, val color: Int,
        val background: Int? = null, val backgroundDark: Int? = null, val backgroundRaised: Int? = null) {
        fun colorFor(setting: String) = if (setting == "themeBackground") background ?: color else color
    }

    private fun themePalette(): List<ThemeColorChoice> {
        val home = MainActivity.themeColorChoices().mapIndexed { index, label ->
            ThemeColorChoice(index.toString(), label, MainActivity.themeColor(index))
        }
        fun tivimateColor(key: String, label: String, accent: Long, background: Long, dark: Long, raised: Long) =
            ThemeColorChoice("tivimate:$key", "$label (TiviMate)", accent.toInt(),
                background.toInt(), dark.toInt(), raised.toInt())
        // TiviMate 5.3.3 resources: backgrounds have their own dark/raised shades, separate from accents.
        val tivimate = listOf(
            tivimateColor("amber", "Ambre", 0xFFFFC107, 0xFF58491D, 0xFF3A3113, 0xFF756226),
            tivimateColor("black", "Noir", 0xFF000000, 0xFF131313, 0xFF060606, 0xFF202020),
            tivimateColor("blue", "Bleu", 0xFF2196F3, 0xFF1F415C, 0xFF142B3D, 0xFF29577B),
            tivimateColor("blueGrey", "Bleu-gris", 0xFF607D8B, 0xFF2C333C, 0xFF1C232C, 0xFF3C434C),
            tivimateColor("brown", "Marron", 0xFF795548, 0xFF362923, 0xFF201913, 0xFF4C3933),
            tivimateColor("cyan", "Cyan", 0xFF00BCD4, 0xFF174247, 0xFF0F2C2F, 0xFF1F585F),
            tivimateColor("dark", "Sombre", 0xFF232629, 0xFF232629, 0xFF131619, 0xFF333639),
            tivimateColor("darkGrey", "Gris foncé", 0xFF262626, 0xFF262626, 0xFF161616, 0xFF363636),
            tivimateColor("green", "Vert", 0xFF4CAF50, 0xFF194C1B, 0xFF103312, 0xFF216624),
            tivimateColor("grey", "Gris", 0xFF9E9E9E, 0xFF363636, 0xFF262626, 0xFF464646),
            tivimateColor("indigo", "Indigo", 0xFF3F51B5, 0xFF1F295F, 0xFF151B3F, 0xFF2A377F),
            tivimateColor("lime", "Citron", 0xFFCDDC39, 0xFF575C1F, 0xFF3A3D14, 0xFF747B29),
            tivimateColor("orange", "Orange", 0xFFFF9800, 0xFF553E1C, 0xFF392912, 0xFF725325),
            tivimateColor("pink", "Rose", 0xFFE91E63, 0xFF642138, 0xFF431625, 0xFF862D4A),
            tivimateColor("purple", "Violet", 0xFF9C27B0, 0xFF4E1D57, 0xFF34133A, 0xFF692674),
            tivimateColor("red", "Rouge", 0xFFF44336, 0xFF6B2523, 0xFF471817, 0xFF8F312F),
            tivimateColor("teal", "Sarcelle", 0xFF009688, 0xFF164440, 0xFF0E2D2A, 0xFF1D5B55),
            ThemeColorChoice("tivimate:white", "Blanc (TiviMate)", Color.WHITE),
            tivimateColor("yellow", "Jaune", 0xFFFFEB3B, 0xFF696223, 0xFF464117, 0xFF8D822F))
        // Keep every persisted key, including choices sharing an RGB value with JojoHome.
        return home + tivimate
    }

    private fun themeColorChoice(key: String): ThemeColorChoice? {
        val selected = pendingThemeColors[key]?.second ?: store.setting(key, "home")
        return themePalette().firstOrNull { it.key == selected }
    }

    private fun themeColorLabel(key: String): String = themeColorChoice(key)?.label
        ?: if (key == "themeSelectedText") "Automatique" else "JojoHome"

    private fun themeColorTitle(key: String): String = when (key) {
        "themeBackground" -> "Couleur d’arrière-plan"
        "themeAccent" -> "Couleur de surbrillance"
        else -> "Couleur du texte sélectionné"
    }

    private fun showThemeColors(focusKey: String = "themeBackground"): Unit =
        settingsPanel("Couleurs du thème", onBack = { showAppearanceSettings("colors") }) { rows, dialog ->
            listOf("themeBackground", "themeAccent", "themeSelectedText").forEach { key ->
                rows.addView(settingsPanelRow(themeColorTitle(key), themeColorLabel(key)) {
                    dialog.dismiss(); showThemePalette(key)
                }.apply { tag = key }, LinearLayout.LayoutParams(-1, dp(62)))
            }
            rows.findViewWithTag<View>(focusKey)
        }

    private fun showThemePalette(key: String): Unit =
        settingsPanel(themeColorTitle(key),
            onBack = { showThemeColors(key) }) { rows, dialog ->
            val selected = themeColorChoice(key)?.key ?: "home"
            val fallback = ThemeColorChoice("home", if (key == "themeSelectedText") "Automatique" else "Suivre JojoHome",
                if (key == "themeBackground") homeBackground else if (key == "themeSelectedText") accentTextColor() else homeAccent)
            (listOf(fallback) + themePalette()).forEach { choice ->
                val row = settingsRadioRow(choice.label, selected == choice.key) {
                    val change = ++themeColorVersion to choice.key
                    pendingThemeColors[key] = change
                    // Paint immediately; persisting the library must not delay the selected color.
                    applyTheme(); dialog.dismiss(); showThemeColors(key)
                    val panel = contextPanel
                    libraryWork.execute {
                        val saved = runCatching { store.setSetting(key, choice.key) }
                        handler.post {
                            if (isDestroyed || isFinishing || pendingThemeColors[key] != change) return@post
                            pendingThemeColors.remove(key)
                            if (saved.isFailure) {
                                applyTheme()
                                if (contextPanel === panel) showThemeColors(key)
                                toast("Impossible d’enregistrer la couleur.")
                            }
                        }
                    }
                }.apply { tag = choice.key }
                row.addView(View(this).apply {
                    background = shape(choice.colorFor(key)).apply { setStroke(dp(1), surface(.55f)) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(12); marginEnd = dp(8) })
                rows.addView(row, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.findViewWithTag<View>(selected)
        }

    private fun showInterfaceTransparency(): Unit =
        settingsPanel("Transparence de l’interface, %", onBack = { showAppearanceSettings("transparency") }) { rows, dialog ->
            val selected = store.intSetting("interfaceTransparency", 50).coerceIn(0, 100)
            playbackSettingsNote(rows, "0 % : fond opaque. 100 % : fond transparent sur la vidéo. Le focus reste visible.")
            ((0..100 step 5).toList() + selected).distinct().sorted().forEach { value ->
                rows.addView(settingsRadioRow("$value %", selected == value) {
                    async({ store.setSetting("interfaceTransparency", value.toString()) }) {
                        applyTheme(); dialog.dismiss(); showAppearanceSettings("transparency")
                    }
                }.apply { tag = value.toString() }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.findViewWithTag<View>(selected.toString())
        }

    private fun showLogoSettings(): Unit = settingsPanel("Logos", onBack = { showAppearanceSettings("logos") }) { rows, _ ->
        settingsPanelToggle(rows, "showLogos", "Afficher les logos du fournisseur", true) { if (guideActive) renderGuide() }
        rows.addView(settingsPanelRow("Vider le cache des logos") { bitmaps.evictAll(); toast("Cache des logos vidé.") }, LinearLayout.LayoutParams(-1, dp(45)))
        playbackSettingsNote(rows, "Les logos de la playlist ou du portail sont prioritaires.")
        rows.getChildAt(0)
    }

    private fun showRemoteSettings(focusKey: String = "guide"): Unit = settingsPanel("Télécommande", onBack = { showSettingsMenu("remote") }) { rows, _ ->
        listOf("guide" to "Guide TV", "player" to "Lecteur").forEach { (key, title) ->
            rows.addView(settingsPanelRow(title) { showRemoteButtons(key == "player") }.apply { tag = key }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        playerAppearanceSection(rows, "Options de déplacement")
        playbackSettingsNote(rows, "Ces options seront disponibles avec la lecture Replay des fournisseurs IPTV.")
        listOf(
            "Utiliser RW/FF/Pause pour se déplacer/mettre en pause pendant la lecture d’un Replay",
            "Utiliser RW pour rembobiner le flux d’une chaîne en direct avec Replay",
            "Utiliser Gauche/Droite pour se déplacer pendant la lecture d’un Replay",
            "Utiliser Gauche pour rembobiner le flux d’une chaîne en direct avec Replay",
            "Utiliser Bas/Haut pour se déplacer pendant la lecture d’un Replay",
            "Utiliser Bas pour rembobiner le flux d’une chaîne en direct avec Replay"
        ).forEach { title ->
            rows.addView(settingsPanelRow(title, "Indisponible sans la lecture Replay") { toast("La lecture Replay n’est pas encore disponible dans ce module.") }.apply {
                alpha = .4f
                val focusStyle = onFocusChangeListener
                setOnFocusChangeListener { view, focused -> alpha = if (focused) 1f else .4f; focusStyle?.onFocusChange(view, focused) }
                addView(Switch(this@IptvActivity).apply { isEnabled = false; isClickable = false; isFocusable = false }, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
    }

    private fun showRemoteButtons(player: Boolean, focusKey: String = "ok"): Unit =
        settingsPanel(if (player) "Lecteur" else "Guide TV", onBack = { showRemoteSettings(if (player) "player" else "guide") }) { rows, _ ->
            IptvRemote.buttons(player).forEach { key ->
                val action = IptvRemote.action(key, player, store.setting(key.setting(player), key.action.name))
                rows.addView(settingsPanelRow(key.label, action.label) { showRemoteActions(player, key) }.apply {
                    tag = key.id; minimumHeight = dp(62)
                }, LinearLayout.LayoutParams(-1, -2))
            }
            if (player) playerAppearanceChoice(rows, "seekSeconds", "Avance et retour rapides dans le lecteur", "10",
                listOf("5" to "5 secondes", "10" to "10 secondes", "30" to "30 secondes", "60" to "60 secondes"),
                back = { showRemoteButtons(true, "seekSeconds") })
            else playerAppearanceChoice(rows, "channelClick", "OK sur une chaîne", "preview",
                listOf("preview" to "Aperçu, puis plein écran", "fullscreen" to "Plein écran directement"),
                back = { showRemoteButtons(false, "channelClick") })
            rows.addView(settingsPanelRow("Restaurer les paramètres par défaut") {
                async({ store.setSettings(IptvRemote.defaults(player)) }) { showRemoteButtons(player, "defaults") }
            }.apply { tag = "defaults" }, LinearLayout.LayoutParams(-1, -2))
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showRemoteActions(player: Boolean, key: IptvRemote.Button): Unit =
        settingsPanel(key.label, onBack = { showRemoteButtons(player, key.id) }) { rows, dialog ->
            val selected = IptvRemote.action(key, player, store.setting(key.setting(player), key.action.name))
            IptvRemote.actions(player).forEach { action ->
                rows.addView(settingsRadioRow(action.label, action == selected) {
                    async({ store.setSetting(key.setting(player), action.name) }) { dialog.dismiss(); showRemoteButtons(player, key.id) }
                }.apply {
                    tag = action.name; minimumHeight = dp(45)
                    isEnabled = action.available; isFocusable = action.available; alpha = if (action.available) 1f else .4f
                    if (!action.available) contentDescription = "${action.label} · Indisponible dans ce module"
                }, LinearLayout.LayoutParams(-1, -2))
            }
            playbackSettingsNote(rows, "Les actions grisées correspondent à des fonctions TiviMate encore absentes de JojoHome.")
            rows.findViewWithTag<View>(selected.name) ?: rows.getChildAt(0)
        }

    private fun companionNasUrl(): String = iptvCompanionNasUrl(getSharedPreferences("home_settings", MODE_PRIVATE)
        .getString("nas_url", null).orEmpty())

    private fun showAboutSettings(): Unit = settingsPanel("À propos", onBack = { showSettingsMenu("about") }) { rows, dialog ->
        val accountName = store.selectedAccount()?.name?.takeIf(String::isNotBlank) ?: "Aucun compte sélectionné"
        val model = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter(String::isNotBlank).distinct().joinToString(" ")
        val deviceName = runCatching { android.provider.Settings.Global.getString(contentResolver, "device_name") }.getOrNull().orEmpty().trim()
        val device = if (deviceName.isBlank() || deviceName.equals(android.os.Build.MODEL, true)) model else "$deviceName ($model)"
        playbackSettingsNote(rows, "Jojo Home IPTV\nM3U · Xtream Codes · Stalker\nLecteur Media3 de Jojo Home")
        playbackSettingsNote(rows, "Compte : $accountName\nAppareil : $device\nVersion : ${com.jojotv.home.BuildConfig.VERSION_NAME} (${com.jojotv.home.BuildConfig.VERSION_CODE})")
        val tvUrl = companionServer?.let { server -> iptvLanAddress()?.let { "http://$it:${server.listeningPort}/" } }
        playbackSettingsNote(rows, "Companion TV : ${tvUrl ?: "indisponible"}\nCompanion NAS : ${companionNasUrl().ifBlank { "Non configuré dans Jojo Home" }}")
        val back = settingsPanelRow("Retour") { dialog.cancel() }
        rows.addView(back, LinearLayout.LayoutParams(-1, dp(45)))
        back
    }

    private fun showParentalSettings(authorized: Boolean = false, focusKey: String = "parentalEnabled") {
        if (!authorized && store.setting("pinHash", "").isNotBlank()) {
            parentalAccess("changePin", true) { showParentalSettings(true, focusKey) }; return
        }
        settingsPanel("Contrôle Parental", onBack = { showSettingsMenu("parental") }) { rows, dialog ->
            val enabled = store.booleanSetting("parentalEnabled", false)
            settingsPanelToggle(rows, "parentalEnabled", if (enabled) "On" else "Off", false)
            rows.findViewWithTag<View>("parentalEnabled").setOnClickListener {
                fun save() {
                    async({ store.setSetting("parentalEnabled", (!enabled).toString()) }) {
                        parentalUnlockedUntil = 0L; dialog.dismiss(); showParentalSettings(true)
                    }
                }
                if (!enabled && store.setting("pinHash", "").isBlank()) editPin { save() } else save()
            }
            rows.addView(settingsPanelRow("Changer le code PIN") {
                parentalAccess("changePin", true) { editPin { showParentalSettings(true, "changePin") } }
            }.apply { tag = "changePin" }, LinearLayout.LayoutParams(-1, dp(45)))
            playerAppearanceChoice(rows, "pinMethod", "Méthode de saisie du code PIN", "selector",
                listOf("selector" to "Sélectionneur", "keyboard" to "Clavier"), enabled) { showParentalSettings(true, "pinMethod") }
            playerAppearanceChoice(rows, "pinRemember", "Ne nécessite pas de code PIN après le déverrouillage", "always",
                listOf("always" to "Toujours demander", "15" to "Ne pas demander pendant 15 minutes",
                    "session" to "Ne pas demander pendant cette session"), enabled) {
                parentalUnlockedUntil = 0L; showParentalSettings(true, "pinRemember")
            }
            rows.findViewWithTag<View>("pinRemember").apply { minimumHeight = dp(62); layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT }
            settingsPanelToggle(rows, "pinChannelsOnly", "Ne nécessite pas uniquement pour les chaînes", false) { parentalUnlockedUntil = 0L }
            playerAppearanceSection(rows, "PIN requis pour")
            listOf("lockSettings" to "Paramètres", "lockPlaylists" to "Paramètres | Listes de Lecture", "lockEpg" to "Paramètres | EPG").forEach { (key, label) ->
                settingsPanelToggle(rows, key, label, false)
            }
            for (index in 0 until rows.childCount) rows.getChildAt(index).let { row ->
                if (row.tag != null && row.tag != "parentalEnabled") {
                    row.isEnabled = enabled; row.isFocusable = enabled; row.alpha = if (enabled) 1f else .4f
                    if (!enabled && row is LinearLayout) for (child in 0 until row.childCount)
                        (row.getChildAt(child) as? Switch)?.isChecked = false
                }
            }
            rows.findViewWithTag<View>(focusKey)?.takeIf { it.isEnabled } ?: rows.getChildAt(0)
        }
    }

    private fun playbackSettingsNote(rows: LinearLayout, value: String) {
        rows.addView(text(value, 12).apply {
            setTextColor(surface(.68f)); setPadding(dp(16), dp(24), dp(16), dp(16))
        })
    }

    private fun bufferChoices() = listOf("none" to "Aucun", "small" to "Petit", "medium" to "Moyen",
        "large" to "Grande", "extraLarge" to "Très Grande")

    private fun afrEnabled(live: Boolean): Boolean = store.booleanSetting(if (live) "afrLive" else "afrVod",
        store.setting("afr", "OFF") in listOf("START", "START_STOP"))

    private fun showAdvancedSettings(focusKey: String = "search"): Unit =
        settingsPanel("Paramètres avancés", onBack = { showSettingsMenu("advanced") }) { rows, dialog ->
            listOf("search" to "Rechercher", "reminders" to "Rappels", "recording" to "Enregistrement", "vod" to "VOD").forEach { (key, title) ->
                rows.addView(settingsPanelRow(title) {
                    dialog.dismiss()
                    when (key) {
                        "search" -> showSearchSettings()
                        "reminders" -> showReminderSettings()
                        "recording" -> showRecordingSettings()
                        else -> showVodSettings()
                    }
                }.apply { tag = key }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showSearchSettings(onBack: (() -> Unit)? = null): Unit =
        settingsPanel("Rechercher", onBack = onBack ?: { showAdvancedSettings("search") }) { rows, _ ->
            settingsPanelToggle(rows, "searchHistory", "Afficher l'historique des recherches", true)
            settingsPanelToggle(rows, "searchFavoritesFirst", "Afficher d'abord les chaînes préférées", true)
            settingsPanelToggle(rows, "searchPastWithoutReplay", "Afficher les programmes antérieurs sans Replay", false)
            settingsPanelToggle(rows, "searchStay", "Restez sur l'écran de recherche lorsque vous changez de chaîne", false)
            rows.getChildAt(0)
        }

    private fun showReminderSettings(focusKey: String = "reminderBefore"): Unit =
        settingsPanel("Rappels", onBack = { showAdvancedSettings("reminders") }) { rows, _ ->
            playerAppearanceChoice(rows, "reminderBefore", "Rappeler avant le début du programme (minutes)", "0",
                listOf(0, 1, 3, 5, 10, 15, 30, 60).map { it.toString() to it.toString() }, back = { showReminderSettings("reminderBefore") })
            playerAppearanceChoice(rows, "reminderTimeout", "Délai d'expiration de la fenêtre contextuelle (secondes)", "10",
                listOf(5, 10, 15, 30, 60, 120).map { it.toString() to it.toString() }, back = { showReminderSettings("reminderTimeout") })
            playerAppearanceChoice(rows, "reminderAction", "Action par défaut", "watch",
                listOf("watch" to "Regarder", "details" to "Afficher la fiche"), back = { showReminderSettings("reminderAction") })
            settingsPanelToggle(rows, "reminderWake", "Sortir du mode veille", false)
            val wake = rows.getChildAt(rows.childCount - 1) as LinearLayout
            (wake.getChildAt(0) as LinearLayout).addView(text("Peut ne pas fonctionner sur tous les appareils", 12).apply {
                setTextColor(surface(.68f))
            })
            for (index in 0 until rows.childCount) rows.getChildAt(index).apply {
                minimumHeight = dp(62); layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showRecordingSettings(focusKey: String = "recordingFolder"): Unit =
        settingsPanel("Enregistrement", onBack = {
            if (section == 4) { recordingsSignature = ""; refreshRecordings() } else showAdvancedSettings("recording")
        }) { rows, dialog ->
            rows.addView(settingsPanelRow("Dossier des enregistrements", IptvRecordingScheduler.rootPath(this)) {
                dialog.dismiss(); showRecordingFolder()
            }.apply { tag = "recordingFolder" }, LinearLayout.LayoutParams(-1, -2))
            playerAppearanceChoice(rows, "recordingBefore", "Démarrer l'enregistrement avant le début du programme (minutes)", "0",
                listOf(0, 1, 2, 3, 4, 5, 10, 15, 20, 30).map { it.toString() to it.toString() }, back = { showRecordingSettings("recordingBefore") })
            playerAppearanceChoice(rows, "recordingAfter", "Arrêter l'enregistrement après la fin du programme (minutes)", "0",
                listOf(0, 1, 2, 3, 4, 5, 10, 15, 20, 30).map { it.toString() to it.toString() }, back = { showRecordingSettings("recordingAfter") })
            for (index in 0 until rows.childCount) rows.getChildAt(index).apply {
                minimumHeight = dp(62); layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showRecordingFolder(path: String = IptvRecordingScheduler.rootPath(this)) {
        val page = IptvContentPage()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var draft = path
        lateinit var entry: LinearLayout
        fun update(value: String) {
            draft = value.trim()
            ((entry.getChildAt(0) as LinearLayout).getChildAt(1) as TextView).text = draft
        }
        entry = settingsPanelRow("Chemin d'accès au dossier", draft.ifBlank { "Non défini" }) {
            showSettingsTextInput("Chemin d'accès au dossier", draft) { value ->
                update(value); contextPanel?.dismiss(); entry.requestFocus()
            }
        }
        fields.addView(entry, LinearLayout.LayoutParams(-1, -2))
        val choose = settingsPanelRow("Sélectionner le dossier") {
            page.dismiss(); showRecordingStorage(draft)
        }
        fields.addView(choose, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(16) })
        actions.addView(settingsPanelRow("OK") {
            val selected = draft
            async({ IptvRecordingScheduler.setRoot(this, selected) }, failure = { toast(it) }) {
                if (sourcePage === page) { page.dismiss(); recordingsSignature = ""; showRecordingSettings() }
            }
        }, LinearLayout.LayoutParams(-1, dp(45)))
        actions.addView(settingsPanelRow("Annuler") { page.cancel() }, LinearLayout.LayoutParams(-1, dp(52)))
        showPlaylistWizard(page, "Dossier des\nenregistrements", android.R.drawable.ic_menu_agenda, fields, actions,
            onBack = { showRecordingSettings() }, initialFocus = choose,
            description = "Entrez le chemin du dossier avec les enregistrements ou sélectionnez-le")
    }

    private fun showRecordingStorage(original: String, focusSystem: Boolean = IptvRecordingScheduler.isDocument(original), focusKey: String? = null) {
        val page = IptvContentPage()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var selected = if (IptvRecordingScheduler.isNetwork(original)) original else android.os.Environment.getExternalStorageDirectory().absolutePath
        val connections = IptvRecordingScheduler.connections(this)
        var selectedConnection = if (IptvRecordingScheduler.isNetwork(selected)) IptvRecordingScheduler.connectionFor(selected, connections) else null
        fun add(title: String, path: String, connection: SmbConnectionConfig? = null) {
            val row = settingsPanelRow(title) { page.dismiss(); selectRecordingFolder(path, original, connection) }.apply { tag = path }
            val paint = row.onFocusChangeListener
            row.setOnFocusChangeListener { view, focused ->
                paint?.onFocusChange(view, focused)
                if (focused) { selected = path; selectedConnection = connection }
            }
            fields.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        }
        add("Stockage partagé interne", android.os.Environment.getExternalStorageDirectory().absolutePath)
        getExternalFilesDirs(null).filterNotNull().drop(1).forEach { folder -> add("Stockage externe", folder.absolutePath) }
        connections.forEach { connection -> add(connection.displayLabel(), connection.browserUrl(), connection) }
        actions.addView(settingsPanelRow("Sélectionner") { page.dismiss(); selectRecordingFolder(selected, original, selectedConnection) }, LinearLayout.LayoutParams(-1, dp(52)))
        val system = settingsPanelRow("Sélecteur de système") { page.dismiss(); selectSystemRecordingFolder(original) }
        actions.addView(system, LinearLayout.LayoutParams(-1, dp(62)))
        actions.addView(settingsPanelRow("Configurer SMB") {
            page.dismiss(); showRecordingSmb(original, selectedConnection)
        }.apply { tag = "smb" }, LinearLayout.LayoutParams(-1, dp(62)))
        actions.addView(settingsPanelRow("Annuler") { page.cancel() }, LinearLayout.LayoutParams(-1, dp(52)))
        val focus = focusKey?.let { fields.findViewWithTag<View>(it) ?: actions.findViewWithTag<View>(it) }
            ?: fields.findViewWithTag<View>(selected) ?: fields.getChildAt(0)
        showPlaylistWizard(page, "Stockage", android.R.drawable.ic_menu_agenda, fields, actions,
            onBack = { showRecordingFolder(original) }, initialFocus = focus,
            description = "Sélectionnez un stockage local ou un dossier partagé SMB / Freebox")
        if (focusSystem && focusKey == null) system.requestFocus()
    }

    private fun selectSystemRecordingFolder(original: String) {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 26 && IptvRecordingScheduler.isDocument(original)) {
            val tree = android.net.Uri.parse(original)
            if (android.provider.DocumentsContract.isTreeUri(tree)) picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree)))
        }
        pendingRecordingFolder = original
        try { startActivityForResult(picker, 8098) }
        catch (_: android.content.ActivityNotFoundException) {
            pendingRecordingFolder = null
            toast("Le sélecteur de système n’est pas disponible sur cet appareil.")
            showRecordingStorage(original, true)
        }
    }

    private fun returnToRecordingFolder(path: String, storage: Boolean) {
        recordingFolderResult = path to storage
        resumeRecordingFolder()
    }

    private fun resumeRecordingFolder() {
        if (!ready || !accountChosen || accountPage != null) return
        val (path, storage) = recordingFolderResult ?: return
        recordingFolderResult = null
        if (storage) showRecordingStorage(path, true) else showRecordingFolder(path)
    }

    private fun <T> readRecordingSmb(path: String, connection: SmbConnectionConfig? = null, read: (jcifs.smb.SmbFile) -> T): T {
        val normalized = try { IptvRecordingScheduler.smbPath(path, false) }
            catch (problem: Exception) {
                throw IllegalStateException("Adresse SMB invalide. Utilisez serveur/partage ou smb://serveur/partage.", problem)
            }
        val context = IptvRecordingScheduler.smbContext(this, normalized, connection)
        try { return jcifs.smb.SmbFile("$normalized/", context).use(read) }
        catch (problem: jcifs.smb.SmbAuthException) {
            throw IllegalStateException(if (problem.ntStatus == jcifs.smb.NtStatus.NT_STATUS_ACCESS_DENIED)
                "Accès au partage SMB refusé. Vérifiez les identifiants et les autorisations du compte."
                else "Identifiants SMB refusés. Saisissez le nom d’utilisateur et le mot de passe configurés sur le serveur.", problem)
        }
        catch (problem: Exception) {
            throw IllegalStateException("Connexion SMB impossible. Vérifiez le serveur et le dossier partagé.", problem)
        }
        finally { context.close() }
    }

    private fun selectRecordingFolder(path: String, original: String, connection: SmbConnectionConfig? = null,
        onBack: (() -> Unit)? = null, focusPath: String? = null) {
        val back = onBack ?: { showRecordingStorage(original, focusKey = path) }
        val loadingPage = IptvContentPage()
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cancel = settingsPanelRow("Annuler") { loadingPage.cancel() }
        actions.addView(cancel, LinearLayout.LayoutParams(-1, dp(52)))
        showPlaylistWizard(loadingPage, "Sélectionner le dossier", android.R.drawable.ic_menu_agenda,
            text("Chargement des dossiers…", 16), actions,
            onBack = back, initialFocus = cancel)
        async({
            if (IptvRecordingScheduler.isNetwork(path)) {
                readRecordingSmb(path, connection) { folder ->
                    val children = folder.listFiles().mapNotNull { child -> child.use {
                        if (it.name.trimEnd('/') != "IPC$" && it.isDirectory) it.name.trimEnd('/') to it.canonicalPath.trimEnd('/') else null
                    } }.sortedBy { it.first.lowercase(Locale.ROOT) }
                    val parent = if (folder.url.path.trim('/').isBlank()) null else folder.parent.trimEnd('/')
                    parent to children
                }
            } else {
                val folder = File(path)
                check(folder.isDirectory && folder.canRead()) { "Dossier inaccessible." }
                folder.parentFile?.absolutePath to folder.listFiles().orEmpty().filter { it.isDirectory && it.canRead() }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }.map { it.name to it.absolutePath }
            }
        }, failure = { if (loadingPage.isShowing) { toast(it); loadingPage.cancel() } }) { (parent, children) ->
            if (!loadingPage.isShowing) return@async
            loadingPage.dismiss(false)
            val page = IptvContentPage()
            val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            playbackSettingsNote(fields, android.net.Uri.decode(path))
            fun row(title: String, target: String) = fields.addView(settingsPanelRow(title) {
                page.dismiss(); selectRecordingFolder(target, original, connection,
                    onBack = { selectRecordingFolder(path, original, connection, onBack, target) })
            }.apply { tag = target }, LinearLayout.LayoutParams(-1, dp(45)))
            if (parent != null && parent != "smb:") row("Dossier parent", parent)
            children.forEach { (name, target) -> row(name, target) }
            val select = settingsPanelRow("Sélectionner") { page.dismiss(); showRecordingFolder(path) }.apply {
                isEnabled = !IptvRecordingScheduler.isNetwork(path) || java.net.URI(path.replace(" ", "%20")).path.orEmpty().trim('/').isNotBlank()
                isFocusable = isEnabled; alpha = if (isEnabled) 1f else .4f
            }
            actions.addView(select, LinearLayout.LayoutParams(-1, dp(52)))
            actions.addView(settingsPanelRow("Annuler") { page.cancel() }, LinearLayout.LayoutParams(-1, dp(52)))
            showPlaylistWizard(page, "Sélectionner\nle dossier", android.R.drawable.ic_menu_agenda, fields, actions,
                onBack = back, initialFocus = focusPath?.let { fields.findViewWithTag<View>(it) } ?: fields.getChildAt(1) ?: select)
        }
    }

    private fun showRecordingSmb(original: String, existing: SmbConnectionConfig? = null, focusSave: Boolean = false) {
        val page = IptvContentPage()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val values = linkedMapOf("Adresse du serveur" to existing?.server.orEmpty(), "Nom de la connexion" to existing?.label.orEmpty(),
            "Nom d'utilisateur" to existing?.user.orEmpty(), "Mot de passe" to existing?.password.orEmpty())
        values.keys.forEach { key ->
            lateinit var row: LinearLayout
            val value = values[key].orEmpty()
            row = settingsPanelRow(key, if (value.isBlank()) "Non défini" else if (key == "Mot de passe") "••••••••" else value) {
                showSettingsTextInput(key, values[key].orEmpty(), password = key == "Mot de passe") { value ->
                    values[key] = value
                    ((row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView).text =
                        if (value.isBlank()) "Non défini" else if (key == "Mot de passe") "••••••••" else value
                    contextPanel?.dismiss(); row.requestFocus()
                }
            }
            fields.addView(row, LinearLayout.LayoutParams(-1, dp(62)))
        }
        var connecting = false
        val save = settingsPanelRow("OK") {
            if (connecting) return@settingsPanelRow
            connecting = true
            val connection = SmbConnectionConfig(values["Nom de la connexion"], values["Adresse du serveur"], values["Nom d'utilisateur"], values["Mot de passe"])
            async({
                val path = readRecordingSmb(connection.browserUrl(), connection) { folder ->
                    check(folder.list() != null) { "Serveur SMB inaccessible." }
                    IptvRecordingScheduler.smbPath(folder.canonicalPath, false)
                }
                IptvRecordingScheduler.saveConnection(this, connection)
                path
            }, failure = { connecting = false; toast(it) }) { path ->
                connecting = false
                if (sourcePage === page) {
                    page.dismiss(); selectRecordingFolder(path, original, connection,
                        onBack = { showRecordingSmb(original, connection, true) })
                }
            }
        }
        actions.addView(save, LinearLayout.LayoutParams(-1, dp(52)))
        actions.addView(settingsPanelRow("Annuler") { page.cancel() }, LinearLayout.LayoutParams(-1, dp(52)))
        showPlaylistWizard(page, "Configurer\nSMB", R.drawable.ic_menu_open, fields, actions,
            onBack = { showRecordingStorage(original, focusKey = "smb") }, initialFocus = if (focusSave) save else fields.getChildAt(0),
            description = "Connectez un dossier partagé du NAS, de la Freebox ou d’un ordinateur. Sans authentification, laissez le nom d’utilisateur et le mot de passe vides.")
    }

    private fun showVodSettings(): Unit =
        settingsPanel("VOD", onBack = { showAdvancedSettings("vod") }) { rows, _ ->
            settingsPanelToggle(rows, "autoNextEpisode", "Lecture automatique du prochain épisode", true)
            rows.getChildAt(0)
        }

    private fun showPlaybackSettings(focusKey: String = "buffer") {
        val version = requestVersion
        lifecycleScope.launch {
            val shared = playerSettingsDataStore.playerSettings.first()
            if (isFinishing || isDestroyed || version != requestVersion) return@launch
            val nextEpisode = IptvStore.playerSettings(listOf("autoNextEpisode", "nextEpisodeThresholdMode",
                "nextEpisodeThresholdPercent", "nextEpisodeThresholdMinutesBeforeEnd")
                .associateWith { store.setting(it, "") }.filterValues(String::isNotBlank), shared, false)
            playbackSettingsPanel(focusKey, nextEpisode)
        }
    }

    private fun playbackSettingsPanel(focusKey: String, nextEpisode: PlayerSettings): Unit =
        settingsPanel("Lecteur", onBack = { showSettingsMenu("playback") }) { rows, dialog ->
            fun entry(key: String, label: String, value: String, action: () -> Unit) {
                rows.addView(settingsPanelRow(label, value) { dialog.dismiss(); action() }.apply { tag = key },
                    LinearLayout.LayoutParams(-1, dp(62)))
            }
            entry("buffer", "Mémoire Tampon", bufferChoices().firstOrNull { it.first == store.setting("bufferSize", "medium") }?.second ?: "Moyen") {
                showBufferSettings()
            }
            val live = afrEnabled(true); val vod = afrEnabled(false)
            entry("afr", "Fréquence d'images automatique (AFR)", when {
                live && vod -> "On"; live -> "Live TV"; vod -> "VOD"; else -> "Off"
            }) { showAfrSettings() }
            entry("audioLanguage", "Langue audio préférée", audioLanguageChoices(false)
                .firstOrNull { it.first == store.setting("audioLanguage", AudioLanguageOption.DEVICE) }?.second.orEmpty()) {
                showAudioLanguageSettings(false)
            }
            entry("secondaryAudioLanguage", "Langue préférée secondaire", audioLanguageChoices(true)
                .firstOrNull { it.first == store.setting("secondaryAudioLanguage", "none") }?.second.orEmpty()) {
                showAudioLanguageSettings(true)
            }
            entry("subtitles", "Sous-titres", "Langues et sélection automatique") { showSubtitleSettings() }
            settingsPanelToggle(rows, "preferSurround", "Sélectionnez la piste audio surround par défaut", false)
            settingsPanelToggle(rows, "audioPassthrough", "Audio passthrough", false)
            settingsPanelToggle(rows, "skipIntroEnabled", getString(com.jojotv.home.R.string.playback_skip_intro), true,
                subtitle = getString(com.jojotv.home.R.string.playback_skip_intro_sub))
            settingsPanelToggle(rows, "autoNextEpisode", "Lecture automatique de l’épisode suivant", true,
                checked = nextEpisode.streamAutoPlayNextEpisodeEnabled,
                subtitle = "Démarrer automatiquement l’épisode suivant quand l’invite apparaît.")
            entry("nextEpisodeThresholdMode", "Mode du seuil de l’épisode suivant",
                if (nextEpisode.nextEpisodeThresholdMode == NextEpisodeThresholdMode.PERCENTAGE) "Pourcentage" else "Minutes avant la fin") {
                showNextEpisodeThresholdMode(nextEpisode.nextEpisodeThresholdMode)
            }
            nextEpisodeThresholdRow(rows, nextEpisode)
            entry("external", "Utiliser un lecteur externe", when (store.setting("externalPlayerMode", "never")) {
                "all" -> "Oui"; "vod" -> "Pour la VOD uniquement"; else -> "Non"
            }) { showExternalPlayerSettings() }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showNextEpisodeThresholdMode(current: NextEpisodeThresholdMode): Unit =
        settingsPanel("Mode du seuil de l’épisode suivant", onBack = { showPlaybackSettings("nextEpisodeThresholdMode") }) { rows, dialog ->
            var saving = false
            listOf(Triple(NextEpisodeThresholdMode.PERCENTAGE, "Pourcentage",
                "Afficher par pourcentage de lecture en l’absence de marqueur de fin."),
                Triple(NextEpisodeThresholdMode.MINUTES_BEFORE_END, "Minutes avant la fin",
                    "Afficher ce nombre de minutes avant la fin de l’épisode en l’absence de marqueur de fin."))
                .forEach { (mode, title, description) ->
                    val row = settingsRadioRow(title, current == mode) {
                        if (!saving) {
                            saving = true
                            async({ store.setSetting("nextEpisodeThresholdMode", mode.name) }, version = null,
                                failure = { saving = false; if (dialog.isShowing) toast(it) }) {
                                if (dialog.isShowing) { dialog.dismiss(); showPlaybackSettings("nextEpisodeThresholdMode") }
                            }
                        }
                    }.apply {
                        tag = mode.name; minimumHeight = dp(86)
                        contentDescription = "$title · $description"
                    }
                    (row.getChildAt(1) as LinearLayout).addView(text(description, 12).apply {
                        setTextColor(surface(.68f)); setPadding(0, dp(4), 0, 0)
                    })
                    rows.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
            rows.findViewWithTag<View>(current.name)
        }

    private fun nextEpisodeThresholdRow(rows: LinearLayout, settings: PlayerSettings) {
        val minutes = settings.nextEpisodeThresholdMode == NextEpisodeThresholdMode.MINUTES_BEFORE_END
        val key = if (minutes) "nextEpisodeThresholdMinutesBeforeEnd" else "nextEpisodeThresholdPercent"
        val title = if (minutes) "Minutes du seuil" else "Pourcentage du seuil"
        val minimum = if (minutes) 0f else 95f
        val initial = if (minutes) settings.nextEpisodeThresholdMinutesBeforeEnd else settings.nextEpisodeThresholdPercent
        val row = settingsPanelRow(title, "Solution de repli en l’absence de marqueur de fin.") {}.apply {
            tag = key; orientation = LinearLayout.VERTICAL; minimumHeight = dp(112)
        }
        val labels = row.getChildAt(0)
        row.removeView(labels)
        val value = text("", 14).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        row.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value, LinearLayout.LayoutParams(dp(60), -2))
        }, LinearLayout.LayoutParams(-1, -2))
        val slider = SeekBar(this).apply {
            max = if (minutes) 7 else 10
            progress = kotlin.math.round((initial - minimum) * 2f).toInt().coerceIn(0, max)
            isFocusable = false; keyProgressIncrement = 1
        }
        fun currentValue() = minimum + slider.progress / 2f
        fun refreshValue() {
            val current = currentValue()
            value.text = (if (current % 1f == 0f) current.toInt().toString()
                else String.format(Locale.FRENCH, "%.1f", current)) + if (minutes) " min" else "%"
            row.contentDescription = "$title · ${value.text}"
        }
        var confirmed = slider.progress
        var revision = 0
        fun save() {
            val requested = slider.progress
            val savedValue = currentValue().toString()
            val change = ++revision
            async({ store.setSetting(key, savedValue) }, version = null, failure = {
                if (row.isAttachedToWindow) {
                    if (revision == change) slider.progress = confirmed
                    toast(it)
                }
            }) { confirmed = requested }
        }
        fun adjust(delta: Int) {
            val next = (slider.progress + delta).coerceIn(0, slider.max)
            if (next != slider.progress) { slider.progress = next; save() }
        }
        val decrease = text("−", 24).apply {
            gravity = Gravity.CENTER; setOnClickListener { adjust(-1) }; isFocusable = false
            contentDescription = "Diminuer le seuil"
        }
        val increase = text("+", 24).apply {
            gravity = Gravity.CENTER; setOnClickListener { adjust(1) }; isFocusable = false
            contentDescription = "Augmenter le seuil"
        }
        row.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(decrease, LinearLayout.LayoutParams(dp(36), dp(40)))
            addView(slider, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(increase, LinearLayout.LayoutParams(dp(36), dp(40)))
        }, LinearLayout.LayoutParams(-1, -2))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { refreshValue() }
            override fun onStartTrackingTouch(seekBar: SeekBar) { row.requestFocus() }
            override fun onStopTrackingTouch(seekBar: SeekBar) { save() }
        })
        val focusListener = row.onFocusChangeListener
        fun paint(focused: Boolean) {
            val foreground = if (focused) contrast else themeForeground
            value.setTextColor(foreground); decrease.setTextColor(foreground); increase.setTextColor(foreground)
            val tint = android.content.res.ColorStateList.valueOf(if (focused) contrast else accentTextColor())
            slider.thumbTintList = tint; slider.progressTintList = tint
            slider.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                if (focused) ColorUtils.blendARGB(accent, contrast, .25f) else surface(.32f))
        }
        row.setOnFocusChangeListener { view, focused -> focusListener?.onFocusChange(view, focused); paint(focused) }
        row.setOnKeyListener { _, code, event ->
            if (code !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
            else {
                if (event.action == KeyEvent.ACTION_DOWN) adjust(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            }
        }
        refreshValue(); paint(false)
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun audioLanguageChoices(secondary: Boolean): List<Pair<String, String>> {
        val special = if (secondary) listOf("none" to "Non définie") else listOf(
            AudioLanguageOption.DEFAULT to "Valeur par défaut du média",
            AudioLanguageOption.DEVICE to "Langue de l’appareil")
        return special + (AudioLanguageOption.ORIGINAL to "Langue originale") + mediaLanguageChoices()
    }

    private fun mediaLanguageChoices(): List<Pair<String, String>> =
        AVAILABLE_SUBTITLE_LANGUAGES.map { it.code to it.displayName }
            .sortedWith(compareBy(java.text.Collator.getInstance(Locale.FRENCH)) { it.second })

    private fun showAudioLanguageSettings(secondary: Boolean) {
        val key = if (secondary) "secondaryAudioLanguage" else "audioLanguage"
        val title = if (secondary) "Langue préférée secondaire" else "Langue audio préférée"
        val current = store.setting(key, if (secondary) "none" else AudioLanguageOption.DEVICE)
        settingsPanel(title, onBack = { showPlaybackSettings(key) }) { rows, dialog ->
            audioLanguageChoices(secondary).forEach { (code, label) ->
                rows.addView(settingsRadioRow(label, code == current) {
                    async({ store.setSetting(key, code) }) { dialog.dismiss(); showPlaybackSettings(key) }
                }.apply { tag = code }, LinearLayout.LayoutParams(-1, dp(48)))
            }
            rows.findViewWithTag<View>(current) ?: rows.getChildAt(0)
        }
    }

    private fun subtitleLanguageChoices(secondary: Boolean): List<Pair<String, String>> =
        listOf("none" to if (secondary) "Non définie" else "Désactivés") + mediaLanguageChoices()

    private fun showSubtitleSettings(focusKey: String = "subtitleLanguage") {
        val version = requestVersion
        lifecycleScope.launch {
            // Read the current profile's existing defaults without blocking the TV thread.
            val shared = playerSettingsDataStore.playerSettings.first().subtitleStyle
            if (isFinishing || isDestroyed || version != requestVersion) return@launch
            settingsPanel("Sous-titres", onBack = { showPlaybackSettings("subtitles") }) { rows, dialog ->
                fun languageRow(secondary: Boolean) {
                    val key = if (secondary) "secondarySubtitleLanguage" else "subtitleLanguage"
                    val fallback = if (secondary) shared.secondaryPreferredLanguage ?: "none" else shared.preferredLanguage
                    val current = store.setting(key, fallback)
                    val title = if (secondary) "Langue préférée secondaire" else "Langue préférée"
                    val label = subtitleLanguageChoices(secondary).firstOrNull { it.first == current }?.second ?: current
                    rows.addView(settingsPanelRow(title, label) {
                        dialog.dismiss(); showSubtitleLanguageSettings(secondary, fallback)
                    }.apply { tag = key }, LinearLayout.LayoutParams(-1, dp(62)))
                }
                languageRow(false)
                languageRow(true)
                settingsPanelToggle(rows, "subtitleUseForced", "Utiliser les sous-titres forcés", shared.useForcedSubtitles,
                    subtitle = "Préférer les sous-titres forcés lorsque l’audio correspond à la langue des sous-titres ; si indisponibles, ne rien sélectionner.")
                settingsPanelToggle(rows, "subtitleShowOnlyPreferredLanguages", "Afficher uniquement les langues préférées", shared.showOnlyPreferredLanguages,
                    subtitle = "Masquer toutes les autres langues de sous-titres dans la liste de sélection.")
                rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
            }
        }
    }

    private fun showSubtitleLanguageSettings(secondary: Boolean, fallback: String) {
        val key = if (secondary) "secondarySubtitleLanguage" else "subtitleLanguage"
        val current = store.setting(key, fallback)
        val title = if (secondary) "Langue préférée secondaire" else "Langue préférée"
        settingsPanel(title, onBack = { showSubtitleSettings(key) }) { rows, dialog ->
            subtitleLanguageChoices(secondary).forEach { (code, label) ->
                rows.addView(settingsRadioRow(label, code == current) {
                    async({ store.setSetting(key, code) }) { dialog.dismiss(); showSubtitleSettings(key) }
                }.apply { tag = code }, LinearLayout.LayoutParams(-1, dp(48)))
            }
            rows.findViewWithTag<View>(current) ?: rows.getChildAt(0)
        }
    }

    private fun showBufferSettings(): Unit =
        settingsPanel("Mémoire Tampon", onBack = { showPlaybackSettings() }) { rows, dialog ->
            val current = store.setting("bufferSize", "medium").takeIf { value -> bufferChoices().any { it.first == value } } ?: "medium"
            bufferChoices().forEach { (value, label) ->
                rows.addView(settingsRadioRow(label, current == value) {
                    async({ store.setSetting("bufferSize", value) }) { dialog.dismiss(); showPlaybackSettings() }
                }.apply { tag = value }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            playbackSettingsNote(rows, "La taille du tampon détermine la durée de la vidéo qui doit être téléchargée pour démarrer la lecture.\n" +
                "Essayez d'augmenter cette taille si vous voyez s'activer fréquemment la mise en mémoire tampon.\n" +
                "Cela peut réduire la vitesse de zapping des chaînes.")
            rows.findViewWithTag<View>(current)
        }

    private fun showAfrSettings(focusKey: String = "afrLive"): Unit =
        settingsPanel("Fréquence d'images automatique (AFR)", onBack = { showPlaybackSettings("afr") }) { rows, _ ->
            settingsPanelToggle(rows, "afrLive", "Activer l'AFR pour le Live TV", afrEnabled(true)) { showAfrSettings("afrLive") }
            settingsPanelToggle(rows, "afrVod", "Activer l'AFR pour la VOD", afrEnabled(false)) { showAfrSettings("afrVod") }
            val enabled = afrEnabled(true) || afrEnabled(false)
            settingsPanelToggle(rows, "afrRefresh", "Changer le taux de rafraîchissement de l'écran", true)
            settingsPanelToggle(rows, "afrResolution", "Changer de résolution d'écran", false)
            listOf("afrRefresh", "afrResolution").forEach { key ->
                rows.findViewWithTag<View>(key)?.apply { isEnabled = enabled; isFocusable = enabled; alpha = if (enabled) 1f else .4f }
            }
            playerAppearanceChoice(rows, "afrDelay", "Délai avant de changer, en sec", "0",
                (0..10).map { "$it" to "$it" }, enabled) { showAfrSettings("afrDelay") }
            playbackSettingsNote(rows, "Activer cette option si vous voulez que le taux de rafraîchissement de votre tv s'adapte avec celui du flux vidéo.\n" +
                "Cela peut rendre la lecture plus fluide.\nNotez que certains appareils ne sont pas compatibles.")
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showExternalPlayerSettings(focusKey: String? = null): Unit =
        settingsPanel("Utiliser un lecteur externe", onBack = { showPlaybackSettings("external") }) { rows, dialog ->
            val current = store.setting("externalPlayerMode", "never")
            listOf("all" to "Oui", "never" to "Non", "vod" to "Pour la VOD uniquement").forEach { (value, label) ->
                rows.addView(settingsRadioRow(label, current == value) {
                    fun save(playerPackage: String? = null) {
                        async({
                            if (playerPackage != null) store.setSetting("externalPlayerPackage", playerPackage)
                            store.setSetting("externalPlayerMode", value)
                        }) { dialog.dismiss(); showPlaybackSettings("external") }
                    }
                    if (value == "never" || store.setting("externalPlayerPackage", "").isNotBlank()) save()
                    else {
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("https://example.invalid/video.mp4"), "video/*")
                        val players = packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != packageName }
                            .distinctBy { it.activityInfo.packageName }
                        if (players.isEmpty()) toast("Aucun lecteur externe installé sur cette TV.")
                        else settingsPanel("Lecteur externe", onBack = { showExternalPlayerSettings(value) }) { choices, _ ->
                            players.forEach { player ->
                                choices.addView(settingsPanelRow(player.loadLabel(packageManager).toString()) {
                                    save(player.activityInfo.packageName)
                                }, LinearLayout.LayoutParams(-1, dp(45)))
                            }
                            choices.getChildAt(0)
                        }
                    }
                }.apply { tag = value }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.findViewWithTag<View>(focusKey ?: current) ?: rows.findViewWithTag<View>("never")
        }

    private fun showGroupAppearance(focusKey: String = "sort"): Unit =
        settingsPanel("Groupes", onBack = { showAppearanceSettings("groups") }) { rows, dialog ->
            rows.addView(settingsPanelRow("Tri des Groupes",
                if (store.setting("groupSort", "playlist") == "name") "Par nom" else "Par ordre dans la playlist") {
                dialog.dismiss(); showGroupSort()
            }.apply { tag = "sort" }, LinearLayout.LayoutParams(-1, dp(62)))
            settingsPanelToggle(rows, "showAllPlaylistsGroup", "Afficher la catégorie \"Toutes les listes de lectures\"", true) { refreshOrganization() }
            settingsPanelToggle(rows, "showFavoritesGroup", "Afficher la catégorie \"Favoris\"", true) { refreshOrganization() }
            settingsPanelToggle(rows, "showAllGroup", "Afficher la catégorie \"Toutes les Chaînes\"", true) { refreshOrganization() }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showGroupSort(): Unit =
        settingsPanel("Tri des Groupes", onBack = { showGroupAppearance() }) { rows, dialog ->
            val current = store.setting("groupSort", "playlist")
            listOf("playlist" to "Par ordre dans la playlist", "name" to "Par nom").forEach { (value, label) ->
                rows.addView(settingsRadioRow(label, current == value) {
                    async({ store.setSetting("groupSort", value) }) {
                        refreshOrganization(); dialog.dismiss(); showGroupAppearance()
                    }
                }.apply { tag = value }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.addView(text("Ce paramètre définit le tri global pour toutes les listes de lecture.\n" +
                "Vous pouvez remplacer la méthode de tri pour des listes de lecture individuelles ou trier manuellement les groupes dans les paramètres de la liste de lecture.", 12).apply {
                setTextColor(surface(.68f)); setPadding(dp(16), dp(24), dp(16), dp(16))
            })
            rows.findViewWithTag<View>(current) ?: rows.getChildAt(0)
        }

    private fun playerAppearanceSection(rows: LinearLayout, title: String) {
        rows.addView(text(title, 12, true).apply {
            setTextColor(accentTextColor()); setPadding(dp(16), dp(22), dp(16), dp(16))
        })
    }

    private fun playerAppearanceChoice(rows: LinearLayout, key: String, title: String, default: String,
        choices: List<Pair<String, String>>, enabled: Boolean = true, back: () -> Unit) {
        val value = store.setting(key, default)
        rows.addView(settingsPanelRow(title, choices.firstOrNull { it.first == value }?.second ?: default) {
            settingsPanel(title, onBack = back) { options, dialog ->
                choices.forEach { (id, label) ->
                    options.addView(settingsRadioRow(label, value == id) {
                        async({ store.setSetting(key, id) }) { dialog.dismiss(); back() }
                    }.apply { tag = id }, LinearLayout.LayoutParams(-1, dp(45)))
                }
                options.findViewWithTag<View>(value) ?: options.getChildAt(0)
            }
        }.apply { tag = key; isEnabled = enabled; isFocusable = enabled; alpha = if (enabled) 1f else .4f },
            LinearLayout.LayoutParams(-1, dp(62)))
    }

    private fun showPlayerAppearance(focusKey: String = "channels"): Unit =
        settingsPanel("Lecteur", onBack = { showAppearanceSettings("player") }) { rows, dialog ->
            listOf(Triple("channels", "Liste des Chaînes", { showPlayerChannelAppearance() }),
                Triple("info", "Panneau d'information", { showPlayerInfoAppearance() }),
                Triple("history", "Historique / chaînes récentes", { showPlayerHistoryAppearance() }),
                Triple("menu", "Menu", { showPlayerMenuAppearance() }),
                Triple("clock", "Horloge", { showPlayerClockAppearance() })).forEach { (key, title, action) ->
                rows.addView(settingsPanelRow(title, if (key == "clock") {
                    if (store.booleanSetting("playerClock", false)) "On" else "Off"
                } else "") { dialog.dismiss(); action() }.apply { tag = key },
                    LinearLayout.LayoutParams(-1, dp(if (key == "clock") 62 else 45)))
            }
            playerAppearanceChoice(rows, "playerPanelTimeout", "Délai d'expiration des panneaux, sec", "5",
                listOf(0, 2, 3, 5, 10, 15, 30, 60).map { "$it" to if (it == 0) "Aucun" else "$it" }) {
                showPlayerAppearance("playerPanelTimeout")
            }
            settingsPanelToggle(rows, "playerBlackScreen", "Afficher un écran noir lors du changement des chaînes", false)
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showPlayerChannelAppearance(focusKey: String = "playerListCurrentColor"): Unit =
        settingsPanel("Liste des Chaînes", onBack = { showPlayerAppearance("channels") }) { rows, _ ->
            settingsPanelToggle(rows, "playerListCurrentColor", "Mettez en surbrillance les programmes actuels en couleur", true)
            settingsPanelToggle(rows, "playerListDimPast", "Assombrir les programmes précédents", false)
            playerAppearanceSection(rows, "Mode superposition")
            settingsPanelToggle(rows, "playerListOverlayPrograms", "Afficher les Programmes", true)
            settingsPanelToggle(rows, "playerListOverlayDescription", "Afficher la description du programme", true)
            settingsPanelToggle(rows, "playerListOverlayAutoPlay", "Lecture automatique des chaînes", false)
            settingsPanelToggle(rows, "playerListOverlayStay", "Restez sur la liste lorsque vous changez de chaînes", false)
            playerAppearanceSection(rows, "Mode aperçu")
            settingsPanelToggle(rows, "playerListPreviewTransition", "Transition animée", true)
            settingsPanelToggle(rows, "playerListPreviewAutoPlay", "Lecture automatique des chaînes", false)
            settingsPanelToggle(rows, "playerListPreviewStay", "Restez sur la liste lorsque vous changez de chaînes", true)
            rows.findViewWithTag<View>(focusKey)
        }

    private fun showPlayerInfoAppearance(focusKey: String = "playerInfoCard"): Unit =
        settingsPanel("Panneau d'information", onBack = { showPlayerAppearance("info") }) { rows, _ ->
            settingsPanelToggle(rows, "playerInfoCard", "Style de carte", false) { showPlayerInfoAppearance("playerInfoCard") }
            settingsPanelToggle(rows, "playerInfoBottom", "Afficher le panneau d'informations en bas lors du changement de chaîne", false)
            rows.findViewWithTag<View>("playerInfoBottom")?.apply {
                isEnabled = store.booleanSetting("playerInfoCard", false); isFocusable = isEnabled
                alpha = if (isEnabled) 1f else .4f
            }
            settingsPanelToggle(rows, "playerInfoTime", "Afficher l'heure", true)
            settingsPanelToggle(rows, "playerInfoDate", "Afficher la date sur l'horloge", true)
            settingsPanelToggle(rows, "playerInfoGroup", "Afficher la liste de lecture et le nom du groupe", true)
            settingsPanelToggle(rows, "playerInfoDescription", "Afficher la description du programme", true)
            settingsPanelToggle(rows, "playerInfoDescriptionOnChange", "Afficher la description lors du changement de chaîne uniquement", true)
            settingsPanelToggle(rows, "playerInfoMedia", "Afficher les propriétés du média", true)
            settingsPanelToggle(rows, "playerInfoResolution", "Afficher la résolution vidéo au lieu des étiquettes", false)
            rows.findViewWithTag<View>(focusKey)
        }

    private fun showPlayerHistoryAppearance(focusKey: String = "playerHistoryGuide"): Unit =
        settingsPanel("Historique / chaînes récentes", onBack = { showPlayerAppearance("history") }) { rows, _ ->
            settingsPanelToggle(rows, "playerHistoryGuide", "Afficher le bouton \"Guide TV\"", true)
            settingsPanelToggle(rows, "playerHistoryButton", "Afficher le bouton \"historique\"", true)
            playerAppearanceSection(rows, "Historique")
            playerAppearanceChoice(rows, "historyDays", "Nombre de jours d'historique", "7",
                listOf(1, 2, 3, 5, 7, 14, 30).map { "$it" to "$it" }) { showPlayerHistoryAppearance("historyDays") }
            val delays = listOf(0, 5, 10, 15, 30, 60, 120).map { "$it" to "$it" }
            playerAppearanceChoice(rows, "historyDelay", "Délai avant d'ajouter à l'historique, s", "10", delays) {
                showPlayerHistoryAppearance("historyDelay")
            }
            settingsPanelToggle(rows, "playerHistoryPrograms", "Afficher les programmes actuels", true)
            settingsPanelToggle(rows, "playerHistoryPast", "Afficher les programmes antérieurs sans Replay", false)
            playerAppearanceSection(rows, "Chaînes récentes")
            playerAppearanceChoice(rows, "playerRecentCount", "Nombre de chaînes récentes", "10",
                listOf(5, 10, 15, 20, 30, 50).map { "$it" to "$it" }) { showPlayerHistoryAppearance("playerRecentCount") }
            playerAppearanceChoice(rows, "playerRecentDelay", "Délai avant l'ajout aux chaînes récentes, sec", "0", delays) {
                showPlayerHistoryAppearance("playerRecentDelay")
            }
            settingsPanelToggle(rows, "playerRecentNames", "Afficher les noms des chaînes", false)
            rows.findViewWithTag<View>(focusKey)
        }

    private fun showPlayerMenuAppearance(focusKey: String = "reorder"): Unit =
        settingsPanel("Menu", onBack = { showPlayerAppearance("menu") }) { rows, dialog ->
            val labels = IptvRemote.playerMenuButtons
            val order = IptvStore.playerMenuOrder(store.setting("playerMenuOrder", "[]"), labels.map { it.first })
            val ordered = order.mapNotNull { id -> labels.firstOrNull { it.first == id } }
            rows.addView(settingsPanelRow("Réorganiser les boutons") {
                dialog.dismiss()
                reorderPanel("Réorganiser les boutons", ordered, onCancel = { showPlayerMenuAppearance() }) { ids ->
                    async({ store.setSetting("playerMenuOrder", iptvJson.encodeToString(ids)) }) { showPlayerMenuAppearance() }
                }
            }.apply { tag = "reorder" }, LinearLayout.LayoutParams(-1, dp(45)))
            listOf(true, false).forEach { visible ->
                playerAppearanceSection(rows, if (visible) "Boutons visibles" else "Boutons cachés")
                ordered.filter { (id, _) -> store.booleanSetting("playerMenu:$id", labels.indexOfFirst { it.first == id } < 12) == visible }
                    .forEach { (id, label) ->
                        val key = "playerMenu:$id"
                        settingsPanelToggle(rows, key, label, visible) { showPlayerMenuAppearance(key) }
                        (rows.getChildAt(rows.childCount - 1) as LinearLayout).addView(playerMenuIcon(id), 0,
                            LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(16) })
                    }
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun playerMenuIcon(name: String): View = View(this).apply {
        tag = "settings-menu-icon"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = themeForeground; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f
                strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val saved = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
                fun line(x: Float, y: Float, ex: Float, ey: Float) = canvas.drawLine(x, y, ex, ey, paint)
                fun box(x: Float = 3f, y: Float = 4f, ex: Float = 21f, ey: Float = 20f) = canvas.drawRect(x, y, ex, ey, paint)
                fun path(vararg points: Float) = canvas.drawPath(android.graphics.Path().apply {
                    moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                }, paint)
                when (name) {
                    "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
                    "channels" -> (5..19 step 7).forEach { y -> line(8f, y.toFloat(), 22f, y.toFloat()); canvas.drawCircle(3f, y.toFloat(), 1f, paint) }
                    "favorite" -> { paint.style = android.graphics.Paint.Style.FILL; path(12f, 2f, 15f, 9f, 23f, 10f, 17f, 15f, 19f, 23f, 12f, 18f, 5f, 23f, 7f, 15f, 1f, 10f, 9f, 9f, 12f, 2f); paint.style = android.graphics.Paint.Style.STROKE }
                    "settings" -> { canvas.drawCircle(12f, 12f, 6f, paint); canvas.drawCircle(12f, 12f, 2f, paint); repeat(8) { canvas.save(); canvas.rotate(it * 45f, 12f, 12f); line(12f, 3f, 12f, 6f); canvas.restore() } }
                    "myList" -> path(6f, 22f, 6f, 3f, 18f, 3f, 18f, 22f, 12f, 18f, 6f, 22f)
                    "audio" -> { path(3f, 9f, 7f, 9f, 12f, 4f, 12f, 20f, 7f, 15f, 3f, 15f, 3f, 9f); canvas.drawArc(8f, 4f, 22f, 20f, -60f, 120f, false, paint) }
                    "audioDelay" -> { canvas.drawCircle(12f, 12f, 1f, paint); path(5f, 9f, 2f, 12f, 5f, 15f); path(19f, 9f, 22f, 12f, 19f, 15f) }
                    "history", "sleep" -> { canvas.drawCircle(12f, 13f, 8f, paint); path(12f, 8f, 12f, 13f, 16f, 15f); if (name == "sleep") line(9f, 1f, 15f, 1f) else path(1f, 9f, 5f, 13f, 8f, 9f) }
                    "exit" -> { box(3f, 3f, 19f, 21f); line(2f, 12f, 15f, 12f); path(11f, 8f, 15f, 12f, 11f, 16f) }
                    "video" -> { box(2f, 6f, 16f, 18f); path(16f, 9f, 22f, 6f, 22f, 18f, 16f, 15f) }
                    "multiview" -> { box(6f, 7f, 22f, 19f); path(2f, 15f, 2f, 3f, 18f, 3f); line(14f, 19f, 14f, 23f); line(10f, 23f, 18f, 23f) }
                    "movies" -> { box(3f, 8f, 21f, 21f); path(3f, 8f, 2f, 3f, 20f, 1f, 21f, 6f, 3f, 8f); line(8f, 2f, 11f, 7f); line(14f, 2f, 17f, 6f) }
                    "series" -> { box(6f, 3f, 22f, 19f); path(2f, 6f, 2f, 23f, 18f, 23f); path(11f, 7f, 17f, 11f, 11f, 15f, 11f, 7f) }
                    "display" -> { box(); path(7f, 11f, 7f, 8f, 10f, 8f); path(14f, 16f, 17f, 16f, 17f, 13f) }
                    else -> { box(); paint.style = android.graphics.Paint.Style.FILL; paint.textSize = if (name == "record") 7f else 9f; paint.textAlign = android.graphics.Paint.Align.CENTER; canvas.drawText(if (name == "record") "DVR" else "CC", 12f, 15f, paint); paint.style = android.graphics.Paint.Style.STROKE }
                }
                canvas.restoreToCount(saved)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
            @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun showPlayerClockAppearance(focusKey: String = "playerClock"): Unit =
        settingsPanel("Horloge", onBack = { showPlayerAppearance("clock") }) { rows, _ ->
            val enabled = store.booleanSetting("playerClock", false)
            settingsPanelToggle(rows, "playerClock", if (enabled) "On" else "Off", false) { showPlayerClockAppearance() }
            playerAppearanceChoice(rows, "playerClockPosition", "Position", "topRight",
                listOf("topRight" to "En haut à droite", "topLeft" to "En haut à gauche",
                    "bottomRight" to "En bas à droite", "bottomLeft" to "En bas à gauche"), enabled) { showPlayerClockAppearance("playerClockPosition") }
            playerAppearanceChoice(rows, "playerClockSize", "Taille", "medium",
                listOf("small" to "Petite", "medium" to "Moyenne", "large" to "Grande"), enabled) { showPlayerClockAppearance("playerClockSize") }
            playerAppearanceChoice(rows, "playerClockTransparency", "Transparence", "medium",
                listOf("none" to "Aucune", "low" to "Faible", "medium" to "Moyenne", "high" to "Élevée"), enabled) { showPlayerClockAppearance("playerClockTransparency") }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun guideSortLabel(value: String): String = when (value) {
        "name" -> "Par nom"; "added" -> "Par date d'ajout"; "watchTime" -> "Par temps de visionnage"
        "manual" -> "Manuellement"; "nameDescending" -> "Par nom (Z–A)"; else -> "Par ordre dans la playlist"
    }

    private fun refreshGuideAppearance() {
        if (!store.booleanSetting("previewEnabled", true) && !guideOverlay) stopPreview(true)
        previewFrame.visibility = if (store.booleanSetting("previewEnabled", true) && !guideOverlay && section in listOf(1, 5)) View.VISIBLE else View.GONE
        if (guideActive) renderGuide(rememberedFocus)
    }

    private fun showGuideSettings(focusKey: String = "sort"): Unit =
        settingsPanel("Guide TV", onBack = { showAppearanceSettings() }) { rows, dialog ->
            listOf(Triple("sort", "Tri des Chaînes", guideSortLabel(store.setting("channelSort", "playlist"))),
                Triple("preview", "Aperçu", if (store.booleanSetting("previewEnabled", true)) "On" else "Off"),
                Triple("editor", "Éditeur de noms de chaîne", if (store.booleanSetting("channelNameEditor", false)) "On" else "Off"))
                .forEach { (key, title, subtitle) ->
                    rows.addView(settingsPanelRow(title, subtitle) {
                        dialog.dismiss()
                        when (key) { "sort" -> showGuideSort(); "preview" -> showGuidePreview(); else -> showChannelNameEditor() }
                    }.apply { tag = key }, LinearLayout.LayoutParams(-1, dp(62)))
                }
            listOf(Triple("channelNumbers", "Afficher les numéros de chaîne", true),
                Triple("channelNames", "Afficher les noms des chaînes", true),
                Triple("channelTwoLines", "Titres de chaîne sur deux lignes", true),
                Triple("showCatchupIcon", "Afficher l'icône du replay dans la liste des chaînes", true),
                Triple("currentChannelColor", "Mettez en surbrillance la chaîne actuelle dans une autre couleur", true),
                Triple("programTwoLines", "Titres de programmes sur deux lignes", true),
                Triple("currentProgramColor", "Mettre en évidence les programmes actuels", true),
                Triple("currentProgramProgressOnly", "Mettre en évidence uniquement la progression", true),
                Triple("currentTimeIndicator", "Afficher l'indicateur de l'heure actuelle", true),
                Triple("animatedChannelScroll", "Défilement des chaînes animées", true),
                Triple("guideOverlayStay", "Restez sur le Guide TV lorsque vous changez de chaîne en mode superposition", false),
                Triple("backToNow", "Utilisez Retour pour revenir aux programmes en cours", true)).forEach { (key, label, default) ->
                settingsPanelToggle(rows, key, label, default) { refreshGuideAppearance() }
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showGuideSort(focusKey: String = store.setting("channelSort", "playlist")): Unit =
        settingsPanel("Tri des Chaînes", onBack = { showGuideSettings("sort") }) { rows, _ ->
            listOf("playlist", "name", "added", "watchTime").forEach { value ->
                rows.addView(settingsRadioRow(guideSortLabel(value), store.setting("channelSort", "playlist") == value) {
                    async({ store.setSetting("channelSort", value) }) { refreshGuideAppearance(); showGuideSort(value) }
                }.apply { tag = value }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            settingsPanelToggle(rows, "favoritesFirst", "Afficher d'abord les chaînes préférées", false) { refreshGuideAppearance() }
            settingsPanelToggle(rows, "groupChannelsByPlaylist", "Regrouper les chaînes par listes de lecture dans la catégorie \"Toutes les listes de lecture\"", true) { refreshGuideAppearance() }
            rows.addView(settingsPanelRow("Réinitialiser le temps de visionnage") {
                async({ store.resetWatchTime() }) { refreshGuideAppearance(); toast("Temps de visionnage réinitialisé.") }
            }.apply { tag = "reset" }, LinearLayout.LayoutParams(-1, dp(62)))
            rows.addView(text("Ce paramètre définit le tri global pour tous les groupes.\nVous pouvez remplacer la méthode de tri pour chaque groupe ou trier manuellement les chaînes dans les options de groupe.", 12).apply {
                setTextColor(surface(.68f)); setPadding(dp(16), dp(12), dp(16), 0)
            })
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showGuidePreview(focusKey: String = "previewEnabled"): Unit =
        settingsPanel("Aperçu", onBack = { showGuideSettings("preview") }) { rows, dialog ->
            settingsPanelToggle(rows, "previewEnabled", if (store.booleanSetting("previewEnabled", true)) "On" else "Off", true) {
                refreshGuideAppearance(); showGuidePreview()
            }
            settingsPanelToggle(rows, "previewTransition", "Transition animée", true)
            settingsPanelToggle(rows, "previewAutoPlay", "Lecture automatique des chaînes", false)
            settingsPanelToggle(rows, "previewStayGuide", "Restez sur le Guide TV lorsque vous changez de chaînes", true)
            rows.addView(settingsPanelRow("Délai de basculement en plein écran, sec", store.setting("previewFullscreenDelay", "120")) {
                dialog.dismiss()
                settingsPanel("Délai de basculement en plein écran, sec", onBack = { showGuidePreview("delay") }) { choices, choiceDialog ->
                    listOf(0, 10, 30, 60, 120, 180, 300).forEach { value ->
                        choices.addView(settingsRadioRow(if (value == 0) "Aucun" else value.toString(), store.intSetting("previewFullscreenDelay", 120) == value) {
                            async({ store.setSetting("previewFullscreenDelay", value.toString()) }) {
                                schedulePreviewFullscreen(); choiceDialog.dismiss(); showGuidePreview("delay")
                            }
                        }.apply { tag = value.toString() }, LinearLayout.LayoutParams(-1, dp(45)))
                    }
                    choices.findViewWithTag<View>(store.setting("previewFullscreenDelay", "120")) ?: choices.getChildAt(0)
                }
            }.apply { tag = "delay" }, LinearLayout.LayoutParams(-1, dp(62)))
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }

    private fun showChannelNameEditor(focusKey: String = "channelNameEditor"): Unit =
        settingsPanel("Éditeur de noms de chaîne", onBack = { showGuideSettings("editor") }) { rows, dialog ->
            settingsPanelToggle(rows, "channelNameEditor", if (store.booleanSetting("channelNameEditor", false)) "On" else "Off", false) {
                refreshGuideAppearance(); showChannelNameEditor()
            }
            listOf("channelNamePrefixes" to "Préfixes à supprimer", "channelNameSuffixes" to "Suffixes à supprimer").forEach { (key, title) ->
                rows.addView(settingsPanelRow(title, store.setting(key, "").ifBlank { "Non définie" }) {
                    dialog.dismiss()
                    showSettingsTextInput(title, store.setting(key, "")) { value ->
                        async({ store.setSetting(key, value) }) { refreshGuideAppearance(); showChannelNameEditor(key) }
                    }
                }.apply {
                    tag = key; isEnabled = store.booleanSetting("channelNameEditor", false); isFocusable = isEnabled
                    if (!isEnabled) for (index in 0 until (getChildAt(0) as LinearLayout).childCount)
                        ((getChildAt(0) as LinearLayout).getChildAt(index) as TextView).setTextColor(surface(.4f))
                }, LinearLayout.LayoutParams(-1, dp(64)))
            }
            rows.addView(text("Saisissez les listes de préfixes et de suffixes séparés par des virgules à supprimer des noms de chaîne,\npar exemple, \"US:, UK:\" et \"| HD, | FHD\"", 12).apply {
                setTextColor(surface(.68f)); setPadding(dp(16), dp(20), dp(16), 0)
            })
            rows.findViewWithTag<View>(focusKey)?.takeIf { it.isFocusable } ?: rows.getChildAt(0)
        }

    private fun showEpgSettings(focusKey: String = "sources"): Unit =
        settingsPanel("EPG (guide des programmes TV)", onBack = { showSettingsMenu("epg") }) { rows, dialog ->
            rows.tag = "epg_settings"
            fun command(key: String, title: String, subtitle: String = "", action: () -> Unit) {
                rows.addView(settingsPanelRow(title, subtitle, action).apply { tag = key },
                    LinearLayout.LayoutParams(-1, dp(if (subtitle.isBlank()) 45 else 62)))
            }
            fun toggle(key: String, title: String, default: Boolean) {
                val control = Switch(this).apply {
                    isChecked = store.booleanSetting(key, default); isClickable = false; isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    thumbTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accentTextColor(), surface(.74f)))
                    trackTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accentTextColor(), surface(.32f)))
                }
                lateinit var row: LinearLayout
                row = settingsPanelRow(title) {
                    row.isEnabled = false
                    async({
                        store.setSetting(key, (!control.isChecked).toString())
                        if (key == "epgDescriptions") sources.forEach { store.clearEpgCache(it.id) }
                    }, failure = { row.isEnabled = true; toast(it) }) {
                        control.isChecked = store.booleanSetting(key, default); row.isEnabled = true
                        if (key == "epgDescriptions") invalidateProviders()
                    }
                }.apply {
                    tag = key; addView(control, LinearLayout.LayoutParams(-2, -2))
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, node: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, node)
                            node.className = Switch::class.java.name; node.isCheckable = true; node.isChecked = control.isChecked
                        }
                    }
                }
                rows.addView(row, LinearLayout.LayoutParams(-1, dp(62)))
            }
            command("sources", "Sources EPG") { dialog.dismiss(); showEpgSources() }
            command("epgPastDays", "Derniers jours pour garder l'EPG", store.setting("epgPastDays", "1")) {
                dialog.dismiss(); showEpgChoice("epgPastDays", "Derniers jours pour garder l'EPG", "1", (1..14).toList())
            }
            toggle("epgDescriptions", "Stocker les descriptions des programmes", true)
            rows.addView(text("Options de mise à jour", 12, true).apply {
                setTextColor(accentTextColor()); setPadding(dp(16), dp(12), dp(16), dp(12))
            }, LinearLayout.LayoutParams(-1, dp(45)))
            command("epgRefreshHours", "Intervalles de mise à jour en heures",
                store.setting("epgRefreshHours", "24").let { if (it == "0") "Aucun" else it }) {
                dialog.dismiss(); showEpgChoice("epgRefreshHours", "Intervalles de mise à jour en heures", "24",
                    listOf(0, 2, 4, 8, 16, 24, 48, 72, 96, 120, 144, 168))
            }
            toggle("epgUpdateOnStart", "Mise à jour au démarrage de l'application", true)
            toggle("epgUpdateOnPlaylist", "Mise à jour sur le changement des listes de lecture", true)
            command("update", if (epgUpdateRunning) "Mise à jour de l'EPG…" else "Mettre à jour l'EPG") {
                refreshEpg(); showEpgSettings("update")
            }
            rows.findViewWithTag<View>("update").apply { isEnabled = !epgUpdateRunning; isFocusable = isEnabled }
            command("clear", "Effacer l'EPG") {
                async({ sources.forEach { store.clearEpgCache(it.id) } }) {
                    invalidateProviders()
                    getSharedPreferences("iptv_epg_status", MODE_PRIVATE).edit().clear().apply()
                    toast("Cache EPG effacé."); showEpgSettings("clear")
                }
            }
            rows.findViewWithTag<View>("clear").apply { isEnabled = !epgUpdateRunning; isFocusable = isEnabled }
            rows.addView(settingsPanelRow("Statut de la dernière mise à jour",
                getSharedPreferences("iptv_epg_status", MODE_PRIVATE).getString("message", "–").orEmpty()) {}.apply {
                isFocusable = false; isEnabled = false
                ((getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(surface(.68f))
            }, LinearLayout.LayoutParams(-1, -2))
            rows.findViewWithTag<View>(focusKey)?.takeIf { it.isFocusable } ?: rows.getChildAt(0)
        }

    private fun showEpgChoice(key: String, title: String, default: String, values: List<Int>): Unit =
        settingsPanel(title, onBack = { showEpgSettings(key) }) { rows, dialog ->
            val selected = store.setting(key, default)
            val choices = (values + listOfNotNull(selected.toIntOrNull())).distinct().sorted()
            choices.forEach { value ->
                rows.addView(settingsPanelRow(if (value == 0) "Aucun" else value.toString()) {
                    async({
                        store.setSetting(key, value.toString()); sources.forEach { store.clearEpgCache(it.id) }
                    }) { invalidateProviders(); dialog.dismiss(); showEpgSettings(key) }
                }.apply {
                    tag = value.toString()
                    addView(RadioButton(this@IptvActivity).apply {
                        isChecked = selected == value.toString(); isClickable = false; isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        buttonTintList = android.content.res.ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(accentTextColor(), surface(.68f)))
                    }, 0, LinearLayout.LayoutParams(dp(40), dp(40)))
                }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.findViewWithTag<View>(selected) ?: rows.getChildAt(0)
        }

    private fun epgSourceUrls(): List<String> =
        (runCatching { iptvJson.decodeFromString<List<String>>(store.setting("epgSources", "[]")) }.getOrDefault(emptyList()) + sources.map { it.epgUrl })
            .filter { it.toHttpUrlOrNull() != null }.distinct()

    private fun epgSourceLabel(url: String) = url.toHttpUrl().let { "${it.host}${it.encodedPath}" }

    private fun showEpgSources(focusUrl: String = "add"): Unit =
        settingsPanel("Sources EPG", onBack = { showEpgSettings("sources") }) { rows, dialog ->
            rows.addView(settingsPanelRow("Ajouter une Source") {
                dialog.dismiss(); addEpgSource()
            }.apply { tag = "add" }, LinearLayout.LayoutParams(-1, dp(45)))
            epgSourceUrls().forEach { url ->
                rows.addView(settingsPanelRow(epgSourceLabel(url)) {
                    dialog.dismiss(); assignEpgSource(url)
                }.apply { tag = url }, LinearLayout.LayoutParams(-1, dp(45)))
            }
            rows.addView(text("Les sources EPG doivent être attribuées dans les paramètres de la liste de lecture", 12).apply {
                setTextColor(surface(.68f)); setPadding(dp(16), dp(14), dp(16), 0)
            })
            rows.findViewWithTag<View>(focusUrl) ?: rows.getChildAt(0)
        }

    private fun assignEpgSource(url: String): Unit =
        settingsPanel("Attribuer la source EPG", onBack = { showEpgSources(url) }) { rows, dialog ->
            orderedSources().filter { it.includeLive }.forEach { source ->
                rows.addView(settingsPanelRow(source.name, if (source.epgUrl == url) "Source attribuée" else "") {
                    savePlaylistEpg(source, url) { dialog.dismiss(); showEpgSources(url) }
                }, LinearLayout.LayoutParams(-1, dp(62)))
            }
            if (rows.childCount == 0) rows.addView(text("Aucune liste de lecture avec des chaînes de télévision.", 14).apply {
                setTextColor(surface(.68f)); setPadding(dp(16), dp(12), dp(16), 0)
            })
            rows.getChildAt(0).takeIf { it.isFocusable }
        }

    private fun showPlaylistEpgSources(source: IptvSource): Unit =
        settingsPanel("Sources EPG", onBack = { showPlaylistSettings(source, "epg") }) { rows, dialog ->
            (listOf("" to "Source fournie par la liste de lecture") + epgSourceUrls().map { it to epgSourceLabel(it) })
                .forEach { (url, label) ->
                    rows.addView(settingsPanelRow(label, if (source.epgUrl == url) "Source attribuée" else "") {
                        savePlaylistEpg(source, url) {
                            dialog.dismiss()
                            sources.firstOrNull { it.id == source.id }?.let { showPlaylistSettings(it, "epg") } ?: showPlaylistsSettings()
                        }
                    }.apply { tag = url }, LinearLayout.LayoutParams(-1, dp(62)))
                }
            rows.findViewWithTag<View>(source.epgUrl) ?: rows.getChildAt(0)
        }

    private fun savePlaylistEpg(source: IptvSource, url: String, done: () -> Unit) {
        async({ store.save(source.copy(epgUrl = url)); store.clearEpgCache(source.id); store.sources() }) {
            sources = it; invalidateProviders(source.id); done()
        }
    }

    private fun addEpgSource() {
        val page = IptvContentPage()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var url = ""
        var saving = false
        lateinit var entry: LinearLayout
        lateinit var accept: LinearLayout
        fun update(value: String) {
            url = value.trim()
            val label = (entry.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
            label.text = url.ifBlank { "Entrer l'URL" }; label.setSingleLine(true); label.ellipsize = android.text.TextUtils.TruncateAt.END
            accept.isEnabled = !saving && runCatching { httpUrl(url) }.isSuccess
            accept.isFocusable = accept.isEnabled
            ((accept.getChildAt(0) as LinearLayout).getChildAt(0) as TextView)
                .setTextColor(if (accept.isEnabled) themeForeground else surface(.3f))
        }
        entry = settingsPanelRow("Entrer l'URL") {
            showSettingsTextInput("EPG URL", url) { value -> update(value); contextPanel?.dismiss(); entry.requestFocus() }
            contextPanel?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        fields.addView(entry, LinearLayout.LayoutParams(-1, dp(45)))
        fields.addView(settingsPanelRow("Coller depuis le presse-papier") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) update(clip.getItemAt(0).coerceToText(this).toString())
        }, LinearLayout.LayoutParams(-1, dp(52)))
        accept = settingsPanelRow("OK") {
            if (!saving) {
                val savedUrl = httpUrl(url)
                saving = true; update(url)
                async({
                    val urls = (epgSourceUrls() + savedUrl).distinct()
                    store.setSetting("epgSources", iptvJson.encodeToString(urls))
                }, failure = { saving = false; update(url); toast(it) }) {
                    if (page.isShowing) { page.dismiss(); showEpgSources(savedUrl) }
                }
            }
        }
        actions.addView(accept, LinearLayout.LayoutParams(-1, dp(45)))
        actions.addView(settingsPanelRow("Annuler") { page.cancel() }, LinearLayout.LayoutParams(-1, dp(52)))
        showPlaylistWizard(page, "EPG URL", android.R.drawable.ic_menu_share, fields, actions,
            onBack = { showEpgSources() }, initialFocus = entry, secure = true,
            description = "Entrez l'URL EPG. Le format XMLTV est uniquement pris en charge.\n\nLa source EPG doit être attribuée dans les paramètres de la liste de lecture.")
        update(url)
    }

    private fun showGeneralSettings(focusKey: String = "start_at_boot") {
        settingsPanel("Général", onBack = { showSettingsMenu("general") }) { rows, dialog ->
            val homeSettings = getSharedPreferences("home_settings", MODE_PRIVATE)
            fun toggle(key: String, title: String, subtitle: String = "", home: Boolean = false, default: Boolean) {
                fun checked() = if (home) homeSettings.getBoolean(key, default) else store.booleanSetting(key, default)
                val toggle = Switch(this).apply {
                    isChecked = checked()
                    isClickable = false; isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    thumbTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accentTextColor(), surface(.74f)))
                    trackTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accentTextColor(), surface(.32f)))
                }
                lateinit var row: LinearLayout
                row = settingsPanelRow(title, subtitle) {
                    val value = !toggle.isChecked
                    if (home) {
                        homeSettings.edit().putBoolean(key, value).apply()
                        toggle.isChecked = value
                    } else {
                        row.isEnabled = false
                        async({ store.setSetting(key, value.toString()) }, failure = {
                            row.isEnabled = true; toast(it)
                        }) {
                            toggle.isChecked = checked(); row.isEnabled = true
                        }
                    }
                }.apply {
                    tag = key
                    addView(toggle, LinearLayout.LayoutParams(-2, -2))
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, node: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, node)
                            node.className = Switch::class.java.name
                            node.isCheckable = true; node.isChecked = toggle.isChecked
                        }
                    }
                }
                rows.addView(row, LinearLayout.LayoutParams(-1, dp(if (subtitle.isBlank()) 62 else 78)))
            }
            toggle("start_at_boot", "Démarrer automatiquement l'application au démarrage d'Android", home = true, default = true)
            toggle("autostart_after_standby", "Démarrer automatiquement l'application au réveil de la mise en veille",
                "Peut ne pas fonctionner sur tous les appareils", home = true, default = true)
            toggle("playLastChannel", "Lancer la dernière chaîne au démarrage de l'application", default = false)
            toggle("confirmExit", "Confirmer la sortie par une seconde pression sur Retour", default = true)
            listOf("userAgent" to "User-Agent", "udpProxy" to "UDP proxy (adresse:port)").forEach { (key, title) ->
                rows.addView(settingsPanelRow(title, store.setting(key, "").ifBlank { "Non définie" }) {
                    dialog.dismiss()
                    showGeneralTextSetting(key, title)
                }.apply { tag = key }, LinearLayout.LayoutParams(-1, dp(64)))
            }
            for (index in 0 until rows.childCount) {
                val row = rows.getChildAt(index)
                row.nextFocusUpId = rows.getChildAt((index + rows.childCount - 1) % rows.childCount).id
                row.nextFocusDownId = rows.getChildAt((index + 1) % rows.childCount).id
                row.nextFocusLeftId = row.id; row.nextFocusRightId = row.id
            }
            rows.findViewWithTag<View>(focusKey) ?: rows.getChildAt(0)
        }
    }

    private fun showGeneralTextSetting(key: String, title: String) {
        showSettingsTextInput(title, store.setting(key, "")) { value ->
            async({ store.setSetting(key, value) }, failure = { toast(it); showGeneralSettings(key) }) {
                invalidateProviders(); showGeneralSettings(key)
            }
        }
    }

    private fun showSettingsTextInput(title: String, value: String, password: Boolean = false, saved: (String) -> Unit) {
        lateinit var field: EditText
        var saving = false
        fun save() {
            if (saving) return
            saving = true
            val value = field.text.toString().let { if (password) it else it.trim() }
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
            saved(value)
        }
        settingsPanel(title, onBack = { save() }) { rows, dialog ->
            if (password) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val input = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }
            field = settingsInput().apply {
                id = View.generateViewId()
                setText(value)
                setSelection(text.length)
                textSize = 16f * textScale
                setTextColor(themeForeground)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setPadding(0, 0, 0, 0)
                background = null
                setOnEditorActionListener { _, action, event ->
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                        save(); true
                    } else false
                }
            }
            input.addView(field, LinearLayout.LayoutParams(-1, dp(30)))
            input.addView(View(this).apply { setBackgroundColor(accentTextColor()) }, LinearLayout.LayoutParams(-1, dp(2)))
            rows.addView(input, LinearLayout.LayoutParams(-1, -2))
            field
        }
    }

    private fun showSettings() {
        groups.removeAllViews()
        groupRows.clear()
        groupRowPaints.clear()
        listOf("account" to "Compte", "general" to "Général", "playlists" to "Listes de lecture", "epg" to "EPG",
            "appearance" to "Apparence", "playback" to "Lecture", "remote" to "Télécommande",
            "parental" to "Contrôle parental", "advanced" to "Paramètres avancés", "about" to "À propos").forEach { (key, title) ->
            groups.addView(group(title, { settingsPage == key }) {
                if (key == "epg") showEpgSettings()
                else if (key == "appearance") showAppearanceSettings()
                else { settingsPage = key; showSettingsPage() }
            })
        }
        showSettingsPage()
    }

    private fun showSettingsPage(parentalAuthorized: Boolean = false) {
        when (settingsPage) {
            "account" -> { showAccountSettings(); return }
            "remote" -> { showRemoteSettings(); return }
            "advanced" -> { showAdvancedSettings(); return }
            "searchOptions" -> { showSearchSettings(); return }
            "reminderOptions" -> { showReminderSettings(); return }
            "recordingOptions" -> { showRecordingSettings(); return }
            "vodOptions" -> { showVodSettings(); return }
        }
        if (settingsPage == "parental") {
            showParentalSettings(parentalAuthorized); return
        }
        requestVersion++
        informationHeader.layoutParams.height = dp(90)
        previewFrame.visibility = View.GONE
        info.removeAllViews()
        info.addView(text("Paramètres IPTV", 28, true))
        results.removeAllViews()
        status.text = ""
        when (settingsPage) {
            "general" -> {
                settingToggle("playLastChannel", "Lancer la dernière chaîne au démarrage du module", false)
                settingToggle("confirmExit", "Confirmer la sortie par une seconde pression sur Retour", true)
                settingText("userAgent", "User-Agent")
                settingText("udpProxy", "Proxy UDP (adresse:port)")
                results.addView(button("Sauvegarder les données IPTV sur cette TV") {
                    async({ store.backup() }) { toast("Sauvegarde chiffrée créée sur cette TV.") }
                })
                results.addView(button("Restaurer la sauvegarde IPTV de cette TV") {
                    if (!store.hasBackup()) toast("Aucune sauvegarde sur cette TV.")
                    else dialog("Remplacer les fournisseurs, favoris, historique et paramètres IPTV par la sauvegarde ?", listOf(
                        "Annuler" to {}, "Restaurer" to {
                            async({ store.restoreBackup(); store.sources() }) { values ->
                                sources = values; invalidateProviders(); parentalUnlockedUntil = 0; sourceId = sources.firstOrNull()?.id.orEmpty()
                                applyTheme(false); applyTextSize(); showSection(1); toast("Sauvegarde restaurée.")
                            }
                        }))
                })
                results.addView(text("La sauvegarde reste chiffrée sur cette TV. Les enregistrements vidéo sont conservés séparément.", 14))
            }
            "playlists" -> showSources()
            "epg" -> {
                results.addView(button("Sources EPG · Configurer les fournisseurs") { settingsPage = "playlists"; showSettingsPage() })
                settingChoice("epgPastDays", "Derniers jours à conserver", "1", listOf("1" to "1 jour", "3" to "3 jours", "7" to "7 jours", "14" to "14 jours"))
                settingToggle("epgDescriptions", "Stocker les descriptions des programmes", true)
                settingChoice("epgRefreshHours", "Intervalle de mise à jour", "24", listOf("1" to "1 heure", "6" to "6 heures", "12" to "12 heures", "24" to "24 heures", "48" to "48 heures"))
                settingToggle("epgUpdateOnStart", "Mettre à jour à l’ouverture du module", true)
                settingToggle("epgUpdateOnPlaylist", "Mettre à jour sur changement de fournisseur", true)
                settingChoice("epgOffsetMinutes", "Décalage horaire du guide", "0", (-12..12).map { (it * 60).toString() to "${if (it > 0) "+" else ""}$it h" })
                results.addView(button("Mettre à jour l’EPG") { refreshEpg() })
                results.addView(button("Effacer l’EPG") {
                    async({ sources.forEach { store.clearEpgCache(it.id) } }) { invalidateProviders(); toast("Cache EPG effacé.") }
                })
            }
            "appearance" -> {
                results.addView(button("Guide TV") { settingsPage = "guideAppearance"; showSettingsPage() })
                results.addView(button("Lecteur · Mini lecteur") { settingsPage = "previewAppearance"; showSettingsPage() })
                results.addView(button("Groupes") { showGroupAppearance() })
                results.addView(button("Logos") { settingsPage = "logosAppearance"; showSettingsPage() })
                settingChoice("textSize", "Taille du texte", "medium", listOf("small" to "Petite", "medium" to "Moyenne", "large" to "Grande", "extraLarge" to "Très Grande"))
                results.addView(button("Couleurs du thème · ${themeColorLabel("themeBackground")} • ${themeColorLabel("themeAccent")}") { showThemeColors() })
                results.addView(button("Transparence de l’interface · ${store.intSetting("interfaceTransparency", 50).coerceIn(0, 100)} %") { showInterfaceTransparency() })
            }
            "guideAppearance" -> {
                settingChoice("channelSort", "Tri des chaînes", "playlist", listOf("playlist" to "Ordre de la playlist", "name" to "Nom"))
                settingToggle("previewEnabled", "Aperçu · Mini lecteur", true)
                settingToggle("channelNumbers", "Afficher les numéros de chaîne", true)
                settingToggle("channelNames", "Afficher les noms des chaînes", true)
                settingToggle("channelTwoLines", "Titres de chaîne sur deux lignes", true)
                settingToggle("programTwoLines", "Titres de programmes sur deux lignes", true)
                settingToggle("currentChannelColor", "Mettre la chaîne actuelle en couleur", true)
                settingToggle("currentProgramColor", "Mettre les programmes en cours en évidence", true)
                settingToggle("currentProgramProgressOnly", "Mettre uniquement la progression en évidence", true)
                settingToggle("currentTimeIndicator", "Afficher l’indicateur de l’heure actuelle", true)
                settingToggle("showLogos", "Afficher les logos", true)
                settingToggle("backToNow", "Utiliser Retour pour revenir aux programmes en cours", true)
            }
            "previewAppearance" -> {
                settingToggle("previewEnabled", "Activer le mini lecteur", true)
                settingToggle("previewSound", "Activer le son dans le mini lecteur", true)
                settingToggle("previewAutoPlay", "Lecture automatique de la chaîne sélectionnée", false)
                settingToggle("playerClock", "Afficher l’horloge dans le lecteur", true)
                settingChoice("historyDays", "Historique · Nombre de jours", "7", listOf("1" to "1", "7" to "7", "14" to "14", "30" to "30"))
                settingChoice("historyDelay", "Délai avant l’ajout à l’historique dans l’aperçu", "10", listOf("0" to "Immédiat", "5" to "5 secondes", "10" to "10 secondes", "30" to "30 secondes"))
            }
            "groupsAppearance" -> {
                settingChoice("groupSort", "Tri des groupes", "playlist", listOf("playlist" to "Ordre de la playlist", "name" to "Nom"))
                settingToggle("showFavoritesGroup", "Afficher le groupe Favoris", true)
                settingToggle("showAllGroup", "Afficher le groupe Toutes les chaînes", true)
            }
            "logosAppearance" -> {
                settingToggle("showLogos", "Afficher les logos du fournisseur", true)
                results.addView(button("Vider le cache des logos") { bitmaps.evictAll(); toast("Cache des logos vidé.") })
                results.addView(text("Les logos de la playlist ou du portail sont prioritaires.", 14))
            }
            "playback" -> {
                showPlaybackSettings()
            }
            "about" -> {
                results.addView(text("Jojo Home IPTV\nM3U · Xtream Codes · Stalker\nLecteur Media3 de Jojo Home", 18))
                results.addView(text("Companion : IP ${iptvLanAddress() ?: "indisponible"} · port 8093", 14))
            }
        }
    }

    private fun settingToggle(key: String, label: String, default: Boolean) {
        lateinit var row: TextView
        fun title() = "$label : ${if (store.booleanSetting(key, default)) "OUI" else "NON"}"
        row = button(title()) {
            val value = !store.booleanSetting(key, default)
            async({ store.setSetting(key, value.toString())
                if (key == "epgDescriptions") sources.forEach { store.clearEpgCache(it.id) }
            }) {
                row.text = title()
                if (key == "epgDescriptions") invalidateProviders()
                if (key == "previewSound") previewPlayer?.volume = if (value) 1f else 0f
                if (key == "previewEnabled" && !value) stopPreview(true)
            }
        }
        results.addView(row)
    }

    private fun settingChoice(key: String, label: String, default: String, choices: List<Pair<String, String>>) {
        lateinit var row: TextView
        fun title() = "$label : ${choices.firstOrNull { it.first == store.setting(key, default) }?.second.orEmpty()}"
        row = button(title()) {
            dialog(label, choices.map { (value, name) -> name to {
                async({ store.setSetting(key, value)
                    if (key.startsWith("epg")) sources.forEach { store.clearEpgCache(it.id) }
                }) {
                    row.text = title()
                    if (key == "textSize") applyTextSize()
                    if (key.startsWith("epg")) invalidateProviders()
                }
            } })
        }
        results.addView(row)
    }

    private fun settingText(key: String, label: String) {
        results.addView(button("$label : ${store.setting(key, "").ifBlank { "Non défini" }}") {
            val field = EditText(this).apply {
                setText(store.setting(key, "")); setTextColor(themeForeground)
                setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT
            }
            val dialog = AlertDialog.Builder(this).setTitle(label).setView(field)
                .setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    async({ store.setSetting(key, field.text.toString().trim()) }) {
                        invalidateProviders(); dialog.dismiss(); showSettingsPage()
                    }
                }
            }
            dialog.show()
        })
    }

    private fun recordEpgStatus(message: String) {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        getSharedPreferences("iptv_epg_status", MODE_PRIVATE).edit().putString("message", "$date · $message").apply()
    }

    private fun refreshEpg() {
        if (epgUpdateRunning) return
        epgUpdateRunning = true
        status.text = "Mise à jour de l’EPG…"
        async({
            try {
                var count = 0
                sources.filter { it.includeLive }.forEach { source ->
                    store.clearEpgCache(source.id); invalidateProviders(source.id)
                    val connection = provider(source)
                    var page: Int? = 0
                    while (page != null) {
                        val batch = connection.items(ContentType.LIVE, "", page)
                        batch.items.forEach { if (epg(it).isNotEmpty()) count++ }
                        page = batch.nextPage
                    }
                }
                val message = if (count == 0) "Aucun programme disponible." else "EPG actualisé pour $count chaînes."
                recordEpgStatus(message)
                message
            } catch (problem: Exception) {
                recordEpgStatus("Échec de la mise à jour.")
                throw problem
            } finally { epgUpdateRunning = false }
        }, failure = {
            status.text = it; toast(it)
            if (contextPanel?.window?.decorView?.findViewWithTag<View>("epg_settings") != null) showEpgSettings("update")
        }) { message ->
            status.text = message
            if (contextPanel?.window?.decorView?.findViewWithTag<View>("epg_settings") != null) showEpgSettings("update")
        }
    }

    private fun applyTheme(refresh: Boolean = true) {
        val nextAccent = themeColorChoice("themeAccent")?.color ?: homeAccent
        val backgroundChoice = themeColorChoice("themeBackground")
        val nextBackground = backgroundChoice?.backgroundDark ?: backgroundChoice?.color ?: homeBackground
        val nextSurface = backgroundChoice?.background
        val nextRaisedSurface = backgroundChoice?.backgroundRaised
        val nextSelectedText = themeColorChoice("themeSelectedText")?.color
        val nextContrast = nextSelectedText?.takeIf { ColorUtils.calculateContrast(it, nextAccent) >= 4.5 } ?: readableThemeForeground(nextAccent)
        val nextForeground = readableThemeForeground(nextBackground)
        val nextOpacity = 255 * (100 - store.intSetting("interfaceTransparency", 50).coerceIn(0, 100)) / 100
        if (accent == nextAccent && themeBackground == nextBackground && selectedTextColor == nextSelectedText &&
            contrast == nextContrast && themeForeground == nextForeground && interfaceOpacity == nextOpacity &&
            themeSurface == nextSurface && themeRaisedSurface == nextRaisedSurface) return
        accent = nextAccent; themeBackground = nextBackground; interfaceOpacity = nextOpacity
        themeSurface = nextSurface; themeRaisedSurface = nextRaisedSurface
        selectedTextColor = nextSelectedText
        contrast = nextContrast; themeForeground = nextForeground
        focusNeedsOutline = ColorUtils.calculateContrast(accent, surface(.07f)) < 1.5
        paintVodHeroTools()
        (shell.parent as View).setBackgroundColor(themeBackground)
        nav.setBackgroundColor(interfaceBackground(surface(.06f)))
        (nav.getChildAt(0) as TextView).setTextColor(accentTextColor())
        navRows.forEachIndexed { index, row -> paintCommand(row as LinearLayout, row.hasFocus(), index == section) }
        status.setTextColor(themeForeground)
        previewFrame.background = shape(surface(.10f))
        previewFrame.foreground = shape(Color.TRANSPARENT).apply {
            setStroke(dp(if (previewFrame.hasFocus()) 3 else 1), if (previewFrame.hasFocus()) accentTextColor() else surface(.55f))
        }
        previewView.setShutterBackgroundColor(surface(.10f))
        previewPlaceholder.setTextColor(themeForeground); previewPlaceholder.setBackgroundColor(surface(.10f))
        content.setBackgroundColor(if (guideOverlay) interfaceBackground(themeBackground)
            else if (vodHero.visibility == View.VISIBLE) Color.BLACK else Color.TRANSPARENT)
        if (!refresh || !ready) return
        val mode = navigationMode
        val focus = results.findFocus()?.tag as? String ?: rememberedFocus
        if (section in listOf(1, 2, 3, 5)) buildGroups()
        when {
            detail != null -> { updateInfo(detail!!); detailActions(detail!!, if (detail!!.type == ContentType.SERIES) episodeSequence else emptyList()) }
            guideActive -> renderGuide(focus, preservedScrollX = guideHorizontal?.scrollX)
            section == 4 -> { recordingsSignature = ""; refreshRecordings() }
            section == 6 && settingsPage !in listOf("account", "remote", "advanced", "searchOptions", "reminderOptions", "recordingOptions", "vodOptions", "parental") -> showSettingsPage()
            section == 5 && listMode in listOf("programs", "reminders") -> showSavedPrograms(listMode == "reminders")
            listMode in listOf("favorites", "history") -> showSaved(savedType, listMode == "history")
            section != 6 -> displayItems(focus)
        }
        showNavigation(mode)
    }

    private fun applyTextSize() {
        val next = when (store.setting("textSize", "medium")) { "small" -> .85f; "large" -> 1.15f; "extraLarge" -> 1.3f; else -> 1f }
        if (next == textScale) return
        fun resize(view: View) {
            if (view is TextView) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, view.textSize * next / textScale)
            if (view is ViewGroup) for (index in 0 until view.childCount) resize(view.getChildAt(index))
        }
        resize(window.decorView)
        textScale = next
    }

    private fun parentalAccess(scope: String, force: Boolean = false, action: () -> Unit) {
        val hash = if (ready) store.setting("pinHash", "") else ""
        val locked = force || ready && store.booleanSetting("parentalEnabled", false) &&
            store.booleanSetting(scope, IptvOrganization.pinScopeDefault(scope))
        val remembered = android.os.SystemClock.elapsedRealtime() < parentalUnlockedUntil &&
            IptvOrganization.pinUnlockApplies(scope, store.booleanSetting("pinChannelsOnly", false))
        if (hash.isBlank() || !locked || !force && remembered) { action(); return }
        pinDialog("Code PIN IPTV", false) { value ->
            if (digest(store.setting("pinSalt", "") + value) != hash) false
            else {
                parentalUnlockedUntil = when (store.setting("pinRemember", "always")) {
                    "session" -> Long.MAX_VALUE; "15" -> android.os.SystemClock.elapsedRealtime() + 900_000; else -> 0L
                }
                action(); true
            }
        }
    }

    private fun editPin(done: (() -> Unit)? = null) {
        pinDialog("Nouveau code PIN IPTV (4 à 8 chiffres)", true) { value ->
            val salt = UUID.randomUUID().toString()
            async({ store.setSetting("pinSalt", salt); store.setSetting("pinHash", digest(salt + value))
                if (done == null) store.setSetting("parentalEnabled", "true") }) {
                parentalUnlockedUntil = 0L; if (done == null) showSettingsPage(true) else done(); toast("Code PIN enregistré.")
            }
            true
        }
    }

    private fun pinDialog(title: String, confirmation: Boolean, submit: (String) -> Boolean) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(12)) }
        val selector = store.setting("pinMethod", "selector") != "keyboard"
        val fields = mutableListOf<EditText>()
        lateinit var dialog: AlertDialog
        fun resizeFields(count: Int) = fields.forEach { field ->
            field.setText(field.text.toString().padEnd(count, '0').take(count)); field.setSelection(0, 1)
        }
        if (selector) {
            panel.addView(text("Nombre de chiffres", 14))
            panel.addView(android.widget.NumberPicker(this).apply {
                minValue = 4; maxValue = 8; value = 4; wrapSelectorWheel = false
                isFocusable = true; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setOnValueChangedListener { _, _, count -> resizeFields(count) }
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else when (code) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { fields.firstOrNull()?.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            value = (value + if (code == KeyEvent.KEYCODE_DPAD_UP) 1 else -1).coerceIn(4, 8)
                            resizeFields(value); true
                        }
                        else -> false
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(90)))
            panel.addView(text("↑/↓ : chiffre · ←/→ : position · OK : suivant", 12))
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(8)); panel.addView(this)
            fields.add(this)
            if (selector) {
                showSoftInputOnFocus = false; setText("0000"); setSelection(0, 1)
                setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                        val next = fields.indexOf(this) + 1
                        if (next < fields.size) fields[next].requestFocus() else dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                        true
                    } else if (event.action != KeyEvent.ACTION_DOWN || code !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
                    else {
                        val value = text.toString().padEnd(4, '0')
                        var position = selectionStart.coerceIn(0, value.lastIndex)
                        if (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                            val digit = (value[position].digitToIntOrNull() ?: 0) + if (code == KeyEvent.KEYCODE_DPAD_UP) 1 else 9
                            setText(value.replaceRange(position, position + 1, (digit % 10).toString()))
                        } else {
                            val next = position + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                            if (next !in value.indices) return@setOnKeyListener false
                            position = next
                        }
                        setSelection(position, position + 1); true
                    }
                }
            }
        }
        val pin = field("Code PIN")
        val repeated = if (confirmation) field("Confirmer le code PIN") else null
        dialog = AlertDialog.Builder(this).setTitle(title).setView(panel).setPositiveButton("Confirmer", null).setNegativeButton("Annuler", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = pin.text.toString()
                if (!value.matches(Regex("[0-9]{4,8}"))) pin.error = "Entre 4 à 8 chiffres."
                else if (repeated != null && repeated.text.toString() != value) repeated.error = "Les deux codes doivent être identiques."
                else if (submit(value)) dialog.dismiss() else pin.error = "Code PIN incorrect."
            }
        }
        dialog.show()
    }

    @Deprecated("Activity playback result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 8098) {
            val original = pendingRecordingFolder ?: IptvRecordingScheduler.rootPath(this)
            pendingRecordingFolder = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { returnToRecordingFolder(original, true); return }
            val flags = (data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            async({
                val path = IptvRecordingScheduler.documentTreePath(uri.toString())
                check(flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                    "Ce dossier ne fournit pas les droits de lecture et d’écriture."
                }
                contentResolver.takePersistableUriPermission(uri, flags)
                IptvRecordingScheduler.storage(this, path).use { check(it.writable()) { "Dossier inaccessible en écriture." } }
                path
            }, failure = { toast(it); returnToRecordingFolder(original, true) }) { path -> returnToRecordingFolder(path, false) }
            return
        }
        if (requestCode == 8097) { recordingPlayback?.stop(); recordingPlayback = null; return }
        if (requestCode == 8095) {
            rememberedFocus = livePlayerFocus.ifBlank { rememberedFocus }
            restoreLiveFocus = true
            if (resultCode == RESULT_OK) {
                val id = data?.getStringExtra(NuvioPlayerActivity.EXTRA_CONTENT_ID).orEmpty()
                val action = data?.getStringExtra(NuvioPlayerActivity.EXTRA_IPTV_NAVIGATION_ACTION).orEmpty()
                if (ready) navigateLivePlayer(id, action) else pendingLiveNavigation = id to action
            }
            return
        }
        if (requestCode == 8096) {
            if (resultCode == RESULT_OK && section == 0 && detail == null) {
                data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
                    searchField?.setText(it); rememberSearch(); runSearch(it, focusResults = true)
                }
            }
            return
        }
        if (requestCode != 8094) return
        val current = pendingEpisode ?: return
        pendingEpisode = null
        if (resultCode != RESULT_OK || data?.getBooleanExtra(NuvioPlayerActivity.EXTRA_IPTV_COMPLETED, false) != true ||
            !store.booleanSetting("autoNextEpisode", true)) return
        val source = sources.firstOrNull { it.id == current.sourceId } ?: return
        async({
            val episodes = if (episodeSequence.any { it.id == current.id }) episodeSequence else provider(source).episodes(
                current.copy(id = itemId(current.sourceId, ContentType.SERIES, current.seriesId), providerId = current.seriesId, type = ContentType.SERIES))
            IptvStore.nextEpisode(current, episodes)?.copy(categoryId = current.categoryId, categoryName = current.categoryName)
        }) { next -> next?.let { play(it, true) } }
    }
    private fun guideRemoteActive() = ready && (guideActive || liveChannelList) && detail == null && navigationMode == 2 &&
        contextPanel == null && sourcePage == null && accountPage == null && recordingPage == null && !previewFullscreen &&
        currentFocus?.tag?.toString()?.startsWith("iptv:") == true

    private fun cancelGuideNumber() {
        remoteGuideNumberRequest?.let(handler::removeCallbacks)
        remoteGuideNumberRequest = null
        remoteGuideNumber.clear()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = IptvRemote.key(event.keyCode)
        if (event.action == KeyEvent.ACTION_DOWN && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) navigationInputVersion++
        if (accountPage != null || recordingPage != null || sourcePage != null) return super.dispatchKeyEvent(event)
        if (event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return super.dispatchKeyEvent(event)
        if (restoringPlaybackFocus && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) {
            playbackReturnFocus = null; restoringPlaybackFocus = false
        }
        if (previewFullscreen) return super.dispatchKeyEvent(event)
        val active = guideRemoteActive()
        val digit = IptvRemote.digit(keyCode)
        if (active && guideActive && digit != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
                val number = remoteGuideNumber.append(digit, event.eventTime)
                remoteGuideNumberRequest?.let(handler::removeCallbacks)
                val version = requestVersion
                val focus = currentFocus
                remoteGuideNumberRequest = Runnable {
                    remoteGuideNumberRequest = null; remoteGuideNumber.clear()
                    if (activityResumed && hasWindowFocus() && guideActive && guideRemoteActive() && version == requestVersion && currentFocus === focus) {
                        val channels = catalogueItems()
                        channels.getOrNull(number - 1)?.let { item ->
                            guideOffset = ((number - 1) / 12) * 12
                            renderGuide(item.id, channels, guideHorizontal?.scrollX)
                        }
                    }
                }.also { handler.postDelayed(it, remoteGuideNumber.timeoutMs) }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) cancelGuideNumber()
        if (!active && !remoteGuidePress.owns(keyCode)) return super.dispatchKeyEvent(event)
        val key = IptvRemote.guide.firstOrNull { it.keyCode == keyCode && !it.long } ?: return super.dispatchKeyEvent(event)
        val action = IptvRemote.action(key, false, store.setting(key.setting(false), key.action.name))
        val longKey = IptvRemote.guide.firstOrNull { it.keyCode == keyCode && it.long }
        val longAction = longKey?.let { IptvRemote.action(it, false, store.setting(it.setting(false), it.action.name)) }
        // Keep the guide's existing cell/time navigation for its default arrows.
        if (active && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) && action == key.action)
            return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            currentFocus?.let { remoteGuidePressTargets[keyCode] = Triple(event.eventTime, it, it.tag) }
        }
        val pressTarget = remoteGuidePressTargets[keyCode]
        if (pressTarget != null && (currentFocus !== pressTarget.second || currentFocus?.tag != pressTarget.third)) {
            remoteGuidePress.cancel(keyCode, pressTarget.first)
            remoteGuidePressTargets.remove(keyCode)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) remoteGuidePressTargets.remove(keyCode)
        val result = remoteGuidePress.handle(keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime,
            action, longAction, event.repeatCount, event.isCanceled)
        if (active && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && longAction != null) {
            val startedAt = event.eventTime
            val focus = currentFocus
            val focusTag = focus?.tag
            handler.postDelayed({
                if (!activityResumed || !hasWindowFocus() || currentFocus !== focus || currentFocus?.tag != focusTag ||
                    !(guideActive || liveChannelList) || navigationMode != 2 || contextPanel != null || sourcePage != null)
                    remoteGuidePress.cancel(keyCode, startedAt)
                else remoteGuidePress.hold(keyCode, startedAt, android.os.SystemClock.uptimeMillis()).action?.let { guideRemoteAction(it) }
            }, remoteGuidePress.longPressMs)
        }
        if (active) result.action?.let {
            if (it == IptvRemoteAction.WATCH && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) currentFocus?.performClick()
            else guideRemoteAction(it)
        }
        return true
    }

    private fun guideRemoteAction(action: IptvRemoteAction, channel: IptvItem? = remoteGuideChannel, program: EpgProgram? = remoteGuideProgram) {
        fun groupOptions(reorder: Boolean = false) {
            val item = channel ?: return
            val source = sources.firstOrNull { it.id == item.sourceId } ?: return
            groupAccess(item.sourceId, item.type, item.categoryId) {
                categoriesFor(source, item.type) { values ->
                    val group = values.firstOrNull { it.id == item.categoryId }
                    if (reorder) reorderChannels(source, item.type, group) { refreshOrganization() }
                    else if (group != null) groupMenu(source, item.type, group) else providerMenu(source, item.type)
                }
            }
        }
        when (action) {
            IptvRemoteAction.IGNORE -> Unit
            IptvRemoteAction.WATCH -> channel?.let { play(it) }
            IptvRemoteAction.RETURN_PLAYER -> (previewItem ?: channel)?.let { play(it, fullscreen = true) }
            IptvRemoteAction.EXTERNAL -> channel?.let { item ->
                if (store.setting("externalPlayerPackage", "").isBlank()) { toast("Choisis le lecteur externe."); showExternalPlayerSettings() }
                else play(item, fullscreen = true, external = true)
            }
            IptvRemoteAction.MENU -> channel?.let { item ->
                groupAccess(item.sourceId, item.type, item.categoryId) {
                    if (program != null && IptvRemote.isProgramContext(item.id, currentFocus?.tag?.toString(), true)) programmeActions(item, program)
                    else channelMenu(item)
                }
            }
            IptvRemoteAction.GROUPS -> { showNavigation(1); focusGroup() }
            IptvRemoteAction.DESCRIPTION -> channel?.let { item ->
                groupAccess(item.sourceId, item.type, item.categoryId) {
                    settingsPanel(program?.title ?: item.title) { rows, dialog ->
                        val format = SimpleDateFormat("HH:mm", Locale.FRANCE)
                        playbackSettingsNote(rows, program?.let { "${format.format(Date(it.start))} – ${format.format(Date(it.end))}\n\n${it.description}" }
                            ?: item.description.ifBlank { "Pas d’information" })
                        settingsPanelRow("Retour") { dialog.dismiss() }.also { rows.addView(it) }
                    }
                }
            }
            IptvRemoteAction.SEARCH -> showSection(0)
            IptvRemoteAction.SETTINGS -> showSettingsMenu()
            IptvRemoteAction.CHANNEL_OPTIONS -> channel?.let { item -> groupAccess(item.sourceId, item.type, item.categoryId) { channelMenu(item) } }
            IptvRemoteAction.GROUP_OPTIONS -> groupOptions()
            IptvRemoteAction.MOVIES -> showSection(2)
            IptvRemoteAction.SERIES -> showSection(3)
            IptvRemoteAction.RECORDINGS -> showSection(4)
            IptvRemoteAction.CUSTOM_RECORDING -> channel?.let { item -> playbackAccess(item) { recordingOptions(item) } }
            IptvRemoteAction.BLOCKING, IptvRemoteAction.VISIBILITY -> channel?.let { item ->
                sources.firstOrNull { it.id == item.sourceId }?.let { source ->
                    manageChannelOrganization(source, item.categoryId, action == IptvRemoteAction.BLOCKING)
                }
            }
            IptvRemoteAction.MY_LIST -> showSection(5)
            IptvRemoteAction.FAVORITE -> channel?.let { item ->
                groupAccess(item.sourceId, item.type, item.categoryId) { changeFavorite(item) { refreshOrganization() } }
            }
            IptvRemoteAction.MANAGE_FAVORITES -> { showSection(5); showSaved(ContentType.LIVE, false) }
            IptvRemoteAction.REORDER -> groupOptions(true)
            IptvRemoteAction.RETURN_NOW -> {
                guideDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                renderGuide(channel?.id.orEmpty())
            }
            IptvRemoteAction.BACK -> onBackPressed()
            IptvRemoteAction.EXIT -> finish()
            IptvRemoteAction.PAGE_UP -> remoteGuidePage?.invoke(false)
            IptvRemoteAction.PAGE_DOWN -> remoteGuidePage?.invoke(true)
            IptvRemoteAction.NEXT_PROGRAMS -> currentFocus?.focusSearch(View.FOCUS_RIGHT)?.requestFocus()
            IptvRemoteAction.HISTORY -> { showSection(5); showSaved(ContentType.LIVE, true) }
            IptvRemoteAction.RECORD -> channel?.let { item -> playbackAccess(item) {
                async({ store.recordings().firstOrNull { it.channel.id == item.id && it.state == RecordingState.RUNNING } }) { running ->
                    if (running == null) recordingDuration(item, System.currentTimeMillis())
                    else async({ IptvRecordingScheduler.cancel(this, store, running) }) { toast("Enregistrement arrêté et conservé.") }
                }
            } }
            else -> Unit
        }
    }

    private fun navigateLivePlayer(id: String, action: String) {
        val remote = if (action.startsWith("remote:")) IptvRemoteAction.entries.firstOrNull { it.name == action.removePrefix("remote:") && it.available } else null
        if (action !in listOf("previous", "next", "guide") && remote == null) return
        val current = visibleItems.firstOrNull { it.id == id } ?: store.rememberedItem(id)
        previewResumePending = false
        stopPreview(true)
        if (action in listOf("previous", "next")) {
            val next = IptvOrganization.adjacentChannel(catalogueItems(), id, action == "next")
            if (next != null) { play(next, fullscreen = true); return }
            toast("Choisis une chaîne dans le Guide TV.")
        }
        detail = null; rememberedFocus = id
        if (section in listOf(1, 5) && visibleItems.any { it.type == ContentType.LIVE }) displayItems(id)
        else {
            current?.let { sourceId = it.sourceId }
            showSection(1)
        }
        showNavigation(2)
        if (remote != IptvRemoteAction.STOP && current != null) {
            IptvRemote.overlayMode(remote ?: if (action == "guide") IptvRemoteAction.GUIDE_OVERLAY else IptvRemoteAction.IGNORE)?.let(::setGuideOverlay)
            if (store.booleanSetting("previewEnabled", true) || guideOverlay) startPreview(current, false)
        }
        when (remote) {
            IptvRemoteAction.RECENT -> store.saved(ContentType.LIVE, true).firstOrNull { it.id != id }?.let { play(it, fullscreen = true) }
            IptvRemoteAction.CHANNELS_OVERLAY, IptvRemoteAction.CHANNELS_PREVIEW -> openChannelList(id)
            IptvRemoteAction.INFO_NEXT, IptvRemoteAction.INFO_PREVIOUS -> showAdjacentPlayerInfo(remote == IptvRemoteAction.INFO_NEXT, current)
            IptvRemoteAction.GROUPS_OVERLAY, IptvRemoteAction.GROUPS_PREVIEW -> { openGuide(id); showNavigation(1); focusGroup() }
            IptvRemoteAction.GUIDE_OVERLAY, IptvRemoteAction.GUIDE_PREVIEW -> openGuide(id)
            IptvRemoteAction.STOP -> Unit
            null -> if (action == "guide") openGuide(id)
            else -> guideRemoteAction(remote, current, null)
        }
    }

    private fun receiveCompanionSource(incoming: IptvSource, handle: CompanionImportStatus) {
        if (!ready || isFinishing || companionServer == null) {
            handle.finish(false, "Jojo Home termine son démarrage. Réessaie depuis Companion dans quelques instants."); return
        }
        val contentSelection = phoneContentSelection?.takeIf { it.first == incoming.type }
        sourcePage?.dismiss()
        val previous = sources.firstOrNull { it.type == incoming.type && it.url == incoming.url && it.username == incoming.username && it.mac.equals(incoming.mac, true) }
        val source = incoming.copy(id = previous?.id ?: incoming.id,
            epgUrl = incoming.epgUrl.ifBlank { if (incoming.type == SourceType.M3U) "" else previous?.epgUrl.orEmpty() },
            includeLive = contentSelection?.second ?: previous?.includeLive ?: incoming.includeLive,
            includeVod = contentSelection?.third ?: previous?.includeVod ?: incoming.includeVod)
        handle.status = "importing"; handle.message = "Vérification du fournisseur sur la TV…"
        status.text = handle.message
        work.execute {
            try {
                providerFor(source).testConnection()
                store.save(source)
                handle.finish(true, "Fournisseur enregistré dans JojoHome IPTV.")
                handler.post {
                    if (!isFinishing) {
                        sources = store.sources(); sourceId = source.id; invalidateProviders(source.id)
                        showSection(if (section in 1..3) section else 1); toast(handle.message)
                    }
                }
            } catch (failure: Exception) {
                android.util.Log.w("JojoHomeIPTV", "Companion import failed: ${failure.javaClass.name}")
                handle.finish(false, companionImportFailureMessage(source.type, failure))
                handler.post { if (!isFinishing) { status.text = handle.message; toast(handle.message) } }
            }
        }
    }
    private fun sourceForm(type: SourceType, existing: IptvSource?, onBack: (() -> Unit)? = null, onSaved: (() -> Unit)? = null) {
        sourcePage?.dismiss()
        phoneContentSelection = null
        var editingPanel: Dialog? = null
        var phoneDialog: AlertDialog? = null
        var optionalDialog: AlertDialog? = null
        val optionalFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(surface(.07f))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (existing != null) { setPadding(dp(22), dp(16), dp(22), dp(16)); setBackgroundColor(themeBackground) }
        }
        if (existing != null) form.addView(text("Modifier · ${existing.name}", 23, true))
        fun field(label: String, value: String = "", secret: Boolean = false, multiline: Boolean = false): EditText {
            val input = settingsInput().apply {
                setText(value); setTextColor(themeForeground); textSize = 16f; setSingleLine(!multiline)
                inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else
                    if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            if (existing != null) {
                form.addView(text(label, 13).apply { setPadding(0, dp(12), 0, dp(3)) })
                form.addView(input, LinearLayout.LayoutParams(-1, if (multiline) dp(90) else dp(44)))
            } else {
                fun caption() = if (secret && input.text.isNotBlank()) "••••••••" else input.text.toString()
                val row = settingsPanelRow(label, caption()) {
                    settingsPanel(label) { rows, _ ->
                        (input.parent as? ViewGroup)?.removeView(input)
                        input.setTextColor(themeForeground)
                        rows.addView(input, LinearLayout.LayoutParams(-1, if (multiline) dp(120) else dp(44)))
                        input
                    }
                    editingPanel = contextPanel
                    editingPanel?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val editor = editingPanel
                    editor?.setOnDismissListener {
                        if (contextPanel === editor) contextPanel = null
                        (form.findViewWithTag<View>(input) ?: optionalFields.findViewWithTag<View>(input))?.requestFocus()
                    }
                    input.setOnEditorActionListener { _, action, _ ->
                        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { editingPanel?.dismiss(); true }
                        else false
                    }
                }.apply { tag = input }
                val labels = row.getChildAt(0) as LinearLayout
                val summary = text(caption(), 12).apply {
                    setTextColor(surface(.68f)); setPadding(0, dp(4), 0, 0)
                }
                // Keep one summary view so a cleared field also refreshes its row.
                if (labels.childCount > 1) labels.removeViewAt(1)
                labels.addView(summary)
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        summary.text = caption(); summary.visibility = if (summary.text.isBlank()) View.GONE else View.VISIBLE
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                summary.visibility = if (summary.text.isBlank()) View.GONE else View.VISIBLE
                form.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
            }
            return input
        }
        val name = field(if (existing == null) "Nom de la liste de lecture (facultatif)" else "Nom du fournisseur", existing?.name.orEmpty())
        val url = field(if (type == SourceType.M3U) "URL de la playlist M3U" else if (type == SourceType.STALKER) "Adresse du portail" else "Adresse du serveur", existing?.url.orEmpty())
        url.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val user = if (type == SourceType.XTREAM) field("Nom d'utilisateur", existing?.username.orEmpty()) else null
        val password = if (type == SourceType.XTREAM) field("Mot de passe", existing?.password.orEmpty(), true) else null
        val mac = if (type == SourceType.STALKER) field("Adresse MAC autorisée", existing?.mac.orEmpty()) else null
        val device = if (type == SourceType.STALKER) field("Identifiant du terminal (si demandé)", existing?.deviceId.orEmpty()) else null
        val epg = if (type == SourceType.M3U) field("URL XMLTV (facultative)", existing?.epgUrl.orEmpty()) else null
        val headers = field("Headers HTTP (un par ligne : Nom: valeur)", existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty(), multiline = true)
        fun include(label: String, checked: Boolean): CheckBox {
            val checkbox = CheckBox(this).apply {
                isChecked = checked; setTextColor(themeForeground)
                buttonTintList = android.content.res.ColorStateList.valueOf(themeForeground)
            }
            if (existing != null) { checkbox.text = label; form.addView(checkbox) }
            else {
                checkbox.isFocusable = false; checkbox.isClickable = false
                checkbox.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                form.addView(settingsPanelRow(label) { checkbox.isChecked = !checkbox.isChecked }.apply {
                    addView(checkbox, 0, LinearLayout.LayoutParams(dp(28), dp(36)))
                }, LinearLayout.LayoutParams(-1, dp(52)))
            }
            return checkbox
        }
        val includeLive = include("Inclure les chaînes de télévision", existing?.includeLive ?: true)
        val includeVod = include("Inclure la VOD", existing?.includeVod ?: true)
        if (existing == null) {
            // Keep the connection step focused on the chosen service's required fields.
            listOf(name, epg, device, headers).filterNotNull().forEach { input ->
                form.findViewWithTag<View>(input)?.let { row ->
                    form.removeView(row); optionalFields.addView(row)
                    row.setOnKeyListener { _, code, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) { optionalDialog?.dismiss(); true }
                        else false
                    }
                }
            }
        }
        val state = text("", 14)
        if (existing == null) {
            state.setTextColor(themeForeground); state.visibility = View.GONE
            state.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { state.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        form.addView(state)
        var phonePanel: LinearLayout? = null
        if (existing == null) {
            val phone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), dp(20), dp(18), dp(12)); setBackgroundColor(themeBackground) }
            phonePanel = phone
            updatePhoneSetup = {
                phone.removeAllViews()
                phone.addView(text("Saisir sur mon téléphone", 19, true))
                val server = companionServer
                val ip = iptvLanAddress()
                if (server != null && ip != null) {
                    val link = server.phoneUrl(ip, type)
                    val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 384, 384, mapOf(EncodeHintType.MARGIN to 4))
                    val pixels = IntArray(384 * 384) { index -> if (matrix[index % 384, index / 384]) Color.BLACK else Color.WHITE }
                    val bitmap = Bitmap.createBitmap(pixels, 384, 384, Bitmap.Config.ARGB_8888)
                    phone.addView(ImageView(this).apply { setImageBitmap(bitmap); contentDescription = "QR code du formulaire mobile JojoHome IPTV" }, LinearLayout.LayoutParams(dp(225), dp(225)).apply { topMargin = dp(14); bottomMargin = dp(14) })
                    phone.addView(text("Scanne ce QR code et envoie les données : le fournisseur sera ajouté automatiquement.\nTéléphone et TV sur le même réseau.", 14))
                    phone.addView(text(link, 11).apply { setPadding(0, dp(12), 0, 0) })
                    phone.addView(text("Companion NAS\n${companionNasUrl().ifBlank { "Non configuré dans Jojo Home" }}\nTV : $ip · port ${server.listeningPort}", 13).apply { setPadding(0, dp(18), 0, 0) })
                } else phone.addView(text(companionError.ifBlank { "Connecte la TV au réseau local pour afficher le QR code." }, 14))
            }
            updatePhoneSetup?.invoke()
        }
        val page = IptvContentPage().apply {
            onDismiss = { editingPanel?.dismiss(); phoneDialog?.dismiss(); optionalDialog?.dismiss() }
        }
        fun draft(): IptvSource {
            if (existing != null) require(name.text.isNotBlank()) { "Donne un nom au fournisseur." }
            require(includeLive.isChecked || includeVod.isChecked) { "Inclus les chaînes de télévision ou la VOD." }
            if (type == SourceType.XTREAM) require(!user?.text.isNullOrBlank() && !password?.text.isNullOrBlank()) { "Renseigne l’utilisateur et le mot de passe." }
            val macValue = mac?.text?.toString()?.trim().orEmpty()
            if (type == SourceType.STALKER) require(macValue.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) { "Saisis une MAC au format 00:1A:79:00:00:00." }
            val httpHeaders = headers.text.lines().filter(String::isNotBlank).associate { line ->
                require(':' in line) { "Chaque header doit être au format Nom: valeur." }
                val key = line.substringBefore(':').trim()
                require(key.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+"))) { "Un nom de header est invalide." }
                key to line.substringAfter(':').trim()
            }
            val epgValue = (epg?.text?.toString() ?: existing?.epgUrl.orEmpty()).trim()
            if (epgValue.isNotBlank()) httpUrl(epgValue)
            val address = httpUrl(url.text.toString())
            return IptvSource(existing?.id ?: UUID.randomUUID().toString(),
                name.text.toString().trim().ifBlank { "${sourceTypeLabel(type)} · ${address.toHttpUrl().host}" }, type,
                address, user?.text?.toString()?.trim().orEmpty(), password?.text?.toString().orEmpty(),
                macValue, device?.text?.toString()?.trim().orEmpty(), epgValue, httpHeaders, includeLive.isChecked, includeVod.isChecked,
                dns = existing?.dns)
        }
        var testing = false
        if (existing != null) form.addView(button("Tester la connexion") {
            if (!testing) try {
                val source = draft()
                testing = true; state.text = "Test en cours…"
                async({ providerFor(source).testConnection() }, failure = { testing = false; state.text = it }) {
                    if (page.isShowing) { testing = false; state.text = it }
                }
            } catch (failure: IllegalArgumentException) { state.text = failure.message }
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val save = settingsPanelRow(if (existing == null) "Suivant  ›" else "Enregistrer") {
            if (!testing) {
                try {
                    val source = draft()
                    testing = true; state.text = "Vérification de la liste de lecture…"
                    async({
                        if (existing == null) providerFor(store.connectionSource(source), udpProxy = store.setting("udpProxy", "")).testConnection()
                        store.save(source); store.sources()
                    }, failure = { testing = false; state.text = it }) { values ->
                        if (page.isShowing) {
                            sources = values; sourceId = source.id; invalidateProviders(source.id); page.dismiss()
                            showSection(if (section in 1..3) section else 1)
                            onSaved?.invoke()
                        }
                    }
                } catch (failure: IllegalArgumentException) { state.text = failure.message }
            }
        }
        actions.addView(save, LinearLayout.LayoutParams(-1, dp(52)))
        actions.addView(settingsPanelRow(if (existing == null) "Précédent" else "Annuler") { page.cancel() }, LinearLayout.LayoutParams(-1, dp(52)))
        if (existing == null) {
            actions.addView(settingsPanelRow("Options supplémentaires") {
                (optionalFields.parent as? ViewGroup)?.removeView(optionalFields)
                optionalDialog = AlertDialog.Builder(this).setView(optionalFields).setPositiveButton("Fermer", null).create().also { options ->
                    options.show()
                    options.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    options.window?.setLayout(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT)
                    optionalFields.getChildAt(0)?.requestFocus()
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
            actions.addView(settingsPanelRow("Saisir sur mon téléphone") {
                if (!includeLive.isChecked && !includeVod.isChecked) state.text = "Inclus les chaînes de télévision ou la VOD."
                else {
                    phoneContentSelection = Triple(type, includeLive.isChecked, includeVod.isChecked)
                    phonePanel?.let { phone ->
                        (phone.parent as? ViewGroup)?.removeView(phone)
                        phoneDialog = AlertDialog.Builder(this).setView(phone).setPositiveButton("Fermer", null).create().also { it.show() }
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(72)))
            fun updateNext() {
                val complete = (includeLive.isChecked || includeVod.isChecked) && url.text.isNotBlank() &&
                    (type != SourceType.XTREAM || !user?.text.isNullOrBlank() && !password?.text.isNullOrBlank()) &&
                    (type != SourceType.STALKER || mac?.text.toString().trim().matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")))
                save.isEnabled = complete
                ((save.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(if (complete) themeForeground else surface(.4f))
            }
            listOfNotNull(url, user, password, mac).forEach { input -> input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateNext() }
                override fun afterTextChanged(s: Editable?) = Unit
            }) }
            includeLive.setOnCheckedChangeListener { _, _ -> updateNext() }
            includeVod.setOnCheckedChangeListener { _, _ -> updateNext() }
            updateNext()
        }
        showPlaylistWizard(page, if (existing == null) "Informations de connexion" else "Modifier la connexion",
            R.drawable.ic_menu_open, form, actions, onBack, form.getChildAt(0))
    }

    private fun dialog(title: String, actions: List<Pair<String, () -> Unit>>) {
        val origin = currentFocus
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18)); setBackgroundColor(themeBackground) }
        panel.addView(text(title, 21, true).apply { setPadding(0, 0, 0, dp(14)) })
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFocusable = false; addView(rows) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        actions.forEach { (label, action) -> rows.addView(button(label) { dialog.dismiss(); action() }.apply {
            minimumHeight = dp(48)
        }, LinearLayout.LayoutParams(-1, -2)) }
        wireVerticalFocus(rows)
        for (index in 0 until rows.childCount) rows.getChildAt(index).setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN || key !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false
            else {
                val next = (index + if (key == KeyEvent.KEYCODE_DPAD_UP) rows.childCount - 1 else 1) % rows.childCount
                rows.getChildAt(next).let { target ->
                    target.requestFocus(); target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
                }
                true
            }
        }
        dialog.setContentView(panel)
        dialog.setOnDismissListener {
            if (window.decorView.hasWindowFocus() && origin?.isShown == true) origin.requestFocus()
        }
        dialog.show()
        val width = minOf(dp(500), contentHost.width - dp(32)).coerceAtLeast(1)
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(panel.measuredHeight, (contentHost.height * .88f).toInt()).coerceAtLeast(1)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            attributes = attributes.apply { x = dp(16) }
            setLayout(width, height)
        }
        rows.getChildAt(0)?.requestFocus()
    }
    private fun message(title: String, description: String = "") {
        setVodHeroVisible(false)
        info.removeAllViews()
        info.addView(text(title, 28, true).apply {
            if (detail == null && section in listOf(1, 2, 3, 5) && (title == categoryTitle || title == selectedSource()?.name)) {
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
            }
        })
        info.addView(text(description, 16).apply { setPadding(0, dp(14), 0, 0) })
        results.removeAllViews()
        status.text = ""
    }
    private fun recordingSidebarIcon() = object : android.graphics.drawable.Drawable() {
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = themeForeground; style = android.graphics.Paint.Style.STROKE
        }
        private val center = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val size = minOf(bounds.width(), bounds.height()).toFloat()
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            ring.strokeWidth = size * .08f
            canvas.drawCircle(x, y, size * .44f, ring)
            canvas.drawCircle(x, y, size * .14f, center)
        }
        override fun getIntrinsicWidth() = dp(24)
        override fun getIntrinsicHeight() = dp(24)
        override fun setAlpha(alpha: Int) { ring.alpha = alpha; center.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { ring.colorFilter = filter; invalidateSelf() }
        @Deprecated("Drawable opacity") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun command(label: String, icon: Int, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; isFocusable = true; id = View.generateViewId(); setPadding(dp(10), 0, dp(10), 0); contentDescription = label }
        val image = ImageView(this).apply { setImageResource(icon); setColorFilter(themeForeground) }
        if (icon == R.drawable.ic_home_category_live_signal) {
            val baseUrl = getSharedPreferences("home_settings", MODE_PRIVATE)
                .getString("nas_url", "http://192.168.1.32:8095").orEmpty().trimEnd('/')
            val url = "$baseUrl/api/ui-icons/home_category_live_tv_v1.png"
            val cached = bitmaps.get(url)
            if (cached != null) image.setImageBitmap(cached) else images.execute {
                val bitmap = runCatching {
                    IptvHttp.read(url.toHttpUrl(), emptyMap()) { BitmapFactory.decodeStream(it.byteStream()) }
                }.getOrNull()
                if (bitmap != null) {
                    bitmaps.put(url, bitmap)
                    runOnUiThread { if (!isDestroyed) image.setImageBitmap(bitmap) }
                }
            }
        }
        row.addView(image, LinearLayout.LayoutParams(dp(24), dp(24)))
        row.addView(text(label, 16).apply { setPadding(dp(12), 0, 0, 0) })
        row.setOnClickListener { onClick() }
        return row
    }
    private fun paintCommand(row: LinearLayout, focused: Boolean, selected: Boolean) {
        row.setBackground(shape(if (focused) accent else if (selected) interfaceBackground(surface(.12f)) else Color.TRANSPARENT, focused))
        val foreground = selectionForeground(focused, selected)
        when (val icon = row.getChildAt(0)) {
            is ImageView -> icon.setColorFilter(foreground)
            is FrameLayout -> styleAccountAvatar(icon, focused)
        }
        (row.getChildAt(1) as TextView).setTextColor(foreground)
    }
    private fun button(label: String, onClick: () -> Unit): TextView = text(label, 16).apply {
        id = View.generateViewId(); isFocusable = true; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackground(shape(interfaceBackground(surface(.08f))))
        setOnFocusChangeListener { _, focused -> setBackground(shape(if (focused) accent else interfaceBackground(surface(.08f)), focused)); setTextColor(if (focused) contrast else themeForeground) }
        setOnClickListener { onClick() }
    }
    private fun text(value: String, size: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat() * textScale; setTextColor(themeForeground)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun shape(color: Int, focused: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(9).toFloat()
        if (focused && focusNeedsOutline) setStroke(dp(1), focusOutlineColor())
    }
    private fun focusOutlineColor() = if (contrast == Color.WHITE) Color.WHITE else Color.TRANSPARENT
    private fun surface(amount: Float): Int {
        val primary = themeSurface ?: return ColorUtils.blendARGB(themeBackground, themeForeground, amount)
        val raised = themeRaisedSurface ?: primary
        // Preserve the imported panel and header colors instead of washing them out with white.
        return when {
            amount <= .07f -> ColorUtils.blendARGB(themeBackground, primary, (amount / .07f).coerceIn(0f, 1f))
            amount < .16f -> ColorUtils.blendARGB(primary, raised, ((amount - .07f) / .09f).coerceIn(0f, 1f))
            else -> ColorUtils.blendARGB(raised, themeForeground, ((amount - .16f) / .84f).coerceIn(0f, 1f))
        }
    }
    private fun selectionForeground(focused: Boolean, selected: Boolean): Int {
        if (focused) return contrast
        if (!selected) return themeForeground
        val color = selectedTextColor ?: themeForeground
        val background = surface(.12f)
        return if (ColorUtils.calculateContrast(color, background) >= 4.5) color else readableThemeForeground(background)
    }
    private fun readableThemeForeground(background: Int): Int {
        val foreground = MainActivity.contrastingThemeTextColor(background)
        if (ColorUtils.calculateContrast(foreground, background) >= 4.5) return foreground
        return if (ColorUtils.calculateContrast(Color.WHITE, background) > ColorUtils.calculateContrast(Color.BLACK, background)) Color.WHITE else Color.BLACK
    }
    private fun accentTextColor(): Int {
        val color = selectedTextColor ?: return if (focusNeedsOutline) themeForeground else accent
        return if (ColorUtils.calculateContrast(color, themeBackground) >= 4.5) color else themeForeground
    }
    private fun interfaceBackground(color: Int) = if (guideOverlay && Color.alpha(color) != 0)
        ColorUtils.setAlphaComponent(color, interfaceOpacity) else color
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    @Suppress("DEPRECATION")
    private fun exitConfirmationToast(): Toast {
        // This foreground-only message carries its own JojoHome icon.
        val message = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(50, 61, 77)); cornerRadius = dp(28).toFloat()
            }
            addView(ImageView(this@IptvActivity).apply {
                setImageResource(R.drawable.jojo_home_icon_install_transparent)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
            addView(text("Appuie encore sur Retour pour quitter IPTV.", 16).apply {
                setTextColor(Color.WHITE); maxLines = 2
            })
        }
        return Toast(this).apply { duration = Toast.LENGTH_SHORT; view = message }
    }
    private fun firstContent(): View? {
        if (guideActive && detail == null) {
            val names = (results.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ViewGroup
            (names?.findViewWithTag<View>(rememberedFocus) ?: names?.getChildAt(1))?.let { return it }
        }
        loadVisibleCardRows()
        orderedCardRows().firstOrNull()?.let { if (it.childCount == 0) cardRows[it]?.invoke() }
        fun find(parent: ViewGroup): View? {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child.visibility != View.VISIBLE || !child.isEnabled) continue
                val tag = child.tag as? String
                if (child.isFocusable && (section != 0 || detail != null || searchQuery.isBlank() ||
                    tag?.startsWith("iptv:") == true || tag?.startsWith("program:") == true || tag?.startsWith("search-retry:") == true)) return child
                if (child is ViewGroup) find(child)?.let { return it }
            }
            return null
        }
        return find(results)
    }
    private fun wireVerticalFocus(parent: ViewGroup) {
        val rows = (0 until parent.childCount).map(parent::getChildAt).filter { it.isFocusable && it.isEnabled && it.visibility == View.VISIBLE }
        rows.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        rows.forEachIndexed { index, row ->
            row.nextFocusUpId = rows[(index + rows.size - 1) % rows.size].id
            row.nextFocusDownId = rows[(index + 1) % rows.size].id
        }
    }

    private fun leftToGroups(view: View) {
        view.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && section == 0 && detail == null &&
                key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) moveSearchFocus(view, key)
            else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                showNavigation(1)
                (if (section == 0) searchField else activeGroup?.takeIf { it.isAttachedToWindow } ?: groupRows.firstOrNull() ?: navRows[section])?.requestFocus() ?: false
            } else false
        }
    }
    @Deprecated("TV back navigation")
    override fun onBackPressed() {
        contextPanel?.takeIf { it.isShowing }?.let { it.cancel(); return }
        sourcePage?.let { it.cancel(); return }
        recordingPageBack?.let { it(true); return }
        if (accountPage != null) {
            accountPageBack?.let { it(); return }
            if (accountChosen) { closeContentPages(); showSection(section, true); accountNavRow.requestFocus() }
            else { exitToast?.cancel(); exitToast = null; finish() }
            return
        }
        if (previewFullscreen) { exitPreviewFullscreen(); return }
        if (section == 0 && detail == null && searchKeyboardOpen) {
            searchKeyboardOpen = false
            searchField?.let { input ->
                input.showSoftInputOnFocus = false
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
            }
            rememberSearch()
            return
        }
        epgVersion++
        if (guideActive) {
            val today = Calendar.getInstance()
            if (store.booleanSetting("backToNow", true) && (guideDay.get(Calendar.YEAR) != today.get(Calendar.YEAR) || guideDay.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR) ||
                    kotlin.math.abs((guideHorizontal?.scrollX ?: guideNowPosition) - guideNowPosition) > dp(72))) {
                guideDay = today.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                renderGuide(rememberedFocus)
                return
            }
        }
        if (detail != null) { detail = null; requestVersion++; displayItems(rememberedFocus) }
        else if (section == 0) {
            val saved = searchReturnSaved
            showSection(searchReturnSection)
            if (saved != null) {
                rememberedFocus = saved.focusId
                showSaved(saved.type, saved.history); showNavigation(2); focusVodCard(saved.focusId)
            }
            else { showNavigation(0); navRows[0].requestFocus() }
        }
        else if (section == 6 && settingsPage in listOf("guideAppearance", "previewAppearance", "groupsAppearance", "logosAppearance")) {
            val focus = when (settingsPage) { "guideAppearance" -> "guide"; "previewAppearance" -> "player"; "groupsAppearance" -> "groups"; else -> "logos" }
            settingsPage = "appearance"; showAppearanceSettings(focus)
        }
        else if (section == 6 && settingsPage in listOf("searchOptions", "recordingOptions", "vodOptions", "reminderOptions")) {
            val focus = when (settingsPage) { "searchOptions" -> "search"; "recordingOptions" -> "recording"; "vodOptions" -> "vod"; else -> "reminders" }
            settingsPage = "advanced"; showAdvancedSettings(focus)
        }
        else if (section == 6 && settingsPage in listOf("remote", "about")) {
            val focus = settingsPage; settingsPage = "general"; showSettingsMenu(focus)
        }
        else if (navigationMode == 2 && section in listOf(1, 2, 3, 5)) { showNavigation(1); focusGroup() }
        else if (navigationMode != 0) { showNavigation(0); navRows[section].requestFocus() }
        else if (ready && store.booleanSetting("confirmExit", true) && android.os.SystemClock.elapsedRealtime() - exitBackAt > 2000) {
            exitBackAt = android.os.SystemClock.elapsedRealtime()
            exitToast?.cancel()
            exitToast = exitConfirmationToast().also { it.show() }
        } else {
            exitToast?.cancel(); exitToast = null
            super.onBackPressed()
        }
    }

    private fun <T> async(task: () -> T, version: Int? = requestVersion,
        failure: (String) -> Unit = { status.text = it }, success: (T) -> Unit) {
        work.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed || isFinishing || version != null && version != requestVersion) return@runOnUiThread
                result.fold(success) { problem ->
                    // Network/parser exceptions can contain account URLs. Show only our controlled messages.
                    failure(if (problem is IllegalStateException)
                        problem.message ?: "Connexion indisponible." else "Connexion indisponible ou réponse du fournisseur incompatible.")
                }
            }
        }
    }
    private fun resetArtworkRequests() {
        vodFocusRequest++
        vodCards = null; vodCardItems.clear()
        vodCardProgressGeneration++; vodCardProgress.clear()
        vodCardProgressTask?.cancel(true); vodCardProgressTask = null
        vodSeasonScroll = null; vodEpisodeScroll = null
        vodHeroRequest?.let(handler::removeCallbacks); vodHeroRequest = null
        vodHeroScope?.cancel(); vodHeroScope = null; vodHeroItemId = ""
        artworkScope.cancel()
        artworkViewScopes.values.forEach { it.cancel() }; artworkViewScopes.clear()
        pendingArtwork.clear()
        artworkLoads.clear(); artworkPlaceholders.clear(); cardRows.clear(); cardItems.clear()
        artworkScope = IptvRequestScope(IptvHttp.artworkClient)
    }
    private fun restoreArtworkPlaceholder(view: ImageView) {
        val icon = artworkPlaceholders[view]?.first
        if (icon == null) view.setImageDrawable(null) else {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.setImageResource(icon)
            val parent = view.parent as? View
            val seriesEpisode = parent?.tag == "series_episode_artwork"
            val focused = (if (seriesEpisode) parent?.parent as? View else parent)?.hasFocus() == true
            val homeArtwork = seriesEpisode || vodCards != null && parent?.parent === vodCards
            if (homeArtwork) view.setBackgroundColor(if (focused) accent else Color.TRANSPARENT)
            view.setColorFilter(if (focused) contrast else if (homeArtwork) Color.WHITE else themeForeground)
        }
    }
    private fun artworkViewport() = android.graphics.Rect().also {
        // Keep the neighboring row ready while distant rows remain recycled.
        if (results.getGlobalVisibleRect(it)) it.inset(0, -dp(240))
    }
    private fun isNearArtworkViewport(view: View, viewport: android.graphics.Rect, position: IntArray): Boolean {
        if (!view.isShown || viewport.isEmpty) return false
        view.getLocationOnScreen(position)
        return position[0] < viewport.right && position[0] + view.width > viewport.left &&
            position[1] < viewport.bottom && position[1] + view.height > viewport.top
    }
    private fun loadVisibleArtwork() {
        if (isDestroyed || isFinishing) { pendingArtwork.clear(); return }
        if (!activityResumed) return
        loadVisibleCardRows()
        val bounds = android.graphics.Rect()
        val viewport = artworkViewport()
        val position = IntArray(2)
        val ready = mutableListOf<() -> Unit>()
        val iterator = artworkLoads.iterator()
        while (iterator.hasNext()) {
            val (view, _) = iterator.next()
            if (!view.isAttachedToWindow) {
                artworkViewScopes.remove(view)?.cancel()
                iterator.remove(); pendingArtwork.remove(view); artworkPlaceholders.remove(view)
            } else if (view.isShown && (view.getGlobalVisibleRect(bounds) || isNearArtworkViewport(view, viewport, position))) {
                pendingArtwork.remove(view)?.let(ready::add)
            }
        }
        ready.forEach { it() }
    }
    private fun loadImage(view: ImageView, url: String, item: IptvItem? = null, targetWidth: Int = 1000, targetHeight: Int = 1000) {
        val landscapeHero = ::vodHeroArtwork.isInitialized && view === vodHeroArtwork
        val heroArtwork = view === backdrop || landscapeHero
        val selected = item?.takeIf { it.type != ContentType.LIVE }?.let { selectedVodArtwork(it, heroArtwork) }.orEmpty()
        val urls = if (landscapeHero && item != null)
            IptvMediaPresentation.heroArtworkCandidates(item, mediaDetails.get(mediaKey(item)), selected)
        else listOf(selected, url, item?.artwork.orEmpty(), item?.backdrop.orEmpty()).filter(String::isNotBlank).distinct()
        val imageScale = artworkPlaceholders[view]?.second ?: view.scaleType
        val crop = !heroArtwork && imageScale == ImageView.ScaleType.CENTER_CROP
        val policy = if (landscapeHero) ":landscape-hero-v1" else ""
        val key = "${item?.sourceId.orEmpty()}:${targetWidth}x$targetHeight:$crop$policy:${urls.joinToString("|")}"
        if (view.tag == key && (view.drawable is android.graphics.drawable.BitmapDrawable || view in artworkLoads)) return
        val scope = artworkScope
        val source = sources.firstOrNull { it.id == item?.sourceId }?.let(store::connectionSource)
        val headers = source?.headers.orEmpty() + item?.headers.orEmpty()
        view.tag = key
        artworkViewScopes.remove(view)?.cancel()
        pendingArtwork.remove(view); artworkLoads.remove(view); artworkPlaceholders.remove(view)
        view.clearColorFilter()
        bitmaps.get(key)?.let { view.scaleType = imageScale; view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        if (item != null && !heroArtwork) {
            artworkPlaceholders[view] = when (item.type) {
                ContentType.LIVE -> R.drawable.ic_home_category_live_signal
                ContentType.MOVIE -> R.drawable.ic_home_category_movies
                else -> R.drawable.ic_home_category_series
            } to imageScale
            restoreArtworkPlaceholder(view)
        }
        if (urls.isEmpty()) { artworkPlaceholders.remove(view); return }
        var attempts = 0
        val load: () -> Unit = load@{
            bitmaps.get(key)?.let { view.clearColorFilter(); view.scaleType = imageScale; view.setImageBitmap(it); return@load }
            val requestScope = IptvRequestScope(if (attempts++ == 0) IptvHttp.artworkClient else IptvHttp.artworkRetryClient)
            artworkViewScopes[view] = requestScope
            images.execute {
                if (isDestroyed || runCatching { scope.checkActive(); requestScope.checkActive() }.isFailure) return@execute
                var bitmap: Bitmap? = null
                var retryable = false
                for (candidate in urls) {
                    val result = runCatching { IptvHttp.withScope(requestScope) {
                        IptvHttp.read(candidate.toHttpUrl(), artworkHeaders(candidate, source, headers), dns = source?.dns) { body ->
                            val file = File.createTempFile("iptv-artwork-", ".tmp", cacheDir)
                            try {
                                body.byteStream().use { input -> file.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var total = 0
                                    while (true) {
                                        IptvHttp.checkActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(total <= 8 * 1024 * 1024) { "Illustration trop volumineuse." }
                                        output.write(buffer, 0, count)
                                    }
                                }
        }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, bounds)
                            // Provider backdrop fields and saved choices can contain a portrait poster.
                            if (landscapeHero && !IptvArtworkSizing.isLandscape(bounds.outWidth, bounds.outHeight)) return@read null
                            val size = IptvArtworkSizing.size(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight, crop)
                            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = size.sample })?.let { decoded ->
                                val resized = Bitmap.createScaledBitmap(decoded, size.width, size.height, true).also { resized ->
                                    if (resized !== decoded) decoded.recycle()
                                }
                                if (crop) {
                                    val cropWidth = minOf(targetWidth, resized.width)
                                    val cropHeight = minOf(targetHeight, resized.height)
                                    Bitmap.createBitmap(resized, (resized.width - cropWidth) / 2, (resized.height - cropHeight) / 2, cropWidth, cropHeight).also { cropped ->
                                        if (cropped !== resized) resized.recycle()
                                    }
                                } else resized
                            }
                        } finally { file.delete() }
                    }
                } }
                bitmap = result.getOrNull()
                retryable = retryable || artworkRetryable(result.exceptionOrNull())
                if (bitmap != null || runCatching { scope.checkActive(); requestScope.checkActive() }.isFailure) break
            }
            val loaded = bitmap
            if (loaded != null) {
                bitmaps.put(key, loaded)
                runOnUiThread {
                    if (!isDestroyed && view.tag == key && scope === artworkScope && artworkViewScopes[view] === requestScope && artworkLoads.containsKey(view)) {
                        if (activityResumed && view.isAttachedToWindow) {
                            view.clearColorFilter(); view.scaleType = imageScale; view.setImageBitmap(loaded)
                        } else artworkLoads[view]?.let { pendingArtwork[view] = it }
                    }
                }
            } else runOnUiThread {
                if (artworkViewScopes[view] === requestScope) {
                    if (retryable && attempts < 2 && !isDestroyed && !isFinishing) {
                        // Queue the retry behind other visible posters, and never revive a recycled view.
                        handler.postDelayed({
                            if (!isDestroyed && !isFinishing && scope === artworkScope && view.tag == key &&
                                artworkViewScopes[view] === requestScope) {
                                artworkLoads[view]?.let { pendingArtwork[view] = it }
                                loadVisibleArtwork()
                            }
                        }, 750)
                    } else {
                        artworkViewScopes.remove(view)
                        artworkLoads.remove(view)
                    }
                }
            }
        } }
        artworkLoads[view] = load
        pendingArtwork[view] = load
        view.post { loadVisibleArtwork() }
    }
}
