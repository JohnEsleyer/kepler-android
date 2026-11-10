package com.esper.android.ui.lexical

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.esper.android.ui.components.KaTeXMathView
import com.esper.android.ui.components.LocalReadOnlyMode
import com.esper.android.ui.components.PullToRefreshLayout
import com.esper.android.ui.theme.AmberGlow
import com.esper.android.ui.theme.BorderSlate
import com.esper.android.ui.theme.CosmicSlate
import com.esper.android.ui.theme.EmeraldSync
import com.esper.android.ui.theme.IndigoAccent
import com.esper.android.ui.theme.IndigoGlow
import com.esper.android.ui.theme.RoseRuby
import com.esper.android.ui.theme.SpaceBlack
import com.esper.android.ui.theme.SurfaceSlate
import com.esper.android.ui.theme.TextMuted
import com.esper.android.ui.theme.TextPrimary
import com.esper.android.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface LexicalAction {
    object AddH1 : LexicalAction
    object AddH2 : LexicalAction
    object AddH3 : LexicalAction
    object AddBold : LexicalAction
    object AddItalic : LexicalAction
    object AddStrike : LexicalAction
    object AddMathBlock : LexicalAction
    object AddInlineMath : LexicalAction
    object AddImage : LexicalAction
    object AddQuote : LexicalAction
    object AddCodeBlock : LexicalAction
    object AddBulletList : LexicalAction
    object AddNestedBulletList : LexicalAction
    object AddChecklist : LexicalAction
    object AddTable : LexicalAction
    object AddTimestamp : LexicalAction
    object AddDivider : LexicalAction
}

/**
 * Matches the `/code` slash command, optionally with a language tag
 * (`/code python`).
 */
private val SLASH_CODE_REGEX = Regex("^/code(?:\\s+([a-zA-Z0-9_+#.-]+))?$")

/**
 * Expands a `/code` (or `/code <language>`) line into a fenced code block.
 *
 * The block is created with an empty content line — the caret lands there so
 * the user can type the code straight away — and an escape paragraph is added
 * below the closing fence so the cursor can always leave the block again.
 *
 * Returns `null` when the text before the caret is not a slash command.
 */
internal fun expandSlashCode(fresh: TextFieldValue): TextFieldValue? {
    val text = fresh.text
    val cursorPos = fresh.selection.start
    if (cursorPos < 5) return null

    // Only the current line is considered: "/code" must be the whole line.
    val lineStart = text.lastIndexOf('\n', (cursorPos - 1).coerceAtLeast(0)) + 1
    val lineSegment = text.substring(lineStart, cursorPos)

    val match = SLASH_CODE_REGEX.matchEntire(lineSegment.trim()) ?: return null

    val language = match.groupValues.getOrNull(1)?.ifBlank { null } ?: "cpp"
    val prefix = text.substring(0, lineStart)
    val suffix = text.substring(cursorPos)

    // ```lang, an empty content line, the closing fence and an escape line.
    val insert = "```$language\n\n```\n\n"
    val newText = prefix + insert + suffix
    val cursorTarget = prefix.length + "```$language\n".length
    return TextFieldValue(newText, TextRange(cursorTarget))
}

/**
 * True when [text] ends with a fence that actually *closes* a code block.
 *
 * An even number of fence lines means the last one closes a block, so an
 * opening fence that the user is still typing is left alone.
 */
internal fun endsWithClosedFence(text: String): Boolean {
    if (text.isEmpty() || text.endsWith("\n")) return false
    if (text.substringAfterLast('\n').trim() != "```") return false
    val fenceCount = text.lines().count { it.trim().startsWith("```") }
    return fenceCount % 2 == 0
}

/**
 * Guarantees an escape paragraph below a code block that was typed by hand.
 *
 * When the markdown ends with a closing fence the caret would otherwise be
 * stuck on the fence line; appending a blank line gives the user somewhere to
 * write normal text again.
 */
internal fun ensureEscapeParagraph(fresh: TextFieldValue): TextFieldValue {
    if (!endsWithClosedFence(fresh.text)) return fresh
    val newText = fresh.text + "\n\n"
    return TextFieldValue(newText, TextRange(newText.length))
}

/**
 * `String.lines()` drops a trailing newline; this keeps it so a rewrite of
 * the markdown never silently drops the final line break.
 */
private fun trailingNewlineOf(text: String): String = if (text.endsWith("\n")) "\n" else ""

private val CodeBackground = Color(0xFF050811)
private val CodeHeaderBackground = Color(0xFF0B1120)
private val CodeTextColor = Color(0xFFE0E7FF)

