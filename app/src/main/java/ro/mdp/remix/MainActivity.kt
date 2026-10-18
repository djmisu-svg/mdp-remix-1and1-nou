package ro.mdp.remix

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.mdp.remix.brand.BrandLibraryJson
import ro.mdp.remix.brand.BrandLibraryPanel
import ro.mdp.remix.brand.BrandLibraryViewModel
import ro.mdp.remix.transfer.CollectionData
import ro.mdp.remix.transfer.CollectionSharing
import ro.mdp.remix.updates.UpdateDialog
import ro.mdp.remix.updates.UpdateViewModel
import ro.mdp.remix.updates.UpdateError
import ro.mdp.remix.updates.UpdateException
import ro.mdp.remix.updates.UpdatePhase
import ro.mdp.remix.background.BackgroundMediaLayer
import ro.mdp.remix.background.BackgroundSettingsPanel
import ro.mdp.remix.background.BackgroundViewModel
import ro.mdp.remix.external.YouTubeLauncher
import ro.mdp.remix.links.LinkViewModel
import ro.mdp.remix.playback.PlayerViewModel
import ro.mdp.remix.ui.*

private enum class Destination(val tag: String) {
    STUDIO("nav_studio"), YOUTUBE("nav_youtube"), SETTINGS("nav_settings")
}
class MainActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()
    private val linkViewModel: LinkViewModel by viewModels()
    private val backgroundViewModel: BackgroundViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()
    private val brandViewModel: BrandLibraryViewModel by viewModels()
    private var incomingCollection by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveCollectionIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            RemixTheme {
                val preferences = remember { getSharedPreferences("mdp_settings", Context.MODE_PRIVATE) }
                var english by rememberSaveable { mutableStateOf(preferences.getBoolean("english", false)) }
                var destination by rememberSaveable { mutableStateOf(Destination.STUDIO) }
                var externalError by rememberSaveable { mutableStateOf<String?>(null) }
                var panelTransparency by rememberSaveable { mutableIntStateOf(preferences.getInt("panel_transparency", 0).coerceIn(0, 100)) }
                var brandSyncEnabled by rememberSaveable { mutableStateOf(preferences.getBoolean("brand_sync_enabled", true)) }
                var importRevision by rememberSaveable { mutableIntStateOf(0) }
                var confirmShare by remember { mutableStateOf(false) }
                var transferBusy by remember { mutableStateOf(false) }
                var transferNotice by remember { mutableStateOf<String?>(null) }
                var showUpdates by rememberSaveable { mutableStateOf(false) }
                var installAfterDownload by rememberSaveable { mutableStateOf(false) }
                var signingInfo by remember { mutableStateOf<String?>(null) }
                val updateState by updateViewModel.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                val sharing = remember { CollectionSharing(applicationContext) }
                val installVerified: () -> Unit = {
                    scope.launch {
                        try { startActivity(updateViewModel.installationIntent()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { updateViewModel.installationFailed(failure) }
                    }
                }
                val unknownSources = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) installVerified()
                    else updateViewModel.installationFailed(UpdateException(UpdateError.UNKNOWN_SOURCES))
                }
                val requestInstall: () -> Unit = {
                    if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                        try { unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        catch (failure: Exception) { updateViewModel.installationFailed(failure) }
                    } else installVerified()
                }
                LaunchedEffect(updateState.phase, installAfterDownload, showUpdates) {
                    if (updateState.phase == UpdatePhase.ERROR) installAfterDownload = false
                    if (showUpdates && installAfterDownload && updateState.phase == UpdatePhase.READY) {
                        installAfterDownload = false
                        requestInstall()
                    }
                }
                val collectionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) incomingCollection = uri
                }
                val brandExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri != null) scope.launch {
                        try {
                            val library = linkViewModel.sharedSnapshot()
                            withContext(Dispatchers.IO) {
                                val json = BrandLibraryJson.encode(BrandLibraryJson.forPublication(library))
                                require(json.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Biblioteca depășește limita de publicare." }
                                checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                            }
                            transferNotice = if (english) "Brand library exported. Publish it using the tablet guide." else "Biblioteca brandului a fost exportată. Public-o urmând ghidul pentru tabletă."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { externalError = failure.message ?: "Export failed" }
                    }
                }
                val screenStates = rememberSaveableStateHolder()
                val backgroundSettings = backgroundViewModel.settings.collectAsStateWithLifecycle()
                val backgroundRenderError = remember(backgroundViewModel) {
                    { message: String? ->
                        if (message == null) backgroundViewModel.clearError()
                        else backgroundViewModel.onRenderError(message)
                    }
                }
                val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(backgroundViewModel::select)
                }
                CompositionLocalProvider(LocalEnglish provides english, LocalPanelTransparency provides panelTransparency) {
                    BackHandler(destination == Destination.SETTINGS) { destination = Destination.STUDIO }
                    BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
                        val wide = maxWidth >= 840.dp
                        BackgroundMediaLayer(backgroundSettings, Modifier.matchParentSize(), backgroundRenderError)
                        Row(Modifier.fillMaxSize()) {
                            if (wide) NavigationRail(
                                modifier = Modifier.fillMaxHeight().width(104.dp),
                                containerColor = panelColor(),
                                header = {
                                    Surface(color = Red, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 20.dp, bottom = 30.dp).size(56.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Text("M", fontWeight = FontWeight.Black, fontSize = 28.sp) }
                                    }
                                },
                            ) {
                                Destination.entries.forEach { target ->
                                    NavigationRailItem(
                                        selected = destination == target,
                                        onClick = { destination = target },
                                        icon = { DestinationIcon(target) },
                                        label = { Text(destinationLabel(target), maxLines = 1) },
                                        modifier = Modifier.testTag(target.tag).padding(vertical = 8.dp),
                                        colors = NavigationRailItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Red, unselectedIconColor = Muted, unselectedTextColor = Muted),
                                    )
                                }
                            }
                            Scaffold(
                                modifier = Modifier.weight(1f), containerColor = Color.Transparent,
                                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                                bottomBar = {
                                    if (!wide) Column {
                                        HorizontalDivider(color = Hairline)
                                        NavigationBar(containerColor = panelColor(), tonalElevation = 0.dp) {
                                            Destination.entries.forEach { target ->
                                                NavigationBarItem(
                                                    selected = destination == target,
                                                    onClick = { destination = target },
                                                    icon = { DestinationIcon(target) },
                                                    label = { Text(destinationLabel(target), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                    modifier = Modifier.testTag(target.tag),
                                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Red, unselectedIconColor = Muted, unselectedTextColor = Muted),
                                                )
                                            }
                                        }
                                    }
                                },
                            ) { padding ->
                                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                    screenStates.SaveableStateProvider("${destination.name}:$importRevision") {
                                    when (destination) {
                                        Destination.STUDIO -> LocalMusicScreen()
                                        Destination.YOUTUBE -> LinksScreen(linkViewModel) { link ->
                                            when (YouTubeLauncher.open(this@MainActivity, link.url)) {
                                                YouTubeLauncher.Result.OPENED -> linkViewModel.markLinkOpened(link.id)
                                                YouTubeLauncher.Result.INVALID_LINK -> externalError = if (english) "This YouTube link is invalid. Edit it or add it again." else "Linkul YouTube nu este valid. Editează sau adaugă din nou linkul."
                                                YouTubeLauncher.Result.NO_HANDLER -> externalError = if (english) "Install YouTube or a browser to open this link." else "Instalează YouTube sau un browser pentru a deschide linkul."
                                            }
                                        }
                                        Destination.SETTINGS -> PreferencesScreen(english, { value ->
                                            english = value
                                            preferences.edit().putBoolean("english", value).apply()
                                        }, onShare = {
                                            confirmShare = true
                                        }, onUpdates = {
                                            showUpdates = true
                                            updateViewModel.check()
                                        }, onChangeBackground = {
                                            backgroundPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif", "video/*"))
                                        }, onImportCollection = { collectionPicker.launch(arrayOf("application/vnd.mdp.remix.collection", "application/octet-stream", "application/zip")) },
                                            panelTransparency = panelTransparency, onTransparency = { value ->
                                                panelTransparency = value
                                                preferences.edit().putInt("panel_transparency", value).apply()
                                            }, brandSyncEnabled = brandSyncEnabled, onBrandSync = { value ->
                                                brandSyncEnabled = value
                                                preferences.edit().putBoolean("brand_sync_enabled", value).apply()
                                                brandViewModel.setEnabled(value)
                                            }, onSyncBrand = { brandViewModel.sync { library, _ -> linkViewModel.syncBrand(library) } },
                                            onExportBrand = { brandExporter.launch("brand-library.json") }, onSigningInfo = {
                                                scope.launch {
                                                    try {
                                                        val identity = updateViewModel.installedIdentity()
                                                        signingInfo = "Package: ${identity.packageName}\nversionCode: ${identity.versionCode}\nSHA-256:\n${identity.certificates.joinToString("\n")}" 
                                                    } catch (failure: Exception) { externalError = failure.message ?: "Signing identity unavailable" }
                                                }
                                            })
                                    }
                                    }
                                }
                            }
                        }
                    }
                    externalError?.let { error ->
                        AlertDialog(onDismissRequest = { externalError = null }, title = { Text(uiText("Acțiunea nu este disponibilă", "Action unavailable")) }, text = { Text(error) }, confirmButton = { TextButton(onClick = { externalError = null }) { Text(uiText("Închide", "Close")) } })
                    }
                    if (confirmShare) AlertDialog(
                        onDismissRequest = { confirmShare = false },
                        title = { Text(uiText("Distribuie aplicația și colecția", "Share app and collection")) },
                        text = { Text(uiText("Se vor trimite două fișiere: APK-ul instalat și colecția ta cu playlist-uri, muzică locală, fundal și setări. Fișierele mari pot necesita Quick Share, Bluetooth sau un serviciu de fișiere. Destinatarul instalează APK-ul și importă colecția.", "Two files will be shared: the installed APK and your collection with playlists, local music, background and settings. Large files may need Quick Share, Bluetooth or a file service. The recipient installs the APK and imports the collection.")) },
                        confirmButton = { TextButton(onClick = {
                            confirmShare = false
                            transferBusy = true
                            scope.launch {
                                try {
                                    backgroundViewModel.flushDimming()
                                    val data = CollectionData(playerViewModel.sharedSnapshot(), linkViewModel.sharedSnapshot(), english,
                                        RecorderDesignPreferences.load(applicationContext).id, backgroundViewModel.settings.value,
                                        panelTransparency, brandSyncEnabled)
                                    val prepared = sharing.prepareShare(data)
                                    openExternal(Intent.createChooser(sharing.shareIntent(prepared, english),
                                        if (english) "Share APK and collection" else "Trimite APK-ul și colecția")) {
                                        externalError = if (english) "No app can share these files." else "Nu există o aplicație care să trimită aceste fișiere."
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { externalError = failure.message ?: if (english) "Sharing failed." else "Distribuirea nu a reușit." }
                                finally { transferBusy = false }
                            }
                        }) { Text(uiText("Pregătește și distribuie", "Prepare and share")) } },
                        dismissButton = { TextButton(onClick = { confirmShare = false }) { Text(uiText("Anulează", "Cancel")) } },
                    )
                    incomingCollection?.let { uri ->
                        if (!transferBusy) AlertDialog(
                            onDismissRequest = { clearCollectionIntent() },
                            title = { Text(uiText("Importă colecția primită", "Import received collection")) },
                            text = { Text(uiText("Playlist-urile și muzica primite se adaugă la biblioteca ta. Se aplică fundalul, limba, aspectul și setările primite. Fișierele tale existente nu se șterg.", "Received playlists and music are added to your library. The received background, language, design and settings are applied. Your existing files are kept.")) },
                            confirmButton = { TextButton(onClick = {
                                clearCollectionIntent()
                                transferBusy = true
                                scope.launch {
                                    try {
                                        val received = sharing.receive(uri)
                                        playerViewModel.importShared(received.data.music, received.id)
                                        linkViewModel.importShared(received.data.youtube, received.id)
                                        backgroundViewModel.importShared(received.data.background)
                                        withContext(Dispatchers.IO) {
                                            check(preferences.edit().putBoolean("english", received.data.english)
                                                .putString("recorder_design", received.data.recorderDesignId)
                                                .putInt("panel_transparency", received.data.panelTransparency)
                                                .putBoolean("brand_sync_enabled", received.data.brandSyncEnabled).commit())
                                        }
                                        english = received.data.english
                                        panelTransparency = received.data.panelTransparency
                                        brandSyncEnabled = received.data.brandSyncEnabled
                                        brandViewModel.setEnabled(brandSyncEnabled)
                                        importRevision++
                                        destination = Destination.STUDIO
                                        transferNotice = if (english) "Collection imported. Music and background are saved on this device." else "Colecția a fost importată. Muzica și fundalul sunt salvate pe acest dispozitiv."
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { externalError = (if (english) "Import did not finish. Your existing library was kept. " else "Importul nu s-a încheiat. Biblioteca existentă a fost păstrată. ") + failure.message.orEmpty() }
                                    finally { transferBusy = false }
                                }
                            }) { Text(uiText("Importă", "Import")) } },
                            dismissButton = { TextButton(onClick = { clearCollectionIntent() }) { Text(uiText("Anulează", "Cancel")) } },
                        )
                    }
                    if (transferBusy) AlertDialog(onDismissRequest = {}, title = { Text(uiText("Se pregătesc fișierele…", "Preparing files…")) },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(uiText("Muzica și fundalul sunt copiate. Păstrează aplicația deschisă până la finalizare.", "Music and background are being copied. Keep the app open until this finishes."))
                        } }, confirmButton = {})
                    transferNotice?.let { notice -> AlertDialog(onDismissRequest = { transferNotice = null },
                        title = { Text(uiText("MdP RemiX 1&1", "MdP RemiX 1&1")) }, text = { Text(notice) },
                        confirmButton = { TextButton(onClick = { transferNotice = null }) { Text(uiText("Închide", "Close")) } }) }
                    if (showUpdates) UpdateDialog(updateState, { showUpdates = false; installAfterDownload = false }, updateViewModel::check,
                        { installAfterDownload = true; updateViewModel.download() }, requestInstall,
                        { installAfterDownload = false; updateViewModel.cancel() })
                    signingInfo?.let { details -> AlertDialog(onDismissRequest = { signingInfo = null },
                        title = { Text(uiText("Identitatea aplicației instalate", "Installed application identity")) },
                        text = { SelectionContainer { Text(details, style = MaterialTheme.typography.bodySmall) } },
                        confirmButton = { TextButton(onClick = { signingInfo = null }) { Text(uiText("Închide", "Close")) } }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveCollectionIntent(intent)
    }

    private fun receiveCollectionIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "content") incomingCollection = intent.data
    }

    private fun clearCollectionIntent() {
        incomingCollection = null
        if (intent?.action == Intent.ACTION_VIEW) setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    override fun onStart() {
        super.onStart()
        val enabled = getSharedPreferences("mdp_settings", Context.MODE_PRIVATE).getBoolean("brand_sync_enabled", true)
        brandViewModel.setEnabled(enabled)
        if (enabled) brandViewModel.syncIfDue { library, _ -> linkViewModel.syncBrand(library) }
    }

    private fun openExternal(intent: Intent, onFailure: () -> Unit) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { onFailure() } catch (_: SecurityException) { onFailure() }
    }

    override fun onStop() {
        backgroundViewModel.flushDimming()
        super.onStop()
    }

    @Composable
    private fun DestinationIcon(destination: Destination) {
        Icon(when (destination) { Destination.STUDIO -> Icons.Default.GraphicEq; Destination.YOUTUBE -> Icons.Default.SmartDisplay; Destination.SETTINGS -> Icons.Default.Tune }, null)
    }

    @Composable
    private fun destinationLabel(destination: Destination): String = when (destination) {
        Destination.STUDIO -> "Studio"
        Destination.YOUTUBE -> "YouTube"
        Destination.SETTINGS -> uiText("Setări", "Settings")
    }

    @Composable
    private fun PreferencesScreen(english: Boolean, onLanguage: (Boolean) -> Unit, onShare: () -> Unit, onUpdates: () -> Unit,
        onChangeBackground: () -> Unit, onImportCollection: () -> Unit, panelTransparency: Int, onTransparency: (Int) -> Unit,
        brandSyncEnabled: Boolean, onBrandSync: (Boolean) -> Unit, onSyncBrand: () -> Unit, onExportBrand: () -> Unit, onSigningInfo: () -> Unit) {
        val backgroundSettings by backgroundViewModel.settings.collectAsStateWithLifecycle()
        val backgroundLoading by backgroundViewModel.loading.collectAsStateWithLifecycle()
        val originalAvailable by backgroundViewModel.originalAvailable.collectAsStateWithLifecycle()
        val backgroundError by backgroundViewModel.error.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(7.dp).background(Red, RoundedCornerShape(2.dp)))
                    Text("MdP RemiX 1&1 / PREMIUM", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiText("Preferințe", "Preferences"), style = MaterialTheme.typography.displaySmall)
                    Text(uiText("Fă loc sunetului tău.", "Make room for your sound."), color = Muted)
                }
                BackgroundSettingsPanel(
                    settings = backgroundSettings, loading = backgroundLoading, originalAvailable = originalAvailable,
                    error = backgroundError,
                    onChange = onChangeBackground,
                    onRestore = backgroundViewModel::restoreOriginal, onScale = backgroundViewModel::setScale,
                    onDimming = backgroundViewModel::setDimming, onAnimation = backgroundViewModel::setAnimationEnabled,
                    onClearError = backgroundViewModel::clearError,
                    onDimmingFinished = backgroundViewModel::flushDimming,
                )
                Surface(shape = RoundedCornerShape(24.dp), color = panelColor(), border = BorderStroke(1.dp, Hairline)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(uiText("Transparența panourilor", "Panel transparency"), style = MaterialTheme.typography.titleLarge)
                        Text("$panelTransparency%", color = Muted, modifier = Modifier.testTag("panel_transparency_value"))
                        Slider(value = panelTransparency.toFloat(), onValueChange = { onTransparency(it.toInt().coerceIn(0, 100)) },
                            valueRange = 0f..100f, modifier = Modifier.fillMaxWidth().testTag("panel_transparency"))
                        Text(uiText("0%: panouri opace. 100%: fundal vizibil prin panouri. Comenzile și instrumentele rămân vizibile.",
                            "0%: opaque panels. 100%: background visible through panels. Controls and instruments remain visible."), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                BrandLibraryPanel(brandViewModel, brandSyncEnabled, onBrandSync, onSyncBrand, onExportBrand)
                Surface(shape = RoundedCornerShape(24.dp), color = panelColor(), border = BorderStroke(1.dp, Hairline)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, null, tint = Color.White)
                            Spacer(Modifier.width(12.dp))
                            Text(uiText("Limbă", "Language"), style = MaterialTheme.typography.titleLarge)
                        }
                        Text(uiText("Alege limba interfeței. Preferința se păstrează pe acest dispozitiv.", "Choose your interface language. Your preference is saved on this device."), color = Muted, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LanguageChoice("Română", !english, Modifier.weight(1f).testTag("language_romanian")) { onLanguage(false) }
                            LanguageChoice("English", english, Modifier.weight(1f).testTag("language_english")) { onLanguage(true) }
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(24.dp), color = panelColor(), border = BorderStroke(1.dp, Hairline)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Icon(Icons.Default.Share, null, tint = Red, modifier = Modifier.size(28.dp))
                        Text(uiText("Sunetul merge mai departe.", "Good sound travels."), style = MaterialTheme.typography.titleLarge)
                        Text(uiText("Trimite APK-ul real și colecția ta: playlist-uri, linkuri YouTube, muzică locală, fundal și setări. Destinatarul instalează aplicația și importă fișierul .mdpremix.", "Send the actual APK and your collection: playlists, YouTube links, local music, background and settings. The recipient installs the app and imports the .mdpremix file."), color = Muted)
                        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("share_app"), shape = RoundedCornerShape(14.dp)) {
                            Text(uiText("Distribuie aplicația", "Share app")); Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowOutward, null, Modifier.size(18.dp))
                        }
                        OutlinedButton(onClick = onImportCollection, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("import_collection")) {
                            Text(uiText("Importă colecția primită", "Import received collection"))
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(24.dp), color = panelColor(), border = BorderStroke(1.dp, Hairline)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, null, tint = Red)
                            Spacer(Modifier.width(12.dp)); Text(uiText("Rămâi în ritm.", "Stay in the loop."), style = MaterialTheme.typography.titleLarge)
                        }
                        Text(uiText("Verifică versiunea oficială, descarcă APK-ul verificat și confirmă actualizarea în Android. Datele sunt păstrate când pachetul și semnătura corespund.", "Check the official version, download the verified APK and confirm the update in Android. Data is kept when package and signature match."), color = Muted)
                        OutlinedButton(onClick = onUpdates, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("check_updates"), shape = RoundedCornerShape(14.dp)) { Text(uiText("Verifică actualizări", "Check for updates"), color = Color.White) }
                        TextButton(onClick = onSigningInfo, modifier = Modifier.fillMaxWidth().testTag("signing_identity")) {
                            Text(uiText("Identitatea aplicației instalate", "Installed application identity"))
                        }
                    }
                }
                Text((if (packageName.endsWith(".premiumtest")) "PREMIUM TEST" else "PREMIUM") + " / " + packageManager.getPackageInfo(packageName, 0).versionName,
                    style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }

    @Composable
    private fun LanguageChoice(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Surface(onClick = onClick, modifier = modifier.semantics { selected = active }, color = if (active) Red.copy(alpha = 0.18f) else panelColor(), border = BorderStroke(if (active) 2.dp else 1.dp, if (active) Red else Hairline), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.SemiBold)
                if (active) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }

    @Composable
    private fun LocalMusicScreen() {
        val library by playerViewModel.library.collectAsStateWithLifecycle()
        val playback by playerViewModel.playback.collectAsStateWithLifecycle()
        val importing by playerViewModel.importing.collectAsStateWithLifecycle()
        val message by playerViewModel.message.collectAsStateWithLifecycle()
        var notificationRequested by rememberSaveable { mutableStateOf(false) }
        val importer = rememberLauncherForActivityResult(
            LocalAudioDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) playerViewModel.importUris(uris)
        }
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* Redarea funcționează și când notificările sunt dezactivate. */ }
        fun requestNotifications() {
            if (!notificationRequested &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationRequested = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        RemixApp(
            library = library,
            playback = playback,
            importing = importing,
            message = message,
            onClearMessage = playerViewModel::clearMessage,
            onImport = { importer.launch(arrayOf("audio/*")) },
            onPlayTracks = { tracks, index ->
                playerViewModel.playTracks(tracks, index)
                requestNotifications()
            },
            onTogglePlayback = {
                playerViewModel.togglePlayPause()
                if (!playback.isPlaying && playback.currentTrackId != null) requestNotifications()
            },
            onNext = playerViewModel::next,
            onPrevious = playerViewModel::previous,
            onSeek = playerViewModel::seekTo,
            onVolume = playerViewModel::setVolume,
            onFavorite = playerViewModel::toggleFavorite,
            onCreatePlaylist = playerViewModel::createPlaylist,
            onAddToPlaylist = playerViewModel::addToPlaylist,
            onRemoveFromPlaylist = playerViewModel::removeFromPlaylist,
            onDeletePlaylist = playerViewModel::deletePlaylist,
            onRemoveTrack = playerViewModel::removeTrack,
            onRenamePlaylist = playerViewModel::renamePlaylist,
            onMovePlaylist = playerViewModel::movePlaylist,
            onMoveTrackInPlaylist = playerViewModel::moveTrackInPlaylist,
        )
    }
}

private class LocalAudioDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
