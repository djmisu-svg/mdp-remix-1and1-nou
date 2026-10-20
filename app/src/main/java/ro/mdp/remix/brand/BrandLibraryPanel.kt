package ro.mdp.remix.brand

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.mdp.remix.ui.Background
import ro.mdp.remix.ui.Hairline
import ro.mdp.remix.ui.Muted
import ro.mdp.remix.ui.uiText
import ro.mdp.remix.ui.panelColor

@Composable
fun BrandLibraryPanel(
    viewModel: BrandLibraryViewModel,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onSync: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(modifier.fillMaxWidth().testTag("brand_library_settings"), color = panelColor(),
        shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(uiText("Biblioteca MdP RemiX", "MdP RemiX library"), style = MaterialTheme.typography.titleLarge)
            Text(uiText("Playlist-uri și linkuri YouTube publicate de brand, comune tuturor. Muzica locală și fundalul rămân private. Biblioteca personală este păstrată.",
                "Playlists and YouTube links published by the brand for everyone. Local music and backgrounds stay private. Your personal library is preserved."),
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("Sincronizare automată", "Automatic synchronization"), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabled, modifier = Modifier.testTag("brand_sync_enabled"))
            }
            if (state.syncing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(uiText("Se verifică biblioteca oficială…", "Checking the official library…"), color = Muted)
            } else {
                val status = when (state.status) {
                    BrandLibraryStatus.IDLE -> uiText("Disponibilă și offline după prima sincronizare.", "Available offline after the first sync.")
                    BrandLibraryStatus.UPDATED -> uiText("Biblioteca brandului a fost actualizată.", "The brand library was updated.")
                    BrandLibraryStatus.CURRENT -> uiText("Biblioteca este actualizată.", "Your library is up to date.")
                    BrandLibraryStatus.NOT_PUBLISHED -> uiText("Brandul nu a publicat încă biblioteca online.", "The brand has not published its online library yet.")
                    BrandLibraryStatus.ERROR -> uiText("Sincronizarea nu a reușit. Verifică conexiunea și încearcă din nou. Biblioteca salvată a fost păstrată.",
                        "Synchronization failed. Check your connection and try again. Your saved library was preserved.")
                }
                Text(status, color = Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("brand_sync_status"))
            }
            Button(onClick = onSync, enabled = !state.syncing, modifier = Modifier.fillMaxWidth().testTag("brand_sync_now")) {
                Text(uiText("Sincronizează acum", "Sync now"))
            }
            OutlinedButton(onClick = onExport, enabled = !state.syncing,
                modifier = Modifier.fillMaxWidth().testTag("brand_export")) {
                Text(uiText("Exportă biblioteca personală pentru publicare", "Export personal library for publication"))
            }
            Text(uiText("Exportul nu publică automat. Doar proprietarul repository-ului poate publica fișierul online, urmând ghidul pentru tabletă.",
                "Export does not publish automatically. Only the repository owner can publish the file online using the tablet guide."),
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