@Composable
fun ComposeLexicalEditor(
    initialMarkdown: String,
    onContentChanged: ((String) -> Unit)? = null,
    /**
     * Reports dirty/clean transitions so the surrounding screen can guard
     * navigation before in-memory edits are lost.
     */
    onHasUnsavedChangesChanged: ((Boolean) -> Unit)? = null,
    /** Explicit save handler. Returns true when the server confirmed the write. */
    onSave: (suspend (String) -> Boolean)? = null,
    onTimestampClicked: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    readOnly: Boolean = LocalReadOnlyMode.current,
    /** Kept for API compatibility: the live editor is always tap-to-edit. */
    tapToEditAnywhere: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentOnContentChanged by rememberUpdatedState(onContentChanged)
    val currentOnHasUnsavedChangesChanged by rememberUpdatedState(onHasUnsavedChangesChanged)

    val textState = remember { mutableStateOf(initialMarkdown) }
    val rawText = textState.value

    var lastSavedText by remember { mutableStateOf(initialMarkdown) }
    var isSaving by remember { mutableStateOf(false) }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }
    val hasUnsavedChanges = rawText != lastSavedText

    // Real-time debounce auto-save (400ms after user stops typing)
    LaunchedEffect(rawText) {
        if (!readOnly && onSave != null && rawText != lastSavedText) {
            delay(400)
            isSaving = true
            try {
                onSave(rawText)
                lastSavedText = rawText
                saveStatusMessage = "Saved"
            } catch (_: Exception) {
            } finally {
                isSaving = false
            }
        }
    }

    // Let the host screen know whenever the draft becomes dirty/clean. This is
    // the single source of truth used by the navigation guards.
    LaunchedEffect(hasUnsavedChanges) {
        currentOnHasUnsavedChangesChanged?.invoke(hasUnsavedChanges)
    }

    val blocks = remember(rawText) { LexicalParser.parseToLexicalAst(rawText) }

    // Raw markdown remains available as an escape hatch for power users and
    // for constructs the block editor deliberately does not own (images,
    // tables, HTML). It is no longer how you normally edit a note.
    var isRawEditMode by remember { mutableStateOf(false) }

    var fieldValue by remember {
        mutableStateOf(TextFieldValue(initialMarkdown, TextRange(initialMarkdown.length)))
    }
    var isFieldFocused by remember { mutableStateOf(false) }

    // Exactly one block is in edit mode at a time ("tap a block to edit it").
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingCaret by remember { mutableStateOf<Int?>(null) }
    var focusLine by remember { mutableStateOf<Int?>(null) }

    // Document-level undo. One entry per burst of edits: typing a word is one
    // step, not one step per keystroke.
    val history = remember { LexicalHistory() }
    // Plain object, so the toolbar's enabled states need a nudge to recompose.
    var historyRevision by remember { mutableStateOf(0) }
    val canUndo = remember(historyRevision) { history.canUndo }
    val canRedo = remember(historyRevision) { history.canRedo }

    val listState = rememberLazyListState()
    val scrollState = rememberScrollState()
    // Source line a paste landed on; the list scrolls there once the document
    // has been re-parsed so the user sees what they just pasted.
    var scrollToLine by remember { mutableStateOf<Int?>(null) }

    val colors = remember { lexicalInlineColors() }
    // Shared with the focused field; the `:` strip is drawn at the bottom of
    // the screen so the IME cannot hide it.
    val emojiSuggestions = remember { EmojiSuggestionHost() }

    /** Snapshots the current document for undo, coalescing rapid edits. */
    fun recordHistory() {
        history.record(textState.value)
        historyRevision++
    }

    fun commitText(newText: String, recordHistory: Boolean = true) {
        if (readOnly) return
        if (newText == textState.value) return
        if (recordHistory) recordHistory()
        textState.value = newText
        saveStatusMessage = null
        currentOnContentChanged?.invoke(newText)
    }

    /** Swaps the document for a history snapshot. */
    fun applyHistory(text: String) {
        editingKey = null
        editingCaret = null
        focusLine = null
        historyRevision++
        fieldValue = TextFieldValue(text, TextRange(text.length))
        textState.value = text
        saveStatusMessage = null
        currentOnContentChanged?.invoke(text)
    }

    fun undo() {
        val previous = history.undo(textState.value) ?: return
        applyHistory(previous)
    }

    fun redo() {
        val next = history.redo(textState.value) ?: return
        applyHistory(next)
    }

    fun performManualSave() {
        if (readOnly || isSaving) return
        val save = onSave ?: return
        val textToSave = textState.value
        coroutineScope.launch {
            isSaving = true
            saveStatusMessage = null
            try {
                val serverConfirmed = save(textToSave)
                lastSavedText = textToSave
                saveStatusMessage = if (serverConfirmed) "Saved to cloud" else "Saved offline"
                val toast = if (serverConfirmed) {
                    "Saved to server"
                } else {
                    "Saved locally \u2014 server unreachable"
                }
                Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Save failed: ${e.localizedMessage ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isSaving = false
            }
        }
    }

    

    /** Writes the edited text of one block back into the document. */
    fun commitBlockText(block: LexicalBlockNode, text: String) {
        // `/code` typed on its own line becomes a real fenced block.
        if (block is LexicalParagraphNode) {
            val match = SLASH_CODE_REGEX.matchEntire(text.trim())
            if (match != null) {
                val language = match.groupValues.getOrNull(1)?.ifBlank { null } ?: "cpp"
                commitText(LexicalSource.withEditableText(textState.value, block, "```$language\n\n```"))
                return
            }
        }
        commitText(LexicalSource.withEditableText(textState.value, block, text))
    }

    fun commitSplit(block: LexicalBlockNode, text: String, offset: Int) {
        val result = LexicalSource.splitBlock(textState.value, block, text, offset) ?: return
        commitText(result.markdown)
        focusLine = result.focusLine
    }

    fun requestEdit(block: LexicalBlockNode, caret: Int) {
        if (readOnly) return
        editingCaret = caret
        editingKey = block.key
    }

    /** Leaves the inline field and scrolls the rendered result into view. */
    fun revealLine(line: Int) {
        editingKey = null
        editingCaret = null
        scrollToLine = line.coerceAtLeast(0)
    }

    /**
     * Markdown pasted while a block is being edited.
     *
     * The pasted text replaces the block's marker line rather than being
     * prefixed by it, so a whole ChatGPT answer becomes headings, lists,
     * fences and tables instead of a nested quote or one long paragraph.
     */
    fun handleMarkdownPaste(
        pastedContent: String,
        block: LexicalBlockNode,
        selection: TextRange,
        blockText: String
    ) {
        if (readOnly) return
        val updated = LexicalSource.insertMarkdownAtBlock(
            currentMarkdown = textState.value,
            block = block,
            pastedMarkdown = pastedContent,
            currentSelection = selection,
            currentBlockText = blockText
        )
        if (updated == textState.value) {
            // Nothing to insert, but still leave the field so it cannot be
            // latched in "paste handled" state.
            revealLine(block.lineStart)
            return
        }
        commitText(updated)
        fieldValue = TextFieldValue(updated, TextRange(updated.length))
        revealLine(block.lineStart)
    }

    /** Toolbar Paste: at the edited block when there is one, else at the end. */
    fun pasteClipboardText(clipText: String) {
        if (readOnly) return
        val anchor = blocks.firstOrNull { it.key == editingKey }
        val updated = if (anchor != null && LexicalSource.canEditInPlace(anchor)) {
            LexicalSource.insertMarkdownAtBlock(
                currentMarkdown = textState.value,
                block = anchor,
                pastedMarkdown = clipText
            )
        } else {
            LexicalSource.appendMarkdown(textState.value, clipText)
        }
        if (updated == textState.value) return
        commitText(updated)
        fieldValue = TextFieldValue(updated, TextRange(updated.length))
        revealLine(anchor?.lineStart ?: textState.value.count { it == '\n' })
    }

    /** Toolbar actions insert after the block being edited, or at the end. */
    fun commitToolbarAction(action: LexicalAction) {
        val snippet = actionSnippet(action)
        val anchor = blocks.firstOrNull { it.key == editingKey }
        val updated = if (anchor != null) {
            val lines = textState.value.split('\n').toMutableList()
            val at = (anchor.lineEnd + 1).coerceIn(0, lines.size)
            lines.addAll(at, snippet.split('\n'))
            lines.joinToString("\n")
        } else {
            val base = textState.value
            val separator = when {
                base.isEmpty() -> ""
                base.endsWith("\n") -> ""
                else -> "\n"
            }
            base + separator + snippet
        }
        commitText(updated)
        fieldValue = TextFieldValue(updated, TextRange(updated.length))
        editingKey = null
    }

    /** Remote/Room re-emissions: update the source of truth + rendered blocks. */
    fun renderExternal(text: String) {
        textState.value = text
        fieldValue = TextFieldValue(text, TextRange(text.length))
        editingKey = null
        // The document changed underneath us; undo across that boundary would
        // resurrect text from another device or an older snapshot.
        history.clear()
        historyRevision++
        saveStatusMessage = null
    }

    // External markdown only replaces the editor when there is nothing to
    // lose. Unsaved local edits stay put until the user saves or explicitly
    // pulls to refresh; background Room syncs can no longer clobber a draft.
    LaunchedEffect(initialMarkdown) {
        if (textState.value == initialMarkdown) {
            if (lastSavedText != initialMarkdown) lastSavedText = initialMarkdown
            return@LaunchedEffect
        }
        if (!hasUnsavedChanges) {
            renderExternal(initialMarkdown)
            lastSavedText = initialMarkdown
        }
    }

    // Always leave an editable paragraph at the end so there is somewhere to
    // continue writing after a list, code block or table.
    val displayBlocks = remember(blocks, rawText) {
        val list = blocks.toMutableList()
        if (list.lastOrNull() !is LexicalParagraphNode) {
            val line = rawText.split('\n').size
            list += LexicalParagraphNode(
                key = "p@tail",
                inlines = emptyList(),
                lineStart = line,
                lineEnd = line
            )
        }
        list
    }

    // Scroll to freshly pasted content once it exists as blocks.
    LaunchedEffect(scrollToLine, blocks) {
        val line = scrollToLine ?: return@LaunchedEffect
        val index = displayBlocks.indexOfFirst { it.lineStart >= line }
        if (index >= 0) listState.animateScrollToItem(index)
        scrollToLine = null
    }

    // After a split, hand editing over to the block that was just created so
    // the caret continues on the new line without any extra tap.
    LaunchedEffect(focusLine, blocks) {
        val line = focusLine ?: return@LaunchedEffect
        val target = blocks.firstOrNull { it.lineStart == line }
        if (target != null) {
            editingCaret = 0
            editingKey = target.key
        }
        focusLine = null
    }

    val canScrollDown by remember {
        derivedStateOf {
            if (isRawEditMode) scrollState.canScrollForward else listState.canScrollForward
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(SpaceBlack)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    false
                } else {
                    when (event.key) {
                        Key.S -> {
                            performManualSave()
                            true
                        }
                        Key.Z -> {
                            if (event.isShiftPressed) redo() else undo()
                            true
                        }
                        Key.Y -> {
                            redo()
                            true
                        }
                        else -> false
                    }
                }
            }
    ) {
        LexicalToolbar(
            isRawMode = isRawEditMode,
            readOnly = readOnly,
            isSaving = isSaving,
            hasUnsavedChanges = hasUnsavedChanges,
            saveConfirmation = saveStatusMessage,
            onSave = if (onSave != null) ({ performManualSave() }) else null,
            onToggleMode = {
                if (!isRawEditMode) {
                    editingKey = null
                    fieldValue = TextFieldValue(rawText, TextRange(rawText.length))
                }
                isRawEditMode = !isRawEditMode
            },
            onAction = { action -> commitToolbarAction(action) },
            onPaste = { clip -> pasteClipboardText(clip) },
            canUndo = canUndo,
            canRedo = canRedo,
            onUndo = { undo() },
            onRedo = { redo() }
        )

        HorizontalDivider(color = BorderSlate.copy(alpha = 0.5f), thickness = 1.dp)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val editorContent: @Composable () -> Unit = {
                AnimatedContent(
                    targetState = isRawEditMode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.fillMaxSize(),
                    label = "EditorEngineView"
                ) { isRaw ->
                    if (isRaw) {
                        RawMarkdownEditor(
                            value = fieldValue,
                            scrollState = scrollState,
                            onValueChange = { fresh ->
                                if (readOnly) return@RawMarkdownEditor
                                val processed = expandSlashCode(fresh) ?: ensureEscapeParagraph(fresh)
                                fieldValue = processed
                                commitText(processed.text)
                            },
                            readOnly = readOnly,
                            isFocused = { isFieldFocused = it }
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            itemsIndexed(displayBlocks, key = { _, block -> block.key }) { index, block ->
                                // Consecutive list items hug each other; everything
                                // else keeps the normal block rhythm.
                                val previous = displayBlocks.getOrNull(index - 1)
                                val gap = if (previous is LexicalListItemNode && block is LexicalListItemNode) {
                                    2.dp
                                } else {
                                    BlockVerticalGap
                                }
                                Column(modifier = Modifier.padding(top = gap)) {
                                    LiveBlock(
                                        block = block,
                                        markdown = rawText,
                                        readOnly = readOnly,
                                        colors = colors,
                                        isEditing = editingKey == block.key,
                                        editingCaret = editingCaret,
                                        focusLine = focusLine,
                                        tapToEditAnywhere = tapToEditAnywhere,
                                        suggestionsHost = emojiSuggestions,
                                        onPasteMarkdown = { pasted, target, selection, text ->
                                            handleMarkdownPaste(pasted, target, selection, text)
                                        },
                                        onRequestEdit = { target, caret -> requestEdit(target, caret) },
                                        onFocusLost = {
                                            if (editingKey == block.key) {
                                                editingKey = null
                                                editingCaret = null
                                            }
                                        },
                                        onFocusStateChanged = { isFieldFocused = it },
                                        onFocusConsumed = {
                                            if (focusLine == block.lineStart) focusLine = null
                                        },
                                        onTimestampClicked = onTimestampClicked,
                                        onToggleTask = { checked ->
                                            if (block is LexicalListItemNode) {
                                                commitText(LexicalSource.setTaskChecked(textState.value, block, checked))
                                            }
                                        },
                                        onTextCommitted = { text -> commitBlockText(block, text) },
                                        onSplit = { text, offset -> commitSplit(block, text, offset) },
                                        onDeleteBlock = { commitText(LexicalSource.removeBlock(textState.value, block)) },
                                        onChangeCodeLanguage = { language ->
                                            if (block is LexicalCodeBlockNode) {
                                                val lines = textState.value.split('\n').toMutableList()
                                                val fenceIndex = block.lineStart
                                                if (fenceIndex in lines.indices) {
                                                    val fence = lines[fenceIndex]
                                                    val indent = fence.takeWhile { it == ' ' || it == '\t' }
                                                    lines[fenceIndex] = indent + "```" + language
                                                    commitText(lines.joinToString("\n") + trailingNewlineOf(textState.value))
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            // Empty space under the note is a target: tapping it edits
                            // the trailing paragraph, which is where a paste lands.
                            item(key = "tail-space") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            val target = displayBlocks.lastOrNull() ?: return@clickable
                                            val length =
                                                LexicalSource.editableText(rawText, target).length
                                            requestEdit(target, length)
                                        }
                                )
                            }
                        }
                    }
                }
            }

            editorContent()

            // Jump to the end of a long note. It floats above the IME and
            // disappears once the user is already at the bottom.
            ScrollToBottomButton(
                visible = canScrollDown,
                onClick = {
                    coroutineScope.launch {
                        if (isRawEditMode) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        } else {
                            listState.animateScrollToItem(displayBlocks.size)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            )
        }

        // Pinned below the list (and above the keyboard) so it is always
        // reachable, even when the edited block is the last one on screen.
        EmojiSuggestionTray(host = emojiSuggestions)
    }
}

// ---------------------------------------------------------------------------
// Raw markdown escape hatch
// ---------------------------------------------------------------------------

@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = CosmicSlate.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, IndigoAccent.copy(alpha = 0.6f)),
            shadowElevation = 8.dp,
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom",
                    tint = IndigoGlow,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun RawMarkdownEditor(
    value: TextFieldValue,
    scrollState: androidx.compose.foundation.ScrollState,
    onValueChange: (TextFieldValue) -> Unit,
    readOnly: Boolean,
    isFocused: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (readOnly) TextSecondary else TextPrimary,
                lineHeight = 22.sp,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(IndigoAccent),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused(it.isFocused) }
        )
        Spacer(modifier = Modifier.height(240.dp))
    }
}

