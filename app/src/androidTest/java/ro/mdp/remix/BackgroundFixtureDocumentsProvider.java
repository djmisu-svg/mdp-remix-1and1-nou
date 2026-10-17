package ro.mdp.remix;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A real SAF source installed only in the test APK, with no access to user documents.
 *
 * DocumentsUI launches this provider in the test APK's own process, outside the
 * instrumented application's class loader. Keep it entirely Java/Android: AGP can
 * deduplicate Kotlin dependencies into the target APK, which this process cannot load.
 */
public final class BackgroundFixtureDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "ro.mdp.remix.premiumtest.test.backgroundfixtures";

    private static final String[][] DOCUMENTS = {
            {"mdp-test-animation.gif", "image/gif"},
            {"mdp-test-video.mp4", "video/mp4"},
            {"mdp-test-still.png", "image/png"},
    };
    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
    };
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    };

    @Override
    public boolean onCreate() {
        Context providerContext = fixtureContext();
        byte[] buffer = new byte[8192];
        for (String[] document : DOCUMENTS) {
            String name = document[0];
            File file = new File(providerContext.getCacheDir(), name);
            if (file.isFile()) continue;
            try (InputStream input = providerContext.getAssets().open("background_test/" + name);
                 FileOutputStream output = new FileOutputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Could not prepare the isolated background fixture " + name, failure);
            }
        }
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "background-fixtures");
        values.put(Root.COLUMN_DOCUMENT_ID, "root");
        values.put(Root.COLUMN_TITLE, "MdP test media");
        values.put(Root.COLUMN_SUMMARY, "Isolated GIF, image and video fixtures");
        values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD);
        values.put(Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery);
        values.put(Root.COLUMN_MIME_TYPES, "image/*\nvideo/*");
        addValues(cursor, values);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!"root".equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        for (String[] document : DOCUMENTS) addDocument(cursor, document[0]);
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return "root".equals(parentDocumentId) && mimeType(documentId) != null;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode) || mimeType(documentId) == null) {
            throw new FileNotFoundException(documentId);
        }
        return ParcelFileDescriptor.open(new File(fixtureContext().getCacheDir(), documentId),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = "root".equals(id);
        String mime = root ? Document.MIME_TYPE_DIR : mimeType(id);
        if (mime == null) throw new FileNotFoundException(id);
        File file = new File(fixtureContext().getCacheDir(), id);
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id);
        values.put(Document.COLUMN_DISPLAY_NAME, root ? "MdP test media" : id);
        values.put(Document.COLUMN_MIME_TYPE, mime);
        values.put(Document.COLUMN_FLAGS, 0);
        values.put(Document.COLUMN_SIZE, root ? 0L : file.length());
        values.put(Document.COLUMN_LAST_MODIFIED, root ? 0L : file.lastModified());
        addValues(cursor, values);
    }

    private static String mimeType(String id) {
        for (String[] document : DOCUMENTS) {
            if (document[0].equals(id)) return document[1];
        }
        return null;
    }

    private static void addValues(MatrixCursor cursor, Map<String, Object> values) {
        String[] columns = cursor.getColumnNames();
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) row[index] = values.get(columns[index]);
        cursor.addRow(row);
    }

    private Context fixtureContext() {
        Context providerContext = getContext();
        if (providerContext == null) throw new IllegalStateException("Provider context is unavailable");
        return providerContext;
    }
}
