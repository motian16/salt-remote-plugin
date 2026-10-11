package io.github.motian16.spwremote

/**
 * 极简 JSON 实现。
 *
 * 不引入任何第三方 JSON 库，避免把额外 jar 打进 .spmod 的 lib/ 目录
 * （宿主运行时堆只有 512M，插件包越小越好）。
 * 只覆盖本插件协议需要的子集：对象、数组、字符串、数字、布尔、null。
 */
sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    fun asStringOrNull(): String? = (this as? Str)?.value
    fun asBoolOrNull(): Boolean? = (this as? Bool)?.value
    fun asLongOrNull(): Long? = when (this) {
        is Num -> value.toLong()
        is Str -> value.trim().toLongOrNull()
        else -> null
    }

    fun encode(): String = StringBuilder().also { write(it) }.toString()

    private fun write(out: StringBuilder) {
        when (this) {
            is Null -> out.append("null")
            is Bool -> out.append(if (value) "true" else "false")
            is Num -> {
                // 整数不带小数点，读起来更像协议文档里的样子
                if (value == Math.floor(value) && !value.isInfinite()) {
                    out.append(value.toLong())
                } else {
                    out.append(value)
                }
            }
            is Str -> writeString(out, value)
            is Arr -> {
                out.append('[')
                items.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    v.write(out)
                }
                out.append(']')
            }
            is Obj -> {
                out.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k)
                    out.append(':')
                    v.write(out)
                }
                out.append('}')
            }
        }
    }

    companion object {
        private fun writeString(out: StringBuilder, s: String) {
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else -> if (c < ' ') {
                        out.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        out.append(c)
                    }
                }
            }
            out.append('"')
        }

        /** 构造对象的便捷方法，值为 null 的字段会被忽略 */
        fun obj(vararg pairs: Pair<String, JsonValue?>): Obj {
            val m = LinkedHashMap<String, JsonValue>()
            for ((k, v) in pairs) if (v != null) m[k] = v
            return Obj(m)
        }

        fun of(s: String?): JsonValue = if (s == null) Null else Str(s)
        fun of(b: Boolean): JsonValue = Bool(b)
        fun of(n: Long): JsonValue = Num(n.toDouble())
        fun of(n: Int): JsonValue = Num(n.toDouble())
        fun of(n: Double): JsonValue = Num(n)

        fun parse(text: String): JsonValue = Parser(text).parseValue()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): JsonValue {
            skipWs()
            require(i < s.length) { "JSON 为空" }
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> if (c == '-' || c.isDigit()) parseNumber()
                else error("JSON 第 $i 位出现意外字符 '$c'")
            }
        }

        private fun parseObject(): Obj {
            i++ // {
            val m = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') { i++; return Obj(m) }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(peek() == ':') { "JSON 期望 ':' 于第 $i 位" }
                i++
                m[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return Obj(m) }
                    else -> error("JSON 期望 ',' 或 '}' 于第 $i 位")
                }
            }
        }

        private fun parseArray(): Arr {
            i++ // [
            val list = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') { i++; return Arr(list) }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return Arr(list) }
                    else -> error("JSON 期望 ',' 或 ']' 于第 $i 位")
                }
            }
        }

        private fun parseString(): String {
            require(peek() == '"') { "JSON 期望字符串于第 $i 位" }
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "JSON 字符串未闭合" }
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("JSON 非法转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Num {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        s[i] == '+' || s[i] == '-')
            ) {
                i++
            }
            return Num(s.substring(start, i).toDouble())
        }

        private fun peek(): Char = if (i < s.length) s[i] else error("JSON 意外结束")

        private fun expect(literal: String) {
            require(s.startsWith(literal, i)) { "JSON 期望 '$literal' 于第 $i 位" }
            i += literal.length
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}

/** 解析失败时返回 null，方便在协议层统一回 error 消息 */
fun parseJsonOrNull(text: String): JsonValue? = try {
    JsonValue.parse(text)
} catch (_: Throwable) {
    null
}
