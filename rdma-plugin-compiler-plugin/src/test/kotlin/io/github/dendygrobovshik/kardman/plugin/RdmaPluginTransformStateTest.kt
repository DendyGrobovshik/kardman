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
package io.github.dendygrobovshik.kardman.plugin

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertFalse

class RdmaPluginTransformStateTest {

    private fun rewrite(text: String, actions: (path: String) -> Unit): String {
        val outDir = Files.createTempDirectory("rdma-rewrite").toFile()
        val src = File(outDir, "Probe.kt")
        val path = src.absolutePath
        try {
            RdmaPluginTransformState.outputDir = outDir.absolutePath
            RdmaPluginTransformState.registerText(path, text)
            actions(path)
            RdmaPluginTransformState.flush()
            return File(outDir, "Probe_rdma.kt").readText()
        } finally {
            RdmaPluginTransformState.outputDir = ""
        }
    }

    @Test
    fun `nested rewrite keeps the typeDesc insert sitting on its boundary`() {
        // Shape of `mutableStateOf(Person("ada", 3))`: the state factory inserts
        // `, typeDesc = ...` at the call's closing paren — exactly at the nested
        // constructor rewrite's end offset. A whole-span rewrite drops only edits
        // that START inside its span, so this boundary insert must survive; nested
        // edits must still be dropped (they would double-rewrite embedded text).
        val text = """val os = mutableStateOf(Person("ada", 3))"""
        val ctorStart = text.indexOf("Person(")
        val ctorEnd = ctorStart + """Person("ada", 3)""".length
        val insertAt = text.lastIndexOf(')')
        check(insertAt == ctorEnd) { "expected the insert at the nested span boundary" }

        val out = rewrite(text) { path ->
            RdmaPluginTransformState.addEdit(path, ctorStart + 1, ctorStart + 2, "XXX")
            RdmaPluginTransformState.addEdit(path, insertAt, insertAt, ", typeDesc = \"com.example.Person\"")
            RdmaPluginTransformState.removeEditsWithin(path, ctorStart, ctorEnd)
            RdmaPluginTransformState.addEdit(path, ctorStart, ctorEnd, """js("RDMA.createPerson('ada', 3)")""")
        }

        assertContains(out, """val os = mutableStateOf(js("RDMA.createPerson('ada', 3)"), typeDesc = "com.example.Person")""")
        assertFalse(out.contains("XXX"), "nested edits inside the span must be dropped")
    }
}
