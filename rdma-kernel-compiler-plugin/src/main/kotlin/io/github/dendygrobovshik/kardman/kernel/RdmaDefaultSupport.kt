/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dendygrobovshik.kardman.kernel

/**
 * Shared helpers for materializing Kotlin default-parameter expressions into the generated native
 * wrappers and the dynamic polyfill prologue.
 *
 * A default expression is kept verbatim (so it compiles in native and JS alike), with two textual
 * rewrites applied:
 *  - cross-parameter references are resolved by emitting ordered `val <name>` declarations (the
 *    generators rename the raw parameters to `_pN` and declare `val <name> = <resolved>` in order);
 *  - the receiver `this` of a defaulted method is rewritten to `self` (the wrapper's receiver
 *    parameter / a locally-declared `val self`).
 */
object RdmaDefaultSupport {

    /** `mask and (1 shl i) != 0` — true when parameter [i] was omitted and its default applies. */
    fun omitted(i: Int): String = "mask and ${1 shl i} != 0"

    /** The raw, collision-free name of parameter [i] after renaming (`_p0`, `_p1`, …). */
    fun raw(i: Int): String = "_p$i"

    /**
     * Recovers default-parameter text from the source of a callable by locating [keyword] (e.g.
     * `fun ` for a function/method, `class Name` for a primary constructor) within `[startOffset,
     * endOffset)` and parsing the parameter list that follows. The Compose compiler nulls
     * `IrValueParameter.defaultValue` after lowering `@Composable` functions, and the offset-based
     * default text drops receiver/qualifier prefixes (`this.step` → `step`), so generators re-parse
     * the source to recover the full default expression. Returns a map from parameter index to the
     * trimmed default text.
     */
    fun defaultValuesFromDecl(source: String, startOffset: Int, endOffset: Int, keyword: String): Map<Int, String> {
        val start = startOffset.coerceAtLeast(0)
        val kwIdx = source.indexOf(keyword, start)
        if (kwIdx < 0 || kwIdx > endOffset) return emptyMap()
        val open = source.indexOf('(', kwIdx)
        if (open < 0 || open > endOffset) return emptyMap()
        val close = matchingParen(source, open) ?: return emptyMap()
        val params = splitParams(source.substring(open + 1, close))
        val result = mutableMapOf<Int, String>()
        params.forEachIndexed { i, p ->
            val eq = topLevelIndex(p, '=')
            if (eq >= 0) result[i] = p.substring(eq + 1).trim()
        }
        return result
    }

    /** Convenience for top-level functions and methods: locates the `fun <name>(…)` parameter list. */
    fun defaultValuesFromFunctionSource(source: String, startOffset: Int, endOffset: Int): Map<Int, String> =
        defaultValuesFromDecl(source, startOffset, endOffset, "fun ")

    /**
     * Recovers each parameter's DECLARED nullability from the source parameter list
     * (`text: String` -> false, `title: String?` -> true). The compose lowering widens
     * defaulted parameter types to nullable in the IR, so the IR type cannot be
     * trusted for this: the widget's real contract is the source declaration.
     * Returns a map from parameter index to the declared nullability.
     */
    fun declaredNullableFromDecl(source: String, startOffset: Int, endOffset: Int, keyword: String): Map<Int, Boolean> {
        val start = startOffset.coerceAtLeast(0)
        val kwIdx = source.indexOf(keyword, start)
        if (kwIdx < 0 || kwIdx > endOffset) return emptyMap()
        val open = source.indexOf('(', kwIdx)
        if (open < 0 || open > endOffset) return emptyMap()
        val close = matchingParen(source, open) ?: return emptyMap()
        val params = splitParams(source.substring(open + 1, close))
        val result = mutableMapOf<Int, Boolean>()
        params.forEachIndexed { i, p ->
            val eq = topLevelIndex(p, '=')
            val typeText = if (eq >= 0) p.substring(0, eq) else p
            val colon = topLevelIndex(typeText, ':')
            if (colon >= 0) {
                result[i] = typeText.substring(colon + 1).trim().endsWith('?')
            }
        }
        return result
    }

    /** Convenience for top-level functions and methods: locates the `fun <name>(…)` parameter list. */
    fun declaredNullableFromFunctionSource(source: String, startOffset: Int, endOffset: Int): Map<Int, Boolean> =
        declaredNullableFromDecl(source, startOffset, endOffset, "fun ")

    /** Splits a parameter list on top-level commas (see [scanTopLevel]: brackets, lambdas, literals and comments do not split). */
    fun splitParams(s: String): List<String> {
        val params = mutableListOf<String>()
        var from = 0
        scanTopLevel(s) { i, c ->
            if (c == ',') {
                params.add(s.substring(from, i).trim())
                from = i + 1
            }
            false
        }
        if (s.substring(from).isNotBlank()) params.add(s.substring(from).trim())
        return params
    }

    /** The index of the first top-level occurrence of [ch], or -1. See [scanTopLevel]. */
    fun topLevelIndex(s: String, ch: Char): Int {
        var found = -1
        scanTopLevel(s) { i, c -> (c == ch).also { if (it) found = i } }
        return found
    }

