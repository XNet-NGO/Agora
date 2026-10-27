package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A horizontal row of shell "extra keys" for a command composer, ported in spirit from ShellBox's
 * sticky-key bar (com.shellbox.MainActivity.setupExtraKeys).
 *
 * Agora's sandbox runs commands request/response via SandboxManager.executeCommand — there is no
 * live PTY — so instead of emitting terminal escape sequences, these keys edit the command
 * [TextFieldValue] directly, cursor-aware: token keys insert common shell characters at the
 * caret, ◀/▶ move the caret, TAB inserts a literal tab, and ⌫ deletes the char before the caret.
 * This keeps ShellBox's one-tap convenience for the characters that are awkward on a soft keyboard.
 */
@Composable
internal fun ExtraKeysRow(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    // label shown on the key -> action applied to the current TextFieldValue.
    val keys: List<Pair<String, (TextFieldValue) -> TextFieldValue>> = remember {
        listOf(
            "TAB" to insertToken("\t"),
            "|" to insertToken("|"),
            "~" to insertToken("~"),
            "/" to insertToken("/"),
            "-" to insertToken("-"),
            "_" to insertToken("_"),
            "\$" to insertToken("$"),
            "*" to insertToken("*"),
            "&&" to insertToken(" && "),
            ">" to insertToken(" > "),
            "\"" to insertToken("\""),
            "'" to insertToken("'"),
            "◀" to moveCaret(-1),
            "▶" to moveCaret(1),
            "⌫" to backspace(),
        )
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(keys) { (label, action) ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .height(36.dp)
                    .clickable { onValueChange(action(value)) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Insert [token] at the caret (replacing any selection), leaving the caret after it. */
private fun insertToken(token: String): (TextFieldValue) -> TextFieldValue = { v ->
    val start = v.selection.min
    val end = v.selection.max
    val newText = v.text.substring(0, start) + token + v.text.substring(end)
    val caret = start + token.length
    v.copy(text = newText, selection = TextRange(caret))
}

/** Move the caret by [delta], clamped to the text bounds (collapses any selection). */
private fun moveCaret(delta: Int): (TextFieldValue) -> TextFieldValue = { v ->
    val pos = (v.selection.max + delta).coerceIn(0, v.text.length)
    v.copy(selection = TextRange(pos))
}

/** Delete the character before the caret, or the current selection if one exists. */
private fun backspace(): (TextFieldValue) -> TextFieldValue = { v ->
    val start = v.selection.min
    val end = v.selection.max
    if (start != end) {
        v.copy(text = v.text.substring(0, start) + v.text.substring(end), selection = TextRange(start))
    } else if (start > 0) {
        v.copy(text = v.text.substring(0, start - 1) + v.text.substring(start), selection = TextRange(start - 1))
    } else {
        v
    }
}
