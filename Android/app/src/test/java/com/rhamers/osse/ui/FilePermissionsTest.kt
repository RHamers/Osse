package com.rhamers.osse.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilePermissionsTest {

    @Test
    fun `grantPersistableReadPermission persists read permission for the uri`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.example.provider/tree/doc1")

        grantPersistableReadPermission(ctx.contentResolver, uri)

        val persisted = ctx.contentResolver.persistedUriPermissions
        assertTrue(
            "expected a persisted read permission for $uri so the file survives process death",
            persisted.any { it.uri == uri && it.isReadPermission }
        )
    }
}
