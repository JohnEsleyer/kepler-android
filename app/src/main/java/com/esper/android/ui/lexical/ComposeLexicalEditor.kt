package com.esper.android.ui.lexical

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.esper.android.ui.components.KaTeXMathView
import com.esper.android.ui.components.LocalReadOnlyMode
import com.esper.android.ui.theme.*
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
    object AddSubpage : LexicalAction
}

/**
 * Custom minimalist vector icon: outline of a document with a folded top-right
 * corner and interior lines, rendered strictly in blue Kepler color (#60A5FA).
 */
@Composable
fun KeplerDocumentIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF60A5FA),
    strokeWidthDp: Float = 1.8f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val fold = w * 0.32f
        val corner = 3.dp.toPx()
        val sw = strokeWidthDp.dp.toPx()

        val docPath = Path().apply {
            moveTo(corner, 0f)
            lineTo(w - fold, 0f)
            lineTo(w, fold)
            lineTo(w, h - corner)
            quadraticTo(w, h, w - corner, h)
            lineTo(corner, h)
            quadraticTo(0f, h, 0f, h - corner)
            lineTo(0f, corner)
            quadraticTo(0f, 0f, corner, 0f)
            close()
        }

        drawPath(
            path = docPath,
            color = tint,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val foldFlap = Path().apply {
            moveTo(w - fold, 0f)
            lineTo(w - fold, fold)
            lineTo(w, fold)
        }
        drawPath(
            path = foldFlap,
            color = tint,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val linePad = w * 0.22f
        val line1Y = h * 0.44f
        val line2Y = h * 0.64f
        val lineW = w * 0.54f

        drawLine(
            color = tint.copy(alpha = 0.75f),
            start = Offset(linePad, line1Y),
            end = Offset(linePad + lineW, line1Y),
            strokeWidth = sw * 0.85f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint.copy(alpha = 0.75f),
            start = Offset(linePad, line2Y),
            end = Offset(linePad + lineW * 0.65f, line2Y),
            strokeWidth = sw * 0.85f,
            cap = StrokeCap.Round
        )
    }
}

private val SLASH_CODE_REGEX = Regex("^/code(?:\\s+([a-zA-Z0-9_+#.-]+))?$")

internal fun expandSlashCode(fresh: TextFieldValue): TextFieldValue? {
    val text = fresh.text
    val cursorPos = fresh.selection.start
    if (cursorPos < 5) return null
    val lineStart = text.lastIndexOf('\n', (cursorPos - 1).coerceAtLeast(0)) + 1
    val lineSegment = text.substring(lineStart, cursorPos)
    val match = SLASH_CODE_REGEX.matchEntire(lineSegment.trim()) ?: return null
    val language = match.groupValues.getOrNull(1)?.ifBlank { null } ?: "cpp"
    val prefix = text.substring(0, lineStart)
    val suffix = text.substring(cursorPos)
    val insert = "```$language\n\n```\n\n"
    val newText = prefix + insert + suffix
    val cursorTarget = prefix.length + "```$language\n".length
    return TextFieldValue(newText, TextRange(cursorTarget))
}

internal fun endsWithClosedFence(text: String): Boolean {
    if (text.isEmpty() || text.endsWith("\n")) return false
    if (text.substringAfterLast('\n').trim() != "```") return false
    val fenceCount = text.lines().count { it.trim().startsWith("```") }
    return fenceCount % 2 == 0
}

internal fun ensureEscapeParagraph(fresh: TextFieldValue): TextFieldValue {
    if (!endsWithClosedFence(fresh.text)) return fresh
    val newText = fresh.text + "\n\n"
    return TextFieldValue(newText, TextRange(newText.length))
}

private fun trailingNewlineOf(text: String): String = if (text.endsWith("\n")) "\n" else ""

private val CodeBackground = Color(0xFF050811)
private val CodeHeaderBackground = Color(0xFF0B1120)
private val CodeTextColor = Color(0xFFE0E7FF)

