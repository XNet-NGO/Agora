package com.newoether.agora.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.newoether.agora.R
import com.newoether.agora.ui.theme.contrastingOn
import com.newoether.agora.ui.theme.parseHexColor
import com.newoether.agora.viewmodel.ChatViewModel

/** Palette for bubble/background swatches (Material-ish accents). */
private val SWATCHES = listOf(
    "#2E7D32", "#1565C0", "#6A1B9A", "#AD1457", "#C62828",
    "#EF6C00", "#00838F", "#455A64", "#37474F", "#5D4037",
)

/** Copy a picked image Uri into app-private storage; returns the stable absolute path or null. */
fun copyChatBackgroundImage(viewModel: ChatViewModel, uri: Uri): String? = try {
    val ctx = viewModel.getApplication<android.app.Application>()
    val dir = java.io.File(ctx.filesDir, "appearance").apply { mkdirs() }
    // Unique filename per pick so Coil's disk/memory cache can't serve a stale image; prune olds.
    dir.listFiles { f -> f.name.startsWith("chat_background") }?.forEach { it.delete() }
    val dest = java.io.File(dir, "chat_background_${System.currentTimeMillis()}.img")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { input.copyTo(it) }
    }
    if (dest.isFile && dest.length() > 0) dest.absolutePath else null
} catch (_: Exception) {
    null
}

/**
 * Settings → Appearance → Chat appearance: a custom user-bubble color and a chat background
 * (none / solid color / image). Self-contained so it slots into the appearance page with one call.
 */
@Composable
fun SettingsChatAppearanceSection(viewModel: ChatViewModel) {
    val bubble by viewModel.settings.userBubbleColor.collectAsState()
    val background by viewModel.settings.chatBackground.collectAsState()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read access + copy into app storage so the path stays valid.
            val path = com.newoether.agora.ui.settings.copyChatBackgroundImage(viewModel, uri)
            if (path != null) viewModel.settings.setChatBackground("image:$path")
        }
    }

    SettingsGroup(
        title = stringResource(R.string.appearance_chat_section),
        items = buildList {
            add {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.appearance_bubble_color), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SwatchRow(
                        selectedHex = bubble,
                        onPick = { hex -> viewModel.settings.setUserBubbleColor(hex) },
                        onClear = { viewModel.settings.setUserBubbleColor("") },
                    )
                }
            }
            add {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.appearance_chat_background), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SwatchRow(
                        selectedHex = background.removePrefix("color:").takeIf { background.startsWith("color:") } ?: "",
                        onPick = { hex -> viewModel.settings.setChatBackground("color:$hex") },
                        onClear = { viewModel.settings.setChatBackground("") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (background.startsWith("image:")) stringResource(R.string.appearance_bg_image_set)
                            else stringResource(R.string.appearance_bg_pick_image),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { imagePicker.launch(arrayOf("image/*")) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun SwatchRow(selectedHex: String, onPick: (String) -> Unit, onClear: () -> Unit) {
    val selected = parseHexColor(selectedHex)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        // "Default / none" chip.
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onClear() },
            contentAlignment = Alignment.Center,
        ) {
            if (selected == null) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
            else Icon(Icons.Default.Close, null, Modifier.size(14.dp))
        }
        SWATCHES.forEach { hex ->
            val c = parseHexColor(hex) ?: Color.Gray
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c).clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected != null && selectedHex.equals(hex, ignoreCase = true)) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = contrastingOn(c))
                }
            }
        }
    }
}

