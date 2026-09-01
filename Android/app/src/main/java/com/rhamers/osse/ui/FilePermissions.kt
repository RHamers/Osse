package com.rhamers.osse.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

// A file picked via ActivityResultContracts.OpenDocument() only grants a transient
// read permission scoped to the current process. Without persisting it, the URI
// becomes unreadable the moment the process dies — exactly the scenario resume-after-
// process-death is meant to survive.
fun grantPersistableReadPermission(contentResolver: ContentResolver, uri: Uri) {
    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
