package com.jojotv.home.progresspilot

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.util.UUID

/** This catalogue is also installed by SQL 016. Unknown keys never leave the device. */
internal data class AccountSettingSpec(
    val key: String, val scope: String, val kind: String,
    val choices: List<String> = emptyList(), val min: Int = 0, val max: Int = 0
) {
    fun accepts(value: JsonElement): Boolean {
        if (value.isJsonNull) return kind != "profile" // Profiles are never deleted by a missing/reset value.
        return when (kind) {
            "boolean" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
            "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                value.toString().matches(Regex("-?[0-9]+")) && value.asString.toLongOrNull()?.let { it in min.toLong()..max.toLong() } == true
            "enum" -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString in choices
            "language" -> value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                value.asString.matches(Regex("(?:[a-z]{2,3}(?:-[A-Za-z]{2,4})?|device|none|system|original|auto|off)"))
            "decimal" -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) &&
                value.asString.toDoubleOrNull()?.let { it.isFinite() && it in min.toDouble()..max.toDouble() } == true
            "package" -> value.isJsonPrimitive && value.asJsonPrimitive.isString && (value.asString.isEmpty() ||
                value.asString.length <= 200 && value.asString.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))
            "enum_list" -> value.isJsonArray && value.asJsonArray.size() <= choices.size &&
                value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString in choices } &&
                value.asJsonArray.map { it.asString }.distinct().size == value.asJsonArray.size()
            "uuid_list" -> value.isJsonArray && value.asJsonArray.size() <= 200 &&
                value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && accountUuid(it.asString) } &&
                value.asJsonArray.map { it.asString }.distinct().size == value.asJsonArray.size()
            "organization" -> AccountIptvOrganization.accepts(value)
            "packages" -> value.isJsonArray && value.asJsonArray.size() <= 200 &&
                value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString &&
                    it.asString.length <= 200 && it.asString.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) } &&
                value.asJsonArray.map { it.asString }.distinct().size == value.asJsonArray.size()
            "profile" -> value.isJsonObject && value.asJsonObject.let { obj ->
                obj.keySet() == setOf("name", "color", "avatar", "primary") &&
                    listOf("name", "color", "avatar").all { obj[it].isJsonPrimitive && obj[it].asJsonPrimitive.isString } &&
                    obj["primary"].isJsonPrimitive && obj["primary"].asJsonPrimitive.isBoolean &&
                    obj["name"].asString.let { it.isNotBlank() && it.length <= (if (key == "profiles.iptv") 40 else 64) && it.none(Char::isISOControl) } &&
                    obj["color"].asString.matches(Regex("#[0-9a-fA-F]{6}")) &&
                    obj["avatar"].asString in choices
            }
            else -> false
        }
    }
}

