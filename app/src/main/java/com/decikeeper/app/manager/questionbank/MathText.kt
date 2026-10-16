package com.decikeeper.app.manager.questionbank

/**
 * 题目里的“数学写法”轻量解析：把 `e^2`、`lim_{x→∞}`、`\frac{a}{b}`、`\sqrt{x}`、`\alpha`
 * 这类 LaTeX/纯文本混合写法，拆成“普通文字 / 上标 / 下标”的片段，由界面层渲染成真正的上下标。
 *
 * 纯 Kotlin、不依赖 Android，便于 JVM 单测。设计原则：
 * 1. 不含 `\ ^ _ $` 的文字原样返回，普通题目完全不受影响；
 * 2. 宁可不转换也不乱转换：`my_file`、`____` 这类下划线、没有底数的 `^`、`{集合}` 都保持原样；
 * 3. 能读懂不规范写法（如 `lim_{x→∞}`），不要求是严格的 LaTeX。
 */
internal object MathText {

    sealed class Piece {
        data class Plain(val text: String) : Piece()
        data class Sup(val pieces: List<Piece>) : Piece()
        data class Sub(val pieces: List<Piece>) : Piece()
    }

    /** 是否可能含数学写法；不含时调用方可直接使用原文。 */
    fun needsFormatting(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.any { it == '\\' || it == '^' || it == '_' || it == '$' }
    }

    fun parse(input: String): List<Piece> {
        if (!needsFormatting(input)) return listOf(Piece.Plain(input))
        return Parser(stripMathDelimiters(input)).parseAll()
    }

    /** 把片段还原成可读字符串，上标写成 `^[..]`、下标写成 `_[..]`；用于测试与无法使用 span 的场景。 */
    fun debugString(pieces: List<Piece>): String = buildString {
        pieces.forEach {
            when (it) {
                is Piece.Plain -> append(it.text)
                is Piece.Sup -> append("^[").append(debugString(it.pieces)).append("]")
                is Piece.Sub -> append("_[").append(debugString(it.pieces)).append("]")
            }
        }
    }

