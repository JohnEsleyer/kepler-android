package com.esper.android.ui.lexical

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.android.ui.theme.BorderSlate
import com.esper.android.ui.theme.IndigoAccent
import com.esper.android.ui.theme.IndigoGlow
import com.esper.android.ui.theme.SpaceBlack
import com.esper.android.ui.theme.SurfaceSlate
import com.esper.android.ui.theme.TextMuted
import com.esper.android.ui.theme.TextPrimary
import com.esper.android.ui.theme.TextSecondary

/**
 * Makes a plain text field behave like a rendered editor:
 *
 *  * markdown markers (`**`, `*`, `` ` ``, `~~`, `$`, `[ ](…)`, `:shortcode:`)
 *    are styled with subtle dimmed syntax coloring;
 *  * content formatting (bold, italic, code, links) is applied directly;
 *  * 1:1 OffsetMapping.Identity ensures Android IME composing regions and
 *    caret tracking remain synchronized without jumping or jitter.
 */
class InlineMarkdownTransformation(
    private val caret: Int = 0,
    private val base: SpanStyle,
    private val colors: LexicalInlineColors
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val scan = LexicalInline.scan(raw)
        val builder = AnnotatedString.Builder(raw)

        scan.spans.forEach { span ->
            val start = span.rawStart.coerceIn(0, raw.length)
            val end = span.rawEnd.coerceIn(start, raw.length)
            if (start < end) {
                builder.addStyle(styleFor(span), start, end)
            }
        }

        return TransformedText(
            builder.toAnnotatedString(),
            OffsetMapping.Identity
        )
    }

    private fun styleFor(span: LexicalInlineSpan): SpanStyle = when (span.kind) {
        LexicalInlineKind.MARKER -> SpanStyle(
            color = colors.muted.copy(alpha = 0.65f),
            fontWeight = FontWeight.Normal
        )
        LexicalInlineKind.CODE -> SpanStyle(
            color = colors.code,
            background = colors.codeBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = scaledTextSize(base.fontSize, 0.92f)
        )
        LexicalInlineKind.MATH -> SpanStyle(
            color = colors.math,
            background = colors.mathBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = scaledTextSize(base.fontSize, 0.9f)
        )
        LexicalInlineKind.LINK -> SpanStyle(
            color = colors.link,
            textDecoration = TextDecoration.Underline
        )
        LexicalInlineKind.TIMESTAMP -> SpanStyle(
            color = colors.timestamp,
            background = colors.timestampBackground,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = scaledTextSize(base.fontSize, 0.85f)
        )
        LexicalInlineKind.EMOJI -> SpanStyle(fontSize = scaledTextSize(base.fontSize, 1.05f))
        else -> SpanStyle(
            color = when {
                span.url != null -> colors.link
                span.code -> colors.code
                else -> Color.Unspecified
            },
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = when {
                span.url != null -> TextDecoration.Underline
                span.strike -> TextDecoration.LineThrough
                else -> null
            },
            background = if (span.highlight) colors.highlightBackground else Color.Unspecified,
            fontFamily = if (span.code) FontFamily.Monospace else null
        )
    }
}

/**
 * Styles a whole block while it is being edited, so the Markdown the live
 * editor reveals looks like the rendered block:
 *
 *  * `#`, `>`, `-`/`1.` prefixes are tinted and weighted like their rendered
 *    counterparts;
 *  * heading text grows with its level;
 *  * inline construct styling is applied on top.
 *
 * The mapping is [OffsetMapping.Identity]: nothing is inserted or deleted, so
 * the caret, IME composing region and selection stay in sync with the source.
 */
