package ro.mdp.remix

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** A real SAF source installed only in the test APK, with no access to user documents. */
class BackgroundFixtureDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        val providerContext = checkNotNull(context)
        DOCUMENTS.forEach { (name, _) ->
            val file = File(providerContext.cacheDir, name)
            if (!file.isFile) providerContext.assets.open("background_test/$name").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        addValues(mapOf(
            Root.COLUMN_ROOT_ID to "background-fixtures",
            Root.COLUMN_DOCUMENT_ID to "root",
            Root.COLUMN_TITLE to "MdP test media",
            Root.COLUMN_SUMMARY to "Isolated GIF, image and video fixtures",
            Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_IS_CHILD,
            Root.COLUMN_ICON to android.R.drawable.ic_menu_gallery,
            Root.COLUMN_MIME_TYPES to "image/*\nvideo/*",
        ))
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (parentDocumentId != "root") throw FileNotFoundException(parentDocumentId)
            DOCUMENTS.forEach { (name, _) -> addDocument(name) }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == "root" && DOCUMENTS.any { it.first == documentId }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r" || DOCUMENTS.none { it.first == documentId }) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(File(checkNotNull(context).cacheDir, documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun MatrixCursor.addDocument(id: String) {
        val root = id == "root"
        val mime = if (root) Document.MIME_TYPE_DIR else DOCUMENTS.firstOrNull { it.first == id }?.second
            ?: throw FileNotFoundException(id)
        val file = File(checkNotNull(context).cacheDir, id)
        addValues(mapOf(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to if (root) "MdP test media" else id,
            Document.COLUMN_MIME_TYPE to mime,
            Document.COLUMN_FLAGS to 0,
            Document.COLUMN_SIZE to if (root) 0L else file.length(),
            Document.COLUMN_LAST_MODIFIED to if (root) 0L else file.lastModified(),
        ))
    }

    private fun MatrixCursor.addValues(values: Map<String, Any>) {
        addRow(columnNames.map { values[it] }.toTypedArray())
    }

    companion object {
        const val AUTHORITY = "ro.mdp.remix.premiumtest.test.backgroundfixtures"
        private val DOCUMENTS = listOf(
            "mdp-test-animation.gif" to "image/gif",
            "mdp-test-video.mp4" to "video/mp4",
            "mdp-test-still.png" to "image/png",
        )
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
