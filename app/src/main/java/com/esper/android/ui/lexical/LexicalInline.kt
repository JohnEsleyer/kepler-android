package com.esper.android.ui.lexical

/** What a [LexicalInlineSpan] represents. */
enum class LexicalInlineKind { TEXT, CODE, MATH, LINK, TIMESTAMP, EMOJI, MARKER, BREAK }

/**
 * One contiguous piece of a block's inline content.
 *
 * [rawStart]/[rawEnd] point into the original markdown so the live editor can
 * map between what is displayed (markers stripped) and what is stored.
 * [MARKER][LexicalInlineKind.MARKER] tokens are the syntax characters
 * themselves; the rendered view drops them, the editor reveals the ones that
 * belong to the construct the caret is currently inside.
 */
data class LexicalInlineSpan(
    val kind: LexicalInlineKind,
    val rawStart: Int,
    val rawEnd: Int,
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val highlight: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
    val seconds: Int? = null,
    val shortcode: String? = null,
    val hardBreak: Boolean = false,
    /** Raw range of the whole construct this token belongs to. */
    val constructStart: Int = rawStart,
    val constructEnd: Int = rawEnd
)

data class LexicalInlineScan(val spans: List<LexicalInlineSpan>) {
    /** Display text with every syntax marker removed. */
    val displayText: String
        get() = buildString {
            spans.forEach { if (it.kind != LexicalInlineKind.MARKER) append(it.text) }
        }
}

/**
 * A tiny, dependency-free inline markdown scanner shared by the block parser
 * and the live WYSIWYG text field.
 *
 * Keeping a single scanner means the rendered view and the editor can never
 * disagree about what is bold, what is a link, and which characters are
 * punctuation that should be hidden while reading.
 */
object LexicalInline {