class LiveBlockVisualTransformation(
    private val colors: LexicalInlineColors
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val builder = AnnotatedString.Builder(raw)

        // ------------------------------------------------------ block prefixes
        headingRegex.find(raw)?.let { match ->
            val hashes = match.groups[1]!!
            val level = hashes.value.length
            builder.addStyle(
                SpanStyle(color = IndigoGlow.copy(alpha = 0.9f), fontWeight = FontWeight.Bold),
                hashes.range.first,
                hashes.range.last + 1
            )

            val lineEnd = raw.indexOf('\n').let { if (it == -1) raw.length else it }
            val (size, weight, color) = when (level) {
                1 -> Triple(22.sp, FontWeight.ExtraBold, Color.White)
                2 -> Triple(18.sp, FontWeight.Bold, Color(0xFFF1F5F9))
                3 -> Triple(16.sp, FontWeight.SemiBold, Color(0xFFE2E8F0))
                4 -> Triple(15.sp, FontWeight.SemiBold, Color(0xFFCBD5E1))
                5 -> Triple(14.sp, FontWeight.Medium, Color(0xFFB6C2D2))
                else -> Triple(13.sp, FontWeight.Medium, colors.muted)
            }
            // Start after the first line's marker so the hashes keep their
            // syntax colour while the title itself inherits heading weight.
            val contentStart = (hashes.range.last + 1).coerceAtMost(raw.length)
            if (contentStart < lineEnd) {
                builder.addStyle(
                    SpanStyle(fontSize = size, fontWeight = weight, color = color),
                    contentStart,
                    lineEnd
                )
            }
        }

        // A quote block can span several source lines, each with its own `>`.
        var quoteLineStart = 0
        while (quoteLineStart <= raw.length) {
            val quoteLineEnd = raw.indexOf('\n', quoteLineStart)
                .let { if (it == -1) raw.length else it }
            val segment = raw.substring(quoteLineStart, quoteLineEnd)
            val match = quoteRegex.find(segment)
            if (match != null) {
                builder.addStyle(
                    SpanStyle(color = IndigoAccent, fontWeight = FontWeight.Bold),
                    quoteLineStart + match.range.first,
                    quoteLineStart + match.range.last + 1
                )
                if (quoteLineStart + match.range.last + 1 < quoteLineEnd) {
                    builder.addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic, color = colors.muted),
                        quoteLineStart + match.range.last + 1,
                        quoteLineEnd
                    )
                }
            }
            if (quoteLineEnd >= raw.length) break
            quoteLineStart = quoteLineEnd + 1
        }

        listMarkerRegex.find(raw)?.let { match ->
            val marker = match.groups[2]!!
            builder.addStyle(
                SpanStyle(color = IndigoGlow, fontWeight = FontWeight.Bold),
                marker.range.first,
                marker.range.last + 1
            )
        }

        taskMarkerRegex.find(raw)?.let { match ->
            builder.addStyle(
                SpanStyle(color = com.esper.android.ui.theme.EmeraldSync, fontWeight = FontWeight.Bold),
                match.range.first,
                match.range.last + 1
            )
        }

        // ----------------------------------------------------- inline styling
        LexicalInline.scan(raw).spans.forEach { span ->
            val start = span.rawStart.coerceIn(0, raw.length)
            val end = span.rawEnd.coerceIn(start, raw.length)
            if (start >= end) return@forEach
            builder.addStyle(inlineStyle(span), start, end)
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun inlineStyle(span: LexicalInlineSpan): SpanStyle = when (span.kind) {
        LexicalInlineKind.MARKER -> SpanStyle(
            color = colors.muted.copy(alpha = 0.65f),
            fontWeight = FontWeight.Normal
        )
        LexicalInlineKind.CODE -> SpanStyle(
            color = colors.code,
            background = colors.codeBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        LexicalInlineKind.MATH -> SpanStyle(
            color = colors.math,
            background = colors.mathBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        LexicalInlineKind.LINK -> SpanStyle(
            color = colors.link,
            textDecoration = TextDecoration.Underline
        )
        LexicalInlineKind.TIMESTAMP -> SpanStyle(
            color = colors.timestamp,
            background = colors.timestampBackground,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        else -> SpanStyle(
            color = when {
                span.url != null -> colors.link
                span.bold -> Color.White
                else -> Color.Unspecified
            },
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = when {
                span.url != null -> TextDecoration.Underline
                span.strike -> TextDecoration.LineThrough
                else -> null
            },
            background = if (span.highlight) colors.highlightBackground else Color.Unspecified,
            fontFamily = if (span.code) FontFamily.Monospace else null
        )
    }

    private companion object {
        val headingRegex = Regex("""^(#{1,6})([ \t]+)""")
        val quoteRegex = Regex("""^(>[ \t]*)""")
        val listMarkerRegex = Regex("""^([ \t]*)([-*+]|\d{1,9}[.)])([ \t]+)""")
        val taskMarkerRegex = Regex("""\[([ xX])\]""")
    }
}

/** Syntax highlighting applied behind an editable code block. */
class CodeHighlightTransformation(private val language: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            CodeSyntaxHighlighter.highlight(text.text, language),
            OffsetMapping.Identity
        )
}

/**
 * One live `:shortcode` query plus the callback that replaces it in the field
 * that raised it.
 *
 * The field cannot render its own suggestion strip: the strip belongs *below*
 * the whole list so the soft keyboard never covers it, and a field near the
 * bottom of a note would otherwise push its own suggestions off-screen.
 */
class EmojiSuggestionState internal constructor(
    val query: String,
    internal val insert: (String) -> Unit
)

/**
 * Editor-scoped bridge between the focused field and the suggestion strip.
 *
 * The field publishes whatever `:prefix` the caret is in, the editor renders
 * the strip, and picking an entry calls back into that same field — so the
 * insertion happens at the caret even though the UI lives somewhere else.
 */
class EmojiSuggestionHost {
    internal var current by mutableStateOf<EmojiSuggestionState?>(null)
        private set

    internal fun show(query: String, insert: (String) -> Unit) {
        current = EmojiSuggestionState(query, insert)
    }

    internal fun hide() {
        current = null
    }
}

/**
 * A text field that renders markdown (or syntax-highlighted code) live.
 *
 * The field owns its text while focused so the IME is never fought, and falls
 * back to the authoritative block text when focus leaves. Pressing Enter on a
 * paragraph splits the block (two Enters make a new paragraph); inside a code
 * block Enter simply adds a line.
 */
@Composable
fun LiveTextField(
    block: LexicalBlockNode,
    initialText: String,
    textStyle: TextStyle,
    spanBase: SpanStyle,
    colors: LexicalInlineColors,
    readOnly: Boolean,
    requestFocus: Boolean,
    onFocusConsumed: () -> Unit,
    onTextCommitted: (String) -> Unit,
    onSplit: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    useMarkdown: Boolean = true,
    codeLanguage: String? = null,
    showEmojiPicker: Boolean = true,
    suggestions: EmojiSuggestionHost? = null,
    initialCaret: Int? = null,
    onFocusLost: () -> Unit = {},
    onFocusStateChanged: (Boolean) -> Unit = {},
    onPasteMarkdown: ((pasted: String, selection: TextRange, blockText: String) -> Unit)? = null
) {
    var value by remember(block.key) {
        val caret = (initialCaret ?: initialText.length).coerceIn(0, initialText.length)
        mutableStateOf(TextFieldValue(initialText, TextRange(caret)))
    }
    var isFocused by remember(block.key) { mutableStateOf(false) }
    var wasFocused by remember(block.key) { mutableStateOf(false) }
    var blurJob by remember(block.key) { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    // Set once a paste has been handed to the editor: the field is about to be
    // removed, and any further IME callback would commit stale text over the
    // document that was just built from the paste.
    var pasteHandled by remember(block.key) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    DisposableEffect(block.key) {
        onDispose {
            blurJob?.cancel()
            if (isFocused || wasFocused) {
                onFocusStateChanged(false)
            }
        }
    }

    // Follow external edits (sync, undo, toolbar) only when the user is not
    // typing in this field.
    LaunchedEffect(initialText) {
        if (!isFocused && value.text != initialText) {
            value = TextFieldValue(initialText, TextRange(initialText.length))
        }
    }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusConsumed()
        }
    }

    val transformation = remember(value.text, value.selection.start, useMarkdown, codeLanguage, spanBase, colors) {
        when {
            codeLanguage != null -> CodeHighlightTransformation(codeLanguage)
            useMarkdown -> InlineMarkdownTransformation(value.selection.start, spanBase, colors)
            else -> VisualTransformation.None
        }
    }

    val emojiQuery = remember(value.text, value.selection, readOnly) {
        if (!readOnly && useMarkdown && showEmojiPicker) {
            activeEmojiQuery(value.text, value.selection)
        } else {
            null
        }
    }
    val emojiMatches = remember(emojiQuery) {
        emojiQuery?.let { LexicalInline.suggest(it) } ?: emptyList()
    }

    fun insertShortcode(shortcode: String) {
        val text = value.text
        val caret = value.selection.start.coerceIn(0, text.length)
        val start = text.lastIndexOf(':', (caret - 1).coerceAtLeast(0))
        if (start < 0) return
        val replacement = ":$shortcode: "
        val updated = text.substring(0, start) + replacement + text.substring(caret)
        val newCaret = start + replacement.length
        value = TextFieldValue(updated, TextRange(newCaret))
        onTextCommitted(updated)
        // The strip lives outside the field, so tapping it takes focus away
        // and closes the keyboard. Put the caret straight back where it was.
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // Hand the query to the editor so the strip can be drawn above the IME
    // instead of underneath the block being edited.
    if (suggestions != null) {
        SideEffect {
            if (emojiMatches.isEmpty()) {
                suggestions.hide()
            } else {
                suggestions.show(emojiQuery!!) { insertShortcode(it) }
            }
        }
        DisposableEffect(suggestions) {
            onDispose { suggestions.hide() }
        }
    }

    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = { fresh ->
                if (readOnly || pasteHandled) return@BasicTextField
                val oldText = value.text
                val newText = fresh.text
                val oldBreaks = oldText.count { it == '\n' }
                val newBreaks = newText.count { it == '\n' }
                val singleEnter = newText.length == oldText.length + 1 && newBreaks == oldBreaks + 1
                val keepsNewlines = LexicalSource.keepsNewlines(block)

                // A Markdown paste leaves this field and is parsed as blocks,
                // so a ChatGPT answer renders instead of being flattened to one
                // line. Code, math and quotes keep their newlines literally.
                val pastedMarkdown =
                    pastedMarkdownIn(oldText, newText, value.selection, keepsNewlines)

                when {
                    pastedMarkdown != null && onPasteMarkdown != null -> {
                        pasteHandled = true
                        onPasteMarkdown(pastedMarkdown, value.selection, oldText)
                    }
                    // Enter on a prose block splits it into a new block; on an
                    // empty list item it leaves the list. Use the IME's own
                    // newline twice (a blank line) for a full paragraph break.
                    singleEnter && !keepsNewlines -> {
                        if (oldText.isBlank()) {
                            // Nothing to split: just add the line.
                            value = fresh
                            onTextCommitted(newText)
                        } else {
                            val offset = firstDifference(oldText, newText)
                                .coerceIn(0, oldText.length)
                            val before = oldText.substring(0, offset)
                            value = TextFieldValue(before, TextRange(before.length))
                            onSplit(oldText, offset)
                        }
                    }
                    // Multi-line insert (paste): write everything into the block
                    // and keep only the first line in this field; the parser
                    // turns the rest into their own blocks.
                    newBreaks > oldBreaks && !keepsNewlines -> {
                        val firstLine = newText.substringBefore('\n')
                        val caretInFirst = fresh.selection.start.coerceIn(0, firstLine.length)
                        value = TextFieldValue(firstLine, TextRange(caretInFirst))
                        onTextCommitted(newText)
                    }
                    else -> {
                        value = fresh
                        // The IME re-sends the current text on focus/composition
                        // changes; committing that would rewrite the block and,
                        // after a paste, clobber the document.
                        if (newText != oldText) onTextCommitted(newText)
                    }
                }
            },
            textStyle = textStyle,
            cursorBrush = SolidColor(IndigoAccent),
            visualTransformation = transformation,
            readOnly = readOnly,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) {
                        blurJob?.cancel()
                        blurJob = null
                        wasFocused = true
                        onFocusStateChanged(true)
                    } else if (wasFocused) {
                        blurJob?.cancel()
                        blurJob = coroutineScope.launch {
                            delay(200)
                            wasFocused = false
                            onFocusStateChanged(false)
                            onFocusLost()
                        }
                    }
                },
            decorationBox = { innerTextField ->
                Box {
                    if (value.text.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = textStyle.copy(color = colors.muted.copy(alpha = 0.7f)),
                            maxLines = 1
                        )
                    }
                    innerTextField()
                }
            }
        )

        if (suggestions == null && emojiMatches.isNotEmpty()) {
            EmojiSuggestionBar(
                suggestions = emojiMatches,
                onPick = { shortcode -> insertShortcode(shortcode) }
            )
        }
    }
}

