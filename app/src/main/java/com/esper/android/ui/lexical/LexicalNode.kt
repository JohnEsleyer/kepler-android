package com.esper.android.ui.lexical

/**
 * Parsed representation of the markdown document that both the renderer and
 * the live editor work against.
 *
 * Every block records the source lines it came from ([lineStart]..[lineEnd],
 * both 0-based and inclusive). That range is what makes in-place editing
 * possible: a block can be re-rendered as a text field and the edited text can
 * be written back into exactly those lines without disturbing the rest of the
 * document.
 */
sealed interface LexicalNode

sealed class LexicalBlockNode : LexicalNode {
    abstract val key: String

    /** First source line of the block (0-based). */
    abstract val lineStart: Int

    /** Last source line of the block (0-based, inclusive). */
    abstract val lineEnd: Int

    /** The block's own text, used for accessibility and word counts. */
    open val plainText: String get() = ""
}

data class LexicalHeadingNode(
    override val key: String,
    val level: Int, // 1 to 6
    val inlines: List<LexicalInlineNode>,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = inlines.joinToString("") { it.plainText }
}

data class LexicalParagraphNode(
    override val key: String,
    val inlines: List<LexicalInlineNode>,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = inlines.joinToString("") { it.plainText }
}

data class LexicalQuoteNode(
    override val key: String,
    val inlines: List<LexicalInlineNode>,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = inlines.joinToString("") { it.plainText }
}

data class LexicalCodeBlockNode(
    override val key: String,
    val code: String,
    val language: String = "text",
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = code

    /** Line index of the opening fence (kept as an alias for older call sites). */
    val lineStartIndex: Int get() = lineStart

    /** Line index of the closing fence (kept as an alias for older call sites). */
    val lineEndIndex: Int get() = lineEnd
}

data class LexicalMathBlockNode(
    override val key: String,
    val latex: String,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = latex
}

data class LexicalImageNode(
    override val key: String,
    val altText: String,
    val imageUrl: String,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = altText
}

data class LexicalListItemNode(
    override val key: String,
    val isOrdered: Boolean,
    val indexNumber: Int?,
    val indentLevel: Int = 0, // 0 = root, 1 = first nesting step, ...
    val isTask: Boolean = false,
    val isChecked: Boolean = false,
    val inlines: List<LexicalInlineNode>,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0,
    /** Column at which this item's content starts, used for exact write-back. */
    val contentColumn: Int = 0,
    /** The exact marker the author wrote, e.g. `- `, `* `, `12. `. */
    val marker: String = "- ",
    /** The exact leading whitespace of the item's first line. */
    val indentPrefix: String = ""
) : LexicalBlockNode() {
    override val plainText: String get() = inlines.joinToString("") { it.plainText }
}

data class LexicalHorizontalRuleNode(
    override val key: String,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode()

/**
 * First-class Notion-style child subpage reference block:
 * `[Title](kepler-page://<id>)`.
 */
data class LexicalSubpageBlockNode(
    override val key: String,
    val pageId: String,
    val title: String,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = title
}

/**
 * A run of one or more source lines that the author left empty on purpose
 * (two or more consecutive blank lines). Rendering these as real vertical
 * space is what makes "press Enter twice" work in the rendered view.
 */
data class LexicalBlankLineNode(
    override val key: String,
    val count: Int = 1,
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode()

/** Column alignment declared by a table's delimiter row (`|:---|:---:|---:|`). */
enum class LexicalColumnAlignment { NONE, LEFT, CENTER, RIGHT }

data class LexicalTableNode(
    override val key: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<LexicalColumnAlignment> = emptyList(),
    override val lineStart: Int = 0,
    override val lineEnd: Int = 0
) : LexicalBlockNode() {
    override val plainText: String get() = headers.joinToString(" ")

    fun alignmentAt(column: Int): LexicalColumnAlignment =
        alignments.getOrNull(column) ?: LexicalColumnAlignment.NONE
}

sealed interface LexicalInlineNode {
    /** Text used for accessibility, selection and word counting. */
    val plainText: String
}

data class LexicalTextNode(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isStrikethrough: Boolean = false,
    val isCode: Boolean = false,
    val isHighlight: Boolean = false
) : LexicalInlineNode {
    override val plainText: String get() = text
}

/**
 * A line break inside a block.
 *
 * [isHard] is true when the author asked for the break explicitly with two
 * trailing spaces or a trailing backslash. A soft break is just the newline
 * that separated two source lines of the same paragraph — it keeps the author's
 * line structure without starting a new paragraph.
 */
data class LexicalLineBreakNode(
    val isHard: Boolean = false
) : LexicalInlineNode {
    override val plainText: String get() = "\n"
}

data class LexicalLinkNode(
    val text: String,
    val url: String
) : LexicalInlineNode {
    override val plainText: String get() = text
}

data class LexicalInlineMathNode(
    val latex: String
) : LexicalInlineNode {
    override val plainText: String get() = latex
}

data class LexicalTimestampNode(
    val rawText: String,
    val seconds: Int,
    val label: String = rawText
) : LexicalInlineNode {
    override val plainText: String get() = label
}

/** An emoji written as a `:shortcode:` and rendered as its glyph. */
data class LexicalEmojiNode(
    val shortcode: String,
    val emoji: String
) : LexicalInlineNode {
    override val plainText: String get() = emoji
}
