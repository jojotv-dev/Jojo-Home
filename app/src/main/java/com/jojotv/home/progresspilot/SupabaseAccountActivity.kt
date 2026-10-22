package com.jojotv.home.progresspilot

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Normal account page: no endpoint, UUID, NAS account or HMAC field. */
class SupabaseAccountActivity : Activity() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: SupabaseAccountRuntime
    private lateinit var content: SupabaseAccountContentRuntime
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var authStatus: TextView
    private lateinit var contentStatus: TextView
    private lateinit var progressStatus: TextView
    private lateinit var settingsToggle: SyncToggle
    private lateinit var progressToggle: SyncToggle
    private lateinit var contentToggle: SyncToggle
    private lateinit var progress: ProgressPilotEntryPoint
    private val commands = mutableListOf<View>()
    private var busyStatus: TextView? = null
    private var reconnecting = false
    private val accent = Color.rgb(78, 174, 234)
    private data class SyncToggle(val row: LinearLayout, val control: Switch)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.rgb(16, 23, 34))
        }
        setContentView(ScrollView(this).apply { addView(rows) })
        label("Compte Supabase", 26f)
        if (!com.jojotv.home.BuildConfig.JOJO_PROGRESS_PILOT_COMPILED) {
            label("La connexion Supabase n'est pas disponible dans cette version de Jojo Home.")
            return
        }
        runtime = EntryPointAccessors.fromApplication(applicationContext, AccountSettingsEntryPoint::class.java).accountSettings()
        runtime.start()
        content = AccountContentRouting.runtime(this)
        progress = EntryPointAccessors.fromApplication(applicationContext, ProgressPilotEntryPoint::class.java)
        label("Connectez votre compte pour retrouver automatiquement vos données. Les interrupteurs permettent ensuite de choisir ce qui se synchronise. Chaque TV conserve son ordre d’applications et ses réglages matériels.")
        authStatus = label(if (runtime.authenticated) "Compte connecté sur cet appareil." else "Connectez le compte utilisé dans Jojo Home, distinct du compte du tableau de bord Supabase.")
        val email = input("E-mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = input("Mot de passe", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        lateinit var connect: Button
        fun refreshSignIn() {
            val show = !runtime.authenticated || reconnecting
            email.visibility = if (show) View.VISIBLE else View.GONE
            password.visibility = email.visibility; connect.visibility = email.visibility
        }
        connect = command("Se connecter et restaurer") {
            val account = email.text.toString(); val credential = password.text.toString()
            if (account.isBlank() || credential.isBlank()) { status.text = "Renseignez l'e-mail et le mot de passe."; return@command }
            password.text.clear()
            operation { runtime.connect(account, credential); reconnecting = false; refreshSignIn() }
        }
        label("Synchronisation sur cette TV", 22f)
        settingsToggle = syncToggle("Profils et paramètres", "Retrouver les profils et partager les réglages communs.") { enabled ->
            if (enabled) operation { runtime.restoreCurrentAccount() }
            else if (content.state.selected) AlertDialog.Builder(this)
                .setMessage("Désactiver les profils et paramètres arrête aussi la synchronisation de l’activité IPTV sur cette TV. La reprise de lecture conserve sa source.")
                .setNegativeButton("Annuler", null).setPositiveButton("Désactiver") { _, _ -> operation {
                    runtime.setContentSync(false); runtime.stop()
                } }.show()
            else operation { runtime.stop() }
        }
        status = label(runtime.message.value)
        val seed = command("Créer la première sauvegarde depuis la .150") { operation {
            showSeedReview(runtime.seedPreview())
        } }
        val links = command("Associer des profils locaux existants") { chooseProfileLink() }
        progressToggle = syncToggle("Reprise de lecture", "Partager les positions des vidéos, films et épisodes du profil associé.") { enabled ->
            progressOperation { runtime.setProgressSync(enabled) }
        }
        progressStatus = label(progressMessage())
        contentToggle = syncToggle("Activité IPTV", "Partager favoris, historique, films, séries et programmations.") { enabled ->
            operation(contentStatus, { recoveryFeedback() ?: content.message.value }) { runtime.setContentSync(enabled) }
        }
        contentStatus = label(content.message.value)
        label("Les données déjà présentes dans Supabase sont récupérées automatiquement. L’ancien historique local ne sera publié que si vous le choisissez. Seule la TV créatrice exécute un enregistrement.")
        val mainRows = rows
        val advancedRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val advanced = command("Options avancées") {
            advancedRows.visibility = if (advancedRows.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        mainRows.addView(advancedRows)
        rows = advancedRows
        command("Réessayer la récupération du compte") { operation { runtime.restoreCurrentAccount() } }
        command("Se reconnecter au compte") { reconnecting = true; refreshSignIn(); email.requestFocus() }
        val approve = command("Autoriser une TV par code (anciennes versions)") { choosePairing() }
        val conflicts = command("Examiner les conflits de paramètres") { chooseConflict() }
        command("Synchroniser l’activité IPTV maintenant") { contentOperation { content.sync() } }
        command("Examiner puis publier l’activité locale existante") { contentOperation {
            val preview = content.seedPreview()
            AlertDialog.Builder(this).setTitle("Vérifier la publication IPTV").setMessage(preview.inventory)
                .setNegativeButton("Annuler", null).setPositiveButton("Publier") { _, _ -> contentOperation { content.publishExisting(preview) } }.show()
        } }
        command("Examiner les conflits IPTV") { chooseContentConflict() }
        rows = mainRows
        label("Les mots de passe des sources, les fichiers personnels et les autorisations Android ne font pas partie de cette restauration.")
        jobs.launch { runtime.message.collect { if (busyStatus !== status) status.text = it } }
        jobs.launch { content.message.collect { if (busyStatus !== contentStatus) contentStatus.text = it } }
        jobs.launch { runtime.state.collect { state ->
            val signed = runtime.authenticated
            refreshSignIn()
            authStatus.text = if (signed) "Compte connecté sur cet appareil." else "Compte non connecté."
            seed.visibility = if (signed && state.selected && state.backupKnownMissing && !state.initialized && state.seed == null && runtime.canSeed) View.VISIBLE else View.GONE
            links.visibility = if (state.issue == "ACCOUNT_PROFILE_LINK_REQUIRED") View.VISIBLE else View.GONE
            conflicts.visibility = if (state.journal.conflicts.isNotEmpty()) View.VISIBLE else View.GONE
            approve.visibility = if (signed && progress.store().settings.value.scope != null) View.VISIBLE else View.GONE
            advanced.visibility = if (signed) View.VISIBLE else View.GONE
            if (!signed) advancedRows.visibility = View.GONE
            refreshToggles()
            if (state.issue == "ACCOUNT_LOCAL_STORAGE_UNREADABLE") status.text = "Le stockage des paramètres est illisible. Les données ont été conservées ; aucune restauration n'est lancée."
        } }
        jobs.launch { progress.store().settings.collect {
            refreshToggles()
            approve.visibility = if (runtime.authenticated && it.scope != null) View.VISIBLE else View.GONE
            if (busyStatus !== progressStatus) progressStatus.text = progressMessage()
        } }
        refreshToggles()
        (if (runtime.authenticated) settingsToggle.row else email).requestFocus()
    }

    private fun contentOperation(block: suspend () -> Unit) = operation(contentStatus, { content.message.value }, block)
    private fun recoveryFeedback() = runtime.message.value.takeIf { runtime.state.value.issue?.startsWith("ACCOUNT_RECOVERY") == true }
    private fun progressOperation(block: suspend () -> Unit) = operation(progressStatus, { recoveryFeedback() ?: progressMessage() }, block)
    private fun progressMessage() = if (progress.store().settings.value.mode == PilotMode.SUPABASE_PROGRESS)
        "Activée : Supabase fournit les positions du profil associé." else "Désactivée : la reprise conserve la source NAS."

    private suspend fun ensureSettingsRestored() {
        require(runtime.authenticated) { "ACCOUNT_SIGN_IN_REQUIRED" }
        if (!runtime.state.value.selected || !runtime.state.value.restored) runtime.restoreCurrentAccount()
        require(runtime.state.value.restored && runtime.state.value.initialized) { "ACCOUNT_RESTORE_REQUIRED" }
        require(runtime.state.value.issue != "ACCOUNT_PROFILE_LINK_REQUIRED") { "ACCOUNT_PROFILE_LINK_REQUIRED" }
    }

    private fun refreshToggles() {
        if (!::settingsToggle.isInitialized || !::progressToggle.isInitialized || !::contentToggle.isInitialized) return
        listOf(settingsToggle to runtime.state.value.selected,
            progressToggle to (progress.store().settings.value.mode == PilotMode.SUPABASE_PROGRESS),
            contentToggle to content.state.selected).forEach { (toggle, checked) ->
            toggle.control.isChecked = checked
            // Keep D-pad focus on the row while its operation is running; clicks are guarded.
            toggle.row.isEnabled = runtime.authenticated
            toggle.row.alpha = if (runtime.authenticated) 1f else .5f
        }
    }
    private fun chooseContentConflict() {
        val ids = content.state.journal.conflicts.toList()
        if (ids.isEmpty()) { contentStatus.text = "Aucun conflit IPTV."; return }
        AlertDialog.Builder(this).setTitle("Conflits IPTV")
            .setItems(ids.map { it.split('/')[1] }.toTypedArray()) { _, selected ->
                AlertDialog.Builder(this).setMessage("Garder la version Supabase ou publier explicitement la modification locale ?")
                    .setNegativeButton("Annuler", null)
                    .setNeutralButton("Garder Supabase") { _, _ -> contentOperation { content.resolve(ids[selected], false) } }
                    .setPositiveButton("Publier ma version") { _, _ -> contentOperation { content.resolve(ids[selected], true) } }.show()
            }.show()
    }
    private fun choosePairing() = contentOperation {
        ensureSettingsRestored()
        val requests = content.pendingPairings()
        if (requests.isEmpty()) { contentStatus.text = "Aucune demande d’association en attente."; return@contentOperation }
        fun showCode(request: AccountPairRequest) {
            val code = EditText(this).apply {
                hint = "Code à 8 chiffres affiché sur le nouvel appareil"; isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            }
            AlertDialog.Builder(this).setTitle("Vérifier le destinataire").setView(code)
                .setMessage("Saisissez le code affiché sur le nouvel appareil. Ne validez pas une demande inattendue.")
                .setNegativeButton("Annuler", null).setPositiveButton("Autoriser") { _, _ ->
                    val typed = code.text.toString(); code.text.clear()
                    contentOperation { content.approvePairing(request, typed) }
                }.show()
        }
        if (requests.size == 1) showCode(requests.single()) else AlertDialog.Builder(this).setTitle("Appareil à autoriser")
            .setItems(requests.mapIndexed { index, _ -> "Nouvel appareil ${index + 1}" }.toTypedArray()) { _, index ->
                showCode(requests[index])
            }.show()
    }

    private fun showSeedReview(preview: AccountSeedPreview) {
        val inventory = TextView(this).apply {
            text = preview.review.inventory(); textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply {
            id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setBackgroundColor(Color.rgb(16, 23, 34)); addView(inventory)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Vérifier la première sauvegarde")
            .setView(scroll).setNegativeButton("Annuler", null)
            .setPositiveButton("Sauvegarder") { _, _ -> operation { runtime.seedReferenceDevice(preview) } }.create()
        dialog.setOnShowListener {
            scroll.layoutParams.height = (resources.displayMetrics.heightPixels * .55f).toInt(); scroll.requestLayout()
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            listOf(cancel, save).forEach { button ->
                button.isAllCaps = false; button.textSize = 18f; commandFocus(button, false)
                button.setOnFocusChangeListener { _, focused -> commandFocus(button, focused) }
                button.nextFocusUpId = scroll.id
            }
            scroll.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (scroll.canScrollVertically(1)) scroll.smoothScrollBy(0, dp(140)) else save.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (scroll.canScrollVertically(-1)) scroll.smoothScrollBy(0, -dp(140)) else cancel.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { cancel.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { save.requestFocus(); true }
                    else -> false
                }
            }
            scroll.requestFocus()
        }
        dialog.show()
    }

    private fun chooseProfileLink() = operation {
        val (cloud, locals) = withContext(Dispatchers.IO) { runtime.profileLinks() }
        if (cloud.isEmpty()) { status.text = "Aucun profil à associer."; return@operation }
        AlertDialog.Builder(this).setTitle("Profil sauvegardé")
            .setItems(cloud.map { "${if (it.key == "profiles.iptv") "IPTV" else "Application"} · ${it.value.asJsonObject["name"].asString}" }.toTypedArray()) { _, index ->
                val row = cloud[index]
                val options = locals.filter { row.key == "profiles.${it.domain}" && (it.domain != "home" || row.value.asJsonObject["primary"].asBoolean == (it.id == "1")) }
                if (options.isEmpty()) { status.text = "Aucun profil local compatible. Les profils présents sont conservés."; return@setItems }
                AlertDialog.Builder(this).setTitle("Associer au profil local")
                    .setItems(options.map { it.value.asJsonObject["name"].asString }.toTypedArray()) { _, selected ->
                        AlertDialog.Builder(this).setMessage("Associer ces deux profils et restaurer les préférences sauvegardées, sans importer leur historique ?")
                            .setNegativeButton("Annuler", null).setPositiveButton("Associer") { _, _ -> operation { runtime.linkProfile(row, options[selected]) } }.show()
                    }.show()
            }.show()
    }

    private fun chooseConflict() {
        val state = runtime.state.value
        val keys = state.journal.conflicts.keys.toList()
        if (keys.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Conflits · aucune valeur écrasée automatiquement")
            .setItems(keys.map { state.journal.desired[it]?.key ?: "Paramètre" }.toTypedArray()) { _, index ->
                val key = keys[index]
                AlertDialog.Builder(this).setTitle(state.journal.desired[key]?.key ?: "Paramètre")
                    .setMessage("Conserver la valeur actuelle de Supabase, ou publier explicitement la modification de cette TV ?")
                    .setNegativeButton("Annuler", null)
                    .setNeutralButton("Garder Supabase") { _, _ -> operation { runtime.resolveConflict(key, false) } }
                    .setPositiveButton("Publier ma valeur") { _, _ -> operation { runtime.resolveConflict(key, true) } }.show()
            }.show()
    }
    private fun operation(target: TextView = status, resultMessage: () -> String = { runtime.message.value }, block: suspend () -> Unit) {
        if (busyStatus != null) return
        busyStatus = target; commands.filterIsInstance<Button>().forEach { it.isEnabled = false }; target.text = "Opération en cours…"
        jobs.launch {
            try { block(); if (target.text.toString() == "Opération en cours…") target.text = resultMessage() }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                target.text = when (failure.message) {
                    "ACCOUNT_PREVIOUS_OPERATIONS_UNSENT" -> "Des modifications de ce compte restent à envoyer ou à résoudre avant de changer de compte."
                    "ACCOUNT_CONTENT_STOP_FIRST" -> "Arrêtez d’abord la synchronisation de l’activité IPTV."
                    "ACCOUNT_RESTORE_REQUIRED" -> "Restaurez d’abord les profils et paramètres du compte. La première sauvegarde doit exister sur la TV de référence."
                    "ACCOUNT_SIGN_IN_REQUIRED" -> "Connectez d’abord votre compte Supabase sur cette TV."
                    "ACCOUNT_PROFILE_LINK_REQUIRED" -> "Sélectionnez « Associer des profils locaux existants », puis activez à nouveau l’interrupteur."
                    "ACCOUNT_CONTENT_KEY_REQUIRED" -> "L’association et la clé de progression de cet appareil doivent être vérifiées avant la synchronisation IPTV."
                    "SUPABASE_HTTP_404" -> "Cette fonction nécessite les nouvelles migrations approuvées dans le projet Supabase. Aucune activation n’a été effectuée."
                    "ACCOUNT_CONTENT_KEY_CHANGED" -> "La clé a changé. Les anciens échanges restent conservés ; une revue de la clé est nécessaire."
                    "ACCOUNT_PAIR_CODE_MISMATCH" -> "Le code ne correspond pas à cet appareil. Aucune clé n’a été transmise."
                    "ACCOUNT_PAIR_RESTART_REQUIRED" -> "Relancez la demande d’association sur ce nouvel appareil. La clé privée temporaire n’est pas conservée après fermeture."
                    "ACCOUNT_LOCAL_STORAGE_UNREADABLE", "ACCOUNT_LOCAL_KEY_LOST" -> "Le stockage local des paramètres est illisible. Les données ont été conservées."
                    "ACCOUNT_LOCAL_STORAGE_FULL", "ACCOUNT_OUTBOX_FULL" -> "La file des paramètres est pleine. Ses opérations sont conservées ; synchronisez-les avant de poursuivre."
                    "ACCOUNT_SEED_REFERENCE_REQUIRED" -> "La première sauvegarde doit être créée sur la TV .150 après vérification de l'absence de sauvegarde."
                    "ACCOUNT_SEED_PREVIEW_CHANGED" -> if (target === contentStatus)
                        "L’activité IPTV ou la connexion a changé. Aucune publication n’a été lancée. Ouvrez à nouveau l’inventaire avant de confirmer."
                        else "Les réglages, les profils ou la connexion ont changé. Aucune nouvelle sauvegarde n'a été lancée. Ouvrez à nouveau l'inventaire avant de confirmer."
                    "ACCOUNT_PROFILE_METADATA_INVALID" -> "Un profil possède un nom ou une couleur incompatible. Corrigez-le avant la première sauvegarde."
                    else -> if (failure is javax.crypto.AEADBadTagException)
                        "La clé ne correspond pas aux échanges du compte. Vérifiez-la depuis une TV déjà associée ; aucune nouvelle sélection de source n’a été enregistrée."
                        else pilotSignInFailureMessage(failure)
                }
            } finally { busyStatus = null; commands.forEach { it.isEnabled = true }; refreshToggles() }
        }
    }
    private fun label(value: String, size: Float = 18f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(10), 0, dp(10)); rows.addView(this)
    }
    private fun input(hintText: String, type: Int) = EditText(this).apply {
        id = View.generateViewId(); hint = hintText; inputType = type; isSingleLine = true
        setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        rows.addView(this, LinearLayout.LayoutParams(-1, dp(60)))
    }
    private fun command(title: String, action: () -> Unit) = Button(this).apply {
        id = View.generateViewId(); text = title; isAllCaps = false; textSize = 18f
        commandFocus(this, false); setOnFocusChangeListener { _, selected -> commandFocus(this, selected) }
        setOnClickListener { action() }; commands += this
        rows.addView(this, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
    }
    private fun commandFocus(button: Button, selected: Boolean) {
        button.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (selected) accent else Color.rgb(35, 47, 65)) }
        button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
    }
    private fun syncToggle(title: String, description: String, changed: (Boolean) -> Unit): SyncToggle {
        val control = Switch(this).apply {
            isFocusable = false; isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val text = TextView(this).apply { this.text = "$title\n$description"; textSize = 18f }
        val row = LinearLayout(this).apply {
            id = View.generateViewId(); orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = dp(84)
            setPadding(dp(16), dp(12), dp(16), dp(12)); isFocusable = true; isClickable = true
            addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            addView(control, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
            setOnClickListener { if (busyStatus == null) changed(!control.isChecked) }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, node: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, node)
                    node.className = Switch::class.java.name; node.isCheckable = true; node.isChecked = control.isChecked
                    node.text = "$title. $description"
                }
            }
        }
        fun paint(focused: Boolean) {
            val foreground = if (focused) Color.BLACK else Color.WHITE
            row.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (focused) accent else Color.rgb(35, 47, 65)) }
            text.setTextColor(foreground)
            control.thumbTintList = android.content.res.ColorStateList.valueOf(foreground)
            control.trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(if (focused) Color.WHITE else Color.rgb(29, 115, 166), Color.rgb(100, 111, 125)))
        }
        paint(false); row.setOnFocusChangeListener { _, focused -> paint(focused) }
        commands += row
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return SyncToggle(row, control)
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    override fun onDestroy() { jobs.cancel(); super.onDestroy() }
}