internal object AccountSettingsCatalog {
    const val VERSION = 1
    const val ACCOUNT = "account"
    const val PROFILE = "profile"
    const val DEVICE = "device"
    const val ACCOUNT_SUBJECT = "00000000-0000-0000-0000-000000000000"
    val packageSetKeys = setOf("home.favorite_apps", "home.hidden_apps", "home.content_apps")
    val extendedKeys = setOf("home.home_section_order", "iptv.sourceOrder", "iptv.organization")
    // Approved identifiers only. The legacy UI catalogues are excluded from this APK.
    val avatars: List<String> = listOf(
        "", "animal:aigle", "animal:baleine", "animal:cerf", "animal:chat", "animal:cheval",
        "animal:chevre", "animal:chien", "animal:dauphin", "animal:ecureuil", "animal:elephant", "animal:girafe",
        "animal:herisson", "animal:hippopotame", "animal:kangourou", "animal:koala", "animal:lapin", "animal:lion",
        "animal:loup", "animal:mouton", "animal:ours", "animal:panda", "animal:perroquet", "animal:renard",
        "animal:requin", "animal:rhinoceros", "animal:sanglier", "animal:tigre", "animal:tortue", "animal:vache",
        "animal:zebre", "family:femme_cartoon_01", "family:femme_cartoon_02", "family:femme_cartoon_03", "family:femme_cartoon_04", "family:femme_cartoon_05",
        "family:femme_cartoon_06", "family:femme_cartoon_07", "family:femme_cartoon_08", "family:femme_cartoon_09", "family:femme_cartoon_10", "family:femme_cartoon_2_01",
        "family:femme_cartoon_2_02", "family:femme_cartoon_2_03", "family:femme_cartoon_2_04", "family:femme_cartoon_2_05", "family:femme_cartoon_2_06", "family:femme_cartoon_2_07",
        "family:femme_cartoon_2_08", "family:femme_cartoon_2_09", "family:femme_cartoon_2_10", "family:femme_photo_1_01", "family:femme_photo_1_02", "family:femme_photo_1_03",
        "family:femme_photo_1_04", "family:femme_photo_1_05", "family:femme_photo_1_06", "family:femme_photo_1_07", "family:femme_photo_1_08", "family:femme_photo_1_09",
        "family:femme_photo_1_10", "family:femme_photo_2_01", "family:femme_photo_2_02", "family:femme_photo_2_03", "family:femme_photo_2_04", "family:femme_photo_2_05",
        "family:femme_photo_2_06", "family:femme_photo_2_07", "family:femme_photo_2_08", "family:femme_photo_2_09", "family:femme_photo_2_10", "family:homme_00",
        "family:homme_01", "family:homme_02", "family:homme_03", "family:homme_04", "family:homme_05", "family:homme_06",
        "family:homme_07", "family:homme_08", "family:homme_09", "family:homme_10", "family:homme_11", "family:homme_12",
        "family:homme_13", "family:homme_14", "family:homme_15", "family:homme_16", "family:homme_17", "family:homme_18",
        "family:homme_19", "family:homme_20", "family:homme_21", "emoji:😀", "emoji:😃", "emoji:😄",
        "emoji:😁", "emoji:😆", "emoji:😅", "emoji:😂", "emoji:🙂", "emoji:🙃",
        "emoji:😉", "emoji:😊", "emoji:😍", "emoji:🥰", "emoji:😘", "emoji:😎",
        "emoji:🤓", "emoji:🥳", "emoji:🤩", "emoji:😇", "emoji:🤠", "emoji:🥸",
        "emoji:🤗", "emoji:😴", "emoji:🤔", "emoji:👨", "emoji:👩", "emoji:👦",
        "emoji:👧", "emoji:👨🏻", "emoji:👩🏻", "emoji:👦🏻", "emoji:👧🏻", "emoji:👨🏼",
        "emoji:👩🏼", "emoji:👦🏼", "emoji:👧🏼", "emoji:👨🏽", "emoji:👩🏽", "emoji:👦🏽",
        "emoji:👧🏽", "emoji:👨🏾", "emoji:👩🏾", "emoji:👦🏾", "emoji:👧🏾", "emoji:👨🏿",
        "emoji:👩🏿", "emoji:👦🏿", "emoji:👧🏿", "emoji:🤡", "emoji:👻", "emoji:👽",
        "emoji:🤖", "emoji:💩", "emoji:👹", "emoji:👺", "emoji:😈", "emoji:😺",
        "emoji:😸", "emoji:😹", "emoji:😻", "emoji:🙀", "emoji:😼", "emoji:🙈",
        "emoji:🙉", "emoji:🙊", "emoji:🐵", "emoji:🐶", "emoji:🐱", "emoji:🦊",
        "emoji:🐼", "emoji:🦁", "emoji:🐸", "fallback_blue", "fallback_violet", "fallback_pink",
        "fallback_red", "fallback_orange", "fallback_yellow", "fallback_green", "fallback_teal", "fallback_cyan",
        "fallback_indigo", "fallback_brown", "fallback_slate"
    )
    val specs: List<AccountSettingSpec> = buildList {
        fun bool(scope: String, vararg keys: String) { keys.forEach { add(AccountSettingSpec(it, scope, "boolean")) } }
        fun num(scope: String, min: Int, max: Int, vararg keys: String) { keys.forEach { add(AccountSettingSpec(it, scope, "integer", min = min, max = max)) } }
        fun choice(scope: String, key: String, vararg values: String) { add(AccountSettingSpec(key, scope, "enum", values.toList())) }
        choice(ACCOUNT, "home.icon_size", "small", "medium", "large")
        num(ACCOUNT, 1, 8, "home.home_rows")
        num(ACCOUNT, Int.MIN_VALUE, Int.MAX_VALUE, "home.background_color")
        num(ACCOUNT, -1, 64, "home.background_theme_color", "home.button_theme", "home.folder_theme", "home.folder_icon_theme", "home.network_folder_theme", "home.home_category_background_theme", "home.player_color")
        choice(ACCOUNT, "home.interface_style", "Personnalisé", "Bleu nuit", "Ardoise", "Turquoise", "Bordeaux", "Sable", "Contraste")
        choice(ACCOUNT, "home.home_bars_style", "transparent", "black", "frosted")
        choice(ACCOUNT, "home.home_hero_style", "current", "muted", "airy", "editorial")
        choice(ACCOUNT, "home.dynamic_wallpaper_mode", "glow", "gradient", "solid")
        bool(ACCOUNT, "home.dynamic_wallpaper", "home.show_app_labels", "home.settings_grid", "home.files_grid", "home.show_hidden_files", "home.weather_widget")
        choice(ACCOUNT, "home.app_labels_mode", "focus", "always", "never")
        choice(ACCOUNT, "home.continue_watching_position", "before", "after")
        choice(ACCOUNT, "home.continue_watching_hero_size", "small", "medium", "large")
        add(AccountSettingSpec("home.home_section_order", ACCOUNT, "enum_list", listOf("shortcuts", "apps", "resumes", "movies", "series", "live")))
        choice(DEVICE, "home.app_sort_mode", "manual", "alphabetical")
        bool(DEVICE, "home.start_at_boot", "home.autostart_after_standby", "home.fire_tv_home_redirect")
        num(DEVICE, 3, 15, "home.autostart_delay_seconds")
        add(AccountSettingSpec("home.apps_order", DEVICE, "packages"))
        packageSetKeys.forEach { add(AccountSettingSpec(it, DEVICE, "packages")) }
        num(ACCOUNT, 1, 600, "home.player_forward_seconds", "home.player_rewind_seconds")
        bool(ACCOUNT, "iptv.confirmExit", "iptv.epgDescriptions", "iptv.showLogos", "iptv.showFavoritesGroup", "iptv.showAllGroup",
            "iptv.currentProgramColor", "iptv.currentProgramProgressOnly", "iptv.currentTimeIndicator", "iptv.backToNow", "iptv.playerClock",
            "iptv.previewEnabled", "iptv.previewSound", "iptv.previewAutoPlay")
        bool(ACCOUNT, "iptv.channelNumbers", "iptv.channelNames", "iptv.channelTwoLines", "iptv.programTwoLines", "iptv.currentChannelColor",
            "iptv.showAllPlaylistsGroup", "iptv.favoritesFirst", "iptv.groupChannelsByPlaylist", "iptv.searchFavoritesFirst", "iptv.searchPastWithoutReplay",
            "iptv.playerInfoDate", "iptv.playerInfoTime", "iptv.playerInfoMedia", "iptv.playerInfoResolution", "iptv.playerInfoDescription",
            "iptv.playerInfoDescriptionOnChange", "iptv.playerInfoGroup", "iptv.playerInfoBottom", "iptv.playerInfoCard", "iptv.skipIntroEnabled")
        bool(ACCOUNT, "iptv.channelNameEditor", "iptv.showCatchupIcon", "iptv.animatedChannelScroll", "iptv.playerListCurrentColor",
            "iptv.playerListDimPast", "iptv.playerListOverlayPrograms", "iptv.playerListOverlayDescription", "iptv.playerHistoryGuide",
            "iptv.playerHistoryButton", "iptv.playerHistoryPrograms", "iptv.playerHistoryPast", "iptv.playerRecentNames")
        choice(DEVICE, "iptv.textSize", "small", "medium", "large", "extraLarge")
        choice(ACCOUNT, "iptv.themeBackground", *(listOf("home") + (0..63).map(Int::toString)).toTypedArray())
        choice(ACCOUNT, "iptv.themeAccent", *(listOf("home") + (0..63).map(Int::toString)).toTypedArray())
        num(DEVICE, 0, 100, "iptv.interfaceTransparency")
        choice(ACCOUNT, "iptv.epgPastDays", "1", "3", "7", "14")
        choice(ACCOUNT, "iptv.historyDays", "1", "2", "3", "5", "7", "14", "30")
        choice(ACCOUNT, "iptv.historyDelay", "0", "5", "10", "15", "30", "60", "120")
        choice(ACCOUNT, "iptv.groupSort", "playlist", "name")
        choice(ACCOUNT, "iptv.channelSort", "playlist", "name", "nameDescending", "added", "watchTime", "manual")
        choice(ACCOUNT, "iptv.sourceSort", "playlist", "name", "manual")
        add(AccountSettingSpec("iptv.sourceOrder", ACCOUNT, "uuid_list"))
        add(AccountSettingSpec("iptv.organization", ACCOUNT, "organization"))
        choice(ACCOUNT, "iptv.playerPanelTimeout", "0", "2", "3", "5", "10", "15", "30", "60")
        choice(ACCOUNT, "iptv.playerRecentCount", "5", "10", "15", "20", "30", "50")
        choice(ACCOUNT, "iptv.playerRecentDelay", "0", "5", "10", "15", "30", "60", "120")
        choice(DEVICE, "iptv.playerClockPosition", "topRight", "topLeft", "bottomRight", "bottomLeft")
        choice(DEVICE, "iptv.playerClockSize", "small", "medium", "large")
        choice(DEVICE, "iptv.playerClockTransparency", "none", "low", "medium", "high")
        choice(ACCOUNT, "iptv.recordingBefore", "0", "1", "2", "3", "4", "5", "10", "15", "20", "30", "60")
        choice(ACCOUNT, "iptv.recordingAfter", "0", "1", "2", "3", "4", "5", "10", "15", "20", "30", "60")
        bool(ACCOUNT, "iptv.searchHistory")
        bool(DEVICE, "iptv.searchStay", "iptv.searchVoice", "iptv.rememberLastProfile")
        choice(ACCOUNT, "iptv.reminderBefore", "0", "1", "3", "5", "10", "15", "30", "60")
        choice(DEVICE, "iptv.reminderTimeout", "5", "10", "15", "30", "60", "120")
        choice(DEVICE, "iptv.reminderAction", "watch", "details")
        choice(DEVICE, "iptv.previewFullscreenDelay", "0", "10", "30", "60", "120", "180", "300")
        choice(DEVICE, "iptv.channelClick", "preview", "fullscreen")
        choice(DEVICE, "iptv.epgRefreshHours", "0", "1", "2", "4", "6", "8", "12", "16", "24", "48", "72", "96", "120", "144", "168")
        choice(DEVICE, "iptv.afr", "OFF", "START", "START_STOP")
        choice(DEVICE, "iptv.decoderPriority", "default", "hardware", "software")
        choice(DEVICE, "iptv.externalPlayerMode", "all", "never", "vod")
        add(AccountSettingSpec("iptv.externalPlayerPackage", DEVICE, "package"))
        val menu = "search channels record multiview video audio audioDelay subtitles display sleep favorite settings history movies series myList exit".split(' ')
        menu.forEach { bool(DEVICE, "iptv.playerMenu:$it") }
        add(AccountSettingSpec("iptv.playerMenuOrder", DEVICE, "enum_list", menu))
        choice(DEVICE, "iptv.seekSeconds", "5", "10", "15", "30", "60")
        bool(DEVICE, "iptv.playLastChannel", "iptv.afrLive", "iptv.afrVod", "iptv.afrRefresh", "iptv.afrResolution")
        bool(DEVICE, "iptv.audioPassthrough", "iptv.preferSurround", "iptv.playerBlackScreen", "iptv.reminderWake",
            "iptv.epgUpdateOnStart", "iptv.epgUpdateOnPlaylist", "iptv.previewStayGuide", "iptv.previewTransition",
            "iptv.playerListOverlayAutoPlay", "iptv.playerListPreviewAutoPlay", "iptv.playerListPreviewStay", "iptv.playerListOverlayStay",
            "iptv.guideOverlayStay", "iptv.playerListPreviewTransition")
        choice(DEVICE, "iptv.bufferSize", "none", "small", "medium", "large", "extraLarge")
        choice(DEVICE, "iptv.afrDelay", *(0..10).map(Int::toString).toTypedArray())
        listOf("audioLanguage", "secondaryAudioLanguage", "subtitleLanguage", "secondarySubtitleLanguage").forEach {
            add(AccountSettingSpec("iptv.$it", PROFILE, "language"))
        }
        bool(PROFILE, "iptv.subtitleUseForced", "iptv.subtitleShowOnlyPreferredLanguages", "iptv.autoNextEpisode")
        choice(PROFILE, "iptv.nextEpisodeThresholdMode", "PERCENTAGE", "MINUTES_BEFORE_END")
        add(AccountSettingSpec("iptv.nextEpisodeThresholdPercent", PROFILE, "decimal", min = 95, max = 100))
        add(AccountSettingSpec("iptv.nextEpisodeThresholdMinutesBeforeEnd", PROFILE, "decimal", min = 0, max = 30))
        val remoteActions = ("IGNORE WATCH BEGINNING EXTERNAL MENU GROUPS DESCRIPTION SEARCH SETTINGS CHANNEL_OPTIONS GROUP_OPTIONS MOVIES SERIES " +
            "RECORDINGS CUSTOM_RECORDING MY_LIST FAVORITE MANAGE_FAVORITES BLOCKING VISIBILITY REORDER COPY RETURN_PLAYER RETURN_NOW BACK EXIT " +
            "PAGE_UP PAGE_DOWN NEXT_PROGRAMS CHANNELS_OVERLAY CHANNELS_PREVIEW GROUPS_OVERLAY GROUPS_PREVIEW GUIDE_OVERLAY GUIDE_PREVIEW " +
            "INFO_CONTROLS INFO CONTROLS RECENT NEXT PREVIOUS INFO_NEXT INFO_PREVIOUS HISTORY PLAY_PAUSE STOP LIVE RECORD VOLUME_UP VOLUME_DOWN MUTE " +
            "REWIND FORWARD AUDIO SUBTITLES ASPECT").split(' ')
        for (area in listOf("guide", "player")) for (button in ("ok okLong back backLong left leftLong right rightLong up upLong down downLong " +
            "pageUp pageDown volumeUp volumeDown mute menu menuLong play playLong stop rewind forward record delete info guide red green yellow blue").split(' ')) {
            add(AccountSettingSpec("iptv.remote.$area.$button", DEVICE, "enum", remoteActions))
        }
        add(AccountSettingSpec("profiles.home", PROFILE, "profile", avatars))
        add(AccountSettingSpec("profiles.iptv", PROFILE, "profile", avatars))
    }
    private val byKey = specs.associateBy { it.key }
    fun spec(key: String) = byKey[key]
    fun validate(id: AccountRowId, value: JsonElement) {
        require(id.scope in setOf(ACCOUNT, PROFILE, DEVICE) && accountUuid(id.subject)) { "ACCOUNT_SCOPE_INVALID" }
        val spec = byKey[id.key] ?: error("ACCOUNT_KEY_FORBIDDEN")
        require(spec.scope == id.scope && spec.accepts(value)) { "ACCOUNT_VALUE_INVALID" }
        require((id.scope == ACCOUNT) == (id.subject == ACCOUNT_SUBJECT)) { "ACCOUNT_SUBJECT_INVALID" }
    }
    fun profileDomain(key: String) = if (key.startsWith("iptv.")) "iptv" else "home"
    fun homeKey(key: String): Boolean = specs.any { it.key == "home.$key" } || key == "favorite_apps_version" || key.startsWith("app_order_device_")
    fun iptvSetting(key: String): Boolean = byKey.containsKey("iptv.$key") || AccountIptvOrganization.key(key)
    fun iptvRecord(key: String): Boolean {
        if (key.startsWith("profile:") || key.startsWith("accountName:") || key.startsWith("accountAlias:") || key == "legacyAccount") return true
        val setting = key.substringAfter("setting:", "")
        return setting.isNotEmpty() && iptvSetting(setting)
    }
}