    val EMOJI: Map<String, String> = linkedMapOf(
        // smiles & gestures
        "smile" to "\uD83D\uDE04", "smiley" to "\uD83D\uDE03", "grin" to "\uD83D\uDE01",
        "joy" to "\uD83D\uDE02", "laughing" to "\uD83D\uDE06", "wink" to "\uD83D\uDE09",
        "blush" to "\uD83D\uDE0A", "heart_eyes" to "\uD83D\uDE0D", "thinking" to "\uD83E\uDD14",
        "neutral_face" to "\uD83D\uDE10", "unamused" to "\uD83D\uDE12", "sweat_smile" to "\uD83D\uDE05",
        "sob" to "\uD83D\uDE2D", "cry" to "\uD83D\uDE22", "angry" to "\uD83D\uDE20",
        "rage" to "\uD83D\uDE21", "scream" to "\uD83D\uDE31", "yum" to "\uD83D\uDE0B",
        "sunglasses" to "\uD83D\uDE0E", "nerd_face" to "\uD83E\uDD13", "partying_face" to "\uD83E\uDD73",
        "sleepy" to "\uD83D\uDE34", "mask" to "\uD83D\uDE37", "poop" to "\uD83D\uDCA9",
        "skull" to "\uD83D\uDC80", "ghost" to "\uD83D\uDC7B", "alien" to "\uD83D\uDC7D",
        "robot" to "\uD83E\uDD16", "wave" to "\uD83D\uDC4B", "thumbsup" to "\uD83D\uDC4D",
        "+1" to "\uD83D\uDC4D", "thumbsdown" to "\uD83D\uDC4E", "-1" to "\uD83D\uDC4E",
        "clap" to "\uD83D\uDC4F", "pray" to "\uD83D\uDE4F", "muscle" to "\uD83D\uDCAA",
        "ok_hand" to "\uD83D\uDC4C", "point_right" to "\uD83D\uDC49", "point_left" to "\uD83D\uDC48",
        "raise_hands" to "\uD83D\uDE4C", "eyes" to "\uD83D\uDC40", "brain" to "\uD83E\uDDE0",
        // nature
        "sun" to "\u2600\uFE0F", "moon" to "\uD83C\uDF19", "star" to "\u2B50",
        "sparkles" to "\u2728", "sparkle" to "\u2728", "fire" to "\uD83D\uDD25",
        "rain" to "\uD83C\uDF27\uFE0F", "snow" to "\u2744\uFE0F", "cloud" to "\u2601\uFE0F",
        "zap" to "\u26A1", "rainbow" to "\uD83C\uDF08", "earth" to "\uD83C\uDF0D",
        "seedling" to "\uD83C\uDF31", "leaf" to "\uD83C\uDF43", "cactus" to "\uD83C\uDF35",
        "rose" to "\uD83C\uDF39", "sunflower" to "\uD83C\uDF3B", "evergreen" to "\uD83C\uDF32",
        // objects
        "book" to "\uD83D\uDCD5", "books" to "\uD83D\uDCDA", "bookmark" to "\uD83D\uDD16",
        "pencil" to "\u270F\uFE0F", "memo" to "\uD83D\uDCDD", "bulb" to "\uD83D\uDCA1",
        "key" to "\uD83D\uDD11", "lock" to "\uD83D\uDD12", "hammer" to "\uD83D\uDD28",
        "wrench" to "\uD83D\uDD27", "gear" to "\u2699\uFE0F", "computer" to "\uD83D\uDCBB",
        "phone" to "\uD83D\uDCF1", "camera" to "\uD83D\uDCF7", "video" to "\uD83C\uDFA5",
        "music" to "\uD83C\uDFB5", "headphones" to "\uD83C\uDFA7", "coffee" to "\u2615",
        "tea" to "\uD83C\uDF75", "pizza" to "\uD83C\uDF55", "cake" to "\uD83C\uDF70",
        "gift" to "\uD83C\uDF81", "trophy" to "\uD83C\uDFC6", "medal" to "\uD83C\uDFC5",
        "rocket" to "\uD83D\uDE80", "airplane" to "\u2708\uFE0F", "car" to "\uD83D\uDE97",
        "hourglass" to "\u231B", "clock" to "\uD83D\uDD70\uFE0F", "calendar" to "\uD83D\uDCC5",
        "chart" to "\uD83D\uDCCA", "chart_increasing" to "\uD83D\uDCC8", "money" to "\uD83D\uDCB0",
        "dollar" to "\uD83D\uDCB5", "gem" to "\uD83D\uDC8E", "crown" to "\uD83D\uDC51",
        "shield" to "\uD83D\uDEE1\uFE0F", "clipboard" to "\uD83D\uDCCB", "pushpin" to "\uD83D\uDCCC",
        "paperclip" to "\uD83D\uDCCE", "link" to "\uD83D\uDD17", "mag" to "\uD83D\uDD0D",
        "bell" to "\uD83D\uDD14", "megaphone" to "\uD83D\uDCE3", "loudspeaker" to "\uD83D\uDCE2",
        "target" to "\uD83C\uDFAF", "dart" to "\uD83C\uDFAF", "flag" to "\uD83D\uDEA9",
        "construction" to "\uD83D\uDEA7", "bug" to "\uD83D\uDC1B", "wrench_tool" to "\uD83D\uDEE0\uFE0F",
        "flask" to "\uD83E\uDDEA", "dna" to "\uD83E\uDDEC", "microscope" to "\uD83D\uDD2C",
        "telescope" to "\uD83D\uDD2D", "satellite" to "\uD83D\uDEF0\uFE0F", "atom" to "\u269B\uFE0F",
        // symbols
        "white_check_mark" to "\u2705", "heavy_check_mark" to "\u2714\uFE0F", "check" to "\u2714\uFE0F",
        "x" to "\u274C", "warning" to "\u26A0\uFE0F", "question" to "\u2753",
        "exclamation" to "\u2757", "information_source" to "\u2139\uFE0F",
        "no_entry" to "\u26D4", "prohibited" to "\uD83D\uDEAB", "recycle" to "\u267B\uFE0F",
        "infinity" to "\u267E\uFE0F", "heart" to "\u2764\uFE0F", "red_heart" to "\u2764\uFE0F",
        "broken_heart" to "\uD83D\uDC94", "blue_heart" to "\uD83D\uDC99",
        "green_heart" to "\uD83D\uDC9A", "purple_heart" to "\uD83D\uDC9C",
        "orange_heart" to "\uD83E\uDDE1", "yellow_heart" to "\uD83D\uDC9B",
        "black_heart" to "\uD83D\uDDA4", "white_heart" to "\uD83E\uDD0D",
        "arrow_right" to "\u27A1\uFE0F", "arrow_left" to "\u2B05\uFE0F",
        "arrow_up" to "\u2B06\uFE0F", "arrow_down" to "\u2B07\uFE0F",
        "refresh" to "\uD83D\uDD04", "repeat" to "\uD83D\uDD01",
        "plus" to "\u2795", "minus" to "\u2796", "star2" to "\uD83C\uDF1F",
        "small_blue_diamond" to "\uD83D\uDD39", "diamond" to "\uD83D\uDC8E",
        "hundred" to "\uD83D\uDCAF", "boom" to "\uD83D\uDCA5", "collision" to "\uD83D\uDCA5",
        "tada" to "\uD83C\uDF89", "confetti" to "\uD83C\uDF8A", "balloon" to "\uD83C\uDF88",
        "cookie" to "\uD83C\uDF6A", "beer" to "\uD83C\uDF7A", "wine" to "\uD83C\uDF77",
        "salute" to "\uD83E\uDEF1", "raising_hand" to "\uD83D\uDE4B"
    )

