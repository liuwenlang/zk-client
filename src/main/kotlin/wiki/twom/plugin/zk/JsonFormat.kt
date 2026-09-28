package wiki.twom.plugin.zk

/**
 * Minimal JSON pretty-printer: re-indents valid JSON without pulling in a parser dependency.
 * Strings (including escapes) are copied verbatim; structure must be balanced.
 */
object JsonFormat {

    fun tryFormat(source: String, indent: String = "  "): String {
        val s = source.trim()
        require(s.startsWith("{") || s.startsWith("[")) { "Not a JSON object or array" }

        val out = StringBuilder()
        val stack = ArrayDeque<Char>()
        var depth = 0
        var newlinePending = false
        var i = 0

        fun emit(text: String) {
            if (newlinePending) {
                out.append('\n').append(indent.repeat(depth))
                newlinePending = false
            }
            out.append(text)
        }

        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++

                c == '{' || c == '[' -> {
                    val close = if (c == '{') '}' else ']'
                    val j = nextNonWs(s, i + 1)
                    if (j < s.length && s[j] == close) {
                        emit(c.toString() + close.toString())
                        i = j + 1
                    } else {
                        emit(c.toString())
                        stack.addLast(c)
                        depth++
                        newlinePending = true
                        i++
                    }
                }

                c == '}' || c == ']' -> {
                    val open = if (c == '}') '{' else '['
                    require(stack.removeLastOrNull() == open) { "Unbalanced brackets at offset $i" }
                    depth--
                    newlinePending = false
                    out.append('\n').append(indent.repeat(depth)).append(c)
                    i++
                }

                c == ',' -> {
                    out.append(',')
                    newlinePending = true
                    i++
                }

                c == ':' -> {
                    out.append(": ")
                    i++
                }

                c == '"' -> {
                    val end = stringEnd(s, i)
                    emit(s.substring(i, end))
                    i = end
                }

                else -> {
                    val j = tokenEnd(s, i)
                    require(j > i) { "Unexpected character '$c' at offset $i" }
                    emit(s.substring(i, j))
                    i = j
                }
            }
        }
        require(stack.isEmpty()) { "Unbalanced brackets: unclosed '${stack.lastOrNull()}'" }
        require(!newlinePending) { "Trailing separator" }
        return out.toString()
    }

    fun isJson(source: String): Boolean =
        source.trimStart().startsWith("{") || source.trimStart().startsWith("[")

    private fun nextNonWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** Returns the index just past the closing quote of the string starting at [from]. */
    private fun stringEnd(s: String, from: Int): Int {
        var i = from + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        throw IllegalArgumentException("Unterminated string at offset $from")
    }

    private fun tokenEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.eE")) i++
        return i
    }
}
