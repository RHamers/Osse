package com.rhamers.osse.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log

// A file picked via ActivityResultContracts.OpenDocument() only grants a transient
// read permission scoped to the current process. Without persisting it, the URI
// becomes unreadable the moment the process dies — exactly the scenario resume-after-
// process-death is meant to survive.
fun grantPersistableReadPermission(contentResolver: ContentResolver, uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
        // The grant isn't persistable (e.g. the provider doesn't support it, or the flag
        // wasn't present on the originating Intent). Losing resume-ability for this one file
        // is a much better outcome than crashing the file-picker flow.
        Log.w("FilePermissions", "Failed to persist read permission for $uri", e)
    }
}