    /** Shortcodes ordered for the `:` picker, most useful first. */
    val PICKER_ORDER: List<String> = buildList {
        addAll(listOf("smile", "joy", "heart_eyes", "thinking", "thumbsup", "clap", "pray",
            "fire", "sparkles", "star", "rocket", "tada", "white_check_mark", "x", "warning",
            "bulb", "book", "books", "pencil", "memo", "pushpin", "link", "coffee", "zap"))
        EMOJI.keys.forEach { if (it !in this) add(it) }
    }

    /** Emoji suggestions for a partially typed `:prefix`. */
    fun suggest(prefix: String, limit: Int = 8): List<Pair<String, String>> {
        val needle = prefix.lowercase().trimStart(':').trimEnd(':')
        if (needle.isEmpty()) return PICKER_ORDER.take(limit).map { it to EMOJI.getValue(it) }
        val starts = PICKER_ORDER.filter { it.startsWith(needle) }
        val contains = PICKER_ORDER.filter { !it.startsWith(needle) && it.contains(needle) }
        return (starts + contains).take(limit).map { it to EMOJI.getValue(it) }
    }

    fun emojiFor(shortcode: String): String? = EMOJI[shortcode.lowercase()]

    // ---------------------------------------------------------------- patterns

    private const val CODE_DOUBLE = """``([^`\n]+)``"""
    private const val CODE = """`([^`\n]+)`"""
    private const val MATH = """\$([^$\n]+)\$"""
    private const val LINK = """\[([^\]\n]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)"""
    private const val TIMESTAMP = """\[(\d{1,2}:\d{2}(?::\d{2})?)\]"""
    private const val BOLD_STAR = """(\*{2,})(?=\S)(.+?)(?<=\S)(\*{2,})"""
    private const val BOLD_UNDER = """(_{2,})(?=\S)(.+?)(?<=\S)(_{2,})"""
    private const val STRIKE = """~~(?=\S)(.+?)(?<=\S)~~"""
    private const val HIGHLIGHT = """==(?=\S)(.+?)(?<=\S)=="""
    private const val ITALIC_STAR = """(?<![*\w])\*(?=\S)([^*\n]*?\S)\*(?![*\w])"""
    private const val ITALIC_UNDER = """(?<![*_\w])_(?=\S)([^_\n]*?\S)_(?![*_\w])"""
    private const val EMOJI_RE = """(?<![0-9A-Za-z_\-]):([a-z0-9_+\-]{2,32}):"""

    private val codeDoubleRe = Regex(CODE_DOUBLE)
    private val codeRe = Regex(CODE)
    private val mathRe = Regex(MATH)
    private val linkRe = Regex(LINK)
    private val timestampRe = Regex(TIMESTAMP)
    private val boldStarRe = Regex(BOLD_STAR)
    private val boldUnderRe = Regex(BOLD_UNDER)
    private val strikeRe = Regex(STRIKE)
    private val highlightRe = Regex(HIGHLIGHT)
    private val italicStarRe = Regex(ITALIC_STAR)
    private val italicUnderRe = Regex(ITALIC_UNDER)
    private val emojiRe = Regex(EMOJI_RE)