@Composable
fun ComposeLexicalEditor(
    initialMarkdown: String,
    onContentChanged: ((String) -> Unit)? = null,
    onHasUnsavedChangesChanged: ((Boolean) -> Unit)? = null,
    onSave: (suspend (String) -> Boolean)? = null,
    onTimestampClicked: (Int) -> Unit = {},
    onLinkClicked: ((String) -> Unit)? = null,
    onCreateSubpage: ((title: String) -> String?)? = null,
    modifier: Modifier = Modifier,
    readOnly: Boolean = LocalReadOnlyMode.current,
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

    var editingKey by remember { mutableStateOf<String?>(null) }
    var activeBlockEditBuffer by remember { mutableStateOf("") }

    // Auto-save ONLY runs when the user is NOT actively editing a block.
    // This prevents IME interruption, cursor jumps, and premature saves.
    LaunchedEffect(rawText, editingKey) {
        if (!readOnly && onSave != null && editingKey == null && rawText != lastSavedText) {
            delay(500)
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

    LaunchedEffect(hasUnsavedChanges) {
        currentOnHasUnsavedChangesChanged?.invoke(hasUnsavedChanges)
    }

    val blocks = remember(rawText) { LexicalParser.parseToLexicalAst(rawText) }

    var isRawEditMode by remember { mutableStateOf(false) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(initialMarkdown, TextRange(initialMarkdown.length)))
    }

    val history = remember { LexicalHistory() }
    var historyRevision by remember { mutableStateOf(0) }
    val canUndo = remember(historyRevision) { history.canUndo }
    val canRedo = remember(historyRevision) { history.canRedo }

    val listState = rememberLazyListState()
    val scrollState = rememberScrollState()
    val colors = remember { lexicalInlineColors() }

    fun recordHistory() {
        history.record(textState.value)
        historyRevision++
    }

    fun commitText(newText: String, record: Boolean = true) {
        if (readOnly) return
        if (newText == textState.value) return
        if (record) recordHistory()
        textState.value = newText
        saveStatusMessage = null
        currentOnContentChanged?.invoke(newText)
    }

    fun applyHistory(text: String) {
        editingKey = null
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
                Toast.makeText(context, if (serverConfirmed) "Saved" else "Saved locally", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.localizedMessage ?: "Error"}", Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }

    fun commitBlockText(block: LexicalBlockNode, text: String) {
        val match = SLASH_CODE_REGEX.matchEntire(text.trim())
        if (match != null) {
            val language = match.groupValues.getOrNull(1)?.ifBlank { null } ?: "cpp"
            commitText(LexicalSource.withEditableText(textState.value, block, "```$language\n\n```"))
            return
        }
        val pageMatch = Regex("""^/page(?:\s+(.+))?$""").matchEntire(text.trim())
        if (pageMatch != null && onCreateSubpage != null) {
            val subTitle = pageMatch.groupValues.getOrNull(1)?.ifBlank { null } ?: "Subpage"
            val newId = onCreateSubpage(subTitle)
            if (newId != null) {
                commitText(LexicalSource.withEditableText(textState.value, block, "[$subTitle](kepler-page://$newId)"))
                return
            }
        }
        commitText(LexicalSource.withEditableText(textState.value, block, text))
    }

    fun finishEditingCurrentBlock() {
        val curKey = editingKey ?: return
        val active = blocks.find { it.key == curKey }
        if (active != null && activeBlockEditBuffer != LexicalSource.editableText(textState.value, active)) {
            commitBlockText(active, activeBlockEditBuffer)
        }
        editingKey = null
    }

    fun commitToolbarAction(action: LexicalAction) {
        finishEditingCurrentBlock()
        if (action is LexicalAction.AddSubpage) {
            val newId = onCreateSubpage?.invoke("Subpage") ?: ("page-" + java.util.UUID.randomUUID().toString().take(4))
            val snippet = "\n\n[Subpage](kepler-page://$newId)\n\n"
            val updated = textState.value.trimEnd() + snippet
            commitText(updated)
            fieldValue = TextFieldValue(updated, TextRange(updated.length))
            editingKey = null
            return
        }
        val snippet = actionSnippet(action)
        val base = textState.value
        val separator = if (base.isEmpty() || base.endsWith("\n")) "" else "\n\n"
        val updated = base + separator + snippet
        commitText(updated)
        fieldValue = TextFieldValue(updated, TextRange(updated.length))
        editingKey = null
    }

    LaunchedEffect(initialMarkdown) {
        if (textState.value == initialMarkdown) {
            if (lastSavedText != initialMarkdown) lastSavedText = initialMarkdown
            return@LaunchedEffect
        }
        if (!hasUnsavedChanges) {
            textState.value = initialMarkdown
            fieldValue = TextFieldValue(initialMarkdown, TextRange(initialMarkdown.length))
            editingKey = null
            history.clear()
            historyRevision++
            saveStatusMessage = null
            lastSavedText = initialMarkdown
        }
    }

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
                    finishEditingCurrentBlock()
                    fieldValue = TextFieldValue(rawText, TextRange(rawText.length))
                }
                isRawEditMode = !isRawEditMode
            },
            onAction = { action -> commitToolbarAction(action) },
            onPaste = { clip ->
                finishEditingCurrentBlock()
                val updated = LexicalSource.appendMarkdown(textState.value, clip)
                commitText(updated)
                fieldValue = TextFieldValue(updated, TextRange(updated.length))
            },
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
                .pointerInput(editingKey) {
                    detectTapGestures {
                        if (editingKey != null) {
                            finishEditingCurrentBlock()
                        }
                    }
                }
        ) {
            AnimatedContent(
                targetState = isRawEditMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.fillMaxSize(),
                label = "EditorEngineView"
            ) { isRaw ->
                if (isRaw) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = { fresh ->
                                if (readOnly) return@BasicTextField
                                val processed = expandSlashCode(fresh) ?: ensureEscapeParagraph(fresh)
                                fieldValue = processed
                                commitText(processed.text)
                            },
                            readOnly = readOnly,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = if (readOnly) TextSecondary else TextPrimary,
                                lineHeight = 22.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(IndigoAccent),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(240.dp))
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        itemsIndexed(displayBlocks, key = { _, block -> block.key }) { index, block ->
                            val previous = displayBlocks.getOrNull(index - 1)
                            val gap = if (previous is LexicalListItemNode && block is LexicalListItemNode) {
                                2.dp
                            } else {
                                BlockVerticalGap
                            }

                            Column(modifier = Modifier.padding(top = gap)) {
                                BlockComponent(
                                    block = block,
                                    markdown = rawText,
                                    readOnly = readOnly,
                                    colors = colors,
                                    isEditing = (editingKey == block.key),
                                    onStartEditing = {
                                        finishEditingCurrentBlock()
                                        activeBlockEditBuffer = LexicalSource.editableText(rawText, block)
                                        editingKey = block.key
                                    },
                                    onEditBufferChanged = { fresh ->
                                        activeBlockEditBuffer = fresh
                                    },
                                    onLinkClicked = { url ->
                                        onLinkClicked?.invoke(url)
                                    },
                                    onTimestampClicked = onTimestampClicked,
                                    onToggleTask = { checked ->
                                        if (block is LexicalListItemNode) {
                                            commitText(LexicalSource.setTaskChecked(textState.value, block, checked))
                                        }
                                    },
                                    onChangeCodeLanguage = { lang ->
                                        if (block is LexicalCodeBlockNode) {
                                            val lines = textState.value.split('\n').toMutableList()
                                            val fenceIndex = block.lineStart
                                            if (fenceIndex in lines.indices) {
                                                val fence = lines[fenceIndex]
                                                val indent = fence.takeWhile { it == ' ' || it == '\t' }
                                                lines[fenceIndex] = indent + "```" + lang
                                                commitText(lines.joinToString("\n") + trailingNewlineOf(textState.value))
                                            }
                                        }
                                    }
                                )
                            }
                        }

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
                                        finishEditingCurrentBlock()
                                        activeBlockEditBuffer = LexicalSource.editableText(rawText, target)
                                        editingKey = target.key
                                    }
                            )
                        }
                    }
                }
            }

            // Fixed vertical-center hovering toolset that only appears during active block editing
            if (editingKey != null && !readOnly) {
                val currentEditingBlock = displayBlocks.find { it.key == editingKey }
                if (currentEditingBlock != null) {
                    BlockActionToolset(
                        onDone = { finishEditingCurrentBlock() },
                        onAddBelow = {
                            finishEditingCurrentBlock()
                            val updated = LexicalSource.insertBlockBelow(textState.value, currentEditingBlock)
                            commitText(updated)
                            coroutineScope.launch {
                                delay(80)
                                val refreshed = LexicalParser.parseToLexicalAst(textState.value)
                                val nextIndex = refreshed.indexOfFirst { it.lineStart > currentEditingBlock.lineEnd }
                                val target = refreshed.getOrNull(nextIndex)
                                if (target != null) {
                                    activeBlockEditBuffer = LexicalSource.editableText(textState.value, target)
                                    editingKey = target.key
                                }
                            }
                        },
                        onDelete = {
                            commitText(LexicalSource.removeBlock(textState.value, currentEditingBlock))
                            editingKey = null
                        },
                        onMoveUp = {
                            finishEditingCurrentBlock()
                            commitText(LexicalSource.moveBlockUp(textState.value, currentEditingBlock))
                        },
                        onMoveDown = {
                            finishEditingCurrentBlock()
                            commitText(LexicalSource.moveBlockDown(textState.value, currentEditingBlock))
                        },
                        onTransform = { targetType ->
                            finishEditingCurrentBlock()
                            commitText(LexicalSource.transformBlock(textState.value, currentEditingBlock, targetType))
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                    )
                }
            }

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
    }
}