internal fun accountUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

/** Shared organization only. Navigation, parental controls and player overrides stay local. */
internal object AccountIptvOrganization {
    private val item = "iptv:[A-Za-z0-9_-]{1,64}:(?:live|movie|series):[0-9a-f]{64}"
    private val itemPattern = Regex(item)
    private val pattern = Regex("organization:[0-9a-f]{64}:(?:hidden|groupName|groupSort|channelSort|itemOrder|(?:newGroupsVisible|knownGroups|groupOrder):(?:LIVE|MOVIE|SERIES)|(?:channelHidden|channelName|channelOriginalName):$item)")
    private val identifier = Regex("[A-Za-z0-9_.:*\\-]{1,128}")
    fun key(value: String) = pattern.matches(value)
    private fun value(key: String, raw: String): String? {
        if (!key(key) || raw.length > 65536) return null
        val field = key.substringAfter(':').substringAfter(':')
        return when {
            field == "hidden" || field.startsWith("channelHidden:") || field.startsWith("newGroupsVisible:") -> raw.takeIf { it in setOf("true", "false") }
            field == "groupName" || field.startsWith("channelName:") || field.startsWith("channelOriginalName:") ->
                raw.takeIf { it.codePointCount(0, it.length) <= 160 && it.none(Char::isISOControl) && "://" !in it }
            field == "groupSort" -> raw.takeIf { it in setOf("playlist", "name", "manual") }
            field == "channelSort" -> raw.takeIf { it in setOf("playlist", "name", "nameDescending", "added", "rating", "watchTime", "manual") }
            else -> runCatching { JsonParser.parseString(raw) }.getOrNull()?.takeIf { list ->
                list.isJsonArray && list.asJsonArray.size() <= 512 && list.asJsonArray.all {
                    it.isJsonPrimitive && it.asJsonPrimitive.isString &&
                        (if (field == "itemOrder") itemPattern.matches(it.asString) else identifier.matches(it.asString))
                } && list.asJsonArray.map { it.asString }.distinct().size == list.asJsonArray.size()
            }?.toString()
        }
    }
    fun accepts(data: JsonElement): Boolean = data.isJsonObject && data.asJsonObject.let { obj ->
        obj.size() <= 512 && obj.entrySet().all { (key, entry) ->
            entry.isJsonPrimitive && entry.asJsonPrimitive.isString && value(key, entry.asString) != null
        } && data.toString().toByteArray(Charsets.UTF_8).size + (obj.size() * 2 - 1).coerceAtLeast(0) <= 65536
    }
    fun capture(settings: Map<String, String>): JsonElement {
        val result = JsonObject()
        settings.toSortedMap().forEach { (key, raw) -> value(key, raw)?.let { result.addProperty(key, it) } }
        require(accepts(result)) { "ACCOUNT_ORGANIZATION_LIMIT" }
        return result.takeIf { it.size() > 0 } ?: JsonNull.INSTANCE
    }
    fun patch(current: Map<String, String>, expected: String?, incoming: String?): Map<String, String>? {
        val before = expected?.let(JsonParser::parseString) ?: JsonNull.INSTANCE
        if (capture(current) != before) return null
        val next = incoming?.let(JsonParser::parseString) ?: JsonNull.INSTANCE
        require(next.isJsonNull || accepts(next)) { "ACCOUNT_ORGANIZATION_INVALID" }
        return current.filterKeys { !key(it) } + if (next.isJsonNull) emptyMap() else
            next.asJsonObject.entrySet().associate { (key, entry) -> key to requireNotNull(value(key, entry.asString)) }
    }
}

internal fun accountHomeSettingValue(key: String, raw: Any?): JsonElement = when (raw) {
    is Boolean -> JsonPrimitive(raw)
    is Int -> JsonPrimitive(raw)
    is String -> when (key) {
        "home.apps_order" -> accountAppsOrder(raw)
        "home.home_section_order" -> com.google.gson.JsonArray().apply { if (raw.isNotEmpty()) raw.split('|').forEach { add(it) } }
        else -> JsonPrimitive(raw)
    }
    is Set<*> -> accountPackages(raw)
    else -> JsonNull.INSTANCE
}

