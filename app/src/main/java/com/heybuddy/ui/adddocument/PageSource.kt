package com.heybuddy.ui.adddocument

import android.net.Uri

/** Where a new page comes from: a picked file, or a photo just taken with the in-app camera. */
sealed interface PageSource {

    /**
     * A file the user picked. Read once; no long-term access is kept.
     *
     * @property uri The picked file.
     */
    class PickedFile(val uri: Uri) : PageSource

    /**
     * A photo from the in-app camera, in memory only (Task 2.4). Whoever holds it must either
     * prepare it (which wipes the bytes) or call [dropUnused].
     *
     * @property jpeg The photo, already upright and without metadata.
     */
    class CameraPhoto(val jpeg: ByteArray) : PageSource

    /** Wipes a camera photo that will not be prepared (for example the vault locked). */
    fun dropUnused() {
        if (this is CameraPhoto) jpeg.fill(0)
    }
}
