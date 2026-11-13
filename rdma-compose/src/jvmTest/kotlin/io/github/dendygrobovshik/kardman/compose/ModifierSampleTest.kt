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
package io.github.dendygrobovshik.kardman.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ModifierSampleTest {
    @Test
    fun `shipped plugin source matches the modifiers example`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "rdma-compose").isDirectory && File(it, "demo").isDirectory }
        val generated = File(
            root,
            "demo/plugin/generated/src/kotlin/com/example/plugin/generated/Generated.kt",
        ).readText().trim()
        val examples = File(root, "demo/shared-ui/src/commonMain/kotlin/org/example/ui/Examples.kt").readText()
        val header = "Example(\"Modifiers\", \"\"\""
        val start = examples.indexOf(header)
        check(start >= 0) { "Modifiers example is missing" }
        val bodyStart = start + header.length
        val bodyEnd = examples.indexOf("\"\"\")", bodyStart)
        check(bodyEnd > bodyStart) { "Modifiers example is not closed" }
        assertEquals(generated, examples.substring(bodyStart, bodyEnd).trim())
    }
}
