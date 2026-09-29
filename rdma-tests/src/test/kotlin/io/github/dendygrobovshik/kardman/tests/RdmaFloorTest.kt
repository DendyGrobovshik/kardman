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
package io.github.dendygrobovshik.kardman.tests

import io.github.dendygrobovshik.kardman.kernel.RdmaFloor
import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.ChangelogChange
import io.github.dendygrobovshik.kardman.types.ChangelogChangeKind
import io.github.dendygrobovshik.kardman.types.ChangelogEntry
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaUse
import org.junit.Test
import kotlin.test.assertEquals

class RdmaFloorTest {

    private fun decl(fqn: String, emulatable: Boolean = true) =
        RdmaDeclaration(fqn, RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", isRdma = true, emulatable = emulatable)

    private fun cls(fqn: String, emulatable: Boolean = true) =
        RdmaDeclaration(fqn, RdmaSymbolKind.CLASS, "h", isRdma = true, emulatable = emulatable)

    private fun changelog(module: String, vararg introduced: Pair<String, Int>): Changelog {
        val entries = introduced.map { (fqn, version) ->
            ChangelogEntry(version, "t", module, listOf(ChangelogChange(fqn, ChangelogChangeKind.ADD, "h-$fqn")))
        }
        return Changelog(h = entries.maxOfOrNull { it.version } ?: 0, entries = entries)
    }

    @Test
    fun `non-emulatable anchor gets its introduction version as floor`() {
        val c = changelog("m", "widget" to 5)
        val analysis = RdmaAnalysis("m", declarations = listOf(decl("widget", emulatable = false)))
        assertEquals(mapOf("widget" to 5), RdmaFloor.compute(c, analysis))
    }

    @Test
    fun `emulatable node transitively using an anchor inherits its floor`() {
        val c = changelog("m", "widget" to 5)
        val analysis = RdmaAnalysis(
            "m",
            declarations = listOf(decl("widget", emulatable = false), decl("helper"), decl("api")),
            uses = listOf(RdmaUse("api", "helper"), RdmaUse("helper", "widget")),
        )
        val floor = RdmaFloor.compute(c, analysis)
        assertEquals(5, floor["api"])
        assertEquals(5, floor["helper"])
        assertEquals(5, floor["widget"])
    }

    @Test
    fun `emulatable symbol with no non-emulatable closure has floor zero`() {
        val c = changelog("m", "widget" to 5)
        val analysis = RdmaAnalysis(
            "m",
            declarations = listOf(decl("widget", emulatable = false), decl("plain")),
            uses = emptyList(),
        )
        val floor = RdmaFloor.compute(c, analysis)
        assertEquals(null, floor["plain"])
        assertEquals(0, RdmaFloor.minHost(floor, setOf("plain")))
    }

    @Test
    fun `floor is the max over multiple reachable anchors`() {
        val c = changelog("m", "w1" to 3, "w2" to 7)
        val analysis = RdmaAnalysis(
            "m",
            declarations = listOf(decl("w1", emulatable = false), decl("w2", emulatable = false), decl("api")),
            uses = listOf(RdmaUse("api", "w1"), RdmaUse("api", "w2")),
        )
        assertEquals(7, RdmaFloor.compute(c, analysis)["api"])
    }

    @Test
    fun `class node floor covers its members uses`() {
        val c = changelog("m", "widget" to 6)
        val analysis = RdmaAnalysis(
            "m",
            declarations = listOf(decl("widget", emulatable = false), cls("Person"), decl("Person.greet", emulatable = true)),
            uses = listOf(RdmaUse("Person", "widget")),
        )
        assertEquals(6, RdmaFloor.compute(c, analysis)["Person"])
    }

    @Test
    fun `minHost is max floor over used symbols and zero for none`() {
        val floor = mapOf("a" to 4, "b" to 0, "c" to 9)
        assertEquals(9, RdmaFloor.minHost(floor, setOf("a", "b", "c")))
        assertEquals(4, RdmaFloor.minHost(floor, setOf("a", "b")))
        assertEquals(0, RdmaFloor.minHost(floor, setOf("b")))
        assertEquals(0, RdmaFloor.minHost(emptyMap(), setOf("x")))
    }
}
