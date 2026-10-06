package com.rhamers.osse.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Regression coverage for the bug where instrumented tests binding the real
// MultiTrackService wiped a real connected device's production "osse.db" instead of
// an isolated test database — see useIsolatedTestDatabase()'s doc comment.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsseDatabaseSingletonTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        OsseDatabase.resetForTesting()
    }

    @After
    fun tearDown() {
        OsseDatabase.resetForTesting()
        ctx.deleteDatabase("osse.db")
        ctx.deleteDatabase("custom_test.db")
    }

    @Test
    fun `useIsolatedTestDatabase redirects get() away from the production file`() {
        OsseDatabase.useIsolatedTestDatabase(ctx, "custom_test.db")
        OsseDatabase.get(ctx).openHelper.writableDatabase // force the file to actually open

        assertTrue(ctx.getDatabasePath("custom_test.db").exists())
        assertFalse(ctx.getDatabasePath("osse.db").exists())
    }

    @Test
    fun `useIsolatedTestDatabase is a no-op once the singleton already exists`() {
        val first = OsseDatabase.get(ctx) // creates the singleton backed by "osse.db"
        first.openHelper.writableDatabase // force the file to actually open

        OsseDatabase.useIsolatedTestDatabase(ctx, "custom_test.db")
        val second = OsseDatabase.get(ctx)

        assertSame(first, second)
        assertFalse(ctx.getDatabasePath("custom_test.db").exists())
    }

    @Test
    fun `get() defaults to the production filename when no override is set`() {
        OsseDatabase.get(ctx).openHelper.writableDatabase // force the file to actually open

        assertTrue(ctx.getDatabasePath("osse.db").exists())
    }
}