    private fun matchingParen(source: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < source.length) {
            val skipped = skipLiteralOrComment(source, i)
            if (skipped != i) { i = skipped; continue }
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * Calls [onTopLevel] for every character outside brackets — `()`, `[]`, `{}` (lambdas) and
     * generic `<>` — and outside string/char literals and comments; stops when it returns true.
     * A `<` opens a generic only right after an identifier (`List<Int>`), so a comparison in a
     * default value (`a < b`) does not swallow the rest of the list; `->` is not a bracket.
     */
    private fun scanTopLevel(s: String, onTopLevel: (Int, Char) -> Boolean) {
        var depth = 0
        var angle = 0
        var i = 0
        while (i < s.length) {
            val skipped = skipLiteralOrComment(s, i)
            if (skipped != i) { i = skipped; continue }
            val c = s[i]
            when {
                c == '-' && i + 1 < s.length && s[i + 1] == '>' -> i++
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> if (depth > 0) depth--
                c == '<' && i > 0 && (s[i - 1].isLetterOrDigit() || s[i - 1] == '_' || s[i - 1] == '?') -> angle++
                c == '>' && angle > 0 -> angle--
                depth == 0 && angle == 0 -> if (onTopLevel(i, c)) return
            }
            i++
        }
    }

    /**
     * When a string literal (plain or raw, with `${…}` templates), a char literal or a comment
     * starts at [i], the index just after it; otherwise [i].
     */
    private fun skipLiteralOrComment(s: String, i: Int): Int {
        if (i >= s.length) return i
        val c = s[i]
        return when {
            c == '/' && i + 1 < s.length && s[i + 1] == '/' -> s.indexOf('\n', i).let { if (it < 0) s.length else it }
            c == '/' && i + 1 < s.length && s[i + 1] == '*' -> s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
            c == '\'' -> {
                var j = i + 1
                while (j < s.length && s[j] != '\'') j += if (s[j] == '\\') 2 else 1
                minOf(j + 1, s.length)
            }
            c == '"' && s.startsWith("\"\"\"", i) -> {
                val close = s.indexOf("\"\"\"", i + 3)
                if (close < 0) s.length else close + 3
            }
            c == '"' -> {
                var j = i + 1
                while (j < s.length && s[j] != '"') {
                    j = when {
                        s[j] == '\\' -> j + 2
                        s[j] == '$' && j + 1 < s.length && s[j + 1] == '{' -> skipTemplate(s, j + 1)
                        else -> j + 1
                    }
                }
                minOf(j + 1, s.length)
            }
            else -> i
        }
    }

    /** From the `{` of a `${…}` template, the index just after its matching `}`. */
    private fun skipTemplate(s: String, open: Int): Int {
        var depth = 0
        var j = open
        while (j < s.length) {
            val skipped = skipLiteralOrComment(s, j)
            if (skipped != j) { j = skipped; continue }
            when (s[j]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return j + 1 }
            }
            j++
        }
        return s.length
    }

    /**
     * Rewrites the bare `this` keyword to `self`, skipping string literals (including
     * triple-quoted), char literals, line comments and block comments. A labeled `this@Label` is
     * left untouched: it is not the receiver of the defaulted method itself.
     */
    fun rewriteThis(source: String): String {
        val sb = StringBuilder(source.length + 8)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' -> {
                    if (i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"') {
                        sb.append(source, i, i + 3)
                        i += 3
                        while (i < source.length) {
                            if (source[i] == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"') {
                                sb.append(source, i, i + 3)
                                i += 3
                                break
                            }
                            sb.append(source[i])
                            i++
                        }
                    } else {
                        sb.append(c)
                        i++
                        while (i < source.length) {
                            val ch = source[i]
                            sb.append(ch)
                            i++
                            if (ch == '\\' && i < source.length) { sb.append(source[i]); i++; continue }
                            if (ch == '"') break
                        }
                    }
                }
                c == '\'' -> {
                    sb.append(c)
                    i++
                    while (i < source.length) {
                        val ch = source[i]
                        sb.append(ch)
                        i++
                        if (ch == '\\' && i < source.length) { sb.append(source[i]); i++; continue }
                        if (ch == '\'') break
                    }
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') { sb.append(source[i]); i++ }
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    sb.append("/*")
                    i += 2
                    while (i < source.length) {
                        if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                            sb.append("*/")
                            i += 2
                            break
                        }
                        sb.append(source[i])
                        i++
                    }
                }
                else -> {
                    if (source.startsWith("this", i) &&
                        !isIdentChar(source, i - 1) &&
                        !isIdentChar(source, i + 4) &&
                        !(i + 4 < source.length && source[i + 4] == '@')
                    ) {
                        sb.append("self")
                        i += 4
                    } else {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun isIdentChar(s: String, idx: Int): Boolean =
        idx >= 0 && idx < s.length && (s[idx].isLetterOrDigit() || s[idx] == '_')
}
