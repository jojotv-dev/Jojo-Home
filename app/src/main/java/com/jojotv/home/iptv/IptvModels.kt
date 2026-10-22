package com.jojotv.home.iptv

import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.net.URI
import java.security.MessageDigest
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

@Serializable enum class SourceType { M3U, XTREAM, STALKER }
@Serializable enum class ContentType { LIVE, MOVIE, SERIES, EPISODE }

@Serializable
data class IptvAccount @JvmOverloads constructor(val id: String, val name: String, val avatar: String = "",
    val ornamentColor: String = "#D3A033") {
    fun ornamentColorValue(): Int = if (ornamentColor.matches(Regex("#[0-9a-fA-F]{6}")))
        ornamentColor.substring(1).toInt(16) or 0xff000000.toInt() else 0xffd3a033.toInt()
}

@Serializable
data class IptvAvatar(val id: String, val group: String, val name: String,
    val image: String = "", val emoji: String = "", val color: String = "") {
    fun initialColor(): Int? {
        if (!color.matches(Regex("#[0-9a-fA-F]{6}"))) return null
        return color.substring(1).toInt(16) or 0xff000000.toInt()
    }
}

@Serializable
internal data class IptvAvatarCatalog(val version: Int = 1, val avatars: List<IptvAvatar> = emptyList())

@Serializable
data class IptvSource(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val deviceId: String = "",
    val epgUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val includeLive: Boolean = true,
    val includeVod: Boolean = true,
    val dns: IptvDnsConfig? = null,
) {
    fun includes(type: ContentType): Boolean = if (type == ContentType.LIVE) includeLive else includeVod
}

@Serializable
data class IptvItem(
    val id: String,
    val sourceId: String,
    val providerId: String,
    val type: ContentType,
    val title: String,
    val categoryId: String = "",
    val categoryName: String = "",
    val artwork: String = "",
    val backdrop: String = "",
    val streamUrl: String = "",
    val command: String = "",
    val epgId: String = "",
    val description: String = "",
    val year: String = "",
    val genre: String = "",
    val rating: String = "",
    val duration: String = "",
    val cast: String = "",
    val director: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val seriesId: String = "",
    val headers: Map<String, String> = emptyMap(),
    val catchupDays: Int = 0,
    val tmdbId: Int = 0,
    val titleLogo: String = "",
    val airDate: String = "",
    val scheduledEpisode: Boolean = false,
    val addedAt: Long = 0,
)

internal fun iptvAddedAt(value: String): Long {
    val raw = value.trim()
    raw.toLongOrNull()?.let { return if (it <= 0) 0 else if (it < 1_000_000_000_000L) it * 1000 else it }
    if (raw.isBlank()) return 0
    return listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd").firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw)?.time }.getOrNull()
    }?.coerceAtLeast(0) ?: 0
}

@Serializable
data class IptvCategory(val id: String, val name: String)

internal enum class IptvRemoteAction(val label: String, val available: Boolean = true) {
    IGNORE("Ignorer"), WATCH("Regarder la chaîne"), BEGINNING("Regarder depuis le début", false),
    EXTERNAL("Ouvrir dans un lecteur externe"), MENU("Afficher le menu"), GROUPS("Afficher les groupes"),
    DESCRIPTION("Afficher la description du programme"), SEARCH("Rechercher"), SETTINGS("Paramètres"),
    CHANNEL_OPTIONS("Options de Chaîne"), GROUP_OPTIONS("Options de Groupe"), MOVIES("Films"), SERIES("Séries"),
    RECORDINGS("Enregistrements"), CUSTOM_RECORDING("Enregistrement personnalisé"), MY_LIST("Ma liste"),
    FAVORITE("Changer le statut des Favoris"), MANAGE_FAVORITES("Gestion des Favoris"),
    BLOCKING("Gérer le blocage"), VISIBILITY("Gérer la visibilité"),
    REORDER("Réorganiser les chaînes"), COPY("Copier les chaînes", false),
    RETURN_PLAYER("Retour au lecteur"), RETURN_NOW("Retour aux programmes actuels"), BACK("Revenir"), EXIT("Sortir"),
    PAGE_UP("Défiler la page des chaînes vers le haut"), PAGE_DOWN("Défiler la page des chaînes vers le bas"),
    NEXT_PROGRAMS("Défiler vers les programmes suivants"),
    CHANNELS_OVERLAY("Afficher la liste des chaînes en mode superposition"),
    CHANNELS_PREVIEW("Afficher la liste des chaînes en mode prévisualisation"),
    GROUPS_OVERLAY("Afficher les groupes de Guides TV en mode superposition"),
    GROUPS_PREVIEW("Afficher les groupes de Guides TV en mode prévisualisation"),
    GUIDE_OVERLAY("Afficher le Guide TV en mode superposition"), GUIDE_PREVIEW("Afficher le Guide TV en mode prévisualisation"),
    INFO_CONTROLS("Afficher le panneau d’informations et le panneau de configuration"),
    INFO("Afficher le panneau d’informations"), CONTROLS("Afficher le panneau de configuration"),
    RECENT("Aller sur la chaîne la plus récente"), NEXT("Aller sur la chaîne suivante"), PREVIOUS("Aller sur la chaîne précédente"),
    INFO_NEXT("Afficher le panneau d’informations de la chaîne suivante"),
    INFO_PREVIOUS("Afficher le panneau d’informations de la chaîne précédente"), HISTORY("Historique"),
    PLAY_PAUSE("Lecture/Pause"), STOP("Arrêter la lecture"), LIVE("Accéder à la diffusion en direct"),
    RECORD("Démarrer/arrêter l’enregistrement"), VOLUME_UP("Augmenter le son"), VOLUME_DOWN("Baisser le son"), MUTE("Couper le son"),
    REWIND("Rembobiner (RW)"), FORWARD("Avance rapide (FF)"),
    AUDIO("Pistes audio"), SUBTITLES("Sous-titres"), ASPECT("Changer le format de l’image");

