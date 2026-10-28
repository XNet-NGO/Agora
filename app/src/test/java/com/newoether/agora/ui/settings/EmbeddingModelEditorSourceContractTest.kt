package com.newoether.agora.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingModelEditorSourceContractTest {
    @Test
    fun localTypeUsesTheProviderStarsIcon() {
        val page = readSettingsSource("SettingsSearchPage.kt")

        assertTrue(
            page.contains(
                "if (type == EmbeddingModelType.REMOTE) Icons.Default.Cloud else Icons.Default.AutoAwesome",
            ),
        )
        assertFalse(page.contains("PhoneAndroid"))
    }

    @Test
    fun remoteProviderUsesWholeRowDropdownWithSelectedCheckmark() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val provider = editor.section(
            "var providerExpanded by remember",
            "if (!isNew || preset.editableUrl)",
        )

        assertTrue(provider.contains("SettingsItem("))
        assertTrue(provider.contains("headlineContent = { Text(stringResource(R.string.embedding_provider_label)) }"))
        assertTrue(provider.contains("supportingContent = { Text(stringResource(R.string.embedding_type_remote_desc)) }"))
        assertTrue(provider.contains("Icons.Default.Cloud"))
        assertTrue(provider.contains("trailingContent ="))
        assertTrue(provider.contains("MaterialTheme.typography.labelLarge"))
        assertTrue(provider.contains("color = MaterialTheme.colorScheme.primary"))
        assertTrue(provider.contains("AgoraDropdownMenu("))
        assertTrue(provider.contains("containerColor = MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(provider.contains("tonalElevation = 16.dp"))
        assertTrue(provider.contains("AgoraDropdownMenuItem("))
        assertTrue(provider.contains("if (index == draft.providerIndex)"))
        assertTrue(provider.contains("Icons.Default.Check"))
        assertTrue(provider.contains("Modifier.clickable { providerExpanded = true }"))
        assertTrue(provider.contains("onDismissRequest = { providerExpanded = false }"))
        assertTrue(provider.contains("R.string.embedding_provider_custom"))
        assertFalse(editor.contains("FilterChip("))
        assertFalse(editor.contains("FlowRow("))
    }

    @Test
    fun selectingProviderRetainsDraftResetAndInitialModelDefaults() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val provider = editor.section(
            "var providerExpanded by remember",
            "if (!isNew || preset.editableUrl)",
        )

        assertTrue(provider.contains("if (index != draft.providerIndex)"))
        assertFalse(provider.contains("testError"))
        assertTrue(provider.contains("providerIndex = index"))
        assertTrue(provider.contains("baseUrl = p.baseUrl"))
        assertTrue(provider.contains("modelName = p.models.firstOrNull().orEmpty()"))
        assertTrue(provider.contains("useSavedKey = true"))
        assertTrue(provider.contains("apiKey = \"\""))
        assertTrue(provider.contains("providerExpanded = false"))
        assertTrue(editor.contains("modelName = if (isNew) preset.models.firstOrNull().orEmpty() else initial.remoteModelName"))
    }

    @Test
    fun remoteModelUsesStarsAndOneHintedFieldWithoutPresetChips() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val model = editor.section(
            "title = stringResource(R.string.embedding_model_label)",
            "} else if (isNew) {\n                SettingsGroup(",
        )

        assertTrue(model.contains("SettingsIconContent(icon = Icons.Default.AutoAwesome)"))
        assertEquals(1, Regex("""\bMcpLabeledField\(""").findAll(model).count())
        assertTrue(model.contains("value = draft.modelName"))
        assertTrue(model.contains("placeholder = stringResource(R.string.embedding_model_id_hint)"))
        assertTrue(model.contains("ReadOnlyField("))
        assertTrue(model.contains("initial.remoteModelName"))
        assertFalse(model.contains("FilterChip("))
        assertFalse(model.contains("preset.models.forEach"))
    }

    @Test
    fun localAddShowsOnnxDownloadInsteadOfGgufImport() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val onnxGroup = editor.section(
            "} else if (isNew) {\n                SettingsGroup(",
            "title = stringResource(R.string.embedding_general)",
        )

        assertEquals(1, Regex("""R\.string\.embedding_model_file\b""").findAll(onnxGroup).count())
        assertTrue(onnxGroup.contains("title = stringResource(R.string.embedding_model_file)"))
        // The local option is ONNX-download now, not a GGUF file import.
        assertTrue(onnxGroup.contains("R.string.onnx_embed_title"))
        assertTrue(onnxGroup.contains("R.string.onnx_embed_download"))
        assertTrue(onnxGroup.contains("OnnxModelBootstrap.setup(context)"))
        assertTrue(onnxGroup.contains("OnnxEmbeddingProvider.reset()"))
        assertFalse(onnxGroup.contains("ModelImportItem("))
        assertFalse(onnxGroup.contains("filePicker"))
        assertFalse(editor.contains("importEmbeddingGguf"))
        assertFalse(editor.contains(".removeSuffix(\".gguf\")"))
    }

    @Test
    fun editCacheKeepsStableTitleStatusAndExistingActionSlot() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val cache = editor.section(
            "if (!isNew) {\n                SettingsGroup(",
            "if (showDocFab) Spacer",
        )

        assertTrue(cache.contains("headlineContent = { Text(stringResource(R.string.embedding_cache_status)) }"))
        assertTrue(cache.contains("supportingContent = { Text(embeddingCacheLabel(cacheRow) ?: \"-\") }"))
        assertTrue(cache.contains("EmbeddingCacheAction("))
        assertTrue(cache.contains("modelId = initial.id"))
        assertTrue(cache.contains("cacheRow = cacheRow"))
        assertTrue(cache.contains("size = rememberEmbeddingCacheActionSize()"))
        assertTrue(cache.contains("onCache = onCache"))
        assertTrue(cache.contains("onRecache = onRecache"))
    }

    @Test
    fun editorKeepsExplicitSaveAndUnsavedDraftBackGuard() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")

        assertTrue(editor.contains("IconButton(onClick = ::save, enabled = canSave)"))
        assertTrue(editor.contains("rememberUnsavedChangesBack(hasUnsavedChanges = draft != start || isImporting, onBack = leave)"))
        assertTrue(editor.contains("BackHandler { guardedBack() }"))
        assertTrue(editor.contains("onBack = guardedBack"))
        assertTrue(editor.contains("val lifecycle = remember(initial.id) { EmbeddingEditorLifecycle() }"))
        assertTrue(editor.contains("DisposableEffect(lifecycle)"))
        assertTrue(editor.contains("onDispose { lifecycle.close() }"))
        val leave = editor.section("val leave = {", "val guardedBack =")
        assertTrue(leave.indexOf("lifecycle.close()") >= 0)
        assertTrue(leave.indexOf("lifecycle.close()") < leave.indexOf("onBack()"))
        val save = editor.section("fun save()", "CollapsingSettingsScaffold(")
        assertTrue(save.contains("if (lifecycle.closed || !canSave) return"))
        // Local add now persists a LOCAL_ONNX config (no GGUF path / import transfer).
        assertTrue(save.contains("type = EmbeddingModelType.LOCAL_ONNX"))
        assertFalse(save.contains("lifecycle.imports.transfer(draft.localFilePath)"))
        assertTrue(save.contains("if (lifecycle.closed) return@testConnection"))
        assertFalse(editor.contains("importEmbeddingGguf(context, uri, lifecycle.imports)"))
        assertFalse(editor.contains("File(path).delete()"))
        val lifecycle = readSettingsSource("EmbeddingEditorLifecycle.kt")
        val close = lifecycle.section("override fun close()", "internal suspend fun importEmbeddingGguf")
        assertTrue(close.contains("closed = true"))
        assertTrue(close.contains("connectionTest?.cancel()"))
        assertTrue(close.contains("imports.close()"))
        assertTrue(lifecycle.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(lifecycle.contains("if (!closed) onResult(result)"))
        assertTrue(lifecycle.contains("copyLocalChatGguf(input, dest, imports)"))
    }

    private fun String.section(start: String, end: String): String {
        val startIndex = indexOf(start)
        assertTrue("Missing section start: $start", startIndex >= 0)
        val endIndex = indexOf(end, startIndex + start.length)
        assertTrue("Missing section end: $end", endIndex > startIndex)
        return substring(startIndex, endIndex)
    }

    private fun readSettingsSource(fileName: String): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = checkNotNull(cursor)
            val candidates = listOf(
                File(directory, "app/src/main/java/com/newoether/agora/ui/settings/$fileName"),
                File(directory, "src/main/java/com/newoether/agora/ui/settings/$fileName"),
            )
            candidates.firstOrNull(File::isFile)?.let { return it.readText().replace("\r\n", "\n") }
            cursor = directory.parentFile
        }
        error("Unable to locate Settings source: $fileName")
    }
}