/**
 * The Markdown that was just pasted into [oldText], or null when the change is
 * ordinary typing.
 *
 * `keepsNewlines` blocks (code, math, quotes) never intercept: their newlines
 * are literal, so a paste belongs inside the block.
 */
internal fun pastedMarkdownIn(
    oldText: String,
    newText: String,
    selection: TextRange,
    keepsNewlines: Boolean
): String? {
    if (keepsNewlines) return null
    if (newText.length <= oldText.length) return null

    val from = minOf(selection.start, selection.end).coerceIn(0, oldText.length)
    val to = maxOf(selection.start, selection.end).coerceIn(from, oldText.length)
    val delta = newText.length - (oldText.length - (to - from))
    if (delta <= 0 || from + delta > newText.length) return null
    val inserted = newText.substring(from, from + delta)

    val oldBreaks = oldText.count { it == '\n' }
    val newBreaks = newText.count { it == '\n' }
    val singleEnter = newText.length == oldText.length + 1 && newBreaks == oldBreaks + 1
    val grewLines = newBreaks > oldBreaks && !singleEnter

    return when {
        grewLines -> inserted
        delta > 1 && LexicalSource.isMarkdownFormatting(inserted) -> inserted
        else -> null
    }
}

/** The `:prefix` immediately before the caret, or null. */
internal fun activeEmojiQuery(text: String, selection: TextRange): String? {
    if (!selection.collapsed) return null
    val caret = selection.start
    if (caret <= 0 || caret > text.length) return null
    val start = text.lastIndexOf(':', caret - 1)
    if (start < 0) return null
    val token = text.substring(start + 1, caret)
    // A bare `:` is not a query yet, so "Note:" and "10:30" never pop the
    // strip. Reading `text[start + 1]` here used to throw when the colon was
    // the last character of the block (e.g. right after splitting on it).
    if (token.isEmpty() || token.length > 32) return null
    if (!token[0].isLetter()) return null
    if (!token.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '+' }) return null
    return token
}

