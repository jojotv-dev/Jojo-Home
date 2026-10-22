package com.jojotv.home.progresspilot

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jojotv.home.BuildConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Explicit source selection. NAS and the diagnostic mirror remain the default/legacy paths. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupabaseProgressPilotActivity : Activity() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var services: ProgressPilotEntryPoint
    private lateinit var options: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectionFeedback: TextView
    private val commands = mutableListOf<Button>()
    private var busy = false
    private var keyReceiver: PilotKeyImportSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.JOJO_PROGRESS_PILOT_COMPILED) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val deviceId = try {
            services = EntryPointAccessors.fromApplication(applicationContext, ProgressPilotEntryPoint::class.java)
            services.runtime().start()
            services.store().ensureDeviceId()
        } catch (_: Exception) {
            setContentView(TextView(this).apply {
                text = "Stockage local du pilote indisponible. Le fonctionnement NAS reste actif."
                textSize = 20f
                setPadding(dp(32), dp(32), dp(32), dp(32))
            })
            return
        }
        val saved = services.store().settings.value
        var displayedSession = saved.session

        options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.rgb(16, 23, 34))
        }
        setContentView(ScrollView(this).apply { addView(options) })
        label("Pilote Supabase", 26f)
        label("NAS seul par défaut. Le miroir de diagnostic ne fournit aucune reprise. Le mode Supabase est une source distincte, à activer explicitement.")
        label("Le miroir s'arrête au redémarrage. Une source Supabase explicitement choisie conserve son cache et sa sélection.")
        label("Cet appareil : $deviceId")
        label("Associe un profil et deux ou trois appareils. Garde les UUID A/B existants et la même clé locale. Renseigne C uniquement sur le troisième appareil.")
        status = label("NAS_ONLY · aucun checkpoint envoyé")

        val endpoint = input("Projet de test · https://…supabase.co", saved.endpoint?.url.orEmpty())
        val publishable = input("Clé publishable · sb_publishable_…", saved.endpoint?.publishableKey.orEmpty())
        val email = input("E-mail du compte Supabase de test", "", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = input("Mot de passe Supabase de test", "", secret = true)
        command("Connecter le compte Supabase") {
            val address = PilotEndpoint(endpoint.text.toString().trim(), publishable.text.toString().trim())
            val account = email.text.toString()
            val credential = password.text.toString()
            val inputIssue = pilotSignInInputMessage(account, credential)
            if (inputIssue != null) {
                showFeedback(connectionFeedback, inputIssue)
                return@command
            }
            password.text.clear()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(password.windowToken, 0)
            operation(connectionFeedback, "Connexion réussie. Session enregistrée sur cet appareil.") {
                services.runtime().signIn(address, account, credential)
            }
        }
        connectionFeedback = label(connectionDescription(saved.session))

        val profile = input("Numéro du profil local", (saved.scope?.localProfileId ?: services.profiles().activeProfileId.value).toString(),
            type = InputType.TYPE_CLASS_NUMBER)
        val binding = input("UUID d'association du profil · identique sur tous les appareils",
            saved.scope?.profileBindingId ?: UUID.randomUUID().toString())
        val deviceA = input("UUID de l'appareil A", saved.scope?.deviceA ?: deviceId)
        val deviceB = input("UUID de l'appareil B", saved.scope?.deviceB.orEmpty())
        val deviceC = input("UUID de l'appareil C · facultatif, sur C seulement", saved.scope?.deviceC.orEmpty())
        val hmacKey = input("Clé locale HMAC · 256 bits en Base64", saved.hmacKeyBase64, secret = true)
        command("Générer une clé locale pour les appareils") {
            if (services.store().settings.value.everActivated) { status.text = "L'association de ce pilote est figée."; return@command }
            val key = services.store().generateLinkKey()
            hmacKey.setText(key)
            AlertDialog.Builder(this).setTitle("Clé locale de liaison")
                .setMessage("Recopie cette clé sur les autres appareils. Pour ajouter C, utilise la clé déjà vérifiée sur A/B. Elle reste hors de Supabase et des rapports.\n\n$key")
                .setPositiveButton("Fermer", null).create().also {
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    it.show()
                }
        }
        label("Vérification HMAC : utilise un défi commun et le code de référence du premier appareil. Sans vérification, aucune activation.")
        val keyChallenge = input("Défi de vérification HMAC · UUID commun", "")
        val keyReference = input("Code de référence HMAC · 64 caractères hexadécimaux", "")
        val keyFeedback = label("Clé valide ne signifie pas correspondance vérifiée. La vérification est requise avant activation.")
        label("Utilise le code de référence d’un appareil déjà vérifié. Recopier son propre code ne vérifie pas la correspondance. Sur C, cette vérification est requise dès l’enregistrement.")
        command("Afficher le code de référence local") {
            val nonce = keyChallenge.text.toString().trim()
            operation(keyFeedback, "Code local affiché. Compare-le avec l’autre appareil.",
                runningMessage = "Calcul local…", failureMessage = "Vérifie le défi, NAS_ONLY et les identités associées.") {
                services.runtime().keyCheckScope()
                val code = services.store().localPairingCode(services.store().settings.value, nonce)
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@SupabaseProgressPilotActivity).setTitle("Code de référence HMAC")
                        .setMessage("Défi public : $nonce\n\nCode de référence :\n$code\n\nCe code ne contient pas la clé secrète.")
                        .setPositiveButton("Fermer", null).create().also {
                            it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show()
                        }
                }
            }
        }
        command("Vérifier la clé enregistrée") {
            val nonce = keyChallenge.text.toString().trim()
            val reference = keyReference.text.toString().trim()
            operation(keyFeedback, "Clé conforme au code de référence. Vérification enregistrée ; NAS_ONLY conservé.",
                runningMessage = "Vérification locale…", failureMessage = "Vérification refusée : vérifie le défi, le code et les identités.") {
                services.runtime().confirmKey(nonce, reference)
            }
        }
        command("Vérifier la clé saisie avant enregistrement") {
            val candidate = hmacKey.text.toString().trim()
            val nonce = keyChallenge.text.toString().trim()
            val reference = keyReference.text.toString().trim()
            operation(keyFeedback, "La clé saisie correspond à la référence. Aucune clé enregistrée par ce contrôle.",
                runningMessage = "Vérification locale de la saisie…", failureMessage = "Vérifie la clé, le défi et le code de référence.") {
                services.runtime().keyCheckScope()
                services.store().candidateKeyMatches(services.store().settings.value, candidate, nonce, reference)
            }
        }
        val receiverToken = label("Réception chiffrée : non préparée")
        val encryptedKey = input("Import chiffré temporaire · texte public", "")
        command("Préparer la réception chiffrée de la clé conservée") {
            operation(keyFeedback, "Réception prête pendant cinq minutes. Aucun secret envoyé ou enregistré.",
                runningMessage = "Préparation locale…", failureMessage = "Réception refusée : vérifie NAS_ONLY et les identités.") {
                val bound = services.runtime().keyImportScope()
                val receiver = PilotKeyImportSession(bound)
                val token = receiver.publicToken { Base64.encodeToString(it, Base64.NO_WRAP) }
                withContext(Dispatchers.Main) {
                    keyReceiver = receiver
                    encryptedKey.text.clear()
                    receiverToken.text = "Réception chiffrée : $token"
                }
            }
        }
        command("Recevoir la clé chiffrée en mémoire") {
            if (busy) return@command
            val receiver = keyReceiver
            val ciphertext = encryptedKey.text.toString().trim()
            keyReceiver = null
            operation(keyFeedback, "Clé reçue en mémoire. La correction exige encore une validation explicite.",
                runningMessage = "Réception locale…", failureMessage = "Import refusé : prépare une nouvelle réception et vérifie le texte chiffré.") {
                require(ciphertext.length <= 512)
                val bound = services.runtime().keyImportScope()
                val bytes = requireNotNull(receiver).unwrap(Base64.decode(ciphertext, Base64.DEFAULT), bound)
                try {
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) {
                        hmacKey.setText(encoded)
                        encryptedKey.text.clear()
                        receiverToken.text = "Réception chiffrée : utilisée ; prépare une nouvelle réception si nécessaire"
                    }
                } finally { bytes.fill(0) }
            }
        }
        val repairConsent = CheckBox(this).apply {
            text = "J’autorise la correction locale de la clé et l’isolation des anciennes empreintes techniques (file vide, NAS_ONLY)."
            setTextColor(Color.WHITE)
            isSaveEnabled = false
        }
        options.addView(repairConsent)
        val profileRepairConsent = CheckBox(this).apply {
            text = "J’autorise aussi la réassociation technique du même profil principal si son identifiant d’instance a changé (file vide, NAS_ONLY)."
            setTextColor(Color.WHITE)
            isSaveEnabled = false
        }
        options.addView(profileRepairConsent)
        command("Corriger la clé de référence · NAS_ONLY") {
            if (!repairConsent.isChecked) { showFeedback(keyFeedback, "Confirme explicitement la correction locale avant de continuer."); return@command }
            val candidate = hmacKey.text.toString().trim()
            val nonce = keyChallenge.text.toString().trim()
            val reference = keyReference.text.toString().trim()
            val recoverProfileInstance = profileRepairConsent.isChecked
            operation(keyFeedback, "Clé corrigée et conforme au code de référence. NAS_ONLY conservé ; aucune reprise importée.",
                runningMessage = "Correction locale de la clé…", failureMessage = "Correction refusée : vérifie la file vide, les identités et la référence. Une réparation interrompue exige la même clé.") {
                services.runtime().repairKey(candidate, nonce, reference, recoverProfileInstance)
                withContext(Dispatchers.Main) { repairConsent.isChecked = false; profileRepairConsent.isChecked = false }
            }
        }
        val approved = CheckBox(this).apply {
            text = "Backend validé : migrations, tests RLS et sessions préassociées"
            setTextColor(Color.WHITE)
            isChecked = saved.backendApproved
        }
        options.addView(approved)
        command("Enregistrer l'association locale") {
            val selected = profile.text.toString().toIntOrNull()
            val profileBinding = binding.text.toString().trim()
            val first = deviceA.text.toString().trim()
            val second = deviceB.text.toString().trim()
            val third = deviceC.text.toString().trim().takeIf { it.isNotEmpty() }
            val key = hmacKey.text.toString().trim()
            val backendApproved = approved.isChecked
            val nonce = keyChallenge.text.toString().trim()
            val reference = keyReference.text.toString().trim()
            operation(keyFeedback, "Association locale enregistrée. NAS_ONLY conservé.",
                runningMessage = "Vérification de l'association locale…",
                failureMessage = "Association refusée : vérifie les UUID, le profil et le code de référence HMAC.") {
                services.runtime().awaitLocalIdentity()
                require(selected != null && services.profiles().profiles.value.any { it.id == selected })
                val instance = services.preferences().getOrCreatePilotProfileInstanceId(selected)
                services.store().configure(selected, instance, profileBinding, first, second, key, backendApproved, nonce, reference, third)
            }
        }
        command("Activer le miroir pour les futurs checkpoints") {
            val current = services.store().settings.value
            val bound = current.scope
            // Activation must use the configuration currently shown, never unsaved edits.
            val unchanged = bound != null && bound.localProfileId.toString() == profile.text.toString().trim() &&
                bound.profileBindingId == binding.text.toString().trim() && bound.deviceA == deviceA.text.toString().trim() &&
                bound.deviceB == deviceB.text.toString().trim() &&
                bound.deviceC == deviceC.text.toString().trim().takeIf { it.isNotEmpty() } &&
                current.hmacKeyBase64 == hmacKey.text.toString().trim() &&
                current.endpoint?.url == endpoint.text.toString().trim() &&
                current.endpoint?.publishableKey == publishable.text.toString().trim() && current.backendApproved == approved.isChecked
            if (!unchanged) { status.text = "Enregistre d'abord les paramètres affichés."; return@command }
            operation { services.runtime().activate() }
        }
        command("Désactiver · NAS seul") { operation { services.runtime().disable() } }
        label("Reprise Supabase : aucun import initial. Les positions NAS restent dans leur espace séparé.")
        val cloudEpoch = input("Génération commune de clé approuvée · UUID", saved.cloudKeyEpoch.orEmpty())
        val cloudConsent = CheckBox(this).apply {
            text = "La migration et l'espace cloud ont été approuvés et appliqués"
            setTextColor(Color.WHITE)
            options.addView(this)
        }
        command("Utiliser Supabase pour la progression") {
            if (!cloudConsent.isChecked) { status.text = "Valide d'abord la migration et l'espace cloud."; return@command }
            operation { services.runtime().activateCloud(cloudEpoch.text.toString().trim()) }
        }
        val cloudStatus = label("Source de reprise : NAS")
        command("Conflit · garder la progression Supabase") {
            chooseCloudConflict(false)
        }
        command("Conflit · envoyer ma position locale") {
            chooseCloudConflict(true)
        }
        command("Réessayer l'envoi technique") {
            operation { services.store().recordDelivery(null); services.runtime().scheduleFlush() }
        }
        command("Déconnecter le compte Supabase") { operation { services.runtime().signOut() } }
        label("Comparaison locale : utilise la clé enregistrée et les identifiants déjà capturés. NAS_ONLY est requis.")
        val challenge = input("Défi public de comparaison · identique sur A et B", UUID.randomUUID().toString())
            .apply { isSaveEnabled = false }
        val diagnosticFeedback = label("Aucun contrôle local effectué.")
        command("Afficher les codes de comparaison locale") {
            val nonce = challenge.text.toString().trim()
            operation(diagnosticFeedback, "Codes calculés localement. Aucun envoi demandé.",
                runningMessage = "Calcul local des codes de comparaison…",
                failureMessage = "Contrôle refusé : vérifie le défi commun, NAS_ONLY, le profil associé et la session locale.") {
                val expected = services.store().settings.value
                val bound = expected.validateLocalDiagnostic(PilotJournal())
                val profiles = services.profiles()
                require(profiles.activeProfileReady.value && profiles.activeProfileId.value == bound.localProfileId)
                val queue = services.preferences().pilotDeliveryQueue()
                require(queue.profileInstanceId(bound.localProfileId) == bound.localProfileInstanceId)
                val journal = queue.observe(bound.localProfileId).first()
                require(profiles.activeProfileReady.value && profiles.activeProfileId.value == bound.localProfileId &&
                    queue.profileInstanceId(bound.localProfileId) == bound.localProfileInstanceId)
                val report = services.store().localDiagnostic(expected, nonce, journal)
                withContext(Dispatchers.Main) {
                    val message = buildString {
                        append("Défi public : ").append(report.challenge)
                        append("\nProfil associé : ").append(report.profileBindingId)
                        append("\n\nCode de clé :\n").append(report.keyCode)
                        append("\n\nIdentifiants déjà capturés : ").append(report.contentCount)
                        report.contentCodes.forEachIndexed { index, code ->
                            append("\n\nCode de contenu ").append(index + 1).append(" :\n").append(code)
                        }
                        if (report.contentCount == 0) append("\nSeule la clé peut être comparée.")
                        if (report.contentCount > report.contentCodes.size)
                            append("\nAffichage limité : une absence de code commun ne permet pas de conclure.")
                        append("\n\nCompare d'abord le défi, le profil et le code de clé sur les deux TV.")
                        append("\nCes codes ne prouvent pas que le fichier physique est identique.")
                    }
                    AlertDialog.Builder(this@SupabaseProgressPilotActivity).setTitle("Comparaison locale")
                        .setMessage(message).setPositiveButton("Fermer", null).create().also {
                            it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            it.show()
                        }
                }
            }
        }
        command("Retour") { finish() }

        jobs.launch {
            services.store().settings.flatMapLatest { configured ->
                val localProfile = configured.scope?.localProfileId
                if (localProfile == null) flowOf(PilotJournal()) else services.preferences().observePilotDelivery(localProfile)
                    .catch { emit(PilotJournal(issue = "OUTBOX_UNREADABLE")) }
            }.catch { emit(PilotJournal(issue = "OUTBOX_UNREADABLE")) }.collect { journal ->
                val current = services.store().settings.value
                val description = "${current.mode} · ${journal.pending.size} opérations en attente\n" +
                    "Compte de test : ${current.session?.ownerId ?: "non connecté"}\n" +
                    "Session à préassocier : ${current.session?.sessionId ?: "non connecté"}\n" +
                    "Profil associé : ${current.scope?.localProfileId ?: "aucun"}\n" +
                    "Dernier accusé : ${current.lastAckMs.takeIf { it > 0 } ?: "aucun"}\n" +
                    "Diagnostic : ${journal.issue ?: current.lastIssue ?: "aucune erreur"}\n" +
                    "Vérification HMAC : ${if (current.keyRepairFromEpoch.isNotEmpty()) "réparation à terminer" else if (current.keyCheckEpoch.isNotEmpty() && current.keyCheckEpoch == current.scope?.epoch) "code de référence validé" else "requise avant activation"}\n" +
                    (if (current.mode == PilotMode.SUPABASE_PROGRESS) "Source Supabase sélectionnée. Aucun import NAS."
                     else "Aucun import initial. Aucune lecture de progression depuis Supabase.")
                withContext(Dispatchers.Main) {
                    status.text = description
                    if (displayedSession !== current.session) {
                        displayedSession = current.session
                        connectionFeedback.text = connectionDescription(current.session)
                    }
                }
            }
        }
        jobs.launch {
            services.runtime().cloudJournal().catch { emit(CloudProgressJournal(issue = "CLOUD_CACHE_UNREADABLE")) }
                .collect { cloud -> withContext(Dispatchers.Main) {
                    cloudStatus.text = if (services.store().settings.value.mode == PilotMode.SUPABASE_PROGRESS)
                        "Source de reprise : Supabase\n${cloud.pending.size} en attente · ${cloud.conflicts.size} conflits\n" +
                            "Dernier échange : ${services.store().settings.value.lastAckMs.takeIf { it > 0 } ?: "aucun"}\n" +
                            "État : ${cloud.issue ?: services.store().settings.value.lastIssue ?: "à jour"}"
                    else "Source de reprise : NAS"
                } }
        }
    }

    private fun operation(feedback: TextView? = null, success: String? = null,
                          runningMessage: String = "Connexion Supabase en cours…", failureMessage: String? = null,
                          block: suspend () -> Unit) {
        if (busy) return
        busy = true
        commands.forEach { it.isEnabled = false }
        if (feedback == null) status.text = "Traitement de la commande du pilote…"
        else showFeedback(feedback, runningMessage)
        jobs.launch {
            val failure = try { block(); null } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { error }
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                busy = false
                commands.forEach { it.isEnabled = true }
                val keyFailure = when (failure) {
                    is CloudConflictChanged -> "Le conflit a changé. Relis les deux positions avant de choisir."
                    is PilotKeyCheckBlocked -> failure.message
                    is PilotKeyMismatch -> "La clé ne correspond pas au code de référence de l’autre appareil. Activation bloquée."
                    is PilotKeyCheckRequired -> "Vérifie la correspondance de la clé avant d’activer le miroir."
                    else -> null
                }
                if (feedback != null) showFeedback(feedback,
                    if (failure == null) success ?: "Commande terminée." else keyFailure ?: failureMessage ?: pilotSignInFailureMessage(failure))
                else if (failure != null) status.text = keyFailure ?: if (failure is PilotHttpFailure) "Supabase HTTP ${failure.status}" else
                    "Commande refusée. Vérifie la session, l'association et la validation de la migration."
            }
        }
    }

    private fun chooseCloudConflict(useLocal: Boolean) {
        operation {
            val choice = services.runtime().firstCloudConflict() ?: error("Aucun conflit")
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@SupabaseProgressPilotActivity)
                    .setTitle(if (useLocal) "Envoyer ma position locale ?" else "Garder le point Supabase ?")
                    .setMessage(choice.description).setNegativeButton("Annuler", null)
                    .setPositiveButton("Confirmer") { _, _ -> operation { services.runtime().resolveCloudConflict(useLocal, choice) } }
                    .show()
            }
        }
    }

    private fun connectionDescription(session: PilotAuthSession?): String = if (session == null)
        "Compte Supabase : non connecté." else "Compte Supabase : session enregistrée sur cet appareil."

    private fun showFeedback(view: TextView, message: String) {
        view.text = message
        view.post { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false) }
    }

    private fun label(value: String, size: Float = 17f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, dp(8), 0, dp(8))
        options.addView(this)
    }

    private fun input(hint: String, value: String, secret: Boolean = false,
                      type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
        this.hint = hint
        isSaveEnabled = !secret
        if (!secret) contentDescription = hint
        setText(value)
        setSingleLine(true)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else type
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        options.addView(this, LinearLayout.LayoutParams(-1, dp(58)))
    }

    private fun command(title: String, click: () -> Unit) {
        val button = Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 18f
            setOnClickListener { click() }
            onFocusChangeListener = android.view.View.OnFocusChangeListener { view, focused ->
                (view as Button).setTextColor(if (focused) Color.rgb(16, 23, 34) else Color.WHITE)
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (focused) Color.rgb(210, 230, 250) else Color.rgb(39, 50, 63))
                }
            }
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.rgb(39, 50, 63)) }
        }
        commands += button
        options.addView(button, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() { keyReceiver = null; jobs.cancel(); super.onDestroy() }
}
