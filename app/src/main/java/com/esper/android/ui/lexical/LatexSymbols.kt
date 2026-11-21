package com.esper.android.ui.lexical

/**
 * A small LaTeX-to-Unicode table used for *inline* math.
 *
 * Display math is rendered by KaTeX in a WebView, but inline math lives inside
 * a single [androidx.compose.ui.text.AnnotatedString] and cannot host a
 * composable. Translating the common commands keeps lines such as
 *
 *     `total` $\rightarrow$ `IDENTIFIER`
 *
 * readable instead of showing the literal `\rightarrow`.
 */
object LatexSymbols {

    private val symbols = mapOf(
        // Arrows
        "rightarrow" to "\u2192",
        "to" to "\u2192",
        "longrightarrow" to "\u27F6",
        "leftarrow" to "\u2190",
        "gets" to "\u2190",
        "longleftarrow" to "\u27F5",
        "leftrightarrow" to "\u2194",
        "Rightarrow" to "\u21D2",
        "implies" to "\u21D2",
        "Longrightarrow" to "\u27F9",
        "Leftarrow" to "\u21D0",
        "impliedby" to "\u21D0",
        "Longleftarrow" to "\u27F8",
        "Leftrightarrow" to "\u21D4",
        "iff" to "\u21D4",
        "Longleftrightarrow" to "\u27FA",
        "mapsto" to "\u21A6",
        "uparrow" to "\u2191",
        "downarrow" to "\u2193",
        "updownarrow" to "\u2195",
        "Uparrow" to "\u21D1",
        "Downarrow" to "\u21D3",
        "nearrow" to "\u2197",
        "searrow" to "\u2198",
        "swarrow" to "\u2199",
        "nwarrow" to "\u2196",

        // Operators and relations
        "times" to "\u00D7",
        "div" to "\u00F7",
        "cdot" to "\u00B7",
        "pm" to "\u00B1",
        "mp" to "\u2213",
        "leq" to "\u2264",
        "le" to "\u2264",
        "geq" to "\u2265",
        "ge" to "\u2265",
        "neq" to "\u2260",
        "ne" to "\u2260",
        "approx" to "\u2248",
        "equiv" to "\u2261",
        "sim" to "\u223C",
        "propto" to "\u221D",
        "infty" to "\u221E",
        "sum" to "\u2211",
        "prod" to "\u220F",
        "int" to "\u222B",
        "partial" to "\u2202",
        "nabla" to "\u2207",
        "forall" to "\u2200",
        "exists" to "\u2203",
        "nexists" to "\u2204",
        "in" to "\u2208",
        "notin" to "\u2209",
        "ni" to "\u220B",
        "subset" to "\u2282",
        "supset" to "\u2283",
        "subseteq" to "\u2286",
        "supseteq" to "\u2287",
        "cup" to "\u222A",
        "cap" to "\u2229",
        "land" to "\u2227",
        "lor" to "\u2228",
        "neg" to "\u00AC",
        "lnot" to "\u00AC",
        "emptyset" to "\u2205",
        "varnothing" to "\u2205",
        "therefore" to "\u2234",
        "because" to "\u2235",
        "ldots" to "\u2026",
        "dots" to "\u2026",
        "cdots" to "\u22EF",
        "circ" to "\u2218",
        "degree" to "\u00B0",
        "prime" to "\u2032",
        "ast" to "\u2217",
        "star" to "\u22C6",
        "bullet" to "\u2022",
        "oplus" to "\u2295",
        "otimes" to "\u2297",
        "perp" to "\u22A5",
        "parallel" to "\u2225",
        "angle" to "\u2220",
        "triangle" to "\u25B3",

        // Greek
        "alpha" to "\u03B1",
        "beta" to "\u03B2",
        "gamma" to "\u03B3",
        "delta" to "\u03B4",
        "epsilon" to "\u03B5",
        "varepsilon" to "\u03F5",
        "zeta" to "\u03B6",
        "eta" to "\u03B7",
        "theta" to "\u03B8",
        "vartheta" to "\u03D1",
        "iota" to "\u03B9",
        "kappa" to "\u03BA",
        "lambda" to "\u03BB",
        "mu" to "\u03BC",
        "nu" to "\u03BD",
        "xi" to "\u03BE",
        "pi" to "\u03C0",
        "varpi" to "\u03D6",
        "rho" to "\u03C1",
        "sigma" to "\u03C3",
        "varsigma" to "\u03C2",
        "tau" to "\u03C4",
        "upsilon" to "\u03C5",
        "phi" to "\u03C6",
        "varphi" to "\u03D5",
        "chi" to "\u03C7",
        "psi" to "\u03C8",
        "omega" to "\u03C9",
        "Gamma" to "\u0393",
        "Delta" to "\u0394",
        "Theta" to "\u0398",
        "Lambda" to "\u039B",
        "Xi" to "\u039E",
        "Pi" to "\u03A0",
        "Sigma" to "\u03A3",
        "Upsilon" to "\u03A5",
        "Phi" to "\u03A6",
        "Psi" to "\u03A8",
        "Omega" to "\u03A9"
    )

    private val textCommands = setOf(
        "text", "textrm", "mathrm", "mathbf", "mathit", "operatorname"
    )

    /** Converts common inline LaTeX commands to their Unicode glyphs. */
    fun toInlineText(latex: String): String {
        if (latex.isEmpty() || '\\' !in latex) return latex

        val out = StringBuilder(latex.length)
        var index = 0
        while (index < latex.length) {
            val char = latex[index]
            if (char != '\\' || index + 1 >= latex.length) {
                out.append(char)
                index++
                continue
            }

            val next = latex[index + 1]
            if (!next.isLetter()) {
                when (next) {
                    ',', ';', ':', ' ' -> out.append(' ')
                    '\\' -> out.append('\n')
                    '%', '$', '&', '#', '_', '{', '}' -> out.append(next)
                    else -> out.append(next)
                }
                index += 2
                continue
            }

            var end = index + 1
            while (end < latex.length && latex[end].isLetter()) end++
            val name = latex.substring(index + 1, end)

            if (name in textCommands) {
                var brace = end
                while (brace < latex.length && latex[brace].isWhitespace()) brace++
                if (brace < latex.length && latex[brace] == '{') {
                    val close = matchingBrace(latex, brace)
                    if (close != null) {
                        out.append(latex, brace + 1, close)
                        index = close + 1
                        continue
                    }
                }
            }

            val symbol = symbols[name]
            if (symbol != null) {
                out.append(symbol)
            } else {
                // Preserve commands we do not know instead of silently
                // deleting part of the formula.
                out.append('\\').append(name)
            }
            index = end
        }
        return out.toString()
    }

    private fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
