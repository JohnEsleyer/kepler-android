package com.esper.android.ui.lexical

import androidx.compose.ui.text.TextRange

/**
 * Reads and writes a single block's text inside the full markdown document.
 *
 * The live editor renders each block in place; when the user types, only the
 * lines belonging to that block are rewritten, so the rest of the document
 * (including constructs the block editor does not own, like tables and images)
 * is byte-for-byte preserved.
 */
object LexicalSource {

    /** Blocks that the live editor can turn into a text field. */
    fun canEditInPlace(block: LexicalBlockNode): Boolean = when (block) {
        is LexicalParagraphNode,
        is LexicalHeadingNode,
        is LexicalQuoteNode,
        is LexicalListItemNode,
        is LexicalCodeBlockNode,
        is LexicalMathBlockNode,
        is LexicalSubpageBlockNode -> true
        else -> false
    }

    /** True when Enter should insert a line inside the block instead of splitting it. */
    fun keepsNewlines(block: LexicalBlockNode): Boolean =
        block is LexicalCodeBlockNode ||
            block is LexicalMathBlockNode ||
            // A quote is one continuous block: its lines are line breaks.
            block is LexicalQuoteNode

    // ------------------------------------------------------- markdown pasting

    /**
     * Text a starter document ships with; treated as empty so a paste replaces
     * it instead of being appended underneath it.
     */
    private val STARTER_PLACEHOLDERS = setOf(
        "Start writing notes...",
        "Jot down ideas and quick thoughts here..."
    )

    private fun isPlaceholder(text: String): Boolean =
        text.isBlank() || text.trim() in STARTER_PLACEHOLDERS