/** Only opaque identities and explicit hide/replay clocks leave Home; never app intents or titles. */
internal object AccountResumeMasks {
    const val PREFIX = "supabase_resume_masks_"
    const val SYNC_PREFIX = "supabase_resume_sync_"
    const val MAX_STAMP = 253402300799999L
    fun preference(key: String) = key.startsWith(PREFIX) || key.startsWith(SYNC_PREFIX)
    fun identity(key: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun external(key: String): Boolean = key.matches(Regex("external_app_resume_(?:tizentube|netflix|free-tv)_[0-9a-f]{64}")) ||
        key.substringBefore('|').let { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) &&
            !it.startsWith("com.jojotv.") && !it.startsWith("com.nuvio.") && key.contains('|') }
    fun validStamp(value: Long) = value != 0L && value in -MAX_STAMP..MAX_STAMP
    fun newest(first: Long, second: Long): Long {
        require(validStamp(first) && validStamp(second))
        return if (kotlin.math.abs(first) > kotlin.math.abs(second) ||
            kotlin.math.abs(first) == kotlin.math.abs(second) && first > second) first else second
    }
    fun change(current: Map<String, Long>, key: String, hidden: Boolean, now: Long): Map<String, Long> {
        require(key.matches(Regex("[0-9a-f]{64}")))
        val at = maxOf(now, kotlin.math.abs(current[key] ?: 0) + 1)
        require(at in 1..MAX_STAMP)
        return current + (key to if (hidden) at else -at)
    }
    fun merge(current: Map<String, Long>, incoming: Map<String, Long>): Map<String, Long> = current.toMutableMap().apply {
        incoming.forEach { (key, stamp) ->
            require(key.matches(Regex("[0-9a-f]{64}")) && validStamp(stamp))
            put(key, get(key)?.let { newest(it, stamp) } ?: stamp)
        }
        require(size <= 10000) { "ACCOUNT_RESUME_MASK_LIMIT" }
    }
    fun encode(values: Map<String, Long>) = JsonObject().apply { values.toSortedMap().forEach { (key, stamp) -> addProperty(key, stamp) } }.toString()
    fun decode(raw: String): Map<String, Long> {
        require(raw.length <= 1024 * 1024)
        val json = JsonParser.parseString(raw); require(json.isJsonObject && json.asJsonObject.size() <= 10000)
        return json.asJsonObject.entrySet().associate { (key, value) ->
            require(key.matches(Regex("[0-9a-f]{64}")) && value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                value.toString().matches(Regex("-?[0-9]+")))
            val stamp = value.asString.toLong(); require(validStamp(stamp)); key to stamp
        }
    }
    fun legacy(hidden: Set<String>, profile: Int, removed: (String) -> Long, restored: (String) -> Long): Map<String, Long> =
        hidden.filter(::external).mapNotNull { key ->
            val at = removed(key).takeIf { it > 0 } ?: if (profile == 1) 1L else return@mapNotNull null
            val replayed = restored(key)
            identity(key) to if (replayed >= at) -replayed else at
        }.toMap()
}

internal data class AccountResumeMask(val profile: String, val key: String, val stamp: Long, val revision: Long = 0) {
    fun validate() { require(accountUuid(profile) && key.matches(Regex("[0-9a-f]{64}")) && AccountResumeMasks.validStamp(stamp) && revision >= 0) }
    fun token() = "$profile/$key"
}
internal data class AccountResumeMaskPage(val next_cursor: Long, val head_revision: Long, val rows: List<AccountResumeMask>)
internal data class AccountResumeMaskSync(val bindings: List<String> = emptyList(), val cursor: Long = 0,
    val published: Map<String, Long> = emptyMap()) {
    fun pending(values: List<AccountResumeMask>) = values.filter { published[it.token()] != it.stamp }.take(50)
    fun acknowledge(sent: List<AccountResumeMask>, page: AccountResumeMaskPage) = copy(cursor = page.next_cursor,
        published = published.toMutableMap().apply { (sent + page.rows).forEach { value ->
            put(value.token(), get(value.token())?.let { AccountResumeMasks.newest(it, value.stamp) } ?: value.stamp)
        } })
}
internal fun accountResumeMaskPage(body: String, after: Long): AccountResumeMaskPage {
    require(body.length <= 512 * 1024)
    val page = JsonParser.parseString(body).asJsonObject
    fun integer(value: JsonElement) = value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
        value.toString().matches(Regex("-?[0-9]+")) && value.asString.toLongOrNull() != null
    require(page.keySet() == setOf("schema", "next_cursor", "head_revision", "rows") &&
        integer(page["schema"]) && page["schema"].asInt == 1 && integer(page["next_cursor"]) && integer(page["head_revision"]))
    val cursor = page["next_cursor"].asLong; val head = page["head_revision"].asLong
    require(cursor in after..head && page["rows"].isJsonArray && page["rows"].asJsonArray.size() <= 500)
    val rows = page["rows"].asJsonArray.map { value ->
        val row = value.asJsonObject
        require(row.keySet() == setOf("profile", "key", "stamp", "revision") && integer(row["stamp"]) && integer(row["revision"]) &&
            listOf("profile", "key").all { row[it].isJsonPrimitive && row[it].asJsonPrimitive.isString })
        AccountResumeMask(row["profile"].asString, row["key"].asString, row["stamp"].asLong, row["revision"].asLong).also {
            it.validate(); require(it.revision in (after + 1)..cursor)
        }
    }
    require(rows.map { it.token() }.distinct().size == rows.size && rows.zipWithNext().all { (a, b) -> a.revision < b.revision })
    require(if (rows.isEmpty()) cursor == after && head == after else cursor == rows.last().revision)
    return AccountResumeMaskPage(cursor, head, rows)
}

/** Canonical Freebox programme choices only: no media paths, titles, private images or credentials. */
internal object AccountHomeArtwork {
    const val SYNC_PREFIX = "supabase_home_artwork_sync_"
    private val keyPattern = Regex("home_videos_(?:programme_(?:portrait|hero)_artwork|series_episode_artwork)_[0-9a-f]{1,8}")
    fun key(value: String) = keyPattern.matches(value)
    fun preference(value: String) = key(value) || value.endsWith("_metadata") && key(value.removeSuffix("_metadata"))
    fun newest(a: AccountHomeArtworkChoice, b: AccountHomeArtworkChoice) =
        if (compareValuesBy(a, b, { it.stamp }, { it.image }, { it.tmdb_id }, { it.media_type }) >= 0) a else b

    fun capture(key: String, raw: String?, metadata: String?, previous: AccountHomeArtworkChoice?, now: Long): AccountHomeArtworkChoice? {
        require(key(key) && now in 1..253402300799999L)
        val image = com.jojotv.home.iptv.cloudImageReference(raw.orEmpty())
        if (!raw.isNullOrEmpty() && image.isEmpty()) return previous // A private/local choice stays local.
        val choice = runCatching { JsonParser.parseString(metadata.orEmpty()).asJsonObject }.getOrNull()
        val matches = choice?.let { obj -> listOf("artworkUrl", "poster", "backdrop").any { obj[it]?.let {
            it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == raw
        } == true } } == true
        val selectedAt = if (matches) runCatching { choice!!["selectedAt"].asLong }.getOrDefault(0) else 0
        if (previous == null && (image.isEmpty() || selectedAt !in 1..253402300799999L)) return null
        if (image.isNotEmpty() && selectedAt !in 1..253402300799999L) return previous
        val type = if (matches) runCatching { (choice!!["tmdbMediaType"] ?: choice["mediaType"]).asString }.getOrDefault("") else ""
        val id = if (matches && type in setOf("movie", "tv")) runCatching {
            choice!!["tmdbId"].asLong.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
        }.getOrDefault(0) else 0
        val candidate = AccountHomeArtworkChoice(key, image, if (image.isEmpty()) 0 else id,
            if (image.isEmpty() || id == 0) "" else type, previous?.stamp ?: selectedAt)
        if (previous != null && candidate == previous && selectedAt <= previous.stamp) return previous
        val stamp = if (previous == null || selectedAt > previous.stamp) selectedAt else maxOf(now, previous.stamp + 1)
        return candidate.copy(stamp = stamp).also { it.validate() }
    }
}
internal data class AccountHomeArtworkChoice(val key: String, val image: String, val tmdb_id: Int,
    val media_type: String, val stamp: Long) {
    fun validate() {
        require(AccountHomeArtwork.key(key) && stamp in 1..253402300799999L && tmdb_id >= 0)
        require(image.isEmpty() || com.jojotv.home.iptv.cloudImageName(image))
        require(if (tmdb_id == 0) media_type.isEmpty() else media_type in setOf("movie", "tv"))
        require(image.isNotEmpty() || tmdb_id == 0)
    }
    override fun toString() = "AccountHomeArtworkChoice(data=redacted)"
}
internal data class AccountHomeArtworkRow(val value: AccountHomeArtworkChoice, val revision: Long)
internal data class AccountHomeArtworkPage(val cursor: Long, val head: Long, val rows: List<AccountHomeArtworkRow>)
internal data class AccountHomeArtworkSync(val cursor: Long = 0, val known: Map<String, AccountHomeArtworkChoice> = emptyMap(),
    val published: Map<String, AccountHomeArtworkChoice> = emptyMap()) {
    fun pending(values: Map<String, AccountHomeArtworkChoice>) = values.values.filter { published[it.key] != it }.take(50)
    fun acknowledge(sent: List<AccountHomeArtworkChoice>, page: AccountHomeArtworkPage, current: Map<String, AccountHomeArtworkChoice>) =
        copy(cursor = page.cursor, known = current, published = published.toMutableMap().apply {
            (sent + page.rows.map { it.value }).forEach { put(it.key, get(it.key)?.let { old -> AccountHomeArtwork.newest(old, it) } ?: it) }
        })
}
internal fun accountHomeArtworkPage(body: String, after: Long): AccountHomeArtworkPage {
    require(after >= 0 && body.length <= 512 * 1024)
    val page = JsonParser.parseString(body).asJsonObject
    fun integer(value: JsonElement) = value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
        value.toString().matches(Regex("-?[0-9]+")) && value.asString.toLongOrNull() != null
    require(page.keySet() == setOf("schema", "next_cursor", "head_revision", "rows") &&
        integer(page["schema"]) && page["schema"].asLong == 1L && integer(page["next_cursor"]) && integer(page["head_revision"]))
    val cursor = page["next_cursor"].asLong; val head = page["head_revision"].asLong
    require(cursor in after..head && page["rows"].isJsonArray && page["rows"].asJsonArray.size() <= 500)
    val rows = page["rows"].asJsonArray.map { value ->
        val row = value.asJsonObject
        require(row.keySet() == setOf("key", "image", "tmdb_id", "media_type", "stamp", "revision") &&
            listOf("stamp", "revision", "tmdb_id").all { integer(row[it]) } &&
            listOf("key", "image", "media_type").all { row[it].isJsonPrimitive && row[it].asJsonPrimitive.isString })
        require(row["tmdb_id"].asLong in 0..Int.MAX_VALUE.toLong())
        AccountHomeArtworkRow(AccountHomeArtworkChoice(row["key"].asString, row["image"].asString,
            row["tmdb_id"].asInt, row["media_type"].asString, row["stamp"].asLong).also { it.validate() },
            row["revision"].asLong).also { require(it.revision > after && it.revision <= cursor) }
    }
    require(rows.map { it.value.key }.distinct().size == rows.size && rows.zipWithNext().all { (a, b) -> a.revision < b.revision })
    require(if (rows.isEmpty()) cursor == after && head == after else cursor == rows.last().revision)
    return AccountHomeArtworkPage(cursor, head, rows)
}