@Composable
internal fun EmojiSuggestionBar(
    suggestions: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceSlate)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(suggestions) { (shortcode, glyph) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(shortcode) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = glyph, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = ":$shortcode:",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The `:` strip, pinned under the editor content so the IME can never cover it.
 * Renders nothing unless the focused field is inside a `:shortcode`.
 */
@Composable
fun EmojiSuggestionTray(
    host: EmojiSuggestionHost,
    modifier: Modifier = Modifier
) {
    val current = host.current ?: return
    val matches = remember(current.query) { LexicalInline.suggest(current.query) }
    if (matches.isEmpty()) return
    // A solid backdrop, so list content scrolled underneath the pinned strip
    // never shows through the rounded corners.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(SpaceBlack)
    ) {
        EmojiSuggestionBar(
            suggestions = matches,
            onPick = { shortcode -> current.insert(shortcode) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

private fun firstDifference(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var index = 0
    while (index < limit && a[index] == b[index]) index++
    return index
}

/**
 * Colour palette for inline markdown inside the editor, derived from the app
 * theme so the live view matches the rendered view exactly.
 */
fun lexicalInlineColors(
    text: Color = TextPrimary,
    muted: Color = TextMuted,
    link: Color = IndigoGlow
): LexicalInlineColors = LexicalInlineColors(
    text = text,
    muted = muted,
    code = Color(0xFF9AE6B4),
    codeBackground = Color(0xFF14212E),
    link = link,
    // LaTeX reads as a quiet violet formula chip; the old amber-on-brown pair
    // looked like a highlighter mark and fought the code-chip palette.
    math = Color(0xFFC4B5FD),
    mathBackground = Color(0xFF1E1B4B).copy(alpha = 0.75f),
    timestamp = IndigoGlow,
    timestampBackground = IndigoAccent.copy(alpha = 0.22f),
    highlightBackground = Color(0xFF3F3A12)
)

/** Couple of spacing helpers kept here so the editor file stays readable. */
internal val BlockVerticalGap = 10.dp
internal val InlineCodeRadius = 4.dp
internal val CodeBlockRadius = 12.dp
internal val CodeBlockHeaderHeight = 34.dp
internal val SecondaryTextSize = 12.sp
internal val HairlineColor = BorderSlate