    /**
     * True when [text] looks like Markdown rather than prose.
     *
     * Used to decide whether a paste should be parsed into blocks (and leave
     * the inline field) or simply typed into the current block. Multi-line
     * text always qualifies: ChatGPT and friends answer in Markdown, and even
     * a plain multi-line answer reads better as separate lines than as one
     * run-on line.
     */
    fun isMarkdownFormatting(text: String): Boolean {
        if (text.contains('\n')) return true
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("#") ||
            trimmed.startsWith(">") ||
            trimmed.startsWith("- ") ||
            trimmed.startsWith("* ") ||
            trimmed.startsWith("+ ") ||
            trimmed.startsWith("```") ||
            trimmed.startsWith("~~~") ||
            trimmed.startsWith("$$") ||
            trimmed.startsWith("\\[") ||
            trimmed.startsWith("|") ||
            trimmed.startsWith("---") ||
            trimmed.startsWith("***") ||
            trimmed.startsWith("___")
        ) return true
        if (Regex("""^\d+[.)]\s""").containsMatchIn(trimmed)) return true
        if (Regex("""^[-*+]\s+\[[ xX]\]""").containsMatchIn(trimmed)) return true
        return INLINE_MARKDOWN_REGEX.containsMatchIn(trimmed)
    }

    /**
     * True when the pasted text owns whole lines (a heading, fence, table, …)
     * rather than sitting inside a sentence.
     */
    private fun isBlockMarkdown(text: String): Boolean {
        if (text.contains('\n')) return true
        val trimmed = text.trim()
        return trimmed.startsWith("#") ||
            trimmed.startsWith(">") ||
            trimmed.startsWith("- ") ||
            trimmed.startsWith("* ") ||
            trimmed.startsWith("+ ") ||
            trimmed.startsWith("```") ||
            trimmed.startsWith("~~~") ||
            trimmed.startsWith("$$") ||
            trimmed.startsWith("\\[") ||
            trimmed.startsWith("|") ||
            trimmed.startsWith("---") ||
            trimmed.startsWith("***") ||
            trimmed.startsWith("___") ||
            Regex("""^\d+[.)]\s""").containsMatchIn(trimmed) ||
            Regex("""^[-*+]\s+\[[ xX]\]""").containsMatchIn(trimmed)
    }

    /** Inline constructs that are worth rendering even when pasted on one line. */
    private val INLINE_MARKDOWN_REGEX = Regex(
        """(\*\*[^*]+\*\*)|(__[^_]+__)|(~~[^~]+~~)|(`[^`]+`)|(\$[^$]+\$)|(\[[^\]]+\]\([^)]+\))"""
    )

    /**
     * Inserts pasted Markdown where the caret is, replacing the block's own
     * marker line so the paste is parsed at document level.
     *
     * A list item or a quote would otherwise swallow the paste: every line of
     * it would inherit `- ` or `> ` and turn into text (or a nested fence)
     * instead of headings and code blocks. Replacing the block's lines outright
     * means the same paste renders identically whether it lands in a paragraph,
     * a heading or a bullet.
     */
    fun insertMarkdownAtBlock(
        currentMarkdown: String,
        block: LexicalBlockNode,
        pastedMarkdown: String,
        currentSelection: TextRange? = null,
        currentBlockText: String? = null
    ): String {
        val pasted = normalizePastedMarkdown(pastedMarkdown)
        if (pasted.isEmpty()) return currentMarkdown

        val blockText = currentBlockText ?: editableText(currentMarkdown, block)

        // Pasting into an empty note should produce exactly the pasted note,
        // not a leading blank line above it.
        if (currentMarkdown.isBlank() && isPlaceholder(blockText)) return pasted

        val lines = currentMarkdown.split('\n').toMutableList()
        val start = block.lineStart.coerceIn(0, lines.size)
        val endExclusive = if (block.lineStart >= lines.size) {
            start
        } else {
            (block.lineEnd + 1).coerceIn(start, lines.size)
        }

        val replacement: List<String> = if (isPlaceholder(blockText)) {
            pasted.split('\n')
        } else {
            val sel = currentSelection ?: TextRange(blockText.length)
            val from = minOf(sel.start, sel.end).coerceIn(0, blockText.length)
            val to = maxOf(sel.start, sel.end).coerceIn(from, blockText.length)
            val before = blockText.substring(0, from)
            val after = blockText.substring(to)

            if (!isBlockMarkdown(pasted)) {
                // Inline paste (a word, a link, `**bold**`): splice it in and
                // keep the sentence intact.
                listOf(before + pasted + after)
            } else {
                buildList {
                    // Blank lines on both sides so the paste becomes its own
                    // blocks instead of joining the surrounding paragraph.
                    if (before.isNotBlank()) {
                        addAll(before.trimEnd().split('\n'))
                        add("")
                    }
                    addAll(pasted.split('\n'))
                    if (after.isNotBlank()) {
                        add("")
                        addAll(after.trimStart().split('\n'))
                    }
                }
            }
        }

        lines.subList(start, endExclusive).clear()
        lines.addAll(start, replacement)
        return lines.joinToString("\n")
    }

    /** Appends pasted Markdown at the end of the document as its own blocks. */
    fun appendMarkdown(currentMarkdown: String, pastedMarkdown: String): String {
        val pasted = normalizePastedMarkdown(pastedMarkdown)
        if (pasted.isEmpty()) return currentMarkdown
        val existing = currentMarkdown.trimEnd()
        if (existing.isBlank()) return pasted
        return "$existing\n\n$pasted"
    }

    // ------------------------------------------------- markdown normalising

    private val FENCE_LINE = Regex("""^(`{3,}|~{3,})""")
    private val HEADING_LINE = Regex("""^#{1,6}[ \t]+\S""")
    private val LIST_LINE = Regex("""^([-*+]|\d{1,9}[.)])[ \t]+\S""")
    private val QUOTE_LINE = Regex("""^>""")
    @Suppress("unused")
    private val TABLE_LINE_STRICT = Regex("""^\|.*\|$""")
    private val RULE_LINE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

    /**
     * Rebuilds block structure in text pasted from a chat client.
     *
     * Gemini and ChatGPT copy as *flattened* Markdown: one line per paragraph,
     * single newlines between everything, no blank lines, bullets as `" * x"`,
     * and code as an unfenced `fn main() {` followed by indented lines. Fed
     * straight to the parser that is one long paragraph, which is what "paste
     * does not render" looked like on the device.
     *
     * The rules are deliberately conservative:
     * - prose lines that end a sentence (or are full width) become paragraphs;
     * - short prose lines without punctuation are joined to the next line, so a
     *   hard-wrapped answer does not turn into one paragraph per visual line;
     * - fenced input is copied verbatim, two trailing spaces keep a hard break.
     *
     * Only pasted text goes through this; the document being typed keeps its
     * own soft-break semantics.
     */
    fun normalizePastedMarkdown(text: String): String {
        val source = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        val lines = source.split('\n')
        val out = mutableListOf<String>()
        // Heading inference only makes sense for a whole pasted answer, not for
        // a two-word line pasted into a sentence.
        val inferHeadings = lines.count { it.isNotBlank() } >= 5
        var inFence = false
        // Display math is a fence too: its body must not be split into
        // paragraphs by the rules below.
        var inMath = false
        var i = 0

        fun blankBefore() {
            if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
        }

        while (i < lines.size) {
            val rawLine = lines[i]
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (inMath) {
                out.add(line)
                if (trimmed == "$$" || trimmed == "\\]") inMath = false
                i++
                continue
            }

            if (trimmed == "$$" || trimmed == "\\[") {
                blankBefore()
                out.add(trimmed)
                inMath = true
                i++
                continue
            }

            if (inFence) {
                out.add(line)
                if (FENCE_LINE.containsMatchIn(trimmed)) inFence = false
                i++
                continue
            }

            if (trimmed.isEmpty()) {
                val prev = out.lastOrNull()?.trim()
                if (prev != null && isTableLine(prev)) {
                    var peek = i + 1
                    while (peek < lines.size && lines[peek].isBlank()) peek++
                    if (peek < lines.size && isTableLine(lines[peek].trim())) {
                        i = peek
                        continue
                    }
                }
                out.add("")
                i++
                continue
            }

            if (FENCE_LINE.containsMatchIn(trimmed)) {
                blankBefore()
                out.add(trimmed)
                inFence = true
                i++
                continue
            }

            // "fn main() {" + indented body -> a real fenced code block.
            if (opensCodeBlock(trimmed) && isIndented(lines, i + 1)) {
                val (run, next) = readCodeRun(lines, i)
                blankBefore()
                out.add("```" + sniffLanguage(run.joinToString("\n")))
                out.addAll(run)
                out.add("```")
                i = next
                continue
            }

            // A run of bare code lines (`val x = 1` / `let y = 2`) is code too.
            if (looksLikeCodeLine(trimmed) &&
                i + 1 < lines.size &&
                looksLikeCodeLine(lines[i + 1])
            ) {
                val run = mutableListOf<String>()
                var j = i
                while (j < lines.size) {
                    val candidate = lines[j].trimEnd()
                    if (candidate.isBlank()) {
                        if (run.isEmpty() || run.last().isBlank()) break
                        run.add("")
                        j++
                        continue
                    }
                    if (!looksLikeCodeLine(candidate)) break
                    run.add(candidate.trim())
                    j++
                }
                while (run.isNotEmpty() && run.last().isBlank()) run.removeAt(run.lastIndex)
                if (run.size >= 2) {
                    blankBefore()
                    out.add("```" + sniffLanguage(run.joinToString("\n")))
                    out.addAll(run)
                    out.add("```")
                    i = j
                    continue
                }
            }

            if (isBlockLine(trimmed)) {
                // Keep consecutive list items, table rows and quote lines
                // together; every other block gets air around it.
                if (!sameFamily(out.lastOrNull(), trimmed)) blankBefore()
                out.add(trimmed)
                i++
                continue
            }

            if (inferHeadings &&
                isHeadline(trimmed) &&
                out.lastOrNull()?.isBlank() != false
            ) {
                blankBefore()
                out.add("## $trimmed")
                i++
                continue
            }

            val previous = out.lastOrNull()
            if (previous != null && previous.endsWith("  ")) {
                // Explicit Markdown hard break: stay inside the paragraph.
                out[out.lastIndex] = previous + "\n" + rawLine.trimStart()
            } else if (previous != null &&
                previous.isNotBlank() &&
                !isBlockLine(previous.trim()) &&
                canJoin(previous, trimmed)
            ) {
                out[out.lastIndex] = previous.trimEnd() + " " + trimmed
            } else {
                blankBefore()
                out.add(rawLine.trimStart())
            }
            i++
        }

        return out.joinToString("\n").trim()
    }

    /**
     * A short line that stands alone between blank lines and has no sentence
     * punctuation: how chat clients render a sub-heading without the `##`.
     */
    private fun isHeadline(trimmed: String): Boolean {
        if (trimmed.length !in 3..60) return false
        if (trimmed.last() in ".!?:;,。！？") return false
        if (trimmed.split(' ').size > 8) return false
        if (trimmed.firstOrNull()?.isUpperCase() != true) return false
        if (isBlockLine(trimmed)) return false
        return true
    }

    /** True when [trimmed] is the first line of an unfenced code block. */
    private fun opensCodeBlock(trimmed: String): Boolean {
        // Ignore a trailing comment: `fn f() -> usize { // note`.
        val code = trimmed.substringBefore(" //").substringBefore("\t//").trimEnd()
        return (code.endsWith("{") || (code.endsWith(":") && !isBlockLine(trimmed))) &&
            code.length < 120
    }

    private fun isIndented(lines: List<String>, index: Int): Boolean {
        val line = lines.getOrNull(index) ?: return false
        if (line.isBlank()) return false
        val indent = line.takeWhile { it == ' ' || it == '\t' }.length
        return indent >= 4
    }

    /** Reads `fn main() {` plus its indented body, closing brace included. */
    private fun readCodeRun(lines: List<String>, start: Int): Pair<List<String>, Int> {
        val run = mutableListOf(lines[start].trim())
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                run.add("")
                i++
                continue
            }
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            if (indent >= 2) {
                run.add(line.trimEnd())
                i++
                continue
            }
            if (line.trim() in CODE_CLOSERS) {
                run.add(line.trim())
                i++
            }
            break
        }
        while (run.isNotEmpty() && run.last().isBlank()) run.removeAt(run.lastIndex)
        return run to i
    }

    private val CODE_CLOSERS = setOf("}", "};", "end", "fi", "done")

    private fun isTableLine(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~") || trimmed.startsWith(">") || trimmed.startsWith("#")) return false
        return (trimmed.startsWith("|") && trimmed.contains("|")) ||
            (trimmed.contains("|") && splitCells(trimmed).size >= 2)
    }

    private fun splitCells(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|")) text = text.dropLast(1)
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("""(?<!\\)\|""")).map { it.replace("\\|", "|").trim() }
    }

    private fun isBlockLine(trimmed: String): Boolean =
        HEADING_LINE.containsMatchIn(trimmed) ||
            LIST_LINE.containsMatchIn(trimmed) ||
            QUOTE_LINE.containsMatchIn(trimmed) ||
            isTableLine(trimmed) ||
            RULE_LINE.matches(trimmed) ||
            trimmed.startsWith("$$") ||
            trimmed.startsWith("\\[") ||
            trimmed.startsWith("![")

    private fun isListItem(line: String?): Boolean =
        line != null && LIST_LINE.containsMatchIn(line.trim())

    private fun sameFamily(previous: String?, trimmed: String): Boolean {
        val p = previous?.trim() ?: return false
        if (p.isEmpty()) return false
        return (LIST_LINE.containsMatchIn(trimmed) && LIST_LINE.containsMatchIn(p)) ||
            (isTableLine(trimmed) && isTableLine(p)) ||
            (QUOTE_LINE.containsMatchIn(trimmed) && QUOTE_LINE.containsMatchIn(p))
    }

    /**
     * Joins a hard-wrapped line to its continuation, but never a paragraph that
     * already ended or a line that is itself a block.
     */
    private fun canJoin(previous: String, next: String): Boolean {
        if (previous.endsWith("  ")) return false
        if (previous.length >= 70) return false
        if (previous.last() in ".!?:;\u3002\uFF01\uFF1F") return false
        if (looksLikeCodeLine(previous) || looksLikeCodeLine(next)) return false
        val first = next.firstOrNull() ?: return false
        return first.isLowerCase() || first in ",)]}"
    }

    private fun looksLikeCodeLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("<!") ||
            trimmed.startsWith("```")
        ) return false
        return trimmed.endsWith(";") ||
            trimmed.endsWith("{") ||
            trimmed.endsWith("}") ||
            trimmed.contains(" = ") ||
            trimmed.contains("==") ||
            trimmed.contains("->") ||
            trimmed.startsWith("val ") ||
            trimmed.startsWith("var ") ||
            trimmed.startsWith("let ") ||
            trimmed.startsWith("fun ") ||
            trimmed.startsWith("fn ") ||
            trimmed.startsWith("def ") ||
            trimmed.startsWith("return ") ||
            trimmed.startsWith("import ") ||
            trimmed.startsWith("from ") ||
            trimmed.startsWith("#include") ||
            trimmed.startsWith("print(") ||
            trimmed.startsWith("println!") ||
            trimmed.startsWith("console.")
    }

    private fun sniffLanguage(code: String): String = when {
        Regex("""\bfn\s+\w+\s*[(<]""").containsMatchIn(code) ||
            code.contains("let mut ") ||
            code.contains("println!") ||
            Regex("""\buse\s+std\b""").containsMatchIn(code) -> "rust"
        Regex("""\bfun\s+\w+\s*[(<]""").containsMatchIn(code) -> "kotlin"
        Regex("""(?m)^\s*(def|class)\s+\w+""").containsMatchIn(code) -> "python"
        code.contains("console.log") || code.contains("=>") -> "javascript"
        code.contains("System.out") || Regex("""\bpublic\s+(final\s+)?class\b""").containsMatchIn(code) -> "java"
        else -> ""
    }

    /** Drops leading/trailing blank lines without touching the body. */
    private fun trimBlankEdges(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var first = 0
        var last = lines.lastIndex
        while (first <= last && lines[first].isBlank()) first++
        while (last >= first && lines[last].isBlank()) last--
        if (first > last) return ""
        return lines.subList(first, last + 1).joinToString("\n")
    }

    /**
     * The editable text of [block]: the content without the structural marker
     * (`#`, `>`, `- `, the code fences, …).
     */
    fun editableText(markdown: String, block: LexicalBlockNode): String {
        val lines = markdown.split('\n')
        return when (block) {
            is LexicalSubpageBlockNode -> "[${block.title}](kepler-page://${block.pageId})"
            is LexicalCodeBlockNode -> block.code
            is LexicalMathBlockNode -> block.latex
            is LexicalHeadingNode ->
                slice(lines, block)?.mapIndexed { index, line ->
                    if (index == 0) stripHeading(line) else line
                }?.joinToString("\n") ?: ""
            is LexicalQuoteNode ->
                slice(lines, block)?.joinToString("\n") { stripQuote(it) } ?: ""
            is LexicalListItemNode ->
                slice(lines, block)?.mapIndexed { index, line ->
                    if (index == 0) stripMarker(line, block) else stripColumns(line, block.contentColumn)
                }?.joinToString("\n") ?: ""
            else -> slice(lines, block)?.joinToString("\n") ?: ""
        }
    }

    /**
     * Writes [text] back into [block]'s source lines, re-applying the
     * structural marker so the document keeps its shape.
     */
    fun withEditableText(markdown: String, block: LexicalBlockNode, text: String): String {
        val lines = markdown.split('\n').toMutableList()

        // Synthetic trailing paragraph used to keep an empty document editable.
        if (block.lineStart >= lines.size) {
            val extra = text.split('\n')
            while (lines.size < block.lineStart) lines.add("")
            lines.addAll(extra)
            return lines.joinToString("\n")
        }

        val start = block.lineStart.coerceIn(0, lines.size - 1)
        val end = block.lineEnd.coerceIn(start, lines.size - 1)

        val replacement: List<String> = when (block) {
            is LexicalSubpageBlockNode -> text.split('\n')
            is LexicalHeadingNode -> text.split('\n').mapIndexed { index, line ->
                if (index == 0) "${"#".repeat(block.level)} $line" else line
            }
            is LexicalQuoteNode -> text.split('\n').map { "> $it" }
            is LexicalListItemNode -> {
                val taskPrefix = when {
                    !block.isTask -> ""
                    block.isChecked -> "[x] "
                    else -> "[ ] "
                }
                val continuation = " ".repeat(block.contentColumn.coerceAtLeast(1))
                text.split('\n').mapIndexed { index, line ->
                    if (index == 0) block.indentPrefix + block.marker + taskPrefix + line
                    else continuation + line
                }
            }
            is LexicalCodeBlockNode -> {
                val opening = lines[start]
                val closing = lines[end]
                val closed = closing.trim().let { it.startsWith("```") || it.startsWith("~~~") }
                buildList {
                    add(opening)
                    addAll(text.split('\n'))
                    if (closed) add(closing)
                }
            }
            is LexicalMathBlockNode -> {
                if (start == end) {
                    val original = lines[start].trim()
                    val bracket = original.startsWith("\\[")
                    val open = if (bracket) "\\[" else "$$"
                    val close = if (bracket) "\\]" else "$$"
                    if (text.contains('\n')) {
                        buildList { add(open); addAll(text.split('\n')); add(close) }
                    } else {
                        listOf(open + text + close)
                    }
                } else {
                    val opening = lines[start]
                    val closing = lines[end]
                    buildList { add(opening); addAll(text.split('\n')); add(closing) }
                }
            }
            else -> text.split('\n')
        }

        lines.subList(start, end + 1).let {
            it.clear()
            it.addAll(replacement)
        }
        return lines.joinToString("\n")
    }

    /** Removes a block (fences included) from the document. */
    fun removeBlock(markdown: String, block: LexicalBlockNode): String {
        val lines = markdown.split('\n').toMutableList()
        if (block.lineStart >= lines.size) return markdown
        val start = block.lineStart
        val end = block.lineEnd.coerceIn(start, lines.size - 1)
        lines.subList(start, end + 1).clear()
        return lines.joinToString("\n")
    }

    /** Flips the `[ ]` / `[x]` marker of a task item without touching the text. */
    fun setTaskChecked(markdown: String, block: LexicalListItemNode, checked: Boolean): String {
        val lines = markdown.split('\n').toMutableList()
        val index = block.lineStart
        if (index !in lines.indices) return markdown
        val line = lines[index]
        val existing = listOf("[ ] ", "[x] ", "[X] ").firstOrNull { line.contains(it) } ?: return markdown
        lines[index] = line.replaceFirst(existing, if (checked) "[x] " else "[ ] ")
        return lines.joinToString("\n")
    }

    /**
     * Splits [block] at [offset] and returns the updated document plus the
     * source line where the new block starts (for focus hand-over).
     */
    data class SplitResult(val markdown: String, val focusLine: Int)

    fun splitBlock(
        markdown: String,
        block: LexicalBlockNode,
        text: String,
        offset: Int
    ): SplitResult? {
        if (!canEditInPlace(block) || keepsNewlines(block)) return null
        val safeOffset = offset.coerceIn(0, text.length)
        val before = text.substring(0, safeOffset)
        val after = text.substring(safeOffset).removePrefix("\n")

        // Enter on an empty list item leaves the list (Notion behaviour).
        if (block is LexicalListItemNode && before.isBlank()) {
            val lines = markdown.split('\n').toMutableList()
            val start = block.lineStart.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            val end = block.lineEnd.coerceIn(start, (lines.size - 1).coerceAtLeast(0))
            lines.subList(start, end + 1).clear()
            lines.addAll(start, after.split('\n'))
            return SplitResult(lines.joinToString("\n"), start)
        }

        var updated = withEditableText(markdown, block, before)
        val beforeLines = before.count { it == '\n' } + 1
        val insertAt = block.lineStart + beforeLines

        val prefix = when (block) {
            is LexicalListItemNode ->
                block.indentPrefix + block.marker + if (block.isTask) "[ ] " else ""
            else -> ""
        }
        val body = after.split('\n').mapIndexed { index, line ->
            if (index == 0) prefix + line else line
        }

        // A bare newline joins two lines of the *same* paragraph. Two
        // paragraphs need a blank line between them, so pressing Enter on a
        // paragraph inserts that separator (exactly what the user sees).
        val needsSeparator = block is LexicalParagraphNode && before.isNotBlank()
        val newLines = if (needsSeparator) listOf("") + body else body
        val focusLine = if (needsSeparator) insertAt + 1 else insertAt

        updated = insertLines(updated, insertAt, newLines)
        return SplitResult(updated, focusLine)
    }

    /** Appends a new empty paragraph after [afterLine] (or at the end). */
    fun appendParagraph(markdown: String, afterLine: Int? = null): SplitResult {
        val lines = markdown.split('\n').toMutableList()
        val at = (afterLine ?: lines.size).coerceIn(0, lines.size)
        lines.add(at, "")
        return SplitResult(lines.joinToString("\n"), at)
    }

    /**
     * Swaps [block] with the block immediately preceding it in the AST.
     */
    fun moveBlockUp(markdown: String, block: LexicalBlockNode): String {
        val blocks = LexicalParser.parseToLexicalAst(markdown)
        val index = blocks.indexOfFirst { it.key == block.key }
        if (index <= 0) return markdown
        val prevBlock = blocks[index - 1]

        val lines = markdown.split('\n').toMutableList()
        val prevStart = prevBlock.lineStart
        val prevEnd = prevBlock.lineEnd.coerceIn(prevStart, lines.size - 1)
        val curStart = block.lineStart
        val curEnd = block.lineEnd.coerceIn(curStart, lines.size - 1)
        if (prevStart >= curStart) return markdown

        val curLines = lines.subList(curStart, curEnd + 1).toList()
        val prevLines = lines.subList(prevStart, prevEnd + 1).toList()
        val gapLines = if (curStart > prevEnd + 1) {
            lines.subList(prevEnd + 1, curStart).toList()
        } else emptyList()

        val newSegment = curLines + gapLines + prevLines
        lines.subList(prevStart, curEnd + 1).clear()
        lines.addAll(prevStart, newSegment)
        return lines.joinToString("\n")
    }

    /**
     * Inserts an empty paragraph block immediately after [block].
     */
    fun insertBlockBelow(markdown: String, block: LexicalBlockNode): String {
        val lines = markdown.split('\n').toMutableList()
        val insertIndex = (block.lineEnd + 1).coerceIn(0, lines.size)
        lines.add(insertIndex, "")
        lines.add(insertIndex + 1, "New note...")
        return lines.joinToString("\n")
    }

    /**
     * Swaps [block] with the block immediately succeeding it in the AST.
     */
    fun moveBlockDown(markdown: String, block: LexicalBlockNode): String {
        val blocks = LexicalParser.parseToLexicalAst(markdown)
        val index = blocks.indexOfFirst { it.key == block.key }
        if (index < 0 || index >= blocks.size - 1) return markdown
        val nextBlock = blocks[index + 1]
        return moveBlockUp(markdown, nextBlock)
    }

    /**
     * Transforms [block] into a new block type (H1, H2, H3, Paragraph, Quote, Bullet, Task, Code).
     */
    fun transformBlock(markdown: String, block: LexicalBlockNode, targetType: String): String {
        val content = editableText(markdown, block)
            .replace(Regex("""^#{1,6}\s+"""), "")
            .replace(Regex("""^>\s+"""), "")
            .replace(Regex("""^[-*+]\s+(\[[ xX]\]\s+)? """), "")
            .trim()
        val newText = when (targetType.lowercase()) {
            "h1" -> "# $content"
            "h2" -> "## $content"
            "h3" -> "### $content"
            "quote" -> "> $content"
            "bullet" -> "- $content"
            "task" -> "- [ ] $content"
            "code" -> "```\n$content\n```"
            "paragraph" -> content
            else -> content
        }
        return withEditableText(markdown, block, newText)
    }

    // -------------------------------------------------------------- internals

    private fun slice(lines: List<String>, block: LexicalBlockNode): List<String>? {
        if (lines.isEmpty()) return null
        if (block.lineStart !in lines.indices) {
            return if (block.lineStart >= lines.size) emptyList() else null
        }
        val end = block.lineEnd.coerceIn(block.lineStart, lines.size - 1)
        return lines.subList(block.lineStart, end + 1)
    }

    private fun stripHeading(line: String): String =
        headingPrefix.replaceFirst(line.trimStart(), "")

    private val headingPrefix = Regex("""^#{1,6}[ \t]+""")

    private fun stripQuote(line: String): String {
        val trimmed = line.trimStart()
        return when {
            trimmed.startsWith("> ") -> trimmed.substring(2)
            trimmed.startsWith(">") -> trimmed.substring(1)
            else -> trimmed
        }
    }

    private fun stripMarker(line: String, block: LexicalListItemNode): String {
        var result = line
        if (result.startsWith(block.indentPrefix)) {
            result = result.substring(block.indentPrefix.length)
        }
        if (result.startsWith(block.marker)) {
            result = result.substring(block.marker.length)
        }
        if (block.isTask) {
            result = result.removePrefix("[ ] ").removePrefix("[x] ").removePrefix("[X] ")
        }
        return result
    }

    private fun stripColumns(line: String, columns: Int): String {
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

    private fun insertLines(markdown: String, at: Int, newLines: List<String>): String {
        val lines = markdown.split('\n').toMutableList()
        val index = at.coerceIn(0, lines.size)
        lines.addAll(index, newLines)
        return lines.joinToString("\n")
    }
}
