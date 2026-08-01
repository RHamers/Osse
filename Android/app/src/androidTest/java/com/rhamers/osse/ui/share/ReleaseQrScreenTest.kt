package com.rhamers.osse.ui.share

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.ui.theme.OsseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseQrScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysQrCodeAndReleaseUrl() {
        composeRule.setContent {
            OsseTheme {
                ReleaseQrScreen(onBack = {})
            }
        }

        composeRule.onNodeWithContentDescription("Release QR code").assertIsDisplayed()
        composeRule.onNodeWithText(RELEASE_URL).assertIsDisplayed()
    }

    @Test
    fun backButtonInvokesCallback() {
        var backClicked = false

        composeRule.setContent {
            OsseTheme {
                ReleaseQrScreen(onBack = { backClicked = true })
            }
        }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertTrue(backClicked)
    }

    @Test
    fun tappingUrlOpensItInBrowser() {
        var openedUrl: String? = null
        val fakeUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUrl = uri
            }
        }

        composeRule.setContent {
            OsseTheme {
                CompositionLocalProvider(LocalUriHandler provides fakeUriHandler) {
                    ReleaseQrScreen(onBack = {})
                }
            }
        }

        composeRule.onNodeWithText(RELEASE_URL).performClick()

        assertEquals(RELEASE_URL, openedUrl)
    }
}