/**
 * Universal Block Component Container:
 * Dual-state architecture separating frictionless display & interactive clicks
 * from isolated raw block editing.
 */
@Composable
private fun BlockComponent(
    block: LexicalBlockNode,
    markdown: String,
    readOnly: Boolean,
    colors: LexicalInlineColors,
    isEditing: Boolean,
    onStartEditing: () -> Unit,
    onEditBufferChanged: (String) -> Unit,
    onLinkClicked: (String) -> Unit,
    onTimestampClicked: (Int) -> Unit,
    onToggleTask: (Boolean) -> Unit,
    onChangeCodeLanguage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isEditing && !readOnly) {
        val initialText = remember(block.key) { LexicalSource.editableText(markdown, block) }
        var editBuffer by remember(block.key) {
            mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
        }

        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(isEditing) {
            if (isEditing) {
                delay(60)
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceSlate.copy(alpha = 0.55f),
            border = BorderStroke(1.5.dp, IndigoAccent.copy(alpha = 0.85f)),
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(vertical = 4.dp)
                .padding(end = 56.dp) // Leave room for fixed center-right toolset
        ) {
            BasicTextField(
                value = editBuffer,
                onValueChange = { fresh ->
                    editBuffer = fresh
                    onEditBufferChanged(fresh.text)
                },
                textStyle = TextStyle(
                    fontFamily = if (block is LexicalCodeBlockNode || block is LexicalMathBlockNode) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp
                ),
                cursorBrush = SolidColor(IndigoAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(12.dp)
                    .focusRequester(focusRequester)
            )
        }
        return
    }

    when (block) {
        is LexicalBlankLineNode -> Spacer(modifier = Modifier.height((block.count * 10).dp))

        is LexicalHorizontalRuleNode -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(1.dp)
                .background(BorderSlate)
        )

        is LexicalSubpageBlockNode -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate),
                modifier = modifier
                    .fillMaxWidth()
                    .pointerInput(block.key, readOnly) {
                        detectTapGestures(
                            onTap = {
                                onLinkClicked("kepler-page://${block.pageId}")
                            },
                            onLongPress = {
                                if (!readOnly) onStartEditing()
                            }
                        )
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeplerDocumentIcon(modifier = Modifier.size(24.dp), tint = Color(0xFF60A5FA))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = block.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!readOnly) {
                        IconButton(
                            onClick = onStartEditing,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Subpage", tint = TextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Navigate",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        is LexicalHeadingNode -> {
            val (size, weight, color) = when (block.level) {
                1 -> Triple(24.sp, FontWeight.ExtraBold, Color.White)
                2 -> Triple(19.sp, FontWeight.Bold, Color(0xFFF1F5F9))
                3 -> Triple(16.sp, FontWeight.SemiBold, Color(0xFFE2E8F0))
                4 -> Triple(15.sp, FontWeight.SemiBold, Color(0xFFCBD5E1))
                5 -> Triple(14.sp, FontWeight.Medium, Color(0xFFB6C2D2))
                else -> Triple(13.sp, FontWeight.Medium, TextSecondary)
            }
            val spacing = if (block.level == 6) 0.8.sp else 0.sp

            Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                RenderedBlockText(
                    annotated = remember(block.inlines, colors) {
                        buildInlineText(
                            inlines = block.inlines,
                            base = SpanStyle(fontSize = size, fontWeight = weight, color = color, letterSpacing = spacing),
                            colors = colors,
                            clickable = true,
                            onLink = onLinkClicked,
                            onTimestampClicked = onTimestampClicked
                        )
                    },
                    textStyle = TextStyle(fontSize = size, fontWeight = weight, color = color, lineHeight = (size.value * 1.35f).sp, letterSpacing = spacing),
                    readOnly = readOnly,
                    onStartEditing = onStartEditing,
                    onLinkClicked = onLinkClicked,
                    onTimestampClicked = onTimestampClicked
                )
                if (block.level <= 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderSlate.copy(alpha = 0.8f)))
                }
            }
        }

        is LexicalMathBlockNode -> {
            Column(modifier = modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(block.key, readOnly) {
                            detectTapGestures(
                                onLongPress = { if (!readOnly) onStartEditing() }
                            )
                        }
                ) {
                    KaTeXMathView(latex = block.latex, isDisplayMode = true)
                    if (!readOnly) {
                        IconButton(
                            onClick = onStartEditing,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit LaTeX", tint = TextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        is LexicalImageNode -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CosmicSlate,
                border = BorderStroke(1.dp, BorderSlate.copy(alpha = 0.7f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    AsyncImage(
                        model = block.imageUrl,
                        contentDescription = block.altText,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp))
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
        }

        is LexicalQuoteNode -> {
            Row(
                modifier = modifier
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
                RenderedBlockText(
                    annotated = remember(block.inlines, colors) {
                        buildInlineText(
                            inlines = block.inlines,
                            base = SpanStyle(fontSize = 14.sp, color = TextSecondary, fontStyle = FontStyle.Italic),
                            colors = colors,
                            clickable = true,
                            onLink = onLinkClicked,
                            onTimestampClicked = onTimestampClicked
                        )
                    },
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = TextSecondary, fontStyle = FontStyle.Italic),
                    readOnly = readOnly,
                    onStartEditing = onStartEditing,
                    onLinkClicked = onLinkClicked,
                    onTimestampClicked = onTimestampClicked,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is LexicalCodeBlockNode -> {
            LexicalCodeBlock(
                block = block,
                readOnly = readOnly,
                onStartEditing = onStartEditing
            )
        }

        is LexicalListItemNode -> {
            val startPadding = (block.indentLevel * 18).dp
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(start = startPadding, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (block.isTask) {
                    Checkbox(
                        checked = block.isChecked,
                        onCheckedChange = { checked -> if (!readOnly) onToggleTask(checked) },
                        colors = CheckboxDefaults.colors(checkedColor = IndigoAccent, uncheckedColor = TextMuted),
                        modifier = Modifier.size(26.dp).padding(end = 4.dp)
                    )
                } else if (block.isOrdered) {
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

                RenderedBlockText(
                    annotated = remember(block.inlines, colors) {
                        buildInlineText(
                            inlines = block.inlines,
                            base = SpanStyle(fontSize = 14.sp, color = TextPrimary),
                            colors = colors,
                            clickable = true,
                            onLink = onLinkClicked,
                            onTimestampClicked = onTimestampClicked
                        )
                    },
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = TextPrimary),
                    readOnly = readOnly,
                    onStartEditing = onStartEditing,
                    onLinkClicked = onLinkClicked,
                    onTimestampClicked = onTimestampClicked,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is LexicalTableNode -> LexicalTableBlock(block, colors, onTimestampClicked)

        is LexicalParagraphNode -> {
            RenderedBlockText(
                annotated = remember(block.inlines, colors) {
                    buildInlineText(
                        inlines = block.inlines,
                        base = SpanStyle(fontSize = 14.sp, color = TextPrimary),
                        colors = colors,
                        clickable = true,
                        onLink = onLinkClicked,
                        onTimestampClicked = onTimestampClicked
                    )
                },
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = TextPrimary),
                readOnly = readOnly,
                placeholder = if (block.plainText.isEmpty()) "Start writing..." else null,
                onStartEditing = onStartEditing,
                onLinkClicked = onLinkClicked,
                onTimestampClicked = onTimestampClicked,
                modifier = modifier
            )
        }
    }
}

/**
 * Text renderer that executes link & subpage annotations on single tap,
 * while leaving plain prose un-editable unless long-pressed or double-tapped.
 */
@Composable
private fun RenderedBlockText(
    annotated: AnnotatedString,
    textStyle: TextStyle,
    readOnly: Boolean,
    onStartEditing: () -> Unit,
    onLinkClicked: (String) -> Unit,
    onTimestampClicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null
) {
    if (annotated.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (!readOnly) onStartEditing()
                }
        ) {
            if (placeholder != null) {
                Text(text = placeholder, style = textStyle.copy(color = TextMuted))
            }
        }
        return
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotated,
        style = textStyle,
        onTextLayout = { layoutResult = it },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(annotated, readOnly) {
                detectTapGestures(
                    onTap = { pos ->
                        val layout = layoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)

                        val link = annotated.getLinkAnnotations(offset, offset).firstOrNull()
                        if (link != null) {
                            val url = (link.item as? LinkAnnotation.Url)?.url
                                ?: (link.item as? LinkAnnotation.Clickable)?.tag?.removePrefix("link:")
                            if (url != null) {
                                onLinkClicked(url)
                                return@detectTapGestures
                            }
                        }

                        val ts = annotated.getStringAnnotations("ts", offset, offset).firstOrNull()
                        if (ts != null) {
                            ts.item.toIntOrNull()?.let { onTimestampClicked(it) }
                            return@detectTapGestures
                        }
                    },
                    onLongPress = {
                        if (!readOnly) onStartEditing()
                    },
                    onDoubleTap = {
                        if (!readOnly) onStartEditing()
                    }
                )
            }
    )
}

/**
 * Centered vertical right-hand side action toolset hovering permanently during editing.
 */
@Composable
private fun BlockActionToolset(
    onDone: () -> Unit,
    onAddBelow: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTransform: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTransformMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CosmicSlate,
        border = BorderStroke(1.dp, BorderSlate),
        shadowElevation = 10.dp,
        modifier = modifier.width(46.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = onDone,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = "Done", tint = EmeraldSync, modifier = Modifier.size(20.dp))
            }

            IconButton(
                onClick = onAddBelow,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Block Below", tint = IndigoAccent, modifier = Modifier.size(20.dp))
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Block", tint = RoseRuby, modifier = Modifier.size(19.dp))
            }

            IconButton(
                onClick = onMoveUp,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }

            IconButton(
                onClick = onMoveDown,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }

            Box {
                IconButton(
                    onClick = { showTransformMenu = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Turn Into", tint = IndigoGlow, modifier = Modifier.size(19.dp))
                }

                DropdownMenu(
                    expanded = showTransformMenu,
                    onDismissRequest = { showTransformMenu = false },
                    modifier = Modifier.background(CosmicSlate)
                ) {
                    DropdownMenuItem(text = { Text("Paragraph", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("paragraph") })
                    DropdownMenuItem(text = { Text("Heading 1 (#)", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("h1") })
                    DropdownMenuItem(text = { Text("Heading 2 (##)", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("h2") })
                    DropdownMenuItem(text = { Text("Heading 3 (###)", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("h3") })
                    DropdownMenuItem(text = { Text("Bullet List (-)", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("bullet") })
                    DropdownMenuItem(text = { Text("Task Checkbox (- [ ])", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("task") })
                    DropdownMenuItem(text = { Text("Quote (>)", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("quote") })
                    DropdownMenuItem(text = { Text("Code Block (```)", color = TextPrimary, fontSize = 12.sp) }, onClick = { showTransformMenu = false; onTransform("code") })
                }
            }
        }
    }
}

@Composable
private fun LexicalCodeBlock(
    block: LexicalCodeBlockNode,
    readOnly: Boolean,
    onStartEditing: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember(block.key) { mutableStateOf(false) }

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

    Surface(
        shape = RoundedCornerShape(CodeBlockRadius),
        color = CodeBackground,
        border = BorderStroke(1.dp, BorderSlate.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeHeaderBackground)
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = CodeSyntaxHighlighter.displayLanguageName(block.language),
                    color = IndigoGlow,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(IndigoAccent.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "${block.code.lines().size} ln",
                    color = TextMuted.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp)
                )

                if (!readOnly) {
                    IconAction(
                        icon = Icons.Default.Edit,
                        contentDescription = "Edit Code",
                        tint = IndigoGlow,
                        onClick = onStartEditing
                    )
                }

                IconAction(
                    icon = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy code",
                    tint = if (copied) EmeraldSync else TextMuted,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", block.code))
                        copied = true
                    }
                )
            }

            HorizontalDivider(color = BorderSlate.copy(alpha = 0.55f), thickness = 1.dp)

            Text(
                text = CodeSyntaxHighlighter.highlight(block.code, block.language),
                style = codeStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .pointerInput(block.key, readOnly) {
                        detectTapGestures(
                            onLongPress = { if (!readOnly) onStartEditing() }
                        )
                    }
            )
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
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(14.dp))
    }
}

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
                        modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
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
                            modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
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
    val numerals = listOf(10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
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
                border = if (hasUnsavedChanges && !isSaving) null else BorderStroke(1.dp, BorderSlate),
                modifier = Modifier.height(30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    when {
                        isSaving -> {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                            Text("Saving...", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        saveConfirmation != null && !hasUnsavedChanges -> {
                            Icon(Icons.Default.Check, contentDescription = null, tint = EmeraldSync, modifier = Modifier.size(13.dp))
                            Text(saveConfirmation, color = EmeraldSync, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        else -> {
                            Icon(Icons.Default.Save, contentDescription = "Save", tint = if (hasUnsavedChanges) Color.White else IndigoGlow, modifier = Modifier.size(13.dp))
                            Text("Save", color = if (hasUnsavedChanges) Color.White else IndigoGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (!readOnly) {
            if (onUndo != null && onRedo != null) {
                ToolbarBtn(label = "Undo", accent = if (canUndo) TextPrimary else TextMuted.copy(alpha = 0.4f), enabled = canUndo, onClick = onUndo)
                ToolbarBtn(label = "Redo", accent = if (canRedo) TextPrimary else TextMuted.copy(alpha = 0.4f), enabled = canRedo, onClick = onRedo)
            }
            if (onPaste != null) {
                ToolbarBtn(
                    label = "Paste",
                    accent = EmeraldSync,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!clip.isNullOrBlank()) onPaste(clip)
                    }
                )
            }
            ToolbarBtn(label = "+ /page", accent = Color(0xFF60A5FA), onClick = { onAction(LexicalAction.AddSubpage) })
            ToolbarBtn(label = "H1", onClick = { onAction(LexicalAction.AddH1) })
            ToolbarBtn(label = "H2", onClick = { onAction(LexicalAction.AddH2) })
            ToolbarBtn(label = "B", fontWeight = FontWeight.Bold, onClick = { onAction(LexicalAction.AddBold) })
            ToolbarBtn(label = "I", fontStyle = FontStyle.Italic, onClick = { onAction(LexicalAction.AddItalic) })
            ToolbarBtn(label = "KaTeX $$", accent = AmberGlow, onClick = { onAction(LexicalAction.AddMathBlock) })
            ToolbarBtn(label = "List", onClick = { onAction(LexicalAction.AddBulletList) })
            ToolbarBtn(label = "Task", onClick = { onAction(LexicalAction.AddChecklist) })
            ToolbarBtn(label = "Code", onClick = { onAction(LexicalAction.AddCodeBlock) })
            ToolbarBtn(label = "> Quote", onClick = { onAction(LexicalAction.AddQuote) })
            ToolbarBtn(label = "Table", onClick = { onAction(LexicalAction.AddTable) })
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
            Text(text = label, color = accent, fontSize = 12.sp, fontWeight = fontWeight, fontStyle = fontStyle)
        }
    }
}

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
    LexicalAction.AddSubpage -> "[Subpage](kepler-page://page-new)"
}