    val repeatable: Boolean get() = when (this) { PAGE_UP, PAGE_DOWN, REWIND, FORWARD, VOLUME_UP, VOLUME_DOWN -> true; else -> false }
}

internal object IptvRemote {
    fun digit(code: Int): Int? = if (code in 7..16) code - 7 else null

    fun overlayMode(action: IptvRemoteAction): Boolean? = when (action) {
        IptvRemoteAction.CHANNELS_OVERLAY, IptvRemoteAction.GUIDE_OVERLAY, IptvRemoteAction.GROUPS_OVERLAY -> true
        IptvRemoteAction.CHANNELS_PREVIEW, IptvRemoteAction.GUIDE_PREVIEW, IptvRemoteAction.GROUPS_PREVIEW -> false
        else -> null
    }

    fun isProgramContext(channelId: String, focusedTag: String?, hasProgram: Boolean): Boolean =
        hasProgram && focusedTag != channelId

    data class Button(val id: String, val label: String, val keyCode: Int, val action: IptvRemoteAction, val long: Boolean = false) {
        fun setting(player: Boolean) = "remote.${if (player) "player" else "guide"}.$id"
    }

    // Android key codes are constants here so the input rules also run in local JVM tests.
    val guide = listOf(
        Button("ok", "OK", 23, IptvRemoteAction.WATCH),
        Button("okLong", "Appui long sur OK", 23, IptvRemoteAction.MENU, true),
        Button("back", "Retour", 4, IptvRemoteAction.BACK),
        Button("backLong", "Appui long sur Retour", 4, IptvRemoteAction.RETURN_PLAYER, true),
        Button("left", "Gauche", 21, IptvRemoteAction.GROUPS), Button("right", "Droite", 22, IptvRemoteAction.NEXT_PROGRAMS),
        Button("pageUp", "Pg+ (Ch+)", 166, IptvRemoteAction.PAGE_UP), Button("pageDown", "Pg- (Ch-)", 167, IptvRemoteAction.PAGE_DOWN),
        Button("menu", "Menu", 82, IptvRemoteAction.MENU), Button("menuLong", "Menu Long", 82, IptvRemoteAction.SEARCH, true),
        Button("play", "Lecture/Pause", 85, IptvRemoteAction.DESCRIPTION),
        Button("playLong", "Longue lecture/pause", 85, IptvRemoteAction.EXTERNAL, true),
        Button("stop", "Arrêter", 86, IptvRemoteAction.FAVORITE), Button("rewind", "Rembobiner (RW)", 89, IptvRemoteAction.PAGE_UP),
        Button("forward", "Avance Rapide (FF)", 90, IptvRemoteAction.PAGE_DOWN), Button("record", "Enregistrer", 130, IptvRemoteAction.RECORDINGS),
        Button("delete", "Retour Arrière", 67, IptvRemoteAction.RETURN_PLAYER), Button("info", "Info", 165, IptvRemoteAction.DESCRIPTION),
        Button("guide", "Guide", 172, IptvRemoteAction.GROUPS), Button("red", "Rouge", 183, IptvRemoteAction.FAVORITE),
        Button("green", "Vert", 184, IptvRemoteAction.CHANNEL_OPTIONS), Button("yellow", "Jaune", 185, IptvRemoteAction.GROUP_OPTIONS),
        Button("blue", "Bleu", 186, IptvRemoteAction.SETTINGS)
    )
    val player = listOf(
        Button("ok", "OK", 23, IptvRemoteAction.INFO_CONTROLS), Button("okLong", "Appui long sur OK", 23, IptvRemoteAction.MENU, true),
        Button("back", "Retour", 4, IptvRemoteAction.BACK), Button("backLong", "Appui long sur Retour", 4, IptvRemoteAction.STOP, true),
        Button("left", "Gauche", 21, IptvRemoteAction.GUIDE_OVERLAY), Button("leftLong", "Appui long sur Gauche", 21, IptvRemoteAction.GUIDE_PREVIEW, true),
        Button("right", "Droite", 22, IptvRemoteAction.RECENT), Button("rightLong", "Appui long sur Droite", 22, IptvRemoteAction.DESCRIPTION, true),
        Button("up", "Haut", 19, IptvRemoteAction.NEXT), Button("upLong", "Appui long sur Haut", 19, IptvRemoteAction.HISTORY, true),
        Button("down", "Bas", 20, IptvRemoteAction.PREVIOUS), Button("downLong", "Appui long sur Bas", 20, IptvRemoteAction.SEARCH, true),
        Button("pageUp", "Pg+ (Ch+)", 166, IptvRemoteAction.NEXT), Button("pageDown", "Pg- (Ch-)", 167, IptvRemoteAction.PREVIOUS),
        Button("volumeUp", "Augmenter le volume", 24, IptvRemoteAction.VOLUME_UP),
        Button("volumeDown", "Baisser le volume", 25, IptvRemoteAction.VOLUME_DOWN), Button("mute", "Muet", 164, IptvRemoteAction.MUTE),
        Button("menu", "Menu", 82, IptvRemoteAction.MENU), Button("menuLong", "Menu Long", 82, IptvRemoteAction.GUIDE_OVERLAY, true),
        Button("play", "Lecture/Pause", 85, IptvRemoteAction.PLAY_PAUSE), Button("playLong", "Longue lecture/pause", 85, IptvRemoteAction.LIVE, true),
        Button("stop", "Arrêter", 86, IptvRemoteAction.STOP), Button("rewind", "Rembobiner (RW)", 89, IptvRemoteAction.REWIND),
        Button("forward", "Avance Rapide (FF)", 90, IptvRemoteAction.FORWARD), Button("record", "Enregistrer", 130, IptvRemoteAction.RECORD),
        Button("delete", "Retour Arrière", 67, IptvRemoteAction.GROUPS_OVERLAY), Button("info", "Info", 165, IptvRemoteAction.INFO),
        Button("guide", "Guide", 172, IptvRemoteAction.GUIDE_OVERLAY), Button("red", "Rouge", 183, IptvRemoteAction.GUIDE_OVERLAY),
        Button("green", "Vert", 184, IptvRemoteAction.GROUPS_OVERLAY), Button("yellow", "Jaune", 185, IptvRemoteAction.CONTROLS),
        Button("blue", "Bleu", 186, IptvRemoteAction.SETTINGS)
    )
    val guideActions = IptvRemoteAction.entries.takeWhile { it != IptvRemoteAction.CHANNELS_OVERLAY }
    val playerActions = listOf(IptvRemoteAction.IGNORE) + IptvRemoteAction.entries.dropWhile { it != IptvRemoteAction.CHANNELS_OVERLAY } +
        listOf(IptvRemoteAction.MENU, IptvRemoteAction.DESCRIPTION, IptvRemoteAction.SEARCH, IptvRemoteAction.SETTINGS,
            IptvRemoteAction.EXTERNAL, IptvRemoteAction.FAVORITE, IptvRemoteAction.MOVIES, IptvRemoteAction.SERIES,
            IptvRemoteAction.RECORDINGS, IptvRemoteAction.CUSTOM_RECORDING, IptvRemoteAction.MY_LIST, IptvRemoteAction.BACK, IptvRemoteAction.EXIT)

