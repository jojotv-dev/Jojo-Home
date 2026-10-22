package com.nuvio.tv.ui.screens.freebox

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nuvio.tv.data.freebox.FreeboxOsClient
import com.nuvio.tv.data.local.FreeboxSettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Starts and completes the Freebox OS authorization owned by Jojo Home. */
@AndroidEntryPoint
class FreeboxAuthorizationActivity : ComponentActivity() {
    @Inject lateinit var freeboxOsClient: FreeboxOsClient
    @Inject lateinit var freeboxSettingsDataStore: FreeboxSettingsDataStore

    private lateinit var status: TextView
    private lateinit var authorize: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = normalizeAddress(intent.getStringExtra(EXTRA_ADDRESS).orEmpty())
        if (intent.getBooleanExtra(EXTRA_AUTO_REQUEST, false)) {
            requestAuthorization(address, closeWhenFinished = true)
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 36, 48, 36)
            setBackgroundColor(Color.rgb(16, 25, 43))
        }
        val title = text("Autoriser Jojo Home sur la Freebox", 24f, Color.WHITE)
        layout.addView(title, LinearLayout.LayoutParams(-1, 64))
        val description = text(
            "Appuyez sur le bouton, puis validez la demande qui apparaît sur l'écran de la Freebox.",
            17f,
            Color.rgb(180, 195, 230)
        )
        layout.addView(description, LinearLayout.LayoutParams(-1, 86))
        status = text("Prêt à demander l'autorisation pour $address", 17f, Color.WHITE)
        layout.addView(status, LinearLayout.LayoutParams(-1, 70))
        authorize = action("Demander l'autorisation")
        layout.addView(authorize, LinearLayout.LayoutParams(360, 64))
        val back = action("Fermer")
        layout.addView(back, LinearLayout.LayoutParams(360, 64).apply { topMargin = 14 })
        back.setOnClickListener { finish() }
        authorize.setOnClickListener { requestAuthorization(address) }
        authorize.requestFocus()
        setContentView(layout)
    }

    private fun requestAuthorization(address: String, closeWhenFinished: Boolean = false) {
        if (address.isBlank()) {
            if (closeWhenFinished) {
                Toast.makeText(this, "Adresse Freebox introuvable. Configurez d'abord un lecteur SMB.", Toast.LENGTH_LONG).show()
                finish()
            } else {
                status.text = "Adresse Freebox introuvable. Configurez d'abord un lecteur SMB."
            }
            return
        }
        if (!closeWhenFinished) {
            authorize.isEnabled = false
            authorize.text = "Demande envoyée…"
            status.text = "Validez maintenant la demande sur la Freebox."
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var settings = freeboxSettingsDataStore.settings.first()
                    if (settings.address != address || settings.name.isBlank()) {
                        settings = settings.copy(
                            name = settings.name.ifBlank { "Freebox" },
                            address = address
                        )
                        freeboxSettingsDataStore.save(settings)
                    }
                    val update = freeboxOsClient.requestAuthorization(settings).getOrThrow()
                    freeboxSettingsDataStore.saveAuthorization(update)
                    if (closeWhenFinished) return@runCatching "requested"
                    var current = "pending"
                    for (attempt in 0 until 120) {
                        delay(1_000)
                        current = freeboxOsClient.refreshAuthorizationStatus(
                            settings.copy(authTrackId = update.trackId)
                        ).getOrThrow()
                        if (current != "pending") break
                    }
                    current
                }
            }
            if (result.isFailure) {
                val message = "Échec : ${result.exceptionOrNull()?.message ?: "demande refusée"}"
                if (closeWhenFinished) {
                    Toast.makeText(this@FreeboxAuthorizationActivity, message, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    status.text = message
                    authorize.isEnabled = true
                    authorize.text = "Réessayer"
                }
            } else if (result.getOrNull() == "requested") {
                Toast.makeText(this@FreeboxAuthorizationActivity, "Demande envoyée. Validez maintenant sur la Freebox.", Toast.LENGTH_LONG).show()
                finish()
            } else if (result.getOrNull() == "granted") {
                freeboxSettingsDataStore.saveAuthStatus("granted")
                if (closeWhenFinished) {
                    Toast.makeText(this@FreeboxAuthorizationActivity, "Demande envoyée. Validez maintenant sur la Freebox.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    status.text = "Autorisation accordée. Jojo Home peut maintenant utiliser la Freebox."
                    authorize.text = "Autorisation enregistrée"
                }
            } else {
                if (closeWhenFinished) {
                    Toast.makeText(this@FreeboxAuthorizationActivity, "La demande n'a pas été validée sur la Freebox.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    status.text = "Aucune validation reçue. Vous pourrez réessayer."
                    authorize.isEnabled = true
                    authorize.text = "Demander l'autorisation"
                }
            }
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun action(label: String) = text(label, 18f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(67, 101, 160))
    }

    private fun normalizeAddress(value: String): String {
        var address = value.trim().removePrefix("smb://").trimEnd('/')
        val slash = address.indexOf('/')
        if (slash >= 0) address = address.substring(0, slash)
        return address
    }

    companion object {
        const val EXTRA_ADDRESS = "freebox_authorization_address"
        const val EXTRA_AUTO_REQUEST = "freebox_auto_request"
    }
}