    // ── 公式段落：$...$ $$...$$ \(...\) \[...\] ─────────────────────────────

    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Formula(val latex: String) : Segment()
    }

    /**
     * 按定界符把文字切成“普通文字”和“公式”两种段落。
     * 写法不明确的美元符号（如 `$5 和 $6`：内部以空白开头或结尾、或没有配对）一律当作普通文字。
     */
    fun splitFormulas(input: String): List<Segment> {
        if (input.indexOf('$') < 0 && !input.contains("\\(") && !input.contains("\\[")) {
            return listOf(Segment.Text(input))
        }
        val out = mutableListOf<Segment>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out.add(Segment.Text(buf.toString())); buf.setLength(0) } }
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                val n = input[i + 1]
                val close = when (n) { '(' -> "\\)"; '[' -> "\\]"; else -> null }
                if (close != null) {
                    val end = input.indexOf(close, i + 2)
                    if (end > i + 2 && input.substring(i + 2, end).isNotBlank()) {
                        flush(); out.add(Segment.Formula(input.substring(i + 2, end).trim())); i = end + 2; continue
                    }
                }
                // 其它转义（含 \$）原样保留，并整体跳过，避免把 \$ 当成定界符
                buf.append(c).append(n); i += 2; continue
            }
            if (c == '$') {
                val double = i + 1 < input.length && input[i + 1] == '$'
                val open = if (double) 2 else 1
                val closeToken = if (double) "$$" else "$"
                val end = findClosingDollar(input, i + open, closeToken)
                if (end > 0) {
                    val inner = input.substring(i + open, end)
                    if (isValidDollarInner(inner)) {
                        flush(); out.add(Segment.Formula(inner.trim())); i = end + closeToken.length; continue
                    }
                }
            }
            buf.append(c); i++
        }
        flush()
        return out
    }

    private fun findClosingDollar(s: String, from: Int, token: String): Int {
        var k = from
        while (k < s.length) {
            if (s[k] == '\\') { k += 2; continue }
            if (s.startsWith(token, k)) return k
            k++
        }
        return -1
    }

    private fun isValidDollarInner(inner: String): Boolean =
        inner.isNotEmpty() && !inner.first().isWhitespace() && !inner.last().isWhitespace()

    /** 排版引擎的字体里没有中文：含中日韩字符的公式退回轻量显示。 */
    fun isEngineEligible(latex: String): Boolean = latex.none {
        it.code in 0x2E80..0x9FFF || it.code in 0xFF00..0xFFEF || it.code in 0x3000..0x303F
    }

    // ── 分隔符：$...$、$$...$$、\( \)、\[ \] ───────────────────────────────

    private fun stripMathDelimiters(text: String): String {
        var s = text.replace("\\(", "").replace("\\)", "").replace("\\[", "").replace("\\]", "")
        val dollars = s.count { it == '$' }
        if (dollars >= 2 && dollars % 2 == 0) s = s.replace("$", "")
        return s
    }

    // ── 命令表 ────────────────────────────────────────────────────────────

    private val SYMBOLS: Map<String, String> = mapOf(
        "infty" to "∞", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⇒", "iff" to "⇔",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "pm" to "±", "mp" to "∓",
        "times" to "×", "cdot" to "·", "div" to "÷", "ast" to "∗", "circ" to "∘", "bullet" to "•",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮",
        "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "neg" to "¬",
        "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
        "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
        "land" to "∧", "lor" to "∨", "wedge" to "∧", "vee" to "∨", "perp" to "⊥", "parallel" to "∥",
        "angle" to "∠", "triangle" to "△", "degree" to "°", "ldots" to "…", "dots" to "…", "cdots" to "⋯",
        "vdots" to "⋮", "prime" to "′", "mid" to "∣", "setminus" to "∖", "oplus" to "⊕", "otimes" to "⊗",
        "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉", "lfloor" to "⌊", "rfloor" to "⌋",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", ":" to " ", "!" to "",
        " " to " ", "{" to "{", "}" to "}", "%" to "%", "$" to "$", "_" to "_", "&" to "&", "#" to "#",
        "\\" to " ", "|" to "‖", "backslash" to "\\"
    )

    /** 这些函数名只需要去掉反斜杠。 */
    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "ln", "log", "lg", "exp", "lim", "max", "min", "sup", "inf", "det", "dim", "gcd", "deg", "ker",
        "limsup", "liminf", "arg", "Pr", "mod", "bmod"
    )

    /** 只留内容的修饰命令。 */
    private val PASS_THROUGH = setOf(
        "text", "textbf", "textit", "mathrm", "mathbf", "mathit", "mathbb", "mathcal", "mathsf",
        "operatorname", "boldsymbol", "bm", "mbox", "overline", "underline", "widehat", "widetilde"
    )

    /** 完全忽略的排版命令。 */
    private val IGNORED = setOf(
        "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr",
        "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "nonumber", "label"
    )

    private val COMBINING = mapOf("vec" to '⃗', "hat" to '̂', "bar" to '̄', "tilde" to '̃', "dot" to '̇')

    // ── 解析器 ────────────────────────────────────────────────────────────

    private class Builder {
        val pieces = mutableListOf<Piece>()
        private val sb = StringBuilder()

        fun char(c: Char) { sb.append(c) }
        fun text(t: String) { sb.append(t) }

        fun add(p: Piece) {
            flush()
            pieces.add(p)
        }

        fun addAll(list: List<Piece>) {
            list.forEach { if (it is Piece.Plain) text(it.text) else add(it) }
        }

        /** 上一个可见字符（用于判断 ^ 与 _ 前是否有底数）。 */
        fun lastChar(): Char? {
            if (sb.isNotEmpty()) return sb.last()
            return when (val last = pieces.lastOrNull()) {
                is Piece.Plain -> last.text.lastOrNull()
                is Piece.Sup, is Piece.Sub -> '}'   // x_i^2、C_n^m：上下标之后还可以再接上下标
                null -> null
            }
        }

        fun flush() {
            if (sb.isNotEmpty()) {
                pieces.add(Piece.Plain(sb.toString()))
                sb.setLength(0)
            }
        }

        fun build(): List<Piece> {
            flush()
            // 合并相邻普通文字
            val merged = mutableListOf<Piece>()
            pieces.forEach { p ->
                val last = merged.lastOrNull()
                if (p is Piece.Plain && last is Piece.Plain) merged[merged.size - 1] = Piece.Plain(last.text + p.text)
                else merged.add(p)
            }
            return merged.filterNot { it is Piece.Plain && it.text.isEmpty() }
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseAll(): List<Piece> = parseSeq(stopAtBrace = false)

        /** 解析到字符串结束，或（stopAtBrace 时）遇到未配对的 `}` 为止，`}` 由调用方消耗。 */
        fun parseSeq(stopAtBrace: Boolean): List<Piece> {
            val out = Builder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '}' && stopAtBrace -> return out.build()
                    c == '{' && stopAtBrace -> {
                        i++
                        out.addAll(parseSeq(true))
                        if (i < s.length && s[i] == '}') i++
                    }
                    c == '\\' -> command(out)
                    c == '^' -> script(out, sup = true)
                    c == '_' -> script(out, sup = false)
                    else -> { out.char(c); i++ }
                }
            }
            return out.build()
        }

        private fun isBase(c: Char?): Boolean =
            c != null && (c.isLetterOrDigit() || c == ')' || c == ']' || c == '}' || c == '|' || c == '\'' || c == '′' || c in "∫∑∏∞")

        private fun script(out: Builder, sup: Boolean) {
            val prev = out.lastChar()
            val ok = isBase(prev) && !(prev == '_' ) && tryReadScriptArg(sup)?.let { arg ->
                out.add(if (sup) Piece.Sup(arg) else Piece.Sub(arg))
                true
            } == true
            if (!ok) {
                // 没有底数或写法不明确：保持原样
                if (i < s.length && (s[i] == '^' || s[i] == '_')) { out.char(s[i]); i++ }
            }
        }

        /** 读取上下标的内容；写法不明确返回 null 且不移动位置（调用时 i 指向 ^ 或 _）。 */
        private fun tryReadScriptArg(sup: Boolean): List<Piece>? {
            val start = i
            i++ // 跳过 ^ 或 _
            if (i >= s.length) { i = start; return null }
            val c = s[i]
            when {
                c == '{' -> {
                    i++
                    val inner = parseSeq(true)
                    if (i < s.length && s[i] == '}') i++
                    return inner
                }
                c == '(' && sup -> {
                    // 纯文本写法 e^(2x)：取括号内的内容作为上标，不保留括号
                    val close = matchingParen(i)
                    if (close < 0) { i = start; return null }
                    val inner = Parser(s.substring(i + 1, close)).parseAll()
                    i = close + 1
                    return inner
                }
                c == '\\' -> {
                    val b = Builder()
                    command(b)
                    return b.build().ifEmpty { null }
                }
                c.isDigit() || ((c == '-' || c == '+' || c == '−') && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val b = StringBuilder()
                    b.append(c); i++
                    while (i < s.length && s[i].isDigit()) { b.append(s[i]); i++ }
                    return listOf(Piece.Plain(b.toString()))
                }
                c.isLetter() -> {
                    // 单个字母；后面紧跟字母/数字说明是 my_file 这类标识符，不当作下标
                    val next = s.getOrNull(i + 1)
                    if (next != null && next.isLetterOrDigit() && next.code < 0x2E80) { i = start; return null }
                    i++
                    return listOf(Piece.Plain(c.toString()))
                }
                else -> { i = start; return null }
            }
        }

        private fun matchingParen(open: Int): Int {
            var depth = 0
            for (k in open until s.length) {
                if (s[k] == '(') depth++
                else if (s[k] == ')') { depth--; if (depth == 0) return k }
            }
            return -1
        }

        private fun readName(): String {
            // i 指向反斜杠之后
            if (i >= s.length) return ""
            val c = s[i]
            if (!c.isLetter()) { i++; return c.toString() }
            val st = i
            while (i < s.length && s[i].isLetter()) i++
            return s.substring(st, i)
        }

        /** 读一个命令参数：`{...}` 或单个字符/命令。 */
        private fun readArg(): List<Piece> {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return emptyList()
            if (s[i] == '{') {
                i++
                val inner = parseSeq(true)
                if (i < s.length && s[i] == '}') i++
                return inner
            }
            if (s[i] == '\\') {
                val b = Builder()
                command(b)
                return b.build()
            }
            val ch = s[i]; i++
            return listOf(Piece.Plain(ch.toString()))
        }

        private fun isSimple(pieces: List<Piece>): Boolean {
            if (pieces.size == 1) {
                val p = pieces[0]
                return p is Piece.Plain && p.text.length <= 3 && p.text.all { it.isLetterOrDigit() || it in "∞π" }
            }
            return false
        }

        private fun wrapped(pieces: List<Piece>): List<Piece> =
            if (isSimple(pieces)) pieces else listOf(Piece.Plain("(")) + pieces + listOf(Piece.Plain(")"))

        private fun command(out: Builder) {
            i++ // 跳过反斜杠
            val name = readName()
            when {
                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    val a = readArg()
                    val b = readArg()
                    out.addAll(wrapped(a)); out.text("/"); out.addAll(wrapped(b))
                }
                name == "binom" -> {
                    val a = readArg(); val b = readArg()
                    out.text("C("); out.addAll(a); out.text(", "); out.addAll(b); out.text(")")
                }
                name == "sqrt" -> {
                    var index: List<Piece>? = null
                    if (i < s.length && s[i] == '[') {
                        val close = s.indexOf(']', i)
                        if (close > i) {
                            index = Parser(s.substring(i + 1, close)).parseAll()
                            i = close + 1
                        }
                    }
                    val arg = readArg()
                    if (index != null) out.add(Piece.Sup(index))
                    out.text("√")
                    out.addAll(wrapped(arg))
                }
                COMBINING.containsKey(name) -> {
                    val arg = readArg()
                    val single = arg.singleOrNull() as? Piece.Plain
                    if (single != null && single.text.length == 1) out.text(single.text + COMBINING.getValue(name))
                    else out.addAll(arg)
                }
                name in PASS_THROUGH -> out.addAll(readArg())
                name in IGNORED -> {
                    if (name == "left" || name == "right") {
                        if (i < s.length && s[i] == '.') i++
                    }
                }
                name in FUNCTIONS -> out.text(name)
                SYMBOLS.containsKey(name) -> out.text(SYMBOLS.getValue(name))
                else -> out.text(name) // 未知命令：去掉反斜杠，保留名字
            }
        }
    }
}