    fun key(code: Int): Int = when (code) { 66, 160 -> 23; 126, 127 -> 85; 92 -> 166; 93 -> 167; else -> code }
    fun buttons(player: Boolean) = if (player) this.player else guide
    fun actions(player: Boolean) = if (player) playerActions else guideActions
    fun action(button: Button, player: Boolean, value: String?): IptvRemoteAction =
        actions(player).firstOrNull { it.name == value && it.available } ?: button.action
    fun defaults(player: Boolean) = buttons(player).associate { it.setting(player) to it.action.name }

    val playerMenuButtons = listOf("search" to "Rechercher", "channels" to "Liste des Chaînes", "record" to "Enregistrements",
        "multiview" to "Multi-Vue", "video" to "Pistes vidéo", "audio" to "Pistes audio", "audioDelay" to "Décalage Audio",
        "subtitles" to "Sous-titres", "display" to "Mode d'Affichage", "sleep" to "Minuterie de sommeil",
        "favorite" to "Changer le statut des Favoris", "settings" to "Paramètres", "history" to "Historique",
        "movies" to "Films", "series" to "Séries", "myList" to "Ma liste", "exit" to "Sortir")

    fun visiblePlayerMenu(settings: Map<String, String>): List<String> {
        val defaults = playerMenuButtons.map { it.first }
        return (IptvOrganization.ids(settings["playerMenuOrder"] ?: "[]").filter { it in defaults } + defaults).distinct()
            .filter { id -> settings["playerMenu:$id"]?.let { it == "true" } ?: (defaults.indexOf(id) < 12) }
    }
}

