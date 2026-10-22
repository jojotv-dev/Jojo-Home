package com.jojotv.home.progresspilot

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

internal data class AccountProfileBinding(val domain: String, val cloudId: String, val localId: String, val instance: String = "")
internal data class AccountSeed(val operation_id: String = UUID.randomUUID().toString(), val operations: List<AccountOperation>)
internal data class AccountLocalState(
    val owner: String = "", val project: String = "", val selected: Boolean = false,
    val installation: String = UUID.randomUUID().toString(),
    val proof: String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) },
    val generation: String = UUID.randomUUID().toString(),
    val restored: Boolean = false, val initialized: Boolean = false,
    val backupKnownMissing: Boolean = false,
    val cursor: Long = 0,
    val journal: AccountJournal = AccountJournal(),
    val bindings: List<AccountProfileBinding> = emptyList(),
    val knownLocalProfiles: List<String> = emptyList(),
    val observed: Map<String, JsonElement> = emptyMap(), val seed: AccountSeed? = null,
    val issue: String? = null,
    val content: AccountContentState = AccountContentState(),
    val netflix: AccountNetflixState = AccountNetflixState(),
    val login: AccountLoginChoices? = null,
    val recoveryIdentity: String? = null
) {
    override fun toString() = "AccountLocalState(selected=$selected, restored=$restored, credentials=redacted)"
}