internal fun accountAppsOrder(value: String): com.google.gson.JsonArray = com.google.gson.JsonArray().apply {
    value.split('|').filter(String::isNotBlank).forEach(::add)
}
internal fun accountAppsOrder(value: JsonElement): String = value.asJsonArray.joinToString("|") { it.asString }
internal fun accountPackages(value: Set<*>): com.google.gson.JsonArray = com.google.gson.JsonArray().apply {
    require(value.all { it is String }) { "ACCOUNT_PACKAGE_LIST_INVALID" }
    value.filterIsInstance<String>().sorted().forEach(::add)
}

internal data class AccountRowId(val scope: String, val subject: String, val key: String) {
    fun token() = "$scope/$subject/$key"
    override fun toString() = "AccountRowId($scope, key=$key)"
}
internal data class AccountEntry(val scope: String, val subject: String, val key: String, val value: JsonElement, val revision: Long) {
    fun id() = AccountRowId(scope, subject, key)
    override fun toString() = "AccountEntry($scope, key=$key, revision=$revision, value=redacted)"
}
internal data class AccountOperation(val operation_id: String = UUID.randomUUID().toString(), val expected_revision: Long,
    val scope: String, val subject: String, val key: String, val value: JsonElement) {
    fun id() = AccountRowId(scope, subject, key)
    fun validate() { require(accountUuid(operation_id) && expected_revision >= 0); AccountSettingsCatalog.validate(id(), value) }
    override fun toString() = "AccountOperation(key=$key, value=redacted)"
}
internal data class AccountAck(val operation_id: String, val status: String, val server_revision: Long)
/** A saved v1 cursor may have passed v2 rows that the old APK could not read. */
internal class AccountReadCursor {
    private var restoredIdentity: String? = null
    fun after(identity: String, restored: Boolean, savedCursor: Long): Long =
        if (restored && restoredIdentity == identity) savedCursor else 0L
    fun applied(identity: String) { restoredIdentity = identity }
}

internal data class AccountPage(val schema: Int, val initialized: Boolean, val head_revision: Long, val rows: List<AccountEntry>) {
    fun validate(device: String) {
        require(schema == AccountSettingsCatalog.VERSION && head_revision >= 0 && rows.size <= 2000)
        require(initialized || rows.isEmpty())
        require(rows.map { it.id() }.distinct().size == rows.size)
        rows.forEach { row ->
            AccountSettingsCatalog.validate(row.id(), row.value)
            require(row.revision in 1..head_revision && (row.scope != "device" || row.subject == device))
        }
    }
    override fun toString() = "AccountPage(initialized=$initialized, rows=${rows.size})"
}

internal object AccountProtocol {
    private val gson = GsonBuilder().serializeNulls().create()
    private fun integer(value: JsonElement) = value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
        value.toString().matches(Regex("[0-9]+")) && value.asString.toLongOrNull() != null
    fun registered(body: String, device: String) {
        val result = JsonParser.parseString(body).asJsonObject
        require(result.keySet() == setOf("schema", "device") && integer(result["schema"]) && result["schema"].asInt == 1 &&
            result["device"].isJsonPrimitive && result["device"].asJsonPrimitive.isString && result["device"].asString == device)
    }
    fun seeded(body: String) {
        val result = JsonParser.parseString(body).asJsonObject
        require(result.keySet() == setOf("initialized") && result["initialized"].isJsonPrimitive &&
            result["initialized"].asJsonPrimitive.isBoolean && result["initialized"].asBoolean)
    }
    fun page(body: String, device: String): AccountPage {
        require(body.length <= 2 * 1024 * 1024)
        val json = JsonParser.parseString(body).asJsonObject
        require(json.keySet() == setOf("schema", "initialized", "head_revision", "rows"))
        require(integer(json["schema"]) && integer(json["head_revision"]) && json["initialized"].isJsonPrimitive && json["initialized"].asJsonPrimitive.isBoolean)
        require(json["rows"].isJsonArray && json["rows"].asJsonArray.size() <= 2000)
        require(json["rows"].asJsonArray.all { it.isJsonObject && it.asJsonObject.let { row ->
            row.keySet() == setOf("scope", "subject", "key", "value", "revision") && integer(row["revision"]) &&
                listOf("scope", "subject", "key").all { field -> row[field].isJsonPrimitive && row[field].asJsonPrimitive.isString }
        } })
        val page = gson.fromJson(json, AccountPage::class.java).also { it.validate(device) }
        return page.copy(rows = page.rows.map { row ->
            if (row.key in AccountSettingsCatalog.packageSetKeys && !row.value.isJsonNull)
                row.copy(value = accountPackages(row.value.asJsonArray.map { it.asString }.toSet())) else row
        })
    }
    fun acks(body: String): List<AccountAck> {
        val json = JsonParser.parseString(body).asJsonArray
        require(json.size() <= 50 && json.all { it.isJsonObject && it.asJsonObject.let { ack ->
            ack.keySet() == setOf("operation_id", "status", "server_revision") && integer(ack["server_revision"]) &&
                ack["operation_id"].isJsonPrimitive && ack["operation_id"].asJsonPrimitive.isString && accountUuid(ack["operation_id"].asString) &&
                ack["status"].isJsonPrimitive && ack["status"].asJsonPrimitive.isString && ack["status"].asString in setOf("applied", "conflict")
        } })
        return gson.fromJson(json, Array<AccountAck>::class.java).toList()
    }
}