/** Defer the short action only when this key has a long action; never fire both. */
internal class IptvRemotePress(val longPressMs: Long = 500) {
    data class Result(val action: IptvRemoteAction? = null)
    private data class Press(val short: IptvRemoteAction, val long: IptvRemoteAction?, val at: Long, var firedLong: Boolean = false)
    private val presses = mutableMapOf<Int, Press>()
    fun owns(code: Int) = presses.containsKey(IptvRemote.key(code))
    fun cancel(code: Int, startedAt: Long) {
        if (presses[IptvRemote.key(code)]?.at == startedAt) presses.remove(IptvRemote.key(code))
    }
    fun hold(code: Int, startedAt: Long, now: Long): Result {
        val press = presses[IptvRemote.key(code)] ?: return Result()
        if (press.at != startedAt || press.firedLong || press.long == null || now - press.at < longPressMs) return Result()
        press.firedLong = true
        return Result(press.long)
    }
    fun handle(code: Int, down: Boolean, at: Long, short: IptvRemoteAction, long: IptvRemoteAction? = null,
        repeat: Int = 0, cancelled: Boolean = false): Result {
        val key = IptvRemote.key(code)
        if (!down) {
            val press = presses.remove(key) ?: return Result()
            return Result(if (cancelled || press.firedLong || press.long == null) null
                else if (at - press.at >= longPressMs) press.long else press.short)
        }
        if (repeat == 0) {
            presses[key] = Press(short, long, at)
            return Result(if (long == null) short else null)
        }
        val press = presses[key] ?: return Result()
        if (press.long != null) return hold(key, press.at, at)
        return Result(if (press.long == null && press.short.repeatable) press.short else null)
    }
}

internal class IptvChannelNumberInput(val timeoutMs: Long = 1200) {
    private var number = 0
    private var lastAt: Long? = null

    fun append(digit: Int, at: Long): Int {
        require(digit in 0..9)
        val continued = lastAt?.let { at >= it && at - it <= timeoutMs } == true
        number = if (continued) (number.toLong() * 10 + digit).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: digit else digit
        lastAt = at
        return number
    }

    fun clear() { number = 0; lastAt = null }
}

/** Organization overrides use provider identity, never connection URLs. */
internal object IptvOrganization {
    fun vodGridColumns(width: Int, minimumColumnWidth: Int): Int =
        (width.coerceAtLeast(1) / minimumColumnWidth.coerceAtLeast(1)).coerceIn(1, 8)

    fun vodGridTarget(index: Int, count: Int, columns: Int, direction: String): Int {
        if (count <= 0) return -1
        val current = index.coerceIn(0, count - 1)
        val span = columns.coerceAtLeast(1)
        return when (direction) {
            "left" -> if (current % span > 0) current - 1 else current
            "right" -> if (current % span < span - 1) minOf(current + 1, count - 1) else current
            "up" -> (current - span).takeIf { it >= 0 } ?: current
            "down" -> if (current / span < (count - 1) / span) minOf(current + span, count - 1) else current
            else -> current
        }
    }

    fun channelListPage(itemCount: Int, focusedIndex: Int): IntRange {
        if (itemCount <= 0) return IntRange.EMPTY
        val start = (focusedIndex.coerceIn(0, itemCount - 1) / 12) * 12
        return start until (start + minOf(12, itemCount - start))
    }

    fun adjacentChannel(values: List<IptvItem>, currentId: String, forward: Boolean): IptvItem? {
        val channels = values.filter { it.type == ContentType.LIVE }.distinctBy { it.id }
        val index = channels.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        return channels[(index + if (forward) 1 else channels.size - 1) % channels.size]
    }

    fun key(source: String, type: ContentType, group: String?, name: String): String {
        val kind = if (type == ContentType.EPISODE) ContentType.SERIES else type
        val scope = if (group == null) source else "$source:${kind.name}:$group"
        return "organization:${digest(scope)}:$name"
    }

    fun channelHiddenKey(item: IptvItem): String = key(item.sourceId, item.type,
        item.categoryId.takeIf { it.isNotBlank() }, "channelHidden:${item.id}")