/** Separate from progress/NAS: a failure here must not rewrite their configuration. */
@Singleton
internal class SupabaseAccountStore @Inject constructor(@ApplicationContext context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "supabase-account/settings.bin"))
    private val gson = GsonBuilder().serializeNulls().create()
    private var healthy = true
    private val key: SecretKey by lazy {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey("jojo_account_settings_v1", null) as? SecretKey) ?: run {
            check(!file.baseFile.exists()) { "ACCOUNT_LOCAL_KEY_LOST" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("jojo_account_settings_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    init { if (healthy) writeRoute(mutable.value.selected, mutable.value.content.selected) }

    private fun writeRoute(selected: Boolean, content: Boolean) {
        val flags = app.getSharedPreferences(AccountSettingsRouting.PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean("selected", false) != selected || flags.getBoolean("content", false) != content)
            check(flags.edit().putBoolean("selected", selected).putBoolean("content", content).commit()) {
            "ACCOUNT_ROUTE_STORAGE_FAILED"
        }
    }

    @Synchronized private fun load(): AccountLocalState {
        if (!file.baseFile.exists()) return AccountLocalState()
        return try {
            require(file.baseFile.length() <= 4 * 1024 * 1024)
            val bytes = file.readFully(); require(bytes.size > 28)
            val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                doFinal(bytes, 12, bytes.size - 12)
            }
            try {
                gson.fromJson(String(clear, Charsets.UTF_8), AccountLocalState::class.java).also(::validate)
            } finally { clear.fill(0) }
        } catch (_: Exception) {
            healthy = false
            AccountLocalState(issue = "ACCOUNT_LOCAL_STORAGE_UNREADABLE")
        }
    }

    @Synchronized fun update(block: (AccountLocalState) -> AccountLocalState): AccountLocalState {
        check(healthy) { "ACCOUNT_LOCAL_STORAGE_UNREADABLE" }
        val next = block(mutable.value)
        if (next == mutable.value) { writeRoute(next.selected, next.content.selected); return next }
        validate(next)
        // Protect local settings before a first selection can become durable. If the
        // following write fails, the conservative route is reconciled at restart/stop.
        if (next.selected || next.content.selected) writeRoute(next.selected, next.content.selected)
        val clear = gson.toJson(next).toByteArray(Charsets.UTF_8)
        try {
            require(clear.size <= 4 * 1024 * 1024) { "ACCOUNT_LOCAL_STORAGE_FULL" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val encrypted = cipher.iv + cipher.doFinal(clear)
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        } finally { clear.fill(0) }
        // Unprotect only after a durable explicit stop. Never lose the encrypted outbox.
        writeRoute(next.selected, next.content.selected)
        mutable.value = next
        return next
    }

    private fun validate(state: AccountLocalState) {
        require(accountUuid(state.installation) && accountUuid(state.generation) && state.proof.matches(Regex("[0-9a-f]{64}")))
        require(state.owner.isEmpty() || accountUuid(state.owner))
        require(state.recoveryIdentity == null || state.recoveryIdentity.matches(Regex("[0-9a-f]{64}")))
        require(state.bindings.size <= 40 && state.bindings.all { it.domain in setOf("home", "iptv") && accountUuid(it.cloudId) && it.localId.isNotBlank() })
        require(state.bindings.map { it.cloudId }.distinct().size == state.bindings.size)
        require(state.journal.pending.size <= 2000 && state.journal.desired.size <= 2000 && state.observed.size <= 2000)
        state.journal.pending.forEach(AccountOperation::validate)
        state.journal.desired.values.forEach(AccountOperation::validate)
        state.journal.rows.forEach { AccountSettingsCatalog.validate(it.id(), it.value); require(it.revision > 0) }
        state.seed?.let { require(accountUuid(it.operation_id) && it.operations.size <= 500); it.operations.forEach(AccountOperation::validate) }
        require(accountUuid(state.content.generation) && state.content.journal.cursor >= 0)
        require(state.content.journal.pending.size <= 2000 && state.content.journal.desired.size <= 2000 &&
            state.content.journal.rows.size <= 10000 && state.content.observed.size <= 10000 && state.content.journal.writeBases.size <= 2000)
        require(state.content.journal.writeBases.values.all { it >= 0 })
        state.content.journal.pending.forEach(AccountContentOp::validate)
        state.content.journal.desired.values.forEach(AccountContentValue::validate)
        state.content.journal.rows.values.forEach(AccountContentRow::validate)
        state.netflix.validate()
    }
}

/** Local transport choice only: this never changes the selected cloud or progress source. */
object NasDataSyncRouting {
    private const val KEY = "nas_data_sync_enabled"
    internal fun enabled(stored: Boolean?, cloudSettings: Boolean, cloudActivity: Boolean): Boolean =
        stored ?: !(cloudSettings && cloudActivity)

    @JvmStatic fun enabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        return enabled(if (prefs.contains(KEY)) prefs.getBoolean(KEY, true) else null,
            AccountSettingsRouting.selected(context), AccountContentRouting.selected(context))
    }

    @JvmStatic fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}

/** Only routing flags live in preferences; tokens, profile names and payloads do not. */
object AccountSettingsRouting {
    internal const val PREFS = "supabase_account_routing"
    const val NETFLIX_CHANGED = "supabase_netflix_changed"
    private val resumeCache = linkedMapOf<String, Pair<String, Map<String, Long>>>()
    private val artworkKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var artworkKeysLoaded = false
    @JvmStatic fun selected(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("selected", false)
    @JvmStatic fun managesHome(context: Context, key: String): Boolean =
        key == NETFLIX_CHANGED || AccountResumeMasks.preference(key) || key.startsWith(AccountHomeArtwork.SYNC_PREFIX) ||
            selected(context) && (AccountSettingsCatalog.homeKey(key) || AccountHomeArtwork.preference(key))
    @JvmStatic fun observeNetflixSnapshot(context: Context, snapshot: String) {
        if (selected(context)) EntryPointAccessors.fromApplication(context.applicationContext,
            AccountSettingsEntryPoint::class.java).accountSettings().netflixObserved(snapshot)
    }
    @JvmStatic fun netflixSnapshot(context: Context): String = if (!selected(context)) "[]" else
        EntryPointAccessors.fromApplication(context.applicationContext, AccountSettingsEntryPoint::class.java)
            .accountSettings().netflixSnapshot()
    @JvmStatic fun managesIptv(context: Context, key: String): Boolean = selected(context) && AccountSettingsCatalog.iptvRecord(key)

    @JvmStatic fun isResumeMaskPreference(key: String?): Boolean = key?.startsWith(AccountResumeMasks.PREFIX) == true &&
        accountUuid(key.removePrefix(AccountResumeMasks.PREFIX))
    @JvmStatic fun isHomeArtworkPreference(key: String?): Boolean = key != null && AccountHomeArtwork.preference(key)
    internal fun homeArtworkChanged(key: String) {
        val base = key.removeSuffix("_metadata")
        if (AccountHomeArtwork.key(base)) artworkKeys.add(base)
    }
    @JvmStatic fun homeArtworkReset(context: Context, key: String): Boolean = AccountHomeArtwork.key(key) && runCatching {
        val raw = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE).getString(key + "_metadata", "{}")
        raw?.contains("\"deleted\"") == true && com.google.gson.JsonParser.parseString(raw).asJsonObject["deleted"]?.asBoolean == true
    }.getOrDefault(false)

    internal fun homeArtwork(context: Context, known: Map<String, AccountHomeArtworkChoice>): Map<String, AccountHomeArtworkChoice> {
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        return synchronized(home) {
            // Discover once; changes add their key through the existing preferences observer.
            // An unchanged foreground poll must not clone the entire Home preferences catalogue.
            if (!artworkKeysLoaded) {
                artworkKeys.addAll(home.all.keys.filter(AccountHomeArtwork::key))
                artworkKeysLoaded = true
            }
            val keys = known.keys + artworkKeys
            require(keys.size <= 2000)
            keys.mapNotNull { key -> AccountHomeArtwork.capture(key, home.getString(key, null), home.getString(key + "_metadata", null),
                known[key], System.currentTimeMillis())?.let { key to it } }.toMap()
        }
    }

    internal fun restoreHomeArtwork(context: Context, incoming: AccountHomeArtworkChoice, known: AccountHomeArtworkChoice?): AccountHomeArtworkChoice {
        incoming.validate()
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        return synchronized(home) {
            val raw = home.getString(incoming.key, null)
            val metadata = home.getString(incoming.key + "_metadata", null)
            val local = AccountHomeArtwork.capture(incoming.key, raw, metadata, known, System.currentTimeMillis())
            val next = local?.let { AccountHomeArtwork.newest(it, incoming) } ?: incoming
            if (!raw.isNullOrEmpty() && com.jojotv.home.iptv.cloudImageReference(raw).isEmpty()) return@synchronized next
            val url = com.jojotv.home.iptv.cloudImageUrl(next.image)
            val details = com.google.gson.JsonObject().apply {
                addProperty("artworkUrl", url); addProperty("selectedAt", next.stamp)
                addProperty("tmdbId", next.tmdb_id); addProperty("mediaType", next.media_type)
                addProperty("deleted", next.image.isEmpty())
            }.toString()
            if (raw.orEmpty() != url || local != next) {
                check(home.edit().putString(next.key, url).putString(next.key + "_metadata", details)
                    .remove("continue_watching_home_cache").commit()) { "ACCOUNT_ARTWORK_STORAGE_FAILED" }
            }
            next
        }
    }
    private fun resumeBinding(context: Context, profile: Int) = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext, AccountSettingsEntryPoint::class.java).accountSettings().state.value.bindings
        .firstOrNull { it.domain == "home" && it.localId == profile.toString() }

    private fun resumeValues(home: SharedPreferences, profile: String): Map<String, Long> {
        val key = AccountResumeMasks.PREFIX + profile
        val raw = home.getString(key, "{}").orEmpty()
        resumeCache[profile]?.takeIf { it.first == raw }?.let { return it.second }
        val values = AccountResumeMasks.decode(raw)
        if (resumeCache.size >= 16) resumeCache.remove(resumeCache.keys.first())
        resumeCache[profile] = raw to values
        return values
    }

    @JvmStatic fun resumeDismissal(context: Context, profile: Int, identity: String): Boolean? {
        if (!AccountResumeMasks.external(identity)) return null
        val binding = resumeBinding(context, profile) ?: return null
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        return synchronized(home) { resumeValues(home, binding.cloudId)[AccountResumeMasks.identity(identity)]?.let { it > 0 } }
    }

    @JvmStatic fun recordResumeDismissal(context: Context, profile: Int, identity: String, hidden: Boolean) {
        if (!AccountResumeMasks.external(identity)) return
        val binding = resumeBinding(context, profile) ?: return
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        synchronized(home) {
            val current = resumeValues(home, binding.cloudId)
            val key = AccountResumeMasks.identity(identity)
            if (!hidden && (current[key] == null || current.getValue(key) < 0)) return
            val next = AccountResumeMasks.change(current, key, hidden, System.currentTimeMillis())
            home.edit().putString(AccountResumeMasks.PREFIX + binding.cloudId, AccountResumeMasks.encode(next))
                .remove("continue_watching_home_cache").apply()
        }
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, AccountSettingsEntryPoint::class.java)
            .accountSettings().resumeMasksChanged()
    }

    /** Called on a worker, once per associated profile; raw legacy identities are only hashed locally. */
    internal fun resumeMasks(context: Context, binding: AccountProfileBinding): Map<String, Long> {
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        return synchronized(home) {
            var values = resumeValues(home, binding.cloudId)
            val marker = AccountResumeMasks.PREFIX + "migrated_" + binding.cloudId
            if (!home.getBoolean(marker, false)) {
                val profile = binding.localId.toInt()
                val legacy = AccountResumeMasks.legacy(home.getStringSet("jojotv_home_hidden_resume_keys", emptySet()).orEmpty(), profile,
                    { home.getLong("home_resume_removed_p${profile}_$it", 0) },
                    { home.getLong("home_resume_restored_p${profile}_$it", 0) })
                values = AccountResumeMasks.merge(values, legacy)
                check(home.edit().putString(AccountResumeMasks.PREFIX + binding.cloudId, AccountResumeMasks.encode(values))
                    .putBoolean(marker, true).commit()) { "ACCOUNT_RESUME_STORAGE_FAILED" }
            }
            values
        }
    }

    internal fun restoreResumeMasks(context: Context, profile: String, incoming: Map<String, Long>) {
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        synchronized(home) {
            val previous = resumeValues(home, profile)
            val next = AccountResumeMasks.merge(previous, incoming)
            if (previous != next) check(home.edit().putString(AccountResumeMasks.PREFIX + profile, AccountResumeMasks.encode(next))
                .remove("continue_watching_home_cache").commit()) { "ACCOUNT_RESUME_STORAGE_FAILED" }
        }
    }

    @JvmStatic fun stripHomeNasSnapshot(context: Context, snapshot: org.json.JSONObject) {
        val values = snapshot.optJSONObject("allSettings")
        values?.keys()?.asSequence()?.toList()?.filter { managesHome(context, it) }?.forEach(values::remove)
        if (!selected(context)) return
        // Legacy top-level fields must not publish cloud settings either.
        listOf("iconSize", "homeRows", "backgroundColor", "backgroundThemeColor", "dynamicWallpaper", "dynamicWallpaperMode",
            "appSortMode", "showAppLabels", "appLabelsMode", "startAtBoot", "autostartDelaySeconds", "autostartAfterStandby",
            "settingsGrid", "filesGrid", "continueWatchingPosition", "appOrder", "appOrders", "weatherEnabled",
            "favoriteApps", "hiddenApps", "contentApps").forEach(snapshot::remove)
    }

    internal fun appOrderKey(prefs: SharedPreferences): String? = prefs.getString("nas_device_id", "")?.takeIf(String::isNotBlank)
        ?.let { "app_order_device_${Integer.toUnsignedString(it.hashCode(), 16)}" }

    @JvmStatic fun preserveHome(context: Context, editor: SharedPreferences.Editor) {
        val prefs = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        val current = prefs.all
        val keys = (if (selected(context)) AccountSettingsCatalog.specs.filter { it.key.startsWith("home.") && it.key != "home.apps_order" }
            .map { it.key.removePrefix("home.") }.toSet() + "favorite_apps_version" + current.keys.filter {
                it.startsWith("app_order_device_") || AccountHomeArtwork.preference(it) } else emptySet()) +
                current.keys.filter { AccountResumeMasks.preference(it) || it.startsWith(AccountHomeArtwork.SYNC_PREFIX) }
        for (key in keys) {
            editor.remove(key)
            put(editor, key, current[key])
        }
    }

    internal fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
