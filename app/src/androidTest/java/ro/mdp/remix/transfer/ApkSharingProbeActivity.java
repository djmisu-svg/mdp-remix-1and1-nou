package ro.mdp.remix.transfer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Test-APK-only recipient. Java keeps the standalone process independent of target Kotlin classes. */
public final class ApkSharingProbeActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent shared = getIntent();
        @SuppressWarnings("deprecation")
        final ResultReceiver result = shared.getParcelableExtra("mdp.test.result");
        new Thread(() -> {
            Bundle reply = new Bundle();
            try {
                if (!Intent.ACTION_SEND.equals(shared.getAction()) ||
                    !"application/vnd.android.package-archive".equals(shared.getType())) {
                    throw new IllegalArgumentException("Expected one APK attachment");
                }
                @SuppressWarnings("deprecation")
                Uri uri = shared.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException("Missing content URI");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("No APK stream");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) { digest.update(buffer, 0, read); count += read; }
                    }
                }
                StringBuilder checksum = new StringBuilder();
                for (byte value : digest.digest()) checksum.append(String.format(Locale.ROOT, "%02x", value & 255));
                reply.putString("sha256", checksum.toString());
                reply.putLong("bytes", count);
                reply.putInt("uid", Process.myUid());
                if (result != null) result.send(RESULT_OK, reply);
            } catch (Exception failure) {
                reply.putString("error", failure.getClass().getSimpleName());
                if (result != null) result.send(RESULT_CANCELED, reply);
            } finally { runOnUiThread(this::finish); }
        }).start();
    }
}