    fun channelNameKey(item: IptvItem): String = key(item.sourceId, item.type,
        item.categoryId.takeIf { it.isNotBlank() }, "channelName:${item.id}")

    fun channelOriginalNameKey(item: IptvItem): String = key(item.sourceId, item.type,
        item.categoryId.takeIf { it.isNotBlank() }, "channelOriginalName:${item.id}")

    fun originalItem(item: IptvItem, settings: Map<String, String>): IptvItem {
        val name = settings[channelNameKey(item)]?.trim().orEmpty()
        val original = settings[channelOriginalNameKey(item)].orEmpty()
        return if (name.isNotEmpty() && item.title == name && original.isNotEmpty()) item.copy(title = original) else item
    }

    fun channelBlockedKey(item: IptvItem): String = key(item.sourceId, item.type,
        item.categoryId.takeIf { it.isNotBlank() }, "channelBlocked:${item.id}")

    fun value(settings: Map<String, String>, source: String, type: ContentType, group: String?, name: String, default: String): String =
        group?.takeIf { it.isNotBlank() }?.let { settings[key(source, type, it, name)] }
            ?: settings[key(source, type, null, name)] ?: settings[name] ?: default

    fun blocked(settings: Map<String, String>, item: IptvItem): Boolean =
        settings[key(item.sourceId, item.type, item.categoryId, "blocked")] == "true" ||
            settings[channelBlockedKey(item)] == "true"

    fun playbackLocked(settings: Map<String, String>, item: IptvItem?): Boolean =
        item?.let { blocked(settings, it) } == true || settings["parentalEnabled"] == "true" && settings["lockPlayback"] != "false"

    fun pinScopeDefault(scope: String): Boolean = scope !in setOf("lockSettings", "lockPlaylists", "lockEpg")

    fun pinUnlockApplies(scope: String, channelsOnly: Boolean): Boolean = !channelsOnly || scope == "lockPlayback"