    // ------------------------------------------------------------------ scanner

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val highlight: Boolean = false,
        val code: Boolean = false,
        val url: String? = null
    )

    private class Builder {
        val spans = mutableListOf<LexicalInlineSpan>()

        fun emit(span: LexicalInlineSpan) {
            val last = spans.lastOrNull()
            // Merge neighbouring plain runs so the renderer produces one span
            // instead of a fragment per token.
            if (last != null &&
                last.kind == span.kind &&
                last.kind == LexicalInlineKind.TEXT &&
                last.bold == span.bold && last.italic == span.italic &&
                last.strike == span.strike && last.highlight == span.highlight &&
                last.code == span.code && last.url == span.url &&
                last.rawEnd == span.rawStart
            ) {
                spans[spans.lastIndex] = last.copy(rawEnd = span.rawEnd, text = last.text + span.text)
            } else {
                spans.add(span)
            }
        }

        fun marker(raw: String, start: Int, end: Int, construct: IntRange) {
            if (end <= start) return
            spans.add(
                LexicalInlineSpan(
                    kind = LexicalInlineKind.MARKER,
                    rawStart = start,
                    rawEnd = end,
                    text = raw.substring(start, end),
                    constructStart = construct.first,
                    constructEnd = construct.last + 1
                )
            )
        }
    }

    /**
     * Scans [raw] and returns an ordered token stream that tiles the source
     * exactly: text runs, constructs and the syntax markers between them.
     */
    fun scan(raw: String): LexicalInlineScan {
        val builder = Builder()
        scanRange(raw, 0, raw.length, Style(), builder, 0 until raw.length)
        return LexicalInlineScan(builder.spans)
    }

    private fun scanRange(
        raw: String,
        from: Int,
        to: Int,
        style: Style,
        out: Builder,
        construct: IntRange
    ) {
        if (from >= to) return
        var cursor = from
        while (cursor < to) {
            val hit = nextHit(raw, cursor, to)
            if (hit == null) {
                emitText(raw, cursor, to, style, out, construct)
                return
            }
            if (hit.start > cursor) emitText(raw, cursor, hit.start, style, out, construct)

            val inner = hit.start until hit.end
            when (hit.kind) {
                "bold", "italic", "strike", "highlight" -> {
                    out.marker(raw, hit.start, hit.innerStart, inner)
                    val isTripleOrMore = hit.kind == "bold" && (hit.innerStart - hit.start) >= 3 && (hit.innerStart - hit.start) % 2 == 1
                    val next = when (hit.kind) {
                        "bold" -> style.copy(bold = true, italic = if (isTripleOrMore) true else style.italic)
                        "italic" -> style.copy(italic = true)
                        "strike" -> style.copy(strike = true)
                        else -> style.copy(highlight = true)
                    }
                    scanRange(raw, hit.innerStart, hit.innerEnd, next, out, inner)
                    out.marker(raw, hit.innerEnd, hit.end, inner)
                }
                "link" -> {
                    out.marker(raw, hit.start, hit.innerStart, inner)
                    scanRange(
                        raw, hit.innerStart, hit.innerEnd,
                        style.copy(url = hit.aux ?: ""), out, inner
                    )
                    out.marker(raw, hit.innerEnd, hit.end, inner)
                }
                "code" -> {
                    out.marker(raw, hit.start, hit.innerStart, inner)
                    out.emit(
                        LexicalInlineSpan(
                            kind = LexicalInlineKind.CODE,
                            rawStart = hit.innerStart, rawEnd = hit.innerEnd,
                            text = raw.substring(hit.innerStart, hit.innerEnd),
                            bold = style.bold, italic = style.italic, strike = style.strike,
                            highlight = style.highlight, code = true, url = style.url
                        )
                    )
                    out.marker(raw, hit.innerEnd, hit.end, inner)
                }
                "math" -> {
                    out.marker(raw, hit.start, hit.innerStart, inner)
                    out.emit(
                        LexicalInlineSpan(
                            kind = LexicalInlineKind.MATH,
                            rawStart = hit.innerStart, rawEnd = hit.innerEnd,
                            text = raw.substring(hit.innerStart, hit.innerEnd)
                        )
                    )
                    out.marker(raw, hit.innerEnd, hit.end, inner)
                }
                "timestamp" -> {
                    val body = raw.substring(hit.innerStart, hit.innerEnd)
                    out.marker(raw, hit.start, hit.innerStart, inner)
                    out.emit(
                        LexicalInlineSpan(
                            kind = LexicalInlineKind.TIMESTAMP,
                            rawStart = hit.innerStart, rawEnd = hit.innerEnd,
                            text = body,
                            seconds = parseTimestampSeconds(body)
                        )
                    )
                    out.marker(raw, hit.innerEnd, hit.end, inner)
                }
                "emoji" -> {
                    val shortcode = raw.substring(hit.innerStart, hit.innerEnd)
                    val glyph = emojiFor(shortcode)
                    if (glyph != null) {
                        out.marker(raw, hit.start, hit.innerStart, inner)
                        out.emit(
                            LexicalInlineSpan(
                                kind = LexicalInlineKind.EMOJI,
                                rawStart = hit.innerStart, rawEnd = hit.innerEnd,
                                text = glyph, shortcode = shortcode.lowercase()
                            )
                        )
                        out.marker(raw, hit.innerEnd, hit.end, inner)
                    } else {
                        emitText(raw, hit.start, hit.end, style, out, construct)
                    }
                }
                else -> emitText(raw, hit.start, hit.end, style, out, construct)
            }
            cursor = hit.end
        }
    }

    private fun emitText(
        raw: String,
        from: Int,
        to: Int,
        style: Style,
        out: Builder,
        construct: IntRange
    ) {
        if (from >= to) return
        out.emit(
            LexicalInlineSpan(
                kind = LexicalInlineKind.TEXT,
                rawStart = from, rawEnd = to,
                text = raw.substring(from, to),
                bold = style.bold, italic = style.italic, strike = style.strike,
                highlight = style.highlight, code = style.code, url = style.url,
                constructStart = construct.first,
                constructEnd = construct.last + 1
            )
        )
    }

    private class Hit(
        val start: Int,
        val end: Int,
        val innerStart: Int,
        val innerEnd: Int,
        val kind: String,
        val priority: Int,
        val aux: String? = null
    )

    /**
     * Finds the construct that begins earliest in `raw[start, to)`.
     *
     * Ties (same start position) are broken by priority, then by the longest
     * match. That is what makes `[00:15](url)` a link rather than a timestamp,
     * and `**bold**` bold rather than italic.
     */
    private fun nextHit(raw: String, start: Int, to: Int): Hit? {
        val candidates = mutableListOf<Hit>()

        fun consider(regex: Regex, kind: String, priority: Int, innerGroup: Int, extraGroup: Int? = null,
                     accept: (MatchResult) -> Boolean = { true }) {
            // Walk forward past matches we do not accept (e.g. an unknown
            // `:shortcode:` before a real one) instead of giving up entirely.
            var match = regex.find(raw, start)
            while (match != null) {
                val valid = match.range.first < to &&
                    match.range.last < to &&
                    !isEscaped(raw, match.range.first) &&
                    accept(match)
                if (valid) break
                match = regex.find(raw, match.range.last + 1)
            }
            val m = match ?: return
            val inner = m.groups[innerGroup]
            if (inner == null) return
            candidates += Hit(
                start = m.range.first,
                end = m.range.last + 1,
                innerStart = inner.range.first,
                innerEnd = inner.range.last + 1,
                kind = kind,
                priority = priority,
                aux = extraGroup?.let { m.groups[it]?.value }
            )
        }

        consider(codeDoubleRe, "code", -1, 1)
        consider(codeRe, "code", 0, 1)
        consider(mathRe, "math", 1, 1)
        consider(linkRe, "link", 2, 1, 2)
        consider(timestampRe, "timestamp", 3, 1)
        consider(boldStarRe, "bold", 4, 2)
        consider(boldUnderRe, "bold", 4, 2)
        consider(strikeRe, "strike", 5, 1)
        consider(highlightRe, "highlight", 6, 1)
        consider(italicStarRe, "italic", 7, 1)
        consider(italicUnderRe, "italic", 7, 1)
        consider(emojiRe, "emoji", 8, 1) { m ->
            emojiFor(m.groupValues[1]) != null && !looksLikeTime(raw, m.range.first)
        }

        return candidates.minWithOrNull(
            compareBy<Hit> { it.start }
                .thenBy { it.priority }
                .thenByDescending { it.end - it.start }
        )
    }

    /** `12:30:45` must not be mistaken for the emoji `:45:` — guard it. */
    private fun looksLikeTime(raw: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && raw[i].isDigit()) i--
        return i < at - 1
    }

    private fun isEscaped(raw: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && raw[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    /** `[01:23]` → 83, `[1:02:03]` → 3723. Returns null for malformed input. */
    fun parseTimestampSeconds(body: String): Int? {
        val parts = body.split(':').mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /**
     * Converts a parsed markdown chunk (one line, or several joined by `\n`)
     * into inline nodes. `hardBreaks` marks which of the source lines ended in
     * an explicit hard break.
     */
    fun toNodes(source: String): List<LexicalInlineNode> {
        val nodes = mutableListOf<LexicalInlineNode>()
        source.split('\n').forEachIndexed { index, line ->
            if (index > 0) {
                nodes += LexicalLineBreakNode(isHard = false)
            }
            val scan = scan(line)
            scan.spans.forEach { span ->
                if (span.kind == LexicalInlineKind.MARKER || span.kind == LexicalInlineKind.BREAK) {
                    return@forEach
                }
                // A link's label is scanned recursively, so each label run
                // carries the url instead of being its own LINK token.
                val linkUrl = span.url
                if (linkUrl != null) {
                    nodes += LexicalLinkNode(text = span.text, url = linkUrl)
                    return@forEach
                }
                when (span.kind) {
                    LexicalInlineKind.TEXT -> nodes += LexicalTextNode(
                        text = span.text,
                        isBold = span.bold,
                        isItalic = span.italic,
                        isStrikethrough = span.strike,
                        isCode = span.code,
                        isHighlight = span.highlight
                    )
                    LexicalInlineKind.CODE -> nodes += LexicalTextNode(
                        text = span.text,
                        isBold = span.bold,
                        isItalic = span.italic,
                        isStrikethrough = span.strike,
                        isHighlight = span.highlight,
                        isCode = true
                    )
                    LexicalInlineKind.MATH -> nodes += LexicalInlineMathNode(span.text)
                    LexicalInlineKind.TIMESTAMP -> nodes += LexicalTimestampNode(
                        rawText = "[${span.text}]",
                        seconds = span.seconds ?: 0,
                        label = span.text
                    )
                    LexicalInlineKind.EMOJI -> nodes += LexicalEmojiNode(
                        shortcode = span.shortcode ?: "", emoji = span.text
                    )
                    LexicalInlineKind.MARKER, LexicalInlineKind.BREAK, LexicalInlineKind.LINK -> Unit
                }
            }
        }
        return nodes
    }

    /**
     * Maps an offset in the *rendered* text (markers removed) back to an offset
     * in the raw markdown, so a tap lands the caret where the user touched.
     */
    fun displayOffsetToRaw(raw: String, displayOffset: Int): Int {
        if (raw.isEmpty()) return 0
        val scan = scan(raw)
        var display = 0
        for (span in scan.spans) {
            if (span.kind == LexicalInlineKind.MARKER) continue
            val length = span.text.length
            if (displayOffset <= display + length) {
                val rawLength = (span.rawEnd - span.rawStart).coerceAtLeast(0)
                val within = (displayOffset - display).coerceIn(0, rawLength)
                return (span.rawStart + within).coerceIn(0, raw.length)
            }
            display += length
        }
        return raw.length
    }
}
