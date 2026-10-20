package ro.mdp.remix.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ro.mdp.remix.ui.uiText

@Composable
fun UpdateDialog(state: UpdateState, onDismiss: () -> Unit, onCheck: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit, onCancel: () -> Unit) {
    val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText("Actualizări MdP RemiX", "MdP RemiX updates")) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(when (state.phase) {
                    UpdatePhase.IDLE -> uiText("Verifică versiunea oficială de pe GitHub.", "Check the official version on GitHub.")
                    UpdatePhase.CHECKING -> uiText("Se verifică ultima versiune…", "Checking the latest version…")
                    UpdatePhase.CURRENT -> uiText("Aplicația este actualizată", "Your application is up to date")
                    UpdatePhase.AVAILABLE -> uiText("Este disponibilă o actualizare.", "An update is available.")
                    UpdatePhase.DOWNLOADING -> uiText("Se descarcă APK-ul oficial…", "Downloading the official APK…")
                    UpdatePhase.READY -> uiText("APK verificat. Android va solicita confirmarea instalării. Datele aplicației sunt păstrate.",
                        "APK verified. Android will ask you to confirm installation. Your app data is preserved.")
                    UpdatePhase.VERIFYING -> uiText("Se verifică din nou APK-ul și semnătura…", "Rechecking the APK and signing certificate…")
                    UpdatePhase.ERROR -> uiText("Actualizarea nu a putut fi finalizată.", "The update could not be completed.")
                }, modifier = Modifier.testTag("update_status"))
                state.offer?.let { offer ->
                    Text("${uiText("Versiune", "Version")} ${offer.versionName} (${offer.versionCode})", style = MaterialTheme.typography.titleMedium)
                    Text(offer.release.notes.ifBlank { uiText("Nu au fost publicate note pentru această versiune.", "No release notes were published for this version.") })
                }
                if (state.phase == UpdatePhase.DOWNLOADING) {
                    val progress = if (state.totalBytes > 0) (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                    Text("${(progress * 100).toInt()}%  •  ${state.downloadedBytes / 1024 / 1024} / ${state.totalBytes / 1024 / 1024} MB")
                } else if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error?.let { Text(updateErrorText(it), modifier = Modifier.testTag("update_error")) }
            }
        },
        confirmButton = {
            when {
                state.phase == UpdatePhase.READY -> Button(onClick = onInstall, modifier = Modifier.testTag("update_install")) { Text(uiText("Instalează actualizarea", "Install update")) }
                state.offer != null && !busy -> Button(onClick = onDownload, modifier = Modifier.testTag("update_download")) { Text(uiText("Descarcă și instalează", "Download and install")) }
                !busy -> TextButton(onClick = onCheck) { Text(uiText("Verifică actualizări", "Check for updates")) }
                state.phase == UpdatePhase.DOWNLOADING -> TextButton(onClick = onCancel) { Text(uiText("Anulează descărcarea", "Cancel download")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Închide", "Close")) } },
    )
}

@Composable
private fun updateErrorText(error: UpdateError): String = when (error) {
    UpdateError.NETWORK -> uiText("Conexiunea la GitHub a eșuat. Verifică internetul și încearcă din nou; serverul poate limita temporar cererile.", "Could not connect to GitHub. Check your connection and retry; the server may temporarily limit requests.")
    UpdateError.NO_RELEASE -> uiText("Nu există încă un release public oficial. Nu a fost descărcat niciun APK.", "There is no official public release yet. No APK was downloaded.")
    UpdateError.UNTRUSTED_RELEASE -> uiText("Release-ul sau manifestul său de integritate nu este valid. Instalarea este blocată.", "The release or its integrity manifest is invalid. Installation is blocked.")
    UpdateError.PACKAGE_MISMATCH -> uiText("Pachet incompatibil. Premium Test este separat de aplicația de producție și nu poate fi actualizat cu APK-ul de producție. Aplicația existentă rămâne intactă.", "Incompatible package. Premium Test is separate from production and cannot be updated with the production APK. Your existing app remains intact.")
    UpdateError.SIGNATURE_MISMATCH -> uiText("Semnătura release-ului diferă de cheia aplicației instalate. Actualizarea este blocată pentru a păstra aplicația și datele. Este necesar un APK semnat cu cheia originală.", "The release signer differs from the installed app's key. The update is blocked to preserve the app and data. An APK signed with the original key is required.")
    UpdateError.DOWNLOAD -> uiText("Descărcarea a eșuat. Verifică internetul și spațiul liber, apoi încearcă din nou.", "The download failed. Check your connection and free space, then retry.")
    UpdateError.INVALID_APK -> uiText("APK-ul este incomplet sau versiunea/pachetul nu corespunde release-ului. Nu se instalează.", "The APK is incomplete or its version/package does not match the release. Installation is blocked.")
    UpdateError.HASH_MISMATCH -> uiText("Verificarea SHA-256 a eșuat. Fișierul nu poate fi instalat; descarcă-l din nou.", "SHA-256 verification failed. The file cannot be installed; download it again.")
    UpdateError.INSTALLER -> uiText("Instalatorul Android nu a putut fi deschis. Încearcă din nou și confirmă instalarea pe dispozitiv.", "The Android installer could not be opened. Retry and confirm installation on your device.")
    UpdateError.UNKNOWN_SOURCES -> uiText("Permite instalarea din această aplicație în ecranul Android, apoi apasă din nou Instalează actualizarea.", "Allow installation from this app in Android settings, then tap Install update again.")
}