    fun ids(value: String): List<String> = runCatching { iptvJson.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    fun groupHidden(id: String, explicit: String, newGroupsVisible: String, knownGroups: String): Boolean =
        if (explicit.isNotBlank()) explicit == "true" else id.isNotBlank() && newGroupsVisible == "false" && id !in ids(knownGroups)

    fun offsetMinutes(text: String): Int? {
        val match = Regex("^([+-]?)([0-9]{1,2}):([0-9]{2})$").matchEntire(text.trim().replace('−', '-')) ?: return null
        val hours = match.groupValues[2].toInt(); val minutes = match.groupValues[3].toInt()
        val total = hours * 60 + minutes
        return total.takeIf { minutes < 60 && it <= 720 }?.let { if (match.groupValues[1] == "-") -it else it }
    }

    fun <T> ordered(values: List<T>, ids: List<String>, identity: (T) -> String): List<T> {
        val ranks = ids.withIndex().associate { it.value to it.index }
        return values.sortedBy { ranks[identity(it)] ?: Int.MAX_VALUE }
    }

    fun sources(values: List<IptvSource>, sort: String, order: List<String>): List<IptvSource> =
        if (sort == "manual") ordered(values, order) { it.id }
        else values.sortedBy { it.name.lowercase(Locale.ROOT) }

    fun groups(values: List<IptvCategory>, sort: String, order: List<String>,
        label: (IptvCategory) -> String = { it.name }): List<IptvCategory> = when (sort) {
        "name" -> values.sortedBy { label(it).lowercase(Locale.ROOT) }
        "manual" -> ordered(values, order) { it.id }
        else -> values
    }

    fun items(values: List<IptvItem>, settings: Map<String, String>, favorites: Set<String>, source: String,
        type: ContentType, group: String): List<IptvItem> {
        val showHidden = value(settings, source, type, null, "showHiddenChannels", "false") == "true"
        val favoritesOnly = value(settings, source, type, group, "favoritesOnly", "false") == "true"
        val hiddenGroups = mutableMapOf<Triple<String, ContentType, String>, Boolean>()
        val filtered = values.filter { item ->
            settings[channelHiddenKey(item)] != "true" &&
                (group.isNotBlank() || showHidden || !hiddenGroups.getOrPut(Triple(item.sourceId, item.type, item.categoryId)) {
                groupHidden(item.categoryId, settings[key(item.sourceId, item.type, item.categoryId, "hidden")].orEmpty(),
                    settings[key(item.sourceId, item.type, null, "newGroupsVisible:${item.type.name}")].orEmpty(),
                    settings[key(item.sourceId, item.type, null, "knownGroups:${item.type.name}")].orEmpty())
            }) &&
                (!favoritesOnly || item.id in favorites)
        }.map { item ->
            settings[channelNameKey(item)]?.trim()?.takeIf { it.isNotEmpty() }?.let { item.copy(title = it) } ?: item
        }
        return sortedItems(filtered, settings, favorites, source, type, group)
    }

    fun channelTitle(title: String, settings: Map<String, String>): String {
        if (settings["channelNameEditor"] != "true") return title
        var result = title.trim()
        fun parts(key: String) = settings[key].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        parts("channelNamePrefixes").firstOrNull { result.startsWith(it, ignoreCase = true) }?.let { result = result.drop(it.length).trim() }
        parts("channelNameSuffixes").firstOrNull { result.endsWith(it, ignoreCase = true) }?.let { result = result.dropLast(it.length).trim() }
        return result.ifBlank { title }
    }

    fun sortedItems(values: List<IptvItem>, settings: Map<String, String>, favorites: Set<String>, source: String,
        type: ContentType, group: String): List<IptvItem> {
        val sorted = when (value(settings, source, type, group, "channelSort", "playlist")) {
            "name" -> values.sortedBy { channelTitle(it.title, if (type == ContentType.LIVE) settings else emptyMap()).lowercase(Locale.ROOT) }
            "nameDescending" -> values.sortedByDescending { it.title.lowercase(Locale.ROOT) }
            "rating" -> values.map { item -> item to (item.rating.replace(',', '.').toFloatOrNull()?.takeIf { rating -> rating.isFinite() && rating >= 0 } ?: -1f) }
                .sortedByDescending { it.second }.map { it.first }
            "added" -> values.sortedByDescending { if (type == ContentType.LIVE) settings["channelAdded:${it.id}"]?.toLongOrNull() ?: 0L else it.addedAt }
            "watchTime" -> values.sortedByDescending { settings["channelWatchTime:${it.id}"]?.toLongOrNull() ?: 0L }
            "manual" -> ordered(values, ids(value(settings, source, type, group, "itemOrder", "[]"))) { it.id }
            else -> values
        }
        return if (type == ContentType.LIVE && value(settings, source, type, group, "favoritesFirst", "false") == "true")
            sorted.sortedBy { if (it.id in favorites) 0 else 1 } else sorted
    }
}

/** Counts playing time only; pauses and buffering contribute nothing. */
internal class IptvWatchClock {
    private var started: Long? = null
    fun update(playing: Boolean, now: Long): Long {
        val elapsed = if (!playing) started?.let { (now - it).coerceAtLeast(0) } ?: 0L else 0L
        if (playing && started == null) started = now
        if (!playing) started = null
        return elapsed
    }
}
data class IptvPage(val items: List<IptvItem>, val nextPage: Int? = null, val total: Int? = null)

internal object IptvPlaylistSettings {
    fun key(source: String, name: String) = "playlist:$source:$name"
    fun enabled(settings: Map<String, String>, source: String) = settings[key(source, "enabled")] != "false"
    fun updateDue(settings: Map<String, String>, source: String, lastUpdate: Long, now: Long, startup: Boolean): Boolean {
        if (!enabled(settings, source)) return false
        if (startup && settings[key(source, "updateOnStart")] == "true") return true
        val hours = settings[key(source, "updateHours")]?.toIntOrNull()?.coerceIn(0, 168) ?: 24
        return hours > 0 && lastUpdate > 0 && now - lastUpdate >= hours * 3_600_000L
    }
}
data class IptvStream(val url: String, val headers: Map<String, String>, val dns: IptvDnsConfig? = null)
@Serializable data class EpgProgram(val title: String, val description: String, val start: Long, val end: Long)
@Serializable internal data class IptvSavedProgram(val channel: IptvItem, val program: EpgProgram)

internal object IptvGuideViewport {
    fun range(start: Long, end: Long, scrollX: Int, viewport: Int, pixelsPerHour: Int): LongRange {
        val left = (scrollX.toLong() - viewport / 2).coerceAtLeast(0)
        val right = scrollX.toLong() + viewport + viewport / 2
        // Half-hour boundaries keep smooth scrolling from rebuilding rows every pixel.
        val from = (start + left * 3_600_000 / pixelsPerHour / 1_800_000 * 1_800_000).coerceIn(start, end)
        val to = (start + (right * 3_600_000 / pixelsPerHour + 1_799_999) / 1_800_000 * 1_800_000).coerceIn(from, end)
        return from until to
    }
}

internal object IptvArtworkSizing {
    data class Size(val width: Int, val height: Int, val sample: Int)
    fun isLandscape(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * 3 >= height.toLong() * 4

    fun size(width: Int, height: Int, targetWidth: Int, targetHeight: Int, crop: Boolean): Size {
        val sourceWidth = width.coerceAtLeast(1)
        val sourceHeight = height.coerceAtLeast(1)
        val horizontal = targetWidth.coerceAtLeast(1).toDouble() / sourceWidth
        val vertical = targetHeight.coerceAtLeast(1).toDouble() / sourceHeight
        val scale = (if (crop) maxOf(horizontal, vertical) else minOf(horizontal, vertical)).coerceAtMost(1.0)
        val outputWidth = kotlin.math.ceil(sourceWidth * scale).toInt().coerceAtMost(sourceWidth)
        val outputHeight = kotlin.math.ceil(sourceHeight * scale).toInt().coerceAtMost(sourceHeight)
        var sample = 1
        while (sourceWidth / (sample * 2) >= outputWidth && sourceHeight / (sample * 2) >= outputHeight) sample *= 2
        return Size(outputWidth, outputHeight, sample)
    }
}

/** Portals send either Unix timestamps or local date strings, sometimes both. */
internal object ProviderEpgParser {
    fun parse(entries: kotlinx.serialization.json.JsonArray, encoded: Boolean = false, localDates: Boolean = false): List<EpgProgram> = entries.mapNotNull { value ->
        val entry = value as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        fun timestamp(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
            val raw = entry.string(key)
            raw.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
                ?: listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX").firstNotNullOfOrNull { pattern ->
                    runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw)?.time }.getOrNull()
                }
        }
        fun label(vararg keys: String): String {
            val raw = keys.map { entry.string(it) }.firstOrNull(String::isNotBlank).orEmpty()
            return if (encoded) runCatching { String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrDefault(raw) else raw
        }
        // Stalker dates are formatted in the requested cookie timezone; some portals
        // compute the accompanying Unix fields with a fixed winter offset.
        val start = (if (localDates) timestamp("time", "start", "start_timestamp") else timestamp("start_timestamp", "time", "start")) ?: return@mapNotNull null
        val end = (if (localDates) timestamp("time_to", "end", "stop_timestamp", "end_timestamp") else timestamp("stop_timestamp", "end_timestamp", "time_to", "end"))
            ?: entry.string("duration").toLongOrNull()?.takeIf { it > 0 }?.let { start + it * 1000 }
            ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        EpgProgram(label("name", "title"), label("descr", "description"), start, end)
    }.distinctBy { it.start to it.end }.sortedBy { it.start }
}