// ---------------------------------------------------------------------------
// Blocks
// ---------------------------------------------------------------------------

private val ParagraphSize = 14.sp
private val ParagraphLineHeight = 22.sp

@Composable
private fun LiveBlock(
    block: LexicalBlockNode,
    markdown: String,
    readOnly: Boolean,
    colors: LexicalInlineColors,
    isEditing: Boolean,
    editingCaret: Int?,
    focusLine: Int?,
    tapToEditAnywhere: Boolean,
    suggestionsHost: EmojiSuggestionHost,
    onPasteMarkdown: (String, LexicalBlockNode, TextRange, String) -> Unit,
    onRequestEdit: (LexicalBlockNode, Int) -> Unit,
    onFocusLost: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onFocusConsumed: () -> Unit,
    onTimestampClicked: (Int) -> Unit,
    onToggleTask: (Boolean) -> Unit,
    onTextCommitted: (String) -> Unit,
    onSplit: (String, Int) -> Unit,
    onDeleteBlock: () -> Unit,
    onChangeCodeLanguage: (String) -> Unit
) {
    when (block) {
        is LexicalBlankLineNode ->
            Spacer(modifier = Modifier.height((block.count * 10).dp))

        is LexicalHorizontalRuleNode -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(1.dp)
                .background(BorderSlate)
        )

        is LexicalHeadingNode -> {
            val (size, weight, color) = when (block.level) {
                1 -> Triple(24.sp, FontWeight.ExtraBold, Color.White)
                2 -> Triple(19.sp, FontWeight.Bold, Color(0xFFF1F5F9))
                3 -> Triple(16.sp, FontWeight.SemiBold, Color(0xFFE2E8F0))
                4 -> Triple(15.sp, FontWeight.SemiBold, Color(0xFFCBD5E1))
                5 -> Triple(14.sp, FontWeight.Medium, Color(0xFFB6C2D2))
                else -> Triple(13.sp, FontWeight.Medium, TextSecondary)
            }
            // Below h3 the sizes converge, so letter spacing carries the
            // remaining hierarchy instead of a wall of identical grey lines.
            val spacing = when (block.level) {
                5 -> 0.3.sp
                6 -> 0.8.sp
                else -> 0.sp
            }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                EditableBlockText(
                    block = block,
                    inlines = block.inlines,
                    editableText = LexicalSource.editableText(markdown, block),
                    textStyle = TextStyle(
                        fontSize = size,
                        fontWeight = weight,
                        color = color,
                        lineHeight = (size.value * 1.35f).sp,
                        letterSpacing = spacing
                    ),
                    spanBase = SpanStyle(
                        fontSize = size,
                        fontWeight = weight,
                        color = color,
                        letterSpacing = spacing
                    ),
                    readOnly = readOnly,
                    isEditing = isEditing,
                    editingCaret = editingCaret,
                    colors = colors,
                    placeholder = null,
                    onRequestEdit = onRequestEdit,
                    onFocusLost = onFocusLost,
                    onFocusStateChanged = onFocusStateChanged,
                    onTimestampClicked = onTimestampClicked,
                    suggestions = suggestionsHost,
                    onPasteMarkdown = onPasteMarkdown,
                    onTextCommitted = onTextCommitted,
                    onSplit = onSplit
                )
                if (block.level <= 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BorderSlate.copy(alpha = 0.8f))
                    )
                }
            }
        }

        is LexicalMathBlockNode -> MathBlock(
            block = block,
            readOnly = readOnly,
            isEditing = isEditing,
            editingCaret = editingCaret,
            colors = colors,
            onRequestEdit = onRequestEdit,
            onFocusLost = onFocusLost,
            onFocusStateChanged = onFocusStateChanged,
            onTextCommitted = onTextCommitted
        )

        is LexicalImageNode -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = CosmicSlate,
            border = BorderStroke(1.dp, BorderSlate.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                AsyncImage(
                    model = block.imageUrl,
                    contentDescription = block.altText,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                if (block.altText.isNotBlank()) {
                    Text(
                        text = block.altText,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
            }
        }

        is LexicalQuoteNode -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                .background(IndigoAccent.copy(alpha = 0.08f))
                .padding(vertical = 8.dp, horizontal = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 24.dp)
                    .background(IndigoAccent, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            EditableBlockText(
                block = block,
                inlines = block.inlines,
                editableText = LexicalSource.editableText(markdown, block),
                textStyle = TextStyle(
                    fontSize = ParagraphSize,
                    lineHeight = ParagraphLineHeight,
                    color = TextSecondary,
                    fontStyle = FontStyle.Italic
                ),
                spanBase = SpanStyle(
                    fontSize = ParagraphSize,
                    color = TextSecondary,
                    fontStyle = FontStyle.Italic
                ),
                readOnly = readOnly,
                isEditing = isEditing,
                editingCaret = editingCaret,
                colors = colors,
                placeholder = null,
                onRequestEdit = onRequestEdit,
                onFocusLost = onFocusLost,
                onFocusStateChanged = onFocusStateChanged,
                onTimestampClicked = onTimestampClicked,
                suggestions = suggestionsHost,
                onPasteMarkdown = onPasteMarkdown,
                onTextCommitted = onTextCommitted,
                onSplit = onSplit,
                modifier = Modifier.weight(1f)
            )
        }

        is LexicalCodeBlockNode -> LexicalCodeBlock(
            block = block,
            readOnly = readOnly,
            isEditing = isEditing,
            editingCaret = editingCaret,
            colors = colors,
            onRequestEdit = onRequestEdit,
            onFocusLost = onFocusLost,
            onFocusStateChanged = onFocusStateChanged,
            onTextCommitted = onTextCommitted,
            onDeleteBlock = onDeleteBlock,
            onChangeCodeLanguage = onChangeCodeLanguage
        )

        is LexicalListItemNode -> {
            val startPadding = (block.indentLevel * 18).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = startPadding, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (block.isTask) {
                    Checkbox(
                        checked = block.isChecked,
                        onCheckedChange = { checked -> if (!readOnly) onToggleTask(checked) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = IndigoAccent,
                            uncheckedColor = TextMuted
                        ),
                        modifier = Modifier
                            .size(26.dp)
                            .padding(end = 4.dp)
                    )
                } else if (block.isOrdered) {
                    // Cycle format by nesting level: 1. -> a. -> i.
                    val prefix = when (block.indentLevel % 3) {
                        1 -> "${('a'.code + ((block.indexNumber ?: 1) - 1) % 26).toChar()}."
                        2 -> roman(block.indexNumber ?: 1) + "."
                        else -> "${block.indexNumber ?: 1}."
                    }
                    Text(
                        text = prefix,
                        color = IndigoGlow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.width(30.dp)
                    )
                } else {
                    val bulletChar = when (block.indentLevel % 3) {
                        1 -> "\u25E6"
                        2 -> "\u25AA"
                        else -> "\u2022"
                    }
                    Text(
                        text = bulletChar,
                        color = if (block.indentLevel == 0) IndigoGlow else TextSecondary,
                        fontSize = if (bulletChar == "\u25AA") 11.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(20.dp)
                    )
                }

                EditableBlockText(
                    block = block,
                    inlines = block.inlines,
                    editableText = LexicalSource.editableText(markdown, block),
                    textStyle = TextStyle(
                        fontSize = ParagraphSize,
                        lineHeight = ParagraphLineHeight,
                        color = TextPrimary
                    ),
                    spanBase = SpanStyle(fontSize = ParagraphSize, color = TextPrimary),
                    readOnly = readOnly,
                    isEditing = isEditing,
                    editingCaret = editingCaret,
                    colors = colors,
                    placeholder = null,
                    onRequestEdit = onRequestEdit,
                    onFocusLost = onFocusLost,
                    onFocusStateChanged = onFocusStateChanged,
                    onTimestampClicked = onTimestampClicked,
                    suggestions = suggestionsHost,
                    onPasteMarkdown = onPasteMarkdown,
                    onTextCommitted = onTextCommitted,
                    onSplit = onSplit,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is LexicalTableNode -> LexicalTableBlock(block, colors, onTimestampClicked)

        is LexicalParagraphNode -> EditableBlockText(
            block = block,
            inlines = block.inlines,
            editableText = LexicalSource.editableText(markdown, block),
            textStyle = TextStyle(
                fontSize = ParagraphSize,
                lineHeight = ParagraphLineHeight,
                color = TextPrimary
            ),
            spanBase = SpanStyle(fontSize = ParagraphSize, color = TextPrimary),
            readOnly = readOnly,
            isEditing = isEditing,
            editingCaret = editingCaret,
            colors = colors,
            placeholder = if (block.plainText.isEmpty()) "Start writing\u2026" else null,
            onRequestEdit = onRequestEdit,
            onFocusLost = onFocusLost,
            onFocusStateChanged = onFocusStateChanged,
            onTimestampClicked = onTimestampClicked,
            suggestions = suggestionsHost,
            onPasteMarkdown = onPasteMarkdown,
            onTextCommitted = onTextCommitted,
            onSplit = onSplit
        )
    }
}

/**
 * Renders a block as formatted text and turns it into a live field in place
 * when tapped. That is the whole WYSIWYG story: the note is always rendered,
 * and editing happens exactly where the user is looking.
 */
@Composable
private fun EditableBlockText(
    block: LexicalBlockNode,
    inlines: List<LexicalInlineNode>,
    editableText: String,
    textStyle: TextStyle,
    spanBase: SpanStyle,
    readOnly: Boolean,
    isEditing: Boolean,
    editingCaret: Int?,
    colors: LexicalInlineColors,
    placeholder: String?,
    onRequestEdit: (LexicalBlockNode, Int) -> Unit,
    onFocusLost: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onTimestampClicked: (Int) -> Unit,
    onTextCommitted: (String) -> Unit,
    onSplit: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: EmojiSuggestionHost? = null,
    onPasteMarkdown: (String, LexicalBlockNode, TextRange, String) -> Unit = { _, _, _, _ -> }
) {
    if (!readOnly && isEditing) {
        LiveTextField(
            block = block,
            initialText = editableText,
            textStyle = textStyle,
            spanBase = spanBase,
            colors = colors,
            readOnly = false,
            requestFocus = true,
            onFocusConsumed = {},
            onTextCommitted = onTextCommitted,
            onSplit = onSplit,
            initialCaret = editingCaret,
            placeholder = placeholder,
            onFocusLost = onFocusLost,
            onFocusStateChanged = onFocusStateChanged,
            suggestions = suggestions,
            onPasteMarkdown = { pasted, selection, text ->
                onPasteMarkdown(pasted, block, selection, text)
            },
            modifier = modifier
        )
        return
    }

    val annotated = remember(inlines, colors, readOnly) {
        buildInlineText(
            inlines = inlines,
            base = spanBase,
            colors = colors,
            clickable = true,
            onTimestampClicked = onTimestampClicked
        )
    }

    if (annotated.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onRequestEdit(block, 0) }
        ) {
            if (placeholder != null) {
                Text(text = placeholder, style = textStyle.copy(color = colors.muted))
            }
        }
        return
    }

    var layout by remember(block.key) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = textStyle,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(block.key, editableText) {
                detectTapGestures { position ->
                    if (readOnly) return@detectTapGestures
                    val displayOffset = layout?.getOffsetForPosition(position) ?: editableText.length
                    onRequestEdit(block, LexicalInline.displayOffsetToRaw(editableText, displayOffset))
                }
            }
    )
}

