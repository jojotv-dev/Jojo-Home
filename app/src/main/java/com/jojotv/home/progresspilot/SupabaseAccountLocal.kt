package com.jojotv.home.progresspilot

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import com.jojotv.home.iptv.IptvAccount
import com.jojotv.home.iptv.IptvStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal data class AccountLocalProfile(val domain: String, val id: String, val instance: String, val value: JsonElement) {
    fun token() = "$domain/$id/$instance"
    override fun toString() = "AccountLocalProfile($domain, data=redacted)"
}
internal data class AccountLocalSnapshot(val values: Map<String, JsonElement>, val profiles: List<AccountLocalProfile>) {
    override fun toString() = "AccountLocalSnapshot(settings=${values.size}, profiles=${profiles.size}, values=redacted)"
}

/** Small typed projections only. No library/settings blob, media catalogue or credentials. */
@Singleton
internal class SupabaseAccountLocal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    private val progress: SupabaseProgressPilotStore,
    private val watch: WatchProgressPreferences,
    private val storage: SupabaseAccountStore
) {
    private val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
    private val iptv by lazy { IptvStore(context) }
    private val instanceKey = stringPreferencesKey("supabase_account_profile_instance")

    private suspend fun instance(id: Int): String {
        // This store is not copied from the primary profile; deletion clears its identity.
        val data = factory.get(id, "watch_progress_preferences")
        data.data.first()[instanceKey]?.let { return it }
        var result = ""
        data.edit { result = it[instanceKey] ?: UUID.randomUUID().toString().also { uuid -> it[instanceKey] = uuid } }
        return result
    }

    private suspend fun localProfiles(): List<AccountLocalProfile> {
        iptv.load() // Local encrypted storage only; no NAS recovery or connection.
        return profiles.profilesList.first().map { profile ->
            AccountLocalProfile("home", profile.id.toString(), instance(profile.id),
                accountProfileValue(profile.name, profile.avatarColorHex, profile.avatarId, profile.id == 1))
        } + iptv.accounts().map { account -> AccountLocalProfile("iptv", account.id, account.id,
            accountProfileValue(account.name, account.ornamentColor, account.avatar)) }
    }

    suspend fun snapshot(state: AccountLocalState): AccountLocalSnapshot {
        val localProfiles = localProfiles()
        val result = linkedMapOf<String, JsonElement>()
        val settings = iptv.supabaseSettingsSnapshot()
        fun put(spec: AccountSettingSpec, subject: String, value: JsonElement?) {
            if (value != null && spec.accepts(value)) result[AccountRowId(spec.scope, subject, spec.key).token()] = value
        }
        AccountSettingsCatalog.specs.filterNot { it.kind == "profile" }.forEach { spec ->
            if (spec.scope == "profile") {
                state.bindings.filter { it.domain == "iptv" }.forEach { binding ->
                    val raw = settings.second[binding.localId]?.get(spec.key.removePrefix("iptv."))
                    put(spec, binding.cloudId, raw?.let { decode(spec, it) })
                }
            } else {
                val subject = if (spec.scope == "account") AccountSettingsCatalog.ACCOUNT_SUBJECT else state.installation
                val value = when {
                    spec.key.startsWith("home.") -> homeValue(spec.key).takeUnless { it.isJsonNull }
                    spec.key == "iptv.organization" -> AccountIptvOrganization.capture(settings.first).takeUnless { it.isJsonNull }
                    else -> settings.first[spec.key.removePrefix("iptv.")]?.let { decode(spec, it) }
                }
                put(spec, subject, value)
            }
        }
        state.bindings.forEach { binding ->
            localProfiles.firstOrNull { it.domain == binding.domain && it.id == binding.localId && it.instance == binding.instance }?.let {
                val spec = AccountSettingsCatalog.spec("profiles.${binding.domain}")!!
                put(spec, binding.cloudId, it.value)
            }
        }
        return AccountLocalSnapshot(result, localProfiles)
    }

    suspend fun prepareSeedProfiles(): AccountLocalState {
        val locals = localProfiles()
        val source = progress.settings.value
        val verified = source.scope?.takeIf { bound -> source.session?.ownerId == storage.state.value.owner &&
            watch.pilotProfileInstanceId(bound.localProfileId) == bound.localProfileInstanceId }
        val state = storage.state.value
        return state.copy(bindings = locals.map { profile ->
            val previous = state.bindings.firstOrNull { it.domain == profile.domain && it.localId == profile.id && it.instance == profile.instance }
            previous ?: AccountProfileBinding(profile.domain,
                when {
                    profile.domain == "home" && profile.id.toIntOrNull() == verified?.localProfileId -> verified!!.profileBindingId
                    profile.domain == "iptv" && accountUuid(profile.id) -> profile.id
                    else -> UUID.randomUUID().toString()
                }, profile.id, profile.instance)
        }, knownLocalProfiles = locals.map { it.token() })
    }

    /** Existing ambiguous profiles are left intact and can be linked by an ordinary picker. */
    suspend fun bindRemoteProfiles(page: AccountPage, valid: () -> Boolean): Boolean {
        var complete = true
        val metadata = page.rows.filter { it.key.startsWith("profiles.") && !it.value.isJsonNull }
            .sortedByDescending { it.value.asJsonObject["primary"].asBoolean }
        for (row in metadata) {
            check(valid()) { "ACCOUNT_SESSION_CHANGED" }
            var state = storage.state.value
            val known = state.bindings.firstOrNull { it.cloudId == row.subject }
            if (known != null) {
                if (localProfiles().none { it.domain == known.domain && it.id == known.localId && it.instance == known.instance }) complete = false
                continue
            }
            val domain = row.key.removePrefix("profiles.")
            val locals = localProfiles()
            val reference = progress.settings.value
            val bound = reference.scope
            val existing = if (domain == "iptv") locals.firstOrNull { it.domain == domain && it.id == row.subject }
                else if (bound?.profileBindingId == row.subject && reference.session?.ownerId == state.owner &&
                    watch.pilotProfileInstanceId(bound.localProfileId) == bound.localProfileInstanceId)
                    locals.firstOrNull { it.domain == domain && it.id == bound.localProfileId.toString() } else null
            if (existing != null) {
                storage.update { it.copy(bindings = it.bindings + AccountProfileBinding(domain, row.subject, existing.id, existing.instance)) }
                continue
            }
            val data = row.value.asJsonObject
            val pristine = domain == "home" && data["primary"].asBoolean && profiles.isUnconfiguredForCloudRestore()
            if (!pristine && locals.any { it.domain == domain && it.value.asJsonObject["name"].asString == data["name"].asString }) {
                complete = false; continue
            }
            if (domain == "home") {
                val current = profiles.profilesList.first()
                val id = if (pristine) 1 else if (data["primary"].asBoolean) null else (2..5).firstOrNull { candidate -> current.none { it.id == candidate } }
                if (id == null) { complete = false; continue }
                val expected = current.firstOrNull { it.id == id }
                val profile = (expected ?: UserProfile(id, "", "#1E88E5")).copy(name = data["name"].asString,
                    avatarColorHex = data["color"].asString, avatarId = data["avatar"].asString.takeIf(String::isNotBlank))
                val binding = AccountProfileBinding(domain, row.subject, id.toString(), instance(id))
                storage.update { it.copy(bindings = it.bindings + binding) }
                check(valid())
                if (!profiles.restoreProfileIfUnchanged(expected, profile)) {
                    storage.update { it.copy(bindings = it.bindings - binding) }; complete = false
                } else factory.markProfileCreated(id)
            } else {
                val binding = AccountProfileBinding(domain, row.subject, row.subject, row.subject)
                storage.update { it.copy(bindings = it.bindings + binding) }
                check(valid())
                if (!iptv.restoreSupabaseProfile(IptvAccount(row.subject, data["name"].asString, data["avatar"].asString, data["color"].asString), compare = true)) {
                    storage.update { it.copy(bindings = it.bindings - binding) }; complete = false
                }
            }
        }
        return complete
    }

    suspend fun discoverNewProfiles(): AccountLocalState {
        val locals = localProfiles()
        return storage.update { state ->
            val additions = locals.filter { it.token() !in state.knownLocalProfiles && state.bindings.none { b -> b.domain == it.domain && b.localId == it.id && b.instance == it.instance } }
            state.copy(bindings = state.bindings + additions.map { AccountProfileBinding(it.domain,
                if (it.domain == "iptv" && accountUuid(it.id)) it.id else UUID.randomUUID().toString(), it.id, it.instance) },
                knownLocalProfiles = (state.knownLocalProfiles + locals.map { it.token() }).distinct())
        }
    }

    suspend fun restore(page: AccountPage, before: AccountLocalSnapshot, valid: () -> Boolean) {
        val current = snapshot(storage.state.value)
        for (row in page.rows) {
            check(valid()) { "ACCOUNT_SESSION_CHANGED" }
            val state = storage.state.value; val id = row.id().token()
            if (id in state.journal.desired || state.journal.pending.any { it.id() == row.id() }) continue
            if (row.scope == "profile") {
                val binding = state.bindings.firstOrNull { it.cloudId == row.subject } ?: continue
                if (current.profiles.none { it.domain == binding.domain && it.id == binding.localId && it.instance == binding.instance }) continue
            }
            var expected = before.values[id] ?: JsonNull.INSTANCE
            if (row.key.startsWith("profiles.")) {
                val binding = state.bindings.firstOrNull { it.cloudId == row.subject } ?: continue
                if (current.profiles.none { it.domain == binding.domain && it.id == binding.localId && it.instance == binding.instance }) continue
                expected = before.profiles.firstOrNull { it.domain == binding.domain && it.id == binding.localId && it.instance == binding.instance }?.value
                    ?: current.values[id] ?: JsonNull.INSTANCE
            }
            val actual = current.values[id] ?: JsonNull.INSTANCE
            if (actual != expected && actual != row.value) continue // A newer local intent wins until CAS resolution.
            if (actual == row.value) continue
            if (row.key.startsWith("home.")) withContext(Dispatchers.Main.immediate) {
                check(valid())
                val latest = homeValue(row.key)
                if (latest == actual) {
                    val editor = home.edit()
                    val key = if (row.key == "home.apps_order") AccountSettingsRouting.appOrderKey(home) else row.key.removePrefix("home.")
                    if (key != null) {
                        val value: Any? = when {
                            row.value.isJsonNull -> null
                            row.key == "home.apps_order" -> accountAppsOrder(row.value)
                            row.key == "home.home_section_order" -> row.value.asJsonArray.joinToString("|") { it.asString }
                            AccountSettingsCatalog.spec(row.key)!!.kind == "packages" -> row.value.asJsonArray.map { it.asString }.toSet()
                            AccountSettingsCatalog.spec(row.key)!!.kind == "boolean" -> row.value.asBoolean
                            AccountSettingsCatalog.spec(row.key)!!.kind == "integer" -> row.value.asInt
                            else -> row.value.asString
                        }
                        AccountSettingsRouting.put(editor, key, value)
                        if (key == "favorite_apps") editor.putInt("favorite_apps_version", 3)
                        check(editor.commit()) { "ACCOUNT_HOME_STORAGE_FAILED" }
                    }
                }
            } else if (row.key.startsWith("iptv.")) {
                val localId = if (row.scope == "profile") state.bindings.firstOrNull { it.domain == "iptv" && it.cloudId == row.subject }?.localId ?: continue else null
                val name = row.key.removePrefix("iptv.")
                fun encoded(value: JsonElement): String? = when {
                    value.isJsonNull -> null
                    value.isJsonArray || value.isJsonObject -> value.toString()
                    else -> value.asString
                }
                iptv.restoreSupabaseSettings(localId, mapOf(name to encoded(row.value)), mapOf(name to encoded(actual)))
            } else if (!row.value.isJsonNull) {
                val binding = state.bindings.firstOrNull { it.cloudId == row.subject } ?: continue
                val data = row.value.asJsonObject
                if (current.profiles.none { it.domain == binding.domain && it.id == binding.localId && it.instance == binding.instance }) continue
                if (binding.domain == "home") {
                    val local = profiles.profilesList.first().firstOrNull { it.id.toString() == binding.localId } ?: continue
                    if (instance(local.id) != binding.instance || accountProfileValue(local.name, local.avatarColorHex, local.avatarId, local.id == 1) != actual) continue
                    profiles.restoreProfileIfUnchanged(local, local.copy(name = data["name"].asString, avatarColorHex = data["color"].asString,
                        avatarId = accountRestoredAvatar(local.avatarId, data["avatar"].asString)))
                } else {
                    val previous = iptv.accounts().firstOrNull { it.id == binding.localId } ?: continue
                    if (accountProfileValue(previous.name, previous.ornamentColor, previous.avatar) != actual) continue
                    iptv.restoreSupabaseProfile(IptvAccount(binding.localId, data["name"].asString,
                        accountRestoredAvatar(previous.avatar, data["avatar"].asString).orEmpty(), data["color"].asString), previous, true)
                }
            }
        }
    }

    private fun homeValue(key: String): JsonElement {
        val storageKey = if (key == "home.apps_order") AccountSettingsRouting.appOrderKey(home) else key.removePrefix("home.")
        val value = storageKey?.let { home.all[it] } ?: return JsonNull.INSTANCE
        return accountHomeSettingValue(key, value)
    }
    private fun decode(spec: AccountSettingSpec, value: String): JsonElement? = when (spec.kind) {
        "boolean" -> value.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        "integer" -> value.toIntOrNull()?.let(::JsonPrimitive)
        "enum_list", "uuid_list" -> runCatching { JsonParser.parseString(value) }.getOrNull()
        else -> JsonPrimitive(value)
    }
}