/** Stable provider identity; URLs with temporary playback tokens are never the key. */
fun itemId(source: String, type: ContentType, providerId: String): String =
    "iptv:$source:${type.name.lowercase()}:${digest(providerId)}"

fun digest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(hex[unsigned ushr 4]); append(hex[unsigned and 0x0f])
        }
    }
}

fun httpUrl(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrNull()
    require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
        "Saisis une adresse HTTP ou HTTPS valide."
    }
    return uri.toASCIIString()
}

internal fun playlistStreamUrl(value: String): String {
    val uri = URI(value.trim())
    return when (uri.scheme?.lowercase()) {
        "rtsp" -> {
            require(!uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)) { "Adresse RTSP invalide." }
            uri.toASCIIString()
        }
        "udp" -> {
            require(!uri.host.isNullOrBlank() && uri.port in 1..65535) { "Adresse multicast invalide." }
            uri.toASCIIString()
        }
        else -> httpUrl(uri.toString())
    }
}

fun artworkUrl(base: String, value: String): String = runCatching {
    if (value.isBlank()) "" else httpUrl(URI(base).resolve(value.trim()).toString())
}.getOrDefault("")

/** M3U parsing is independent of Android, the player and the storage. */
object M3uParser {
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")
    private val episode = Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b")

    fun parse(reader: BufferedReader, source: IptvSource, onEpgUrl: (String) -> Unit = {}): List<IptvItem> {
        val result = LinkedHashMap<String, IptvItem>()
        var info = ""
        var group = ""
        var headers = source.headers.toMutableMap()
        reader.forEachLine { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTM3U", true) -> attribute.findAll(line).firstOrNull {
                    it.groupValues[1].lowercase() in setOf("url-tvg", "x-tvg-url", "tvg-url")
                }?.let { match ->
                    artworkUrl(source.url, match.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty().substringBefore(','))
                        .takeIf(String::isNotBlank)?.let(onEpgUrl)
                }
                line.startsWith("#EXTINF:", true) -> {
                    info = line
                    group = ""
                    headers = source.headers.toMutableMap()
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val option = line.substringAfter(':').substringBefore('=')
                    val value = line.substringAfter('=', "").trim()
                    when (option.lowercase()) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                    }
                }
                info.isNotBlank() && line.isNotBlank() && !line.startsWith('#') -> {
                    val fields = attribute.findAll(info).associate {
                        it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
                    }
                    // Find the title delimiter outside quoted attribute values.
                    var quote: Char? = null
                    val comma = info.indexOfFirst { char ->
                        if (char == '"' || char == '\'') quote = if (quote == char) null else if (quote == null) char else quote
                        char == ',' && quote == null
                    }
                    val title = if (comma >= 0) info.substring(comma + 1).trim() else fields["tvg-name"].orEmpty()
                    val parts = line.split('|', limit = 2)
                    val url = runCatching {
                        playlistStreamUrl(URI(source.url).resolve(parts[0]).toString())
                    }.getOrNull()
                    if (url != null) {
                        if (parts.size == 2) parts[1].split('&').forEach { pair ->
                            val name = pair.substringBefore('=')
                            val value = runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                            if (name.isNotBlank() && value.isNotBlank()) headers[name] = value
                        }
                        val match = episode.find(title)
                        val declared = fields["type"].orEmpty().lowercase()
                        val path = URI(url).path.orEmpty()
                        val type = when {
                            match != null || declared in setOf("series", "episode") || path.contains("/series/") -> ContentType.EPISODE
                            declared in setOf("movie", "vod") || path.contains("/movie/") ||
                                path.matches(Regex("(?i).+\\.(mp4|mkv|avi|mov)$")) -> ContentType.MOVIE
                            else -> ContentType.LIVE
                        }
                        val category = fields["group-title"].orEmpty().ifBlank { group }.ifBlank { "Sans catégorie" }
                        // A repeated tvg-id (e.g. HD/SD) must not merge distinct streams.
                        val providerId = digest(parts[0])
                        val id = itemId(source.id, type, providerId)
                        result[id] = IptvItem(id, source.id, providerId, type,
                            title.ifBlank { fields["tvg-name"].orEmpty().ifBlank { "Chaîne ${result.size + 1}" } },
                            category, category, artworkUrl(source.url, fields["tvg-logo"].orEmpty()),
                            streamUrl = url, epgId = fields["tvg-id"].orEmpty(),
                            season = match?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                            episode = match?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                            seriesId = if (type == ContentType.EPISODE) title.substringBefore(match?.value ?: "\u0000").trim() else "",
                            headers = headers.toMap(), catchupDays = (fields["catchup-days"] ?: fields["timeshift"])?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    }
                    info = ""
                    group = ""
                    headers = source.headers.toMutableMap()
                }
            }
        }
        return result.values.toList()
    }
}

