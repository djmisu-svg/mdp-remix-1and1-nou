package ro.mdp.remix.ui

/** Stable IDs are persisted; enum order and translated UI copy can change safely. */
enum class RecorderDesign(val id: String, val label: String) {
    CLASSIC("classic", "AKAI Classic"),
    BLACK_EDITION("black", "AKAI Black Edition"),
    RED_EDITION("red", "AKAI Red Edition"),
    STUDIO_PRO("studio_pro", "AKAI Studio Pro");

    companion object {
        fun fromId(id: String?): RecorderDesign =
            entries.firstOrNull { it.id == id } ?: BLACK_EDITION
    }
}