// ---------------------------------------------------------------------------
// Code blocks
// ---------------------------------------------------------------------------

@Composable
private fun LexicalCodeBlock(
    block: LexicalCodeBlockNode,
    readOnly: Boolean,
    isEditing: Boolean,
    editingCaret: Int?,
    colors: LexicalInlineColors,
    onRequestEdit: (LexicalBlockNode, Int) -> Unit,
    onFocusLost: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onTextCommitted: (String) -> Unit,
    onDeleteBlock: () -> Unit,
    onChangeCodeLanguage: (String) -> Unit
) {
    val context = LocalContext.current
    var copied by remember(block.key) { mutableStateOf(false) }
    var isLanguageMenuOpen by remember(block.key) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        color = CodeTextColor
    )
    val codeSpan = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        color = CodeTextColor
    )

    Surface(
        shape = RoundedCornerShape(CodeBlockRadius),
        color = CodeBackground,
        border = BorderStroke(1.dp, BorderSlate.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header: language chip + copy + delete, like the web overlay.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeHeaderBackground)
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Text(
                        text = CodeSyntaxHighlighter.displayLanguageName(block.language),
                        color = IndigoGlow,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(IndigoAccent.copy(alpha = 0.16f))
                            .clickable(enabled = !readOnly) { isLanguageMenuOpen = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                    DropdownMenu(
                        expanded = isLanguageMenuOpen,
                        onDismissRequest = { isLanguageMenuOpen = false }
                    ) {
                        CodeSyntaxHighlighter.SUPPORTED_LANGUAGES.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = {
                                    isLanguageMenuOpen = false
                                    onChangeCodeLanguage(value)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "${block.code.lines().size} ln",
                    color = TextMuted.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp)
                )

                IconAction(
                    icon = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy code",
                    tint = if (copied) EmeraldSync else TextMuted,
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", block.code))
                        copied = true
                    }
                )

                if (!readOnly) {
                    IconAction(
                        icon = Icons.Default.Delete,
                        contentDescription = "Delete code block",
                        tint = TextMuted,
                        onClick = onDeleteBlock
                    )
                }
            }

            HorizontalDivider(color = BorderSlate.copy(alpha = 0.55f), thickness = 1.dp)

            if (!readOnly && isEditing) {
                LiveTextField(
                    block = block,
                    initialText = block.code,
                    textStyle = codeStyle,
                    spanBase = codeSpan,
                    colors = colors,
                    readOnly = false,
                    requestFocus = true,
                    onFocusConsumed = {},
                    onTextCommitted = onTextCommitted,
                    onSplit = { _, _ -> },
                    initialCaret = editingCaret,
                    useMarkdown = false,
                    codeLanguage = block.language,
                    showEmojiPicker = false,
                    onFocusLost = onFocusLost,
                    onFocusStateChanged = onFocusStateChanged,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            } else {
                Text(
                    text = CodeSyntaxHighlighter.highlight(block.code, block.language),
                    style = codeStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .pointerInput(block.key, readOnly) {
                            detectTapGestures {
                                if (!readOnly) onRequestEdit(block, block.code.length)
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Math
// ---------------------------------------------------------------------------

@Composable
private fun MathBlock(
    block: LexicalMathBlockNode,
    readOnly: Boolean,
    isEditing: Boolean,
    editingCaret: Int?,
    colors: LexicalInlineColors,
    onRequestEdit: (LexicalBlockNode, Int) -> Unit,
    onFocusLost: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onTextCommitted: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!readOnly && isEditing) {
            LiveTextField(
                block = block,
                initialText = block.latex,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = AmberGlow
                ),
                spanBase = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = AmberGlow
                ),
                colors = colors,
                readOnly = false,
                requestFocus = true,
                onFocusConsumed = {},
                onTextCommitted = onTextCommitted,
                onSplit = { _, _ -> },
                initialCaret = editingCaret,
                useMarkdown = false,
                showEmojiPicker = false,
                onFocusLost = onFocusLost,
                onFocusStateChanged = onFocusStateChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF14100A))
                    .padding(10.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(block.key, readOnly) {
                    detectTapGestures {
                        if (!readOnly) onRequestEdit(block, block.latex.length)
                    }
                }
        ) {
            KaTeXMathView(latex = block.latex, isDisplayMode = true)
        }
    }
}

// ---------------------------------------------------------------------------
// Tables
// ---------------------------------------------------------------------------

@Composable
private fun LexicalTableBlock(
    block: LexicalTableNode,
    colors: LexicalInlineColors,
    onTimestampClicked: (Int) -> Unit
) {
    val cellBase = SpanStyle(fontSize = 12.sp, color = TextPrimary)
    val headerBase = SpanStyle(fontSize = 11.sp, color = IndigoGlow, fontWeight = FontWeight.Bold)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CosmicSlate,
        border = BorderStroke(1.dp, BorderSlate),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSlate)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                block.headers.forEachIndexed { index, header ->
                    Text(
                        text = buildInlineText(
                            inlines = LexicalInline.toNodes(header),
                            base = headerBase,
                            colors = colors,
                            clickable = false,
                            onTimestampClicked = onTimestampClicked
                        ),
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        textAlign = block.alignmentAt(index).toTextAlign(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 3.dp)
                    )
                }
            }

            block.rows.forEachIndexed { rowIndex, row ->
                HorizontalDivider(color = BorderSlate.copy(alpha = 0.5f), thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (rowIndex % 2 == 0) Color.Transparent
                            else SurfaceSlate.copy(alpha = 0.35f)
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    row.forEachIndexed { index, cell ->
                        Text(
                            text = buildInlineText(
                                inlines = LexicalInline.toNodes(cell),
                                base = cellBase,
                                colors = colors,
                                clickable = true,
                                onTimestampClicked = onTimestampClicked
                            ),
                            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                            textAlign = block.alignmentAt(index).toTextAlign(),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun LexicalColumnAlignment.toTextAlign(): TextAlign = when (this) {
    LexicalColumnAlignment.CENTER -> TextAlign.Center
    LexicalColumnAlignment.RIGHT -> TextAlign.End
    else -> TextAlign.Start
}

private fun roman(value: Int): String {
    if (value <= 0 || value > 20) return value.toString()
    val numerals = listOf(
        10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i"
    )
    var remaining = value
    val builder = StringBuilder()
    numerals.forEach { (amount, symbol) ->
        while (remaining >= amount) {
            builder.append(symbol)
            remaining -= amount
        }
    }
    return builder.toString()
}

// ---------------------------------------------------------------------------
// Toolbar
// ---------------------------------------------------------------------------

@Composable
fun LexicalToolbar(
    isRawMode: Boolean,
    onToggleMode: () -> Unit,
    onAction: (LexicalAction) -> Unit,
    readOnly: Boolean = false,
    onPaste: ((String) -> Unit)? = null,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: (() -> Unit)? = null,
    onRedo: (() -> Unit)? = null,
    isSaving: Boolean = false,
    hasUnsavedChanges: Boolean = false,
    saveConfirmation: String? = null,
    onSave: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CosmicSlate)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = !isRawMode,
            onClick = onToggleMode,
            label = { Text(if (!isRawMode) "Live" else "Markdown", fontSize = 11.sp) },
            leadingIcon = {
                Icon(
                    if (!isRawMode) Icons.Default.Visibility else Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = IndigoAccent,
                selectedLabelColor = TextPrimary
            )
        )

        if (!readOnly && onSave != null) {
            Surface(
                onClick = { if (!isSaving) onSave() },
                enabled = !isSaving,
                shape = RoundedCornerShape(8.dp),
                color = when {
                    isSaving -> SurfaceSlate
                    hasUnsavedChanges -> EmeraldSync
                    else -> SurfaceSlate
                },
                border = if (hasUnsavedChanges && !isSaving) {
                    null
                } else {
                    BorderStroke(1.dp, BorderSlate)
                },
                modifier = Modifier.height(30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    when {
                        isSaving -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Text(
                                text = "Saving...",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        saveConfirmation != null && !hasUnsavedChanges -> {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = EmeraldSync,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = saveConfirmation,
                                color = EmeraldSync,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "Save",
                                tint = if (hasUnsavedChanges) Color.White else IndigoGlow,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Save",
                                color = if (hasUnsavedChanges) Color.White else IndigoGlow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (!readOnly) {
            if (onUndo != null && onRedo != null) {
                ToolbarBtn(
                    label = "Undo",
                    accent = if (canUndo) TextPrimary else TextMuted.copy(alpha = 0.4f),
                    enabled = canUndo,
                    onClick = onUndo
                )
                ToolbarBtn(
                    label = "Redo",
                    accent = if (canRedo) TextPrimary else TextMuted.copy(alpha = 0.4f),
                    enabled = canRedo,
                    onClick = onRedo
                )
            }
            if (onPaste != null) {
                ToolbarBtn(
                    label = "Paste",
                    accent = EmeraldSync,
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        if (!clip.isNullOrBlank()) onPaste(clip)
                    }
                )
            }
            ToolbarBtn(label = "H1", onClick = { onAction(LexicalAction.AddH1) })
            ToolbarBtn(label = "H2", onClick = { onAction(LexicalAction.AddH2) })
            ToolbarBtn(label = "B", fontWeight = FontWeight.Bold, onClick = { onAction(LexicalAction.AddBold) })
            ToolbarBtn(label = "I", fontStyle = FontStyle.Italic, onClick = { onAction(LexicalAction.AddItalic) })
            ToolbarBtn(label = "S", onClick = { onAction(LexicalAction.AddStrike) })
            ToolbarBtn(label = "KaTeX $$", accent = AmberGlow, onClick = { onAction(LexicalAction.AddMathBlock) })
            ToolbarBtn(label = "KaTeX $", accent = AmberGlow, onClick = { onAction(LexicalAction.AddInlineMath) })
            ToolbarBtn(label = "Image", accent = EmeraldSync, onClick = { onAction(LexicalAction.AddImage) })
            ToolbarBtn(label = "List", onClick = { onAction(LexicalAction.AddBulletList) })
            ToolbarBtn(label = "Sub-list", accent = IndigoGlow, onClick = { onAction(LexicalAction.AddNestedBulletList) })
            ToolbarBtn(label = "Task", onClick = { onAction(LexicalAction.AddChecklist) })
            ToolbarBtn(label = "Table", onClick = { onAction(LexicalAction.AddTable) })
            ToolbarBtn(label = "Code", onClick = { onAction(LexicalAction.AddCodeBlock) })
            ToolbarBtn(label = "> Quote", onClick = { onAction(LexicalAction.AddQuote) })
            ToolbarBtn(label = "[00:15]", accent = IndigoGlow, onClick = { onAction(LexicalAction.AddTimestamp) })
            ToolbarBtn(label = "---", onClick = { onAction(LexicalAction.AddDivider) })
        }
    }
}

@Composable
private fun ToolbarBtn(
    label: String,
    fontWeight: FontWeight = FontWeight.SemiBold,
    fontStyle: FontStyle = FontStyle.Normal,
    accent: Color = TextPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = SurfaceSlate,
        modifier = Modifier.height(30.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = accent,
                fontSize = 12.sp,
                fontWeight = fontWeight,
                fontStyle = fontStyle
            )
        }
    }
}

/** Markdown inserted by a toolbar action. */
internal fun actionSnippet(action: LexicalAction): String = when (action) {
    LexicalAction.AddH1 -> "# New Chapter Heading"
    LexicalAction.AddH2 -> "## New Sub-section"
    LexicalAction.AddH3 -> "### Topic Details"
    LexicalAction.AddBold -> "**bold statement**"
    LexicalAction.AddItalic -> "*italicized phrasing*"
    LexicalAction.AddStrike -> "~~strikethrough text~~"
    LexicalAction.AddMathBlock -> "$$\n\\oint \\mathbf{B} \\cdot d\\mathbf{l} = \\mu_0 I_{\\text{enc}}\n$$"
    LexicalAction.AddInlineMath -> "\$E = mc^2\$"
    LexicalAction.AddImage -> "![Kepler Ellipse](https://images.unsplash.com/photo-1614728894747-a83421e2b9c9?w=800)"
    LexicalAction.AddBulletList -> "- Top-level item"
    LexicalAction.AddNestedBulletList -> "  - Nested sub-item"
    LexicalAction.AddChecklist -> "- [ ] Unfinished task\n- [x] Verified item"
    LexicalAction.AddTable ->
        "| Parameter | Value | Unit |\n|---|---|---|\n| Semi-Major Axis (a) | 1.496e8 | km |\n| Eccentricity (e) | 0.0167 | - |"
    LexicalAction.AddCodeBlock ->
        "```python\ndef orbital_period(a, mu):\n    return 2 * math.pi * math.sqrt(a**3 / mu)\n```\n"
    LexicalAction.AddQuote -> "> Conservation of angular momentum yields constant areal velocity."
    LexicalAction.AddTimestamp -> "[00:15]"
    LexicalAction.AddDivider -> "---"
}