/** One immutable in-flight operation per key; newer local intent is persisted separately. */
internal data class AccountJournal(
    val rows: List<AccountEntry> = emptyList(),
    val desired: Map<String, AccountOperation> = emptyMap(),
    val pending: List<AccountOperation> = emptyList(),
    val conflicts: Map<String, Long> = emptyMap()
) {
    fun change(id: AccountRowId, value: JsonElement): AccountJournal {
        AccountSettingsCatalog.validate(id, value)
        val revision = rows.firstOrNull { it.id() == id }?.revision ?: 0L
        return copy(desired = desired + (id.token() to AccountOperation(expected_revision = revision,
            scope = id.scope, subject = id.subject, key = id.key, value = value.deepCopy())))
    }
    fun prepare(): AccountJournal {
        val new = desired.filter { (id, _) -> id !in conflicts && pending.none { it.id().token() == id } }.values
            .sortedBy { if (it.key.startsWith("profiles.")) 0 else 1 }
        require(pending.size + new.size <= 2000) { "ACCOUNT_OUTBOX_FULL" }
        return copy(pending = pending + new.map { op -> op.copy(expected_revision = rows.firstOrNull { it.id() == op.id() }?.revision ?: 0L) })
    }
    fun acknowledge(sent: List<AccountOperation>, acks: List<AccountAck>): AccountJournal {
        require(acks.size == sent.size && acks.map { it.operation_id }.distinct().size == sent.size)
        require(sent.all { it in pending })
        acks.forEach { ack ->
            require(sent.any { it.operation_id == ack.operation_id } && ack.status in setOf("applied", "conflict") && ack.server_revision >= 0)
            if (ack.status == "applied") require(ack.server_revision > sent.single { it.operation_id == ack.operation_id }.expected_revision)
        }
        var result = this
        acks.forEach { ack ->
            val op = sent.single { it.operation_id == ack.operation_id }; val id = op.id().token()
            if (ack.status == "applied") {
                val current = result.rows.firstOrNull { it.id() == op.id() }
                val updated = if (current == null || current.revision <= ack.server_revision)
                    result.rows.filterNot { it.id() == op.id() } + AccountEntry(op.scope, op.subject, op.key, op.value, ack.server_revision) else result.rows
                result = result.copy(rows = updated, desired = if (result.desired[id]?.value == op.value) result.desired - id else result.desired)
            } else result = result.copy(conflicts = result.conflicts + (id to ack.server_revision))
        }
        return result.copy(pending = result.pending.filterNot { p -> sent.any { it.operation_id == p.operation_id } })
    }
    fun resolve(id: String, keepLocal: Boolean): AccountJournal {
        require(id in conflicts)
        return copy(conflicts = conflicts - id, desired = if (keepLocal) desired[id]?.let { desired + (id to it.copy(operation_id = UUID.randomUUID().toString())) } ?: desired else desired - id,
            pending = pending.filterNot { it.id().token() == id })
    }
    override fun toString() = "AccountJournal(pending=${pending.size}, conflicts=${conflicts.size})"
}

internal fun accountProfileValue(name: String, color: String, avatar: String?, primary: Boolean = false): JsonObject = JsonObject().apply {
    addProperty("name", name); addProperty("color", color)
    addProperty("avatar", avatar.orEmpty().takeIf { it in AccountSettingsCatalog.avatars }.orEmpty())
    addProperty("primary", primary)
}

internal fun accountRestoredAvatar(current: String?, incoming: String): String? =
    if (incoming.isEmpty() && !current.isNullOrEmpty() && current !in AccountSettingsCatalog.avatars) current
    else incoming.takeIf(String::isNotEmpty)

internal data class AccountSeedReviewContext(val owner: String, val project: String, val installation: String,
    val generation: String, val session: String, val bindings: List<String> = emptyList()) {
    override fun toString() = "AccountSeedReviewContext(data=redacted)"
}

/** Memory-only approval: no outbox entry exists until the user confirms these exact operations. */
internal class AccountSeedReview private constructor(private val context: AccountSeedReviewContext,
    private val observed: Map<String, JsonElement>, private val profiles: Map<String, JsonElement>,
    private val reviewed: List<AccountOperation>) {
    fun matches(current: AccountSeedReviewContext, values: Map<String, JsonElement>, localProfiles: Map<String, JsonElement>) =
        context == current && observed == values && profiles == localProfiles

    fun operations() = reviewed.map { it.copy(value = it.value.deepCopy()) }
    fun observedValues() = observed.mapValues { it.value.deepCopy() }

    fun inventory(): String = buildString {
        val metadata = reviewed.filter { it.key.startsWith("profiles.") }
        val names = metadata.associate { it.subject to it.value.asJsonObject["name"].asString }
        append("Vérifiez les valeurs qui seront envoyées. Les valeurs par défaut sont indiquées explicitement.\n\n")
        for ((key, title) in listOf("profiles.home" to "Profils de l'application", "profiles.iptv" to "Profils IPTV")) {
            val rows = metadata.filter { it.key == key }
            append("$title (${rows.size})\n")
            if (rows.isEmpty()) append("Aucun profil\n")
            rows.forEach { row -> val profile = row.value.asJsonObject
                append("• ${profile["name"].asString}${if (profile["primary"].asBoolean) " · principal" else ""}\n")
                append("  Couleur : ${profile["color"].asString} · Avatar : ${profile["avatar"].asString.ifEmpty { "Aucun" }}\n")
            }
            append('\n')
        }
        fun settings(title: String, entries: List<AccountOperation>) {
            append("$title (${entries.size})\n")
            if (entries.isEmpty()) append("Aucun réglage enregistré\n")
            entries.forEach { append("• ${AccountSeedInventory.label(it.key)} : ${AccountSeedInventory.value(it.key, it.value)}\n") }
            append('\n')
        }
        settings("Réglages communs à vos appareils", reviewed.filter { it.scope == "account" })
        settings("Réglages propres à cette TV · non appliqués aux autres TV", reviewed.filter { it.scope == "device" })
        metadata.filter { it.key == "profiles.iptv" }.forEach { profile ->
            settings("Préférences IPTV · ${names.getValue(profile.subject)}", reviewed.filter {
                it.scope == "profile" && it.subject == profile.subject && !it.key.startsWith("profiles.") })
        }
        append("Les réglages spécifiques absents ne sont pas envoyés.\n\n")
        append("Exclus : historiques et progression de lecture, favoris de contenus, programmations, mots de passe, clés, accès NAS/IPTV/SMB, URL, chemins et fichiers personnels. ")
        append("Les applications listées ci-dessus sont identifiées uniquement par leur nom de paquet ; aucune application ne sera installée.")
    }

    override fun toString() = "AccountSeedReview(operations=${reviewed.size}, values=redacted)"

    companion object {
        fun capture(context: AccountSeedReviewContext, values: Map<String, JsonElement>, profiles: Map<String, JsonElement>): AccountSeedReview {
            val frozen = values.toSortedMap().mapValues { it.value.deepCopy() }
            val localProfiles = profiles.toSortedMap().mapValues { it.value.deepCopy() }
            localProfiles.forEach { (token, value) ->
                require(AccountSettingsCatalog.spec("profiles.${token.substringBefore('/')}")?.accepts(value) == true) { "ACCOUNT_PROFILE_METADATA_INVALID" }
            }
            val payload = linkedMapOf<String, AccountOperation>()
            fun add(token: String, value: JsonElement) {
                val parts = token.split('/', limit = 3)
                require(parts.size == 3) { "ACCOUNT_SCOPE_INVALID" }
                val op = AccountOperation(expected_revision = 0, scope = parts[0], subject = parts[1], key = parts[2], value = value)
                op.validate()
                require(op.scope != "device" || op.subject == context.installation) { "ACCOUNT_SCOPE_INVALID" }
                payload[token] = op
            }
            // Profiles precede their preferences. Missing common settings explicitly restore app defaults.
            frozen.filterKeys { it.substringAfterLast('/').startsWith("profiles.") }.forEach(::add)
            val metadata = payload.values.toList()
            require(metadata.size == localProfiles.size) { "ACCOUNT_SEED_PREVIEW_CHANGED" }
            AccountSettingsCatalog.specs.filter { it.scope == "account" }.forEach { spec ->
                val token = AccountRowId("account", AccountSettingsCatalog.ACCOUNT_SUBJECT, spec.key).token()
                add(token, frozen[token] ?: JsonNull.INSTANCE)
            }
            frozen.forEach { (token, value) -> if (token !in payload) add(token, value) }
            require(payload.size <= 500 && payload.values.all { op -> op.scope != "profile" || metadata.any {
                it.subject == op.subject && it.key == if (op.key == "profiles.home") "profiles.home" else "profiles.iptv"
            } }) { "ACCOUNT_PROFILE_METADATA_INVALID" }
            return AccountSeedReview(context.copy(bindings = context.bindings.toList()), frozen, localProfiles, payload.values.toList())
        }
    }
}

