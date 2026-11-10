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

import org.junit.Test
import kotlin.test.assertEquals

class RdmaDefaultSupportTest {

    @Test
    fun `rewriteThis replaces bare this and skips strings chars comments`() {
        assertEquals("self.step", RdmaDefaultSupport.rewriteThis("this.step"))
        assertEquals("self.method() + 1", RdmaDefaultSupport.rewriteThis("this.method() + 1"))
        // String literals are left untouched.
        assertEquals("\"this\" + self.x", RdmaDefaultSupport.rewriteThis("\"this\" + this.x"))
        // Char literals are left untouched.
        assertEquals("'t' + self.x", RdmaDefaultSupport.rewriteThis("'t' + this.x"))
        // Labeled this is left untouched.
        assertEquals("this@Foo", RdmaDefaultSupport.rewriteThis("this@Foo"))
        // Comments are left untouched.
        assertEquals("// this\nself.x", RdmaDefaultSupport.rewriteThis("// this\nthis.x"))
        assertEquals("/* this */ self.x", RdmaDefaultSupport.rewriteThis("/* this */ this.x"))
    }

    @Test
    fun `splitParams handles arrow types`() {
        val params = RdmaDefaultSupport.splitParams("a: Int, b: (Int, String) -> Unit = {}, c: List<Int>")
        assertEquals(3, params.size)
        assertEquals("a: Int", params[0])
        assertEquals("b: (Int, String) -> Unit = {}", params[1])
        assertEquals("c: List<Int>", params[2])
    }

    @Test
    fun `topLevelIndex skips arrow and generic contents`() {
        assertEquals(7, RdmaDefaultSupport.topLevelIndex("x: Int = 5", '='))
        assertEquals(-1, RdmaDefaultSupport.topLevelIndex("x: Int", '='))
        // The `=` inside the arrow type's parentheses is not top-level.
        assertEquals(16, RdmaDefaultSupport.topLevelIndex("f: (Int) -> Int = 5", '='))
        assertEquals(-1, RdmaDefaultSupport.topLevelIndex("f: (Int) -> Int", '='))
    }

    @Test
    fun `defaultValuesFromDecl parses function defaults`() {
        val src = "fun foo(a: Int, b: Int = a * 2): Int = a + b"
        val defaults = RdmaDefaultSupport.defaultValuesFromDecl(src, 0, src.length, "fun ")
        assertEquals(mapOf(1 to "a * 2"), defaults)
    }

    @Test
    fun `defaultValuesFromDecl parses constructor defaults`() {
        val src = "class Counter(val step: Int = 1, val name: String = \"n\")"
        val defaults = RdmaDefaultSupport.defaultValuesFromDecl(src, 0, src.length, "class Counter")
        assertEquals(mapOf(0 to "1", 1 to "\"n\""), defaults)
    }

    @Test
    fun `omitted and raw render correctly`() {
        assertEquals("mask and 1 != 0", RdmaDefaultSupport.omitted(0))
        assertEquals("mask and 4 != 0", RdmaDefaultSupport.omitted(2))
        assertEquals("_p0", RdmaDefaultSupport.raw(0))
        assertEquals("_p3", RdmaDefaultSupport.raw(3))
    }
}
