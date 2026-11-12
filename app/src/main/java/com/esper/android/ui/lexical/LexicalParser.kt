package com.esper.android.ui.lexical

/**
 * Turns the stored markdown into a list of renderable blocks.
 *
 * The parser follows a pragmatic subset of CommonMark that matches what the
 * web client (`@lexical/markdown`) does, with three deliberate improvements:
 *
 *  * **Continuing lines.** Consecutive non-blank lines are one paragraph with
 *    line breaks inside it instead of one paragraph per source line. The same
 *    is true for block quotes and list items, including "lazy" continuation
 *    lines that are not indented.
 *  * **Blank lines.** One blank line separates blocks; a run of two or more
 *    leaves an explicit gap so pressing Enter twice is visible in the rendered
 *    view.
 *  * **Colons.** `|:---|---:|` delimiter rows are recognised (and their
 *    alignment is rendered), links accept any scheme (`mailto:`, `tel:`,
 *    `esper:`, relative paths), `[H:MM:SS]` timestamps are understood, and
 *    `:shortcode:` emoji are substituted.
 */
object LexicalParser {

    private val imageBlockRegex =
        Regex("""^!\[([^\]]*)\]\(([^)\s]+)(?:\s+"([^"]*)")?\)$""")

    private val orderedMarkerRegex = Regex("""^(\d{1,9})[.)]([ \t]+)(.*)$""")
    private val bulletMarkerRegex = Regex("""^([-*+])([ \t]+)(.*)$""")
    private val taskPrefixRegex = Regex("""^\[([ xX])]\s+(.*)$""")
    private val delimiterCellRegex = Regex("""^:?\s*-+\s*:?$""")
    private val fenceRegex = Regex("""^(`{3,}|~{3,})(.*)$""")
    private val headingRegex = Regex("""^(#{1,6})\s+(.*)$""")
    private val subpageBlockRegex = Regex("""^\[([^\]]+)\]\(kepler-page:\/\/([a-zA-Z0-9_\-]+)\)$""")

    // ---------------------------------------------------------------- parsing

