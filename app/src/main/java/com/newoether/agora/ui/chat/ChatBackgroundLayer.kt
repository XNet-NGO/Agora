package com.newoether.agora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.newoether.agora.ui.theme.ChatBackground
import com.newoether.agora.ui.theme.parseChatBackground
import java.io.File

/**
 * Paints the user's custom chat background (Settings → Appearance) as the bottom layer behind the
 * conversation. No-op for the default (the themed/animated background then shows through). Reads
 * the preference reactively from the active [com.newoether.agora.viewmodel.ChatViewModel].
 */
@Composable
fun ChatBackgroundLayer(backgroundSpec: String) {
    val bg = remember(backgroundSpec) { parseChatBackground(backgroundSpec) }
    when (bg) {
        is ChatBackground.Default -> Unit
        is ChatBackground.SolidColor ->
            Box(Modifier.fillMaxSize().background(bg.color))
        is ChatBackground.Image -> {
            val file = remember(bg.path) { File(bg.path) }
            if (file.isFile) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