/** Stream XMLTV (also .gz), retaining only this playlist's channels and a bounded time window. */
object XmltvParser {
    fun parse(input: InputStream, channels: Set<String>, after: Long, before: Long): Map<String, List<EpgProgram>> {
        val stream = PushbackInputStream(input, 2)
        val signature = ByteArray(2)
        val count = stream.read(signature)
        if (count > 0) stream.unread(signature, 0, count)
        val xml = if (count == 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()) GZIPInputStream(stream) else stream
        val programs = mutableMapOf<String, MutableList<EpgProgram>>()
        val parser = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities").forEach {
                runCatching { setFeature(it, false) }
            }
        }.newSAXParser()
        parser.parse(xml, object : DefaultHandler() {
            var channel = ""
            var start = 0L
            var end = 0L
            var title = ""
            var description = ""
            var field = ""
            val content = StringBuilder()
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
            override fun startElement(uri: String?, localName: String?, name: String, attributes: Attributes) {
                if (name == "programme") {
                    channel = attributes.getValue("channel").orEmpty()
                    start = timestamp(attributes.getValue("start").orEmpty())
                    end = timestamp(attributes.getValue("stop").orEmpty())
                    title = ""; description = ""
                }
                if (name == "title" || name == "desc") { field = name; content.setLength(0) }
            }
            override fun characters(value: CharArray, offset: Int, length: Int) {
                if (field.isNotBlank() && channel in channels && content.length < 32_768) content.append(value, offset, length)
            }
            override fun endElement(uri: String?, localName: String?, name: String) {
                if (name == field) {
                    if (field == "title" && title.isBlank()) title = content.toString().trim()
                    if (field == "desc" && description.isBlank()) description = content.toString().trim()
                    field = ""
                }
                if (name == "programme") {
                    if (channel in channels && end > after && start < before && end > start)
                        programs.getOrPut(channel) { mutableListOf() }.add(EpgProgram(title, description, start, end))
                    channel = ""
                }
            }
        })
        return programs.mapValues { (_, values) -> values.sortedBy { it.start } }
    }
    internal fun timestamp(value: String): Long {
        val parts = value.trim().split(Regex("\\s+"), limit = 2)
        if (parts.firstOrNull()?.length != 14) return 0L
        val pattern = if (parts.size == 2) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss"
        return runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value.trim())?.time ?: 0L }.getOrDefault(0L)
    }
}
