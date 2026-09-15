package com.rhamers.osse.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.media3.session.MediaButtonReceiver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaButtonReceiverRegistrationTest {

    @Test
    fun mediaButtonReceiverIsRegisteredForActionMediaButton() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val expected = ComponentName(ctx, MediaButtonReceiver::class.java)
        val resolveInfos = ctx.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MEDIA_BUTTON), PackageManager.GET_META_DATA
        )
        val matched = resolveInfos.any {
            it.activityInfo.packageName == expected.packageName &&
                it.activityInfo.name == expected.className
        }
        assertTrue(
            "expected androidx.media3.session.MediaButtonReceiver registered for " +
                "ACTION_MEDIA_BUTTON so a dead process can be woken by an earbud press",
            matched
        )
    }
}
