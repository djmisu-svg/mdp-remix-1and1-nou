package ro.mdp.remix.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderDesignTest {
    @Test fun requiredDesignNamesAndStableStorageIdsRemainDistinct() {
        assertEquals(listOf("classic", "black", "red", "studio_pro"), RecorderDesign.entries.map { it.id })
        assertEquals(listOf("AKAI Classic", "AKAI Black Edition", "AKAI Red Edition", "AKAI Studio Pro"),
            RecorderDesign.entries.map { it.label })
        assertEquals(RecorderDesign.entries.size, RecorderDesign.entries.map { it.id }.toSet().size)
    }

    @Test fun everyPersistedIdRestoresItsDesignWithoutRelyingOnEnumOrdinal() {
        RecorderDesign.entries.forEach { design -> assertEquals(design, RecorderDesign.fromId(design.id)) }
    }

    @Test fun missingOrUnknownPreferencesKeepTheExistingBlackEdition() {
        listOf(null, "", "unknown", "CLASSIC", "0").forEach { id ->
            assertEquals(RecorderDesign.BLACK_EDITION, RecorderDesign.fromId(id))
        }
    }
}
