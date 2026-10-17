package ro.mdp.remix.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSettingsTest {
    private val document = "content://com.android.providers.media.documents/document/image%3A42"

    @Test fun `default and restored settings describe original without inventing its URI`() {
        val defaults = BackgroundSettings()
        assertEquals(BackgroundKind.ORIGINAL, defaults.kind)
        assertNull(defaults.uri)
        assertEquals(BackgroundScale.FILL, defaults.scale)
        assertEquals(0.55f, defaults.dimming)
        assertTrue(defaults.animationEnabled)
        assertEquals("backgrounds/original_couple.gif", ORIGINAL_COUPLE_ASSET)
    }

    @Test fun `supported image MIME types have deterministic static and animated kinds`() {
        for (mime in listOf("image/jpeg", "image/jpg", "image/png", "image/webp", "IMAGE/PNG; charset=binary")) {
            assertEquals(mime, BackgroundKind.IMAGE, BackgroundSettingsRules.classify(mime, null))
        }
        assertEquals(BackgroundKind.GIF, BackgroundSettingsRules.classify("image/gif", "my.gif"))
    }

    @Test fun `specific video MIME types are delegated to the actual Android decoder`() {
        for (mime in listOf("video/mp4", "video/webm", "video/3gpp", "video/quicktime", "video/mpeg", "video/x-msvideo")) {
            assertEquals(mime, BackgroundKind.VIDEO, BackgroundSettingsRules.classify(mime, null))
        }
    }

    @Test fun `generic providers can identify supported files by their display extension`() {
        for (extension in listOf("jpg", "jpeg", "png", "webp", "JPG")) {
            assertEquals(BackgroundKind.IMAGE, BackgroundSettingsRules.classify("application/octet-stream", "photo.$extension"))
        }
        assertEquals(BackgroundKind.GIF, BackgroundSettingsRules.classify(null, "animation.gif"))
        for (extension in listOf("mp4", "m4v", "webm", "3gp", "3gpp", "3g2", "mkv", "ts", "mov", "mpeg", "mpg", "avi")) {
            assertEquals(extension, BackgroundKind.VIDEO, BackgroundSettingsRules.classify(null, "clip.$extension"))
        }
    }

    @Test fun `wildcard media types use matching extension families only`() {
        assertEquals(BackgroundKind.IMAGE, BackgroundSettingsRules.classify("image/*", "photo.png"))
        assertEquals(BackgroundKind.GIF, BackgroundSettingsRules.classify("image/*", "loop.gif"))
        assertEquals(BackgroundKind.VIDEO, BackgroundSettingsRules.classify("video/*", "loop.mp4"))
        assertNull(BackgroundSettingsRules.classify("image/*", "loop.mp4"))
        assertNull(BackgroundSettingsRules.classify("video/*", "photo.jpg"))
    }

    @Test fun `unsupported declared types cannot be disguised by an allowed extension`() {
        for (mime in listOf("application/pdf", "image/svg+xml", "image/heic", "text/html", "audio/mpeg")) {
            assertNull(mime, BackgroundSettingsRules.classify(mime, "fake.jpg"))
        }
    }

    @Test fun `unknown generic files are rejected without an applicable media type`() {
        for (name in listOf<String?>(null, "", "document", "photo.svg", "movie.exe", "document.pdf")) {
            assertNull(name, BackgroundSettingsRules.classify("application/octet-stream", name))
        }
        assertNull(BackgroundSettingsRules.classify("video/*", null))
        assertNull(BackgroundSettingsRules.classify("video/", null))
    }

    @Test fun `only structurally valid document content URIs can be persisted`() {
        assertTrue(BackgroundSettingsRules.isDocumentUri(document))
        assertTrue(BackgroundSettingsRules.isDocumentUri("content://10@media/external/images/media/2"))
        for (uri in listOf<String?>(null, "", "file:///sdcard/picture.jpg", "https://example.com/a.gif",
            "content://media", "content:///images/2", "$document#fragment", "content://media/with space")) {
            assertFalse(uri, BackgroundSettingsRules.isDocumentUri(uri))
        }
    }

    @Test fun `selected background and original source cannot be confused`() {
        assertEquals(BackgroundSettings(), BackgroundSettingsRules.validate(BackgroundSettings()))
        for (kind in listOf(BackgroundKind.IMAGE, BackgroundKind.GIF, BackgroundKind.VIDEO)) {
            val settings = BackgroundSettings(document, kind)
            assertEquals(settings, BackgroundSettingsRules.validate(settings))
            assertThrows(IllegalArgumentException::class.java) {
                BackgroundSettingsRules.validate(settings.copy(uri = null))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundSettingsRules.validate(BackgroundSettings(uri = document))
        }
    }

    @Test fun `slider input clamps finite values and preserves all other settings`() {
        val selected = BackgroundSettings(document, BackgroundKind.GIF, BackgroundScale.FIT, animationEnabled = false)
        assertEquals(selected.copy(dimming = 0f), BackgroundSettingsRules.withDimming(selected, -1f))
        assertEquals(selected.copy(dimming = 1f), BackgroundSettingsRules.withDimming(selected, 2f))
        assertEquals(selected.copy(dimming = .3f), BackgroundSettingsRules.withDimming(selected, .3f))
    }

    @Test fun `invalid dimming never enters saved state`() {
        for (level in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { BackgroundSettingsRules.withDimming(BackgroundSettings(), level) }
        }
        for (level in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                BackgroundSettingsRules.validate(BackgroundSettings(dimming = level))
            }
        }
    }

    @Test fun `versioned preference JSON preserves every selected setting`() {
        for (kind in BackgroundKind.entries) {
            val settings = BackgroundSettings(
                uri = if (kind == BackgroundKind.ORIGINAL) null else document,
                kind = kind, scale = BackgroundScale.FIT, dimming = .72f, animationEnabled = false,
            )
            assertEquals(settings, BackgroundSettingsJson.decode(BackgroundSettingsJson.encode(settings)))
        }
    }

    @Test fun `default JSON round trips without assigning a substitute original URI`() {
        val json = BackgroundSettingsJson.encode(BackgroundSettings())
        assertEquals(BackgroundSettings(), BackgroundSettingsJson.decode(json))
        assertFalse(json.contains(ORIGINAL_COUPLE_ASSET))
    }

    @Test fun `missing optional settings use defaults and future noncritical keys are tolerated`() {
        assertEquals(BackgroundSettings(), BackgroundSettingsJson.decode("{\"futureSetting\":true}"))
        assertEquals(BackgroundSettings(document, BackgroundKind.IMAGE),
            BackgroundSettingsJson.decode("{\"uri\":\"$document\",\"kind\":\"IMAGE\"}"))
    }

    @Test fun `unknown schema and invalid enum values fail visibly instead of resetting saved settings`() {
        for (json in listOf("{\"version\":2}", "{\"kind\":\"UNKNOWN\"}", "{\"scale\":\"STRETCH\"}", "{not json}")) {
            assertThrows(Exception::class.java) { BackgroundSettingsJson.decode(json) }
        }
    }

    @Test fun `malformed field values are rejected instead of coerced into a different selection`() {
        for (json in listOf("{\"dimming\":2}", "{\"dimming\":\"0.4\"}", "{\"animationEnabled\":\"true\"}",
            "{\"kind\":\"VIDEO\",\"uri\":\"file:///movie.mp4\"}", "{\"kind\":\"IMAGE\"}")) {
            assertThrows(Exception::class.java) { BackgroundSettingsJson.decode(json) }
        }
    }

    @Test fun `preferences have a dedicated namespace independent of music and language`() {
        assertEquals("mdp_background_v1", BACKGROUND_PREFERENCES)
        assertEquals("background_settings_json", BACKGROUND_SETTINGS_KEY)
    }
}
