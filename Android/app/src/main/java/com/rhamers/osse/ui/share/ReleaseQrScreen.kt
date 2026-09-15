package com.rhamers.osse.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

const val RELEASE_URL = "https://github.com/RHamers/Osse/releases/latest"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseQrScreen(onBack: () -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    val qrBitmap = remember(RELEASE_URL) { QrCodeGenerator.generate(RELEASE_URL) }
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text("Get the latest build") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                bitmap = qrBitmap,
                contentDescription = "Release QR code",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(250.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Scan to get the latest build.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = RELEASE_URL,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                ),
                modifier = Modifier.clickable { uriHandler.openUri(RELEASE_URL) }
            )
        }
    }
}