/** Display labels only: the wire catalogue, scopes and values stay unchanged. */
internal object AccountSeedInventory {
    private fun labels(text: String) = text.trimIndent().lineSequence().associate { line ->
        val parts = line.split('|', limit = 2); parts[0] to parts[1]
    }
    private val names = labels("""
        home.icon_size|Taille des icônes
        home.home_rows|Nombre de lignes de l'accueil
        home.background_color|Couleur de fond
        home.background_theme_color|Palette du fond
        home.button_theme|Palette des boutons
        home.folder_theme|Palette des dossiers
        home.folder_icon_theme|Palette des icônes de dossiers
        home.network_folder_theme|Palette des dossiers réseau
        home.home_category_background_theme|Fond des catégories de l'accueil
        home.player_color|Couleur du lecteur
        home.interface_style|Style de l'interface
        home.home_bars_style|Style des barres de l'accueil
        home.home_hero_style|Style du bandeau de l'accueil
        home.dynamic_wallpaper_mode|Style du fond dynamique
        home.dynamic_wallpaper|Fond dynamique
        home.show_app_labels|Afficher le nom des applications
        home.settings_grid|Paramètres en grille
        home.files_grid|Fichiers en grille
        home.show_hidden_files|Afficher les fichiers cachés
        home.weather_widget|Widget météo
        home.app_labels_mode|Affichage des noms d'applications
        home.continue_watching_position|Position de la reprise de lecture
        home.continue_watching_hero_size|Taille du bandeau de reprise
        home.home_section_order|Ordre des sections de l'accueil
        home.app_sort_mode|Tri des applications
        home.start_at_boot|Démarrage automatique
        home.autostart_after_standby|Démarrage après la veille
        home.fire_tv_home_redirect|Bouton Accueil du Fire TV
        home.autostart_delay_seconds|Délai de démarrage automatique
        home.apps_order|Ordre des applications
        home.favorite_apps|Applications favorites
        home.hidden_apps|Applications masquées
        home.content_apps|Applications de contenus
        home.player_forward_seconds|Avance rapide
        home.player_rewind_seconds|Retour rapide
        iptv.confirmExit|Confirmer la sortie
        iptv.epgDescriptions|Descriptions du guide TV
        iptv.showLogos|Logos des chaînes
        iptv.showFavoritesGroup|Groupe Favoris
        iptv.showAllGroup|Groupe Toutes les chaînes
        iptv.currentProgramColor|Couleur du programme actuel
        iptv.currentProgramProgressOnly|Colorer uniquement la progression
        iptv.currentTimeIndicator|Indicateur de l'heure actuelle
        iptv.backToNow|Retour aux programmes en cours
        iptv.playerClock|Horloge du lecteur
        iptv.previewEnabled|Mini lecteur
        iptv.previewSound|Son du mini lecteur
        iptv.previewAutoPlay|Lecture automatique dans le mini lecteur
        iptv.channelNumbers|Numéros de chaîne
        iptv.channelNames|Noms de chaîne
        iptv.channelTwoLines|Noms de chaîne sur deux lignes
        iptv.programTwoLines|Programmes sur deux lignes
        iptv.currentChannelColor|Couleur de la chaîne actuelle
        iptv.showAllPlaylistsGroup|Groupe de toutes les listes de lecture
        iptv.favoritesFirst|Favoris en premier
        iptv.groupChannelsByPlaylist|Regrouper par liste de lecture
        iptv.searchFavoritesFirst|Rechercher les favoris en premier
        iptv.searchPastWithoutReplay|Rechercher les programmes passés sans replay
        iptv.playerInfoDate|Date dans les informations du lecteur
        iptv.playerInfoTime|Heure dans les informations du lecteur
        iptv.playerInfoMedia|Média dans les informations du lecteur
        iptv.playerInfoResolution|Résolution dans les informations du lecteur
        iptv.playerInfoDescription|Description dans les informations du lecteur
        iptv.playerInfoDescriptionOnChange|Description au changement de chaîne
        iptv.playerInfoGroup|Groupe dans les informations du lecteur
        iptv.playerInfoBottom|Informations en bas de l'écran
        iptv.playerInfoCard|Carte d'informations du lecteur
        iptv.skipIntroEnabled|Passer l'introduction
        iptv.channelNameEditor|Édition des noms de chaîne
        iptv.showCatchupIcon|Icône du replay
        iptv.animatedChannelScroll|Défilement animé des chaînes
        iptv.playerListCurrentColor|Couleur actuelle dans la liste du lecteur
        iptv.playerListDimPast|Atténuer les programmes passés
        iptv.playerListOverlayPrograms|Programmes dans la liste en superposition
        iptv.playerListOverlayDescription|Descriptions dans la liste en superposition
        iptv.playerHistoryGuide|Historique dans le guide du lecteur
        iptv.playerHistoryButton|Bouton Historique du lecteur
        iptv.playerHistoryPrograms|Programmes dans l'historique du lecteur
        iptv.playerHistoryPast|Programmes passés dans l'historique
        iptv.playerRecentNames|Noms dans les chaînes récentes
        iptv.textSize|Taille du texte
        iptv.themeBackground|Palette du fond
        iptv.themeAccent|Couleur d'accent
        iptv.interfaceTransparency|Transparence de l'interface
        iptv.epgPastDays|Conservation du guide TV passé
        iptv.historyDays|Durée de l'historique
        iptv.historyDelay|Délai avant ajout à l'historique
        iptv.groupSort|Tri des groupes
        iptv.channelSort|Tri des chaînes
        iptv.sourceSort|Tri des listes de lecture
        iptv.sourceOrder|Ordre des fournisseurs
        iptv.organization|Groupes et chaînes personnalisés
        iptv.playerPanelTimeout|Durée d'affichage du panneau du lecteur
        iptv.playerRecentCount|Nombre de chaînes récentes
        iptv.playerRecentDelay|Délai avant ajout aux chaînes récentes
        iptv.playerClockPosition|Position de l'horloge
        iptv.playerClockSize|Taille de l'horloge
        iptv.playerClockTransparency|Transparence de l'horloge
        iptv.recordingBefore|Marge avant enregistrement
        iptv.recordingAfter|Marge après enregistrement
        iptv.searchHistory|Mémoriser les recherches
        iptv.searchStay|Rester dans la recherche
        iptv.searchVoice|Recherche vocale
        iptv.rememberLastProfile|Mémoriser le dernier profil
        iptv.reminderBefore|Délai avant rappel
        iptv.reminderTimeout|Durée d'affichage du rappel
        iptv.reminderAction|Action du rappel
        iptv.previewFullscreenDelay|Délai avant le plein écran
        iptv.channelClick|Action sur une chaîne
        iptv.epgRefreshHours|Fréquence de mise à jour du guide TV
        iptv.afr|Fréquence d'images automatique (AFR)
        iptv.decoderPriority|Priorité du décodeur
        iptv.externalPlayerMode|Utilisation du lecteur externe
        iptv.externalPlayerPackage|Application de lecture externe
        iptv.playerMenuOrder|Ordre du menu du lecteur
        iptv.seekSeconds|Durée des sauts de lecture
        iptv.playLastChannel|Lire la dernière chaîne au démarrage
        iptv.afrLive|AFR pour le direct
        iptv.afrVod|AFR pour les films et séries
        iptv.afrRefresh|Adapter la fréquence d'affichage
        iptv.afrResolution|Adapter la résolution
        iptv.audioPassthrough|Transmission audio directe
        iptv.preferSurround|Préférer le son multicanal
        iptv.playerBlackScreen|Écran noir lors des transitions
        iptv.reminderWake|Réveil pour les rappels
        iptv.epgUpdateOnStart|Mise à jour du guide au démarrage
        iptv.epgUpdateOnPlaylist|Mise à jour du guide au changement de liste
        iptv.previewStayGuide|Rester sur le guide avec le mini lecteur
        iptv.previewTransition|Transition du mini lecteur
        iptv.playerListOverlayAutoPlay|Lecture automatique dans la liste superposée
        iptv.playerListPreviewAutoPlay|Lecture automatique dans la liste en aperçu
        iptv.playerListPreviewStay|Rester dans la liste en aperçu
        iptv.playerListOverlayStay|Rester dans la liste superposée
        iptv.guideOverlayStay|Rester sur le guide en superposition
        iptv.playerListPreviewTransition|Transition de la liste en aperçu
        iptv.bufferSize|Taille du tampon de lecture
        iptv.afrDelay|Délai de changement de fréquence
        iptv.audioLanguage|Langue audio préférée
        iptv.secondaryAudioLanguage|Seconde langue audio
        iptv.subtitleLanguage|Langue des sous-titres
        iptv.secondarySubtitleLanguage|Seconde langue des sous-titres
        iptv.subtitleUseForced|Sous-titres forcés
        iptv.subtitleShowOnlyPreferredLanguages|Limiter aux langues de sous-titres préférées
        iptv.autoNextEpisode|Épisode suivant automatique
        iptv.nextEpisodeThresholdMode|Mode du seuil de l'épisode suivant
        iptv.nextEpisodeThresholdPercent|Seuil de l'épisode suivant
        iptv.nextEpisodeThresholdMinutesBeforeEnd|Minutes restantes avant l'épisode suivant
        profiles.home|Profil de l'application
        profiles.iptv|Profil IPTV
    """)
    private val controls = labels("""
        search|Recherche
        channels|Chaînes
        record|Enregistrer
        multiview|Multivue
        video|Vidéo
        audio|Audio
        audioDelay|Décalage audio
        subtitles|Sous-titres
        display|Affichage
        sleep|Mise en veille
        favorite|Favoris
        settings|Paramètres
        history|Historique
        movies|Films
        series|Séries
        myList|Ma liste
        exit|Quitter
    """)
    private val buttons = labels("""
        ok|OK
        back|Retour
        left|Gauche
        right|Droite
        up|Haut
        down|Bas
        pageUp|Page précédente
        pageDown|Page suivante
        volumeUp|Volume +
        volumeDown|Volume −
        mute|Muet
        menu|Menu
        play|Lecture
        stop|Arrêt
        rewind|Retour rapide
        forward|Avance rapide
        record|Enregistrer
        delete|Supprimer
        info|Informations
        guide|Guide
        red|Rouge
        green|Vert
        yellow|Jaune
        blue|Bleu
    """)
    private val actions = labels("""
        IGNORE|Aucune action
        WATCH|Regarder
        BEGINNING|Reprendre au début
        EXTERNAL|Lecteur externe
        MENU|Menu
        GROUPS|Groupes
        DESCRIPTION|Description
        SEARCH|Recherche
        SETTINGS|Paramètres
        CHANNEL_OPTIONS|Options de chaîne
        GROUP_OPTIONS|Options du groupe
        MOVIES|Films
        SERIES|Séries
        RECORDINGS|Enregistrements
        CUSTOM_RECORDING|Enregistrement personnalisé
        MY_LIST|Ma liste
        FAVORITE|Favori
        MANAGE_FAVORITES|Gérer les favoris
        BLOCKING|Blocage
        VISIBILITY|Visibilité
        REORDER|Réordonner
        COPY|Copier
        RETURN_PLAYER|Retour au lecteur
        RETURN_NOW|Retour au programme actuel
        BACK|Retour
        EXIT|Quitter
        PAGE_UP|Page précédente
        PAGE_DOWN|Page suivante
        NEXT_PROGRAMS|Programmes suivants
        CHANNELS_OVERLAY|Chaînes en superposition
        CHANNELS_PREVIEW|Chaînes en aperçu
        GROUPS_OVERLAY|Groupes en superposition
        GROUPS_PREVIEW|Groupes en aperçu
        GUIDE_OVERLAY|Guide en superposition
        GUIDE_PREVIEW|Guide en aperçu
        INFO_CONTROLS|Informations et commandes
        INFO|Informations
        CONTROLS|Commandes
        RECENT|Chaînes récentes
        NEXT|Suivant
        PREVIOUS|Précédent
        INFO_NEXT|Informations suivantes
        INFO_PREVIOUS|Informations précédentes
        HISTORY|Historique
        PLAY_PAUSE|Lecture / pause
        STOP|Arrêt
        LIVE|Direct
        RECORD|Enregistrer
        VOLUME_UP|Volume +
        VOLUME_DOWN|Volume −
        MUTE|Muet
        REWIND|Retour rapide
        FORWARD|Avance rapide
        AUDIO|Audio
        SUBTITLES|Sous-titres
        ASPECT|Format d'image
    """)
    fun label(key: String): String = when {
        key.startsWith("iptv.remote.") -> key.split('.').let { parts ->
            "Télécommande · ${if (parts[2] == "guide") "Guide" else "Lecteur"} · ${buttons.getValue(parts[3].removeSuffix("Long"))}${if (parts[3].endsWith("Long")) " (appui long)" else ""}"
        }
        key.startsWith("iptv.playerMenu:") -> "Menu du lecteur · ${controls.getValue(key.substringAfter(':'))}"
        else -> "${if (key.startsWith("home.")) "Accueil" else "IPTV"} · ${names.getValue(key)}"
    }
    fun value(key: String, value: JsonElement): String {
        if (value.isJsonNull) return "Valeur par défaut (réinitialisation)"
        if (key == "iptv.organization") return "${value.asJsonObject.size()} personnalisation(s)"
        if (key == "iptv.sourceOrder") return "${value.asJsonArray.size()} fournisseur(s)"
        if (value.isJsonArray) return value.asJsonArray.mapIndexed { index, element ->
            "${index + 1}. ${controls[element.asString] ?: element.asString}"
        }.joinToString(" ; ").ifEmpty { "Liste vide" }
        if (value.asJsonPrimitive.isBoolean) return if (value.asBoolean) "Activé" else "Désactivé"
        val raw = value.asString
        if (AccountSettingsCatalog.spec(key)?.kind == "language" && raw !in setOf("device", "system", "none", "off", "auto", "original"))
            return java.util.Locale.forLanguageTag(raw).getDisplayName(java.util.Locale.FRENCH)
        return when {
            key.startsWith("iptv.remote.") -> actions.getValue(raw)
            key == "home.background_color" -> "#%08X".format(value.asInt)
            key in setOf("home.autostart_delay_seconds", "home.player_forward_seconds", "home.player_rewind_seconds", "iptv.historyDelay",
                "iptv.playerPanelTimeout", "iptv.playerRecentDelay", "iptv.reminderTimeout", "iptv.previewFullscreenDelay", "iptv.seekSeconds", "iptv.afrDelay") -> "$raw s"
            key in setOf("iptv.recordingBefore", "iptv.recordingAfter", "iptv.reminderBefore", "iptv.nextEpisodeThresholdMinutesBeforeEnd") -> "$raw min"
            key in setOf("iptv.epgPastDays", "iptv.historyDays") -> "$raw j"
            key == "iptv.epgRefreshHours" -> "$raw h"
            key in setOf("iptv.interfaceTransparency", "iptv.nextEpisodeThresholdPercent") -> "$raw %"
            else -> mapOf("small" to "Petit", "medium" to "Moyen", "large" to "Grand", "extraLarge" to "Très grand", "none" to "Aucun",
                "device" to "Langue de l'appareil", "system" to "Réglage système", "auto" to "Automatique", "off" to "Désactivé", "original" to "Langue originale",
                "home" to "Thème de l'accueil", "manual" to "Manuel", "alphabetical" to "Alphabétique", "transparent" to "Transparent", "black" to "Noir",
                "frosted" to "Dépoli", "current" to "Actuel", "muted" to "Atténué", "airy" to "Aéré", "editorial" to "Éditorial", "glow" to "Halo",
                "gradient" to "Dégradé", "solid" to "Uni", "focus" to "À la sélection", "always" to "Toujours", "never" to "Jamais", "before" to "Avant",
                "after" to "Après", "playlist" to "Liste de lecture", "name" to "Nom", "nameDescending" to "Nom décroissant", "added" to "Ajout", "watchTime" to "Temps de lecture",
                "topRight" to "En haut à droite", "topLeft" to "En haut à gauche", "bottomRight" to "En bas à droite", "bottomLeft" to "En bas à gauche",
                "low" to "Faible", "high" to "Élevée", "watch" to "Regarder", "details" to "Détails", "preview" to "Aperçu", "fullscreen" to "Plein écran",
                "OFF" to "Désactivé", "START" to "Au début", "START_STOP" to "Au début et à l'arrêt", "default" to "Par défaut", "hardware" to "Matériel",
                "software" to "Logiciel", "all" to "Tous les contenus", "vod" to "Films et séries", "PERCENTAGE" to "Pourcentage", "MINUTES_BEFORE_END" to "Minutes avant la fin")[raw] ?: raw.ifEmpty { "Aucune application" }
        }
    }
}
