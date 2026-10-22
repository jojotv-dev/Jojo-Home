package com.nuvio.tv.core.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FreeboxResumeDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELETE_FREEBOX_RESUME) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val contentId = intent.getStringExtra(EXTRA_CONTENT_ID).orEmpty().trim()
            val intentUri = intent.getStringExtra(EXTRA_INTENT_URI).orEmpty()
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().ifBlank { contentId }
            try {
                if (!isJojoHomeSender(appContext, intent)) {
                    throw SecurityException("Broadcast refusé : émetteur Jojo Home non vérifié.")
                }
                val path = validateContentId(contentId)
                // intentUri is deliberately read: it is the stable source identity
                // sent by Jojo Home, while contentId is the deletion key.
                if (intentUri.isBlank()) throw IllegalArgumentException("Reprise Freebox invalide : intentUri absent.")
                val entry = FreeboxFileEntry(
                    name = path.substringAfterLast('/').ifBlank { "Vidéo Freebox" },
                    path = path,
                    isDirectory = false
                )
                EntryPointAccessors.fromApplication(
                    appContext,
                    ReceiverDependencies::class.java
                ).deletionService().delete(entry).getOrThrow()
                sendResult(appContext, requestId, contentId, true, null)
            } catch (error: Exception) {
                Log.w(TAG, "Suppression Freebox demandée par Jojo Home échouée", error)
                sendResult(appContext, requestId, contentId, false, error.message ?: "Erreur de suppression Freebox.")
            } finally {
                pending.finish()
            }
        }
    }

    private fun sendResult(context: Context, requestId: String, contentId: String, success: Boolean, error: String?) {
        context.sendBroadcast(Intent(ACTION_FREEBOX_DELETE_RESULT).setPackage(JOJO_HOME_PACKAGE)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_CONTENT_ID, contentId)
            .putExtra(EXTRA_SUCCESS, success)
            .apply { if (!success) putExtra(EXTRA_ERROR_MESSAGE, error) })
    }

    companion object {
        const val ACTION_DELETE_FREEBOX_RESUME = "com.jojo.tv.action.DELETE_FREEBOX_RESUME"
        const val ACTION_FREEBOX_DELETE_RESULT = "com.jojo.home.action.FREEBOX_DELETE_RESULT"
        const val EXTRA_CONTENT_ID = "contentId"
        const val EXTRA_INTENT_URI = "intentUri"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"
        const val JOJO_HOME_PACKAGE = "com.jojotv.home"
        private const val TAG = "FreeboxDeleteReceiver"

        internal fun validateContentId(contentId: String): String {
            require(contentId.startsWith("freebox:", ignoreCase = true)) { "Identifiant Freebox absent ou invalide." }
            return freeboxPathFromContentId(contentId).also {
                require(it.isNotBlank()) { "Chemin Freebox absent ou invalide." }
            }
        }
    }

    private fun isJojoHomeSender(context: Context, intent: Intent): Boolean {
        val targetPackage = intent.component?.packageName ?: intent.`package`
        if (targetPackage != context.packageName) return false
        // Android only exposes the sending UID from API 34. On older Android
        // TV versions, the package-scoped explicit intent is the available
        // platform guarantee; Jojo Home uses setPackage("com.jojo.tv").
        if (Build.VERSION.SDK_INT < 34) return true
        val senderUid = getSentFromUid()
        return senderUid >= 0 && context.packageManager.getPackagesForUid(senderUid)
            ?.contains(JOJO_HOME_PACKAGE) == true
    }

    @EntryPoint
    @dagger.hilt.InstallIn(SingletonComponent::class)
    interface ReceiverDependencies { fun deletionService(): FreeboxResumeDeletionService }
}