    fun parseToLexicalAst(markdown: String): List<LexicalBlockNode> {
        val lines = markdown.split('\n')
        val blocks = mutableListOf<LexicalBlockNode>()
        val listStack = mutableListOf<Int>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // ------------------------------------------------------ blank run
            if (line.isBlank()) {
                var run = 0
                while (i < lines.size && lines[i].isBlank()) {
                    run++
                    i++
                }
                // A single blank line is just a block separator. Extra blank
                // lines are authored vertical space and are kept.
                if (blocks.isNotEmpty() && run > 1) {
                    blocks += LexicalBlankLineNode(
                        key = "block_${blocks.size}",
                        count = (run - 1).coerceAtMost(3),
                        lineStart = i - run,
                        lineEnd = i - 1
                    )
                }
                listStack.clear()
                continue
            }

            val trimmed = line.trim()
            val indent = leadingWidth(line)

            // ------------------------------------------------------ fenced code
            val fence = fenceRegex.matchEntire(trimmed)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2].trim().ifEmpty { "text" }
                val start = i
                val body = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val candidate = lines[i].trim()
                    if (candidate.startsWith(marker.take(3))) break
                    body += lines[i]
                    i++
                }
                val end = i.coerceAtMost(lines.size - 1)
                blocks += LexicalCodeBlockNode(
                    key = "block_${blocks.size}",
                    code = body.joinToString("\n"),
                    language = language,
                    lineStart = start,
                    lineEnd = end
                )
                if (i < lines.size) i++
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ math block
            if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                val closer = if (trimmed.startsWith("$$")) "$$" else "\\]"
                val start = i
                val body = mutableListOf<String>()
                val inner = trimmed
                    .removePrefix("$$").removePrefix("\\[")
                    .removeSuffix("$$").removeSuffix("\\]")

                val opensAndClosesOnOneLine =
                    trimmed.length > 2 && trimmed.endsWith(closer)

                if (opensAndClosesOnOneLine) {
                    body += inner.trim()
                } else {
                    i++
                    while (i < lines.size && !lines[i].trim().endsWith(closer)) {
                        body += lines[i]
                        i++
                    }
                }
                val end = i.coerceAtMost(lines.size - 1)
                blocks += LexicalMathBlockNode(
                    key = "block_${blocks.size}",
                    latex = body.joinToString("\n").trim('\n'),
                    lineStart = start,
                    lineEnd = end
                )
                if (i < lines.size) i++
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ horizontal rule
            if (isHorizontalRule(trimmed)) {
                blocks += LexicalHorizontalRuleNode(
                    key = "block_${blocks.size}", lineStart = i, lineEnd = i
                )
                i++
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ image
            val image = imageBlockRegex.matchEntire(trimmed)
            if (image != null) {
                blocks += LexicalImageNode(
                    key = "block_${blocks.size}",
                    altText = image.groupValues[1],
                    imageUrl = image.groupValues[2],
                    lineStart = i,
                    lineEnd = i
                )
                i++
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ subpage block
            val subpageMatch = subpageBlockRegex.matchEntire(trimmed)
            if (subpageMatch != null) {
                blocks += LexicalSubpageBlockNode(
                    key = "block_${blocks.size}",
                    title = subpageMatch.groupValues[1],
                    pageId = subpageMatch.groupValues[2],
                    lineStart = i,
                    lineEnd = i
                )
                i++
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ heading
            val heading = headingRegex.matchEntire(trimmed)
            if (heading != null) {
                blocks += LexicalHeadingNode(
                    key = "block_${blocks.size}",
                    level = heading.groupValues[1].length,
                    inlines = inlinesOf(listOf(heading.groupValues[2])),
                    lineStart = i,
                    lineEnd = i
                )
                i++
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ table
            val delimiterIdx = findDelimiterIndex(lines, i)
            if (delimiterIdx != null) {
                val start = i
                val headers = splitCells(lines[i])
                val alignments = splitCells(lines[delimiterIdx]).map { cell ->
                    when {
                        cell.startsWith(":") && cell.endsWith(":") -> LexicalColumnAlignment.CENTER
                        cell.startsWith(":") -> LexicalColumnAlignment.LEFT
                        cell.endsWith(":") -> LexicalColumnAlignment.RIGHT
                        else -> LexicalColumnAlignment.NONE
                    }
                }
                i = delimiterIdx + 1
                val rows = mutableListOf<List<String>>()
                var lastRowIndex: Int = delimiterIdx
                while (i < lines.size) {
                    val candidate = lines[i].trim()
                    if (candidate.isBlank()) {
                        // Look ahead past blank lines to see if another table row follows
                        var peek = i + 1
                        while (peek < lines.size && lines[peek].isBlank()) peek++
                        if (peek < lines.size && lines[peek].trim().contains('|') && !isTableBoundary(lines, peek)) {
                            i = peek
                            continue
                        } else {
                            break
                        }
                    }
                    if (!candidate.contains('|') || isTableBoundary(lines, i)) {
                        break
                    }
                    val cells = splitCells(lines[i])
                    rows += List(headers.size) { index -> cells.getOrElse(index) { "" } }
                    lastRowIndex = i
                    i++
                }
                blocks += LexicalTableNode(
                    key = "block_${blocks.size}",
                    headers = headers,
                    rows = rows,
                    alignments = alignments,
                    lineStart = start,
                    lineEnd = lastRowIndex
                )
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ block quote
            if (trimmed.startsWith(">")) {
                val start = i
                val content = mutableListOf<String>()
                while (i < lines.size) {
                    val candidate = lines[i]
                    if (candidate.isBlank()) break
                    val candidateTrimmed = candidate.trimStart()
                    when {
                        candidateTrimmed.startsWith(">") -> {
                            content += candidateTrimmed.removePrefix(">").removePrefix(" ")
                            i++
                        }
                        // Lazy continuation: a plain line keeps the quote going.
                        !startsNewBlock(lines, i) -> {
                            content += candidate.trimStart()
                            i++
                        }
                        else -> break
                    }
                }
                blocks += LexicalQuoteNode(
                    key = "block_${blocks.size}",
                    inlines = inlinesOf(content),
                    lineStart = start,
                    lineEnd = i - 1
                )
                listStack.clear()
                continue
            }

            // ------------------------------------------------------ list item
            val marker = parseListMarker(line)
            if (marker != null) {
                val start = i
                val content = mutableListOf(marker.content)
                i++

                // Continuation lines: indented lines and lazy (non-block-start)
                // lines belong to this item, not to a new paragraph.
                while (i < lines.size) {
                    val candidate = lines[i]
                    if (candidate.isBlank()) break
                    val candidateIndent = leadingWidth(candidate)
                    val candidateMarker = parseListMarker(candidate)
                    if (candidateMarker != null) break // sibling or child item
                    if (startsNewBlock(lines, i)) break
                    if (candidateIndent > marker.indent) {
                        content += stripIndent(candidate, marker.contentColumn)
                        i++
                    } else if (!startsNewBlock(lines, i)) {
                        // Lazy continuation (CommonMark allows it for lists).
                        content += candidate.trimStart()
                        i++
                    } else {
                        break
                    }
                }

                while (listStack.isNotEmpty() && marker.contentColumn < listStack.last()) {
                    listStack.removeAt(listStack.lastIndex)
                }
                if (listStack.isEmpty() || marker.contentColumn > listStack.last()) {
                    listStack.add(marker.contentColumn)
                }
                val level = (listStack.size - 1).coerceAtLeast(0)

                blocks += LexicalListItemNode(
                    key = "block_${blocks.size}",
                    isOrdered = marker.ordered,
                    indexNumber = marker.number,
                    indentLevel = level,
                    isTask = marker.task,
                    isChecked = marker.checked,
                    inlines = inlinesOf(content),
                    lineStart = start,
                    lineEnd = i - 1,
                    contentColumn = marker.contentColumn,
                    marker = marker.marker,
                    indentPrefix = marker.indentPrefix
                )
                continue
            }

            // ------------------------------------------------------ paragraph
            val start = i
            val content = mutableListOf<String>()
            while (i < lines.size) {
                val candidate = lines[i]
                if (candidate.isBlank()) break
                if (content.isNotEmpty() && startsNewBlock(lines, i)) break
                // Keep trailing whitespace: two trailing spaces are a hard
                // break and must survive until inlinesOf() reads them.
                content += stripLeadingSpaces(candidate, max = 3)
                i++
            }
            if (content.isEmpty()) {
                // Defensive: never let a non-empty line be dropped.
                content += trimmed
                i++
            }
            blocks += LexicalParagraphNode(
                key = "block_${blocks.size}",
                inlines = inlinesOf(content),
                lineStart = start,
                lineEnd = i - 1
            )
            listStack.clear()
        }

        return blocks
    }

    /** Backwards-compatible alias used by older call sites and tests. */
    fun parseInlineNodes(text: String): List<LexicalInlineNode> = LexicalInline.toNodes(text)

    // ------------------------------------------------------------- block tests

    private fun isHorizontalRule(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val ch = trimmed[0]
        if (ch != '-' && ch != '*' && ch != '_') return false
        if (trimmed.any { it != ch && it != ' ' && it != '\t' }) return false
        return trimmed.count { it == ch } >= 3
    }

    private fun isFence(trimmed: String): Boolean =
        trimmed.startsWith("```") || trimmed.startsWith("~~~")

    private fun isMathFence(trimmed: String): Boolean =
        trimmed.startsWith("$$") || trimmed.startsWith("\\[")

    private fun startsNewBlock(lines: List<String>, index: Int): Boolean {
        if (index !in lines.indices) return true
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (isFence(trimmed) || isMathFence(trimmed)) return true
        if (isHorizontalRule(trimmed)) return true
        if (subpageBlockRegex.matches(trimmed)) return true
        if (headingRegex.matches(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        if (parseListMarker(line) != null) return true
        if (imageBlockRegex.matches(trimmed)) return true
        if (isTableStart(lines, index)) return true
        return false
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean =
        findDelimiterIndex(lines, index) != null

    private fun splitCells(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|")) text = text.dropLast(1)
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("""(?<!\\)\|""")).map { it.replace("\\|", "|").trim() }
    }

    private fun findDelimiterIndex(lines: List<String>, index: Int): Int? {
        val header = lines[index].trim()
        if (!header.contains('|')) return null
        if (isTableBoundary(lines, index)) return null
        var next = index + 1
        // Allow optional blank lines between header and delimiter
        while (next < lines.size && lines[next].isBlank()) {
            next++
        }
        if (next >= lines.size) return null
        val delimiter = lines[next].trim()
        if (!delimiter.contains('|') || !delimiter.contains('-')) return null
        val cells = splitCells(delimiter)
        if (cells.isEmpty()) return null
        return if (cells.all { delimiterCellRegex.matches(it) }) next else null
    }

    private fun isTableBoundary(lines: List<String>, index: Int): Boolean {
        if (index !in lines.indices) return true
        val trimmed = lines[index].trim()
        if (isFence(trimmed) || isMathFence(trimmed)) return true
        if (isHorizontalRule(trimmed)) return true
        if (subpageBlockRegex.matches(trimmed)) return true
        if (headingRegex.matches(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        if (parseListMarker(lines[index]) != null) return true
        if (imageBlockRegex.matches(trimmed)) return true
        return false
    }

    // -------------------------------------------------------------- list items

    private data class ListMarker(
        val indent: Int,
        val contentColumn: Int,
        val ordered: Boolean,
        val number: Int?,
        val content: String,
        val task: Boolean,
        val checked: Boolean,
        val marker: String,
        val indentPrefix: String
    )

    private fun parseListMarker(line: String): ListMarker? {
        val indent = leadingWidth(line)
        val indentEnd = indexAfterIndent(line)
        val body = line.substring(indentEnd)
        if (body.isEmpty()) return null

        val ordered = orderedMarkerRegex.matchEntire(body)
        val bullet = if (ordered == null) bulletMarkerRegex.matchEntire(body) else null
        val match = ordered ?: bullet ?: return null

        val markerCharLength = if (ordered != null) {
            ordered.groupValues[1].length + 1 // digits + "." or ")"
        } else {
            1
        }
        val spacing = match.groupValues[2].length
        var content = match.groupValues[3]
        val contentColumn = indent + markerCharLength + spacing

        var task = false
        var checked = false
        val taskMatch = taskPrefixRegex.matchEntire(content)
        if (taskMatch != null) {
            task = true
            checked = taskMatch.groupValues[1].equals("x", ignoreCase = true)
            content = taskMatch.groupValues[2]
        }

        return ListMarker(
            indent = indent,
            contentColumn = contentColumn,
            ordered = ordered != null,
            number = ordered?.groupValues?.get(1)?.toIntOrNull(),
            content = content,
            task = task,
            checked = checked,
            marker = body.substring(0, markerCharLength + spacing),
            indentPrefix = line.substring(0, indentEnd)
        )
    }

    // ------------------------------------------------------------ inline nodes

    /**
     * Converts the content lines of one block into inline nodes, inserting a
     * line break between lines. A line ending in two spaces or a backslash
     * produces a hard break.
     */
    private fun inlinesOf(lines: List<String>): List<LexicalInlineNode> {
        val result = mutableListOf<LexicalInlineNode>()
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                result += LexicalLineBreakNode(isHard = hasHardBreak(lines[index - 1]))
            }
            result += LexicalInline.toNodes(stripHardBreak(line))
        }
        return result
    }

    private fun hasHardBreak(line: String): Boolean =
        line.endsWith("  ") || line.endsWith("\\")

    private fun stripHardBreak(line: String): String = when {
        line.endsWith("\\") -> line.dropLast(1)
        line.endsWith("  ") -> line.trimEnd()
        else -> line
    }

    // -------------------------------------------------------------- utilities

    private fun leadingWidth(line: String): Int {
        var width = 0
        for (char in line) {
            when (char) {
                ' ' -> width++
                '\t' -> width += 4
                else -> return width
            }
        }
        return width
    }

    private fun indexAfterIndent(line: String): Int {
        var index = 0
        while (index < line.length && (line[index] == ' ' || line[index] == '\t')) index++
        return index
    }

    /** Drops up to [max] leading spaces, the way CommonMark ignores minor indentation. */
    private fun stripLeadingSpaces(line: String, max: Int): String {
        var index = 0
        while (index < line.length && index < max && line[index] == ' ') index++
        return line.substring(index)
    }

    private fun stripIndent(line: String, columns: Int): String {
        var width = 0
        var index = 0
        while (index < line.length && width < columns) {
            when (line[index]) {
                ' ' -> width++
                '\t' -> width += 4
                else -> return line.substring(index)
            }
            index++
        }
        return line.substring(index)
    }
}
