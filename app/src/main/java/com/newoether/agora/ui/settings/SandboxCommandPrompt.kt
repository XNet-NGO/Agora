package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import kotlinx.coroutines.launch

private const val COMMAND_LOG_MAX = 8000

/**
 * Typeable command prompt for the sandbox, with a ShellBox-style extra-keys row ([ExtraKeysRow])
 * and quick-install chips that PREFILL the prompt (they do not install directly).
 *
 * This is the single input for the Install Packages section: tapping a quick chip drops
 * `apk add <pkg>` into the field (caret at end) so the user can run it as-is or edit first.
 *
 * Agora's sandbox is request/response (no live PTY), so this runs each entered line through
 * [SandboxManager.executeCommand] and appends stdout/stderr/exit to a local scrollback. The
 * extra-keys row edits the command field directly (cursor-aware) for characters that are awkward
 * on a soft keyboard. [enabled] is false while the backend is busy with package operations.
 */
@Composable
internal fun SandboxCommandPrompt(
    sandboxManager: SandboxManager,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    quickPackages: List<String> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    fun prefill(text: String) {
        input = TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
    }

    fun run(cmd: String) {
        val c = cmd.trim()
        if (c.isBlank() || running || !enabled) return
        running = true
        scope.launch {
            try {
                log = (log + "$ $c\n").takeLast(COMMAND_LOG_MAX)
                val r = sandboxManager.executeCommand(c)
                val out = buildString {
                    if (r.stdout.isNotBlank()) append(r.stdout.trimEnd()).append("\n")
                    if (r.stderr.isNotBlank()) append(r.stderr.trimEnd()).append("\n")
                    if (r.exitCode != 0) append("[exit ${r.exitCode}]\n")
                }
                log = (log + out).takeLast(COMMAND_LOG_MAX)
            } catch (e: Exception) {
                log = (log + "[error: ${e.message}]\n").takeLast(COMMAND_LOG_MAX)
            } finally {
                running = false
            }
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.sandbox_command_label)) },
                placeholder = { Text("ls -la /") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily(Font(R.font.jetbrains_mono_regular)),
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { run(input.text); input = TextFieldValue("") },
                enabled = input.text.isNotBlank() && !running && enabled,
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 28.dp, bottomEnd = 28.dp),
                modifier = Modifier.height(56.dp).widthIn(min = 90.dp).offset(y = 4.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.sandbox_command_run))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ExtraKeysRow(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth())
        if (quickPackages.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.sandbox_quick_install) + ":",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                quickPackages.forEach { pkg ->
                    androidx.compose.material3.AssistChip(
                        onClick = { prefill("apk add $pkg") },
                        enabled = enabled && !running,
                        label = { Text(pkg, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        if (log.isNotBlank()) {
            val cmdScroll = rememberScrollState()
            LaunchedEffect(log) { cmdScroll.scrollTo(cmdScroll.maxValue) }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(200.dp),
            ) {
                SelectionContainer {
                    Text(
                        log.trimEnd(),
                        modifier = Modifier.padding(12.dp).fillMaxWidth().verticalScroll(cmdScroll),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily(Font(R.font.jetbrains_mono_regular)),
                            lineHeight = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Run `apk search -v <query>` in the sandbox and parse installable (not-yet-installed) packages.
 * Lines look like: "name-1.2.3-r0 - description". Returns up to 40 distinct results.
 */
internal suspend fun sandboxSearchRepo(
    sandboxManager: SandboxManager,
    query: String,
): List<SandboxManager.PackageInfo> {
    val r = sandboxManager.executeCommand("apk search -v $query", timeoutMs = 20000)
    val installedNames = sandboxManager.packageList.value.mapTo(HashSet()) { it.name }
    return r.stdout.lineSequence()
        .mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val dash = t.indexOf(" - ")
            val nameVer = if (dash >= 0) t.substring(0, dash) else t
            val desc = if (dash >= 0) t.substring(dash + 3).trim() else ""
            val name = Regex("-\\d[^ ]*$").replace(nameVer, "").ifBlank { nameVer }
            SandboxManager.PackageInfo(name = name, version = "", description = desc)
        }
        .filter { it.name !in installedNames }
        .distinctBy { it.name }
        .take(40)
        .toList()
}

/**
 * Search/filter field for the installed-packages list. When the query matches nothing installed,
 * [showRepoSearch] offers a repository lookup via [onRepoSearch]. Query state is owned by the page
 * (it also drives the installed-list filter), so this is a thin, hoisted-state composable.
 */
@Composable
internal fun SandboxPackageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    showRepoSearch: Boolean,
    repoSearching: Boolean,
    searchEnabled: Boolean,
    onRepoSearch: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.sandbox_search_packages)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.cancel),
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showRepoSearch) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRepoSearch,
                enabled = !repoSearching && searchEnabled,
            ) {
                if (repoSearching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.sandbox_search_repo_fmt, query.trim()))
            }
        }
    }
}
