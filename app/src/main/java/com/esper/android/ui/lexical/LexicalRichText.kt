package com.esper.android.ui.lexical

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** Scales a text size, leaving an unspecified size unspecified. */
internal fun scaledTextSize(size: androidx.compose.ui.unit.TextUnit, factor: Float): androidx.compose.ui.unit.TextUnit =
    if (size == androidx.compose.ui.unit.TextUnit.Unspecified) size else size * factor

/** Colours used for inline markdown, shared by the renderer and the editor. */
data class LexicalInlineColors(
    val text: Color,
    val muted: Color,
    val code: Color,
    val codeBackground: Color,
    val link: Color,
    val math: Color,
    val mathBackground: Color,
    val timestamp: Color,
    val timestampBackground: Color,
    val highlightBackground: Color
)

/**
 * Builds a single [AnnotatedString] for a block's inline nodes.
 *
 * The previous implementation laid each inline node out as its own composable
 * inside a `FlowRow`, which inserted a 4dp gap at every style boundary
 * (`This is **bold** text` gained gaps before and after "bold") and made line
 * breaks impossible. One annotated string fixes the spacing, lets the text
 * wrap naturally, and honours soft/hard line breaks.
 */
fun buildInlineText(
    inlines: List<LexicalInlineNode>,
    base: SpanStyle,
    colors: LexicalInlineColors,
    clickable: Boolean,
    onLink: ((String) -> Unit)? = null,
    onTimestampClicked: ((Int) -> Unit)? = null
): AnnotatedString = buildAnnotatedString {
    inlines.forEach { node ->
        when (node) {
            is LexicalTextNode -> withStyle(
                SpanStyle(
                    color = if (node.isCode) colors.code else base.color,
                    background = when {
                        node.isCode -> colors.codeBackground
                        node.isHighlight -> colors.highlightBackground
                        else -> Color.Unspecified
                    },
                    fontWeight = if (node.isBold) FontWeight.Bold else base.fontWeight,
                    fontStyle = if (node.isItalic) FontStyle.Italic else base.fontStyle,
                    textDecoration = when {
                        node.isStrikethrough -> TextDecoration.LineThrough
                        else -> base.textDecoration
                    },
                    fontFamily = if (node.isCode) FontFamily.Monospace else base.fontFamily,
                    fontSize = if (node.isCode) scaledTextSize(base.fontSize, 0.92f) else base.fontSize,
                    letterSpacing = base.letterSpacing
                )
            ) {
                append(node.text)
            }

            is LexicalLineBreakNode -> append('\n')

            is LexicalLinkNode -> {
                val isSubpage = node.url.startsWith("kepler-page://")
                val label = if (isSubpage) "\uD83D\uDCC4 " + (node.text.ifBlank { "Subpage" }) else node.text.ifBlank { node.url }
                val style = SpanStyle(
                    color = if (isSubpage) Color(0xFF60A5FA) else colors.link,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = if (isSubpage) FontWeight.SemiBold else base.fontWeight,
                    fontSize = base.fontSize
                )
                if (clickable && onLink != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "link:${node.url}",
                            styles = TextLinkStyles(style = style),
                            linkInteractionListener = {
                                runCatching { onLink(node.url) }
                                Unit
                            }
                        )
                    ) { append(label) }
                } else {
                    withStyle(style) { append(label) }
                }
            }

            is LexicalInlineMathNode -> withStyle(
                SpanStyle(
                    color = colors.math,
                    background = colors.mathBackground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = scaledTextSize(base.fontSize, 0.9f)
                )
            ) {
                append(node.latex)
            }

            is LexicalTimestampNode -> {
                val style = SpanStyle(
                    color = colors.timestamp,
                    background = colors.timestampBackground,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = scaledTextSize(base.fontSize, 0.85f)
                )
                if (clickable && onTimestampClicked != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "ts:${node.seconds}",
                            styles = TextLinkStyles(style = style),
                            linkInteractionListener = {
                                onTimestampClicked(node.seconds)
                                Unit
                            }
                        )
                    ) { append(" \u25B6 ${node.label} ") }
                } else {
                    withStyle(style) { append(" \u25B6 ${node.label} ") }
                }
            }

            is LexicalEmojiNode -> append(node.emoji)
        }
    }
}

/** Plain text of a block, used for word counts and accessibility. */
fun plainTextOf(inlines: List<LexicalInlineNode>): String =
    inlines.joinToString("") { it.plainText }
