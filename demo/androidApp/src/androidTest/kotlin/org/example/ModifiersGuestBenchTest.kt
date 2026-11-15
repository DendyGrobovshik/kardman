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
package org.example

import android.util.Log
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the guest benchmark bundle and times the same grid with a modifier
 * chain and with none. Composition runs on the next frame, inside
 * [RdmaComposeHost.Content], which calls the guest synchronously.
 */
@RunWith(AndroidJUnit4::class)
class ModifiersGuestBenchTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun modifiersAndNoModifiers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        waitUntil(10_000) { RdmaBridge.nativeIsReady() }

        val versionBefore = RdmaBridge.nativeContentVersion()
        val hbc = File(app.cacheDir, "bench.hbc")
        instrumentation.context.assets.open("bench.hbc").use { input ->
            hbc.outputStream().use { input.copyTo(it) }
        }
        RdmaBridge.nativeEvalFile(hbc.absolutePath)
        waitUntil(20_000) { RdmaBridge.nativeContentVersion() != versionBefore }

        val roundTripNs = AtomicLong(-1)
        activity.scenario.onActivity { host ->
            host.setContent {
                val started = System.nanoTime()
                RdmaComposeHost.Content()
                roundTripNs.compareAndSet(-1, System.nanoTime() - started)
            }
        }

        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle(60_000)
        val modifiersCompose = waitForLabel(device, "Modifiers", tick = 0)
        click(device, "Recompose")
        val modifiersRecompose = waitForLabel(device, "Modifiers", tick = 1)
        click(device, "NoModifiers")
        val plainCompose = waitForLabel(device, "NoModifiers", tick = 0)
        click(device, "Recompose")
        val plainRecompose = waitForLabel(device, "NoModifiers", tick = 1)
        val hostMs = roundTripNs.get() / 1_000_000.0

        val report = buildString {
            append("modifiers composition ")
            append(millisOf(modifiersCompose))
            append(" ms\n")
            append("modifiers recomposition ")
            append(millisOf(modifiersRecompose))
            append(" ms\n")
            append("nomodifiers composition ")
            append(millisOf(plainCompose))
            append(" ms\n")
            append("nomodifiers recomposition ")
            append(millisOf(plainRecompose))
            append(" ms\n")
            append("modifiers host composition ")
            append(String.format(Locale.US, "%.1f", hostMs))
            append(" ms\n")
        }
        File(app.cacheDir, REPORT).writeText(report)
        Log.i(TAG, report.trim())
        println(report)
    }

    private fun click(device: UiDevice, label: String) {
        val node = device.findObject(By.text(label))
        assertNotNull("$label button did not appear", node)
        node.click()
        device.waitForIdle(60_000)
    }

    private fun millisOf(label: String): String {
        val match = Regex("""([0-9.]+) ms""").find(label)
        checkNotNull(match) { "guest label has no duration: $label" }
        return match.groupValues[1]
    }

    private fun waitForLabel(device: UiDevice, name: String, tick: Int): String {
        val pattern = if (name == "Modifiers") {
            Pattern.compile("(?<!No)Modifiers [0-9.]+ ms tick $tick")
        } else {
            Pattern.compile("NoModifiers [0-9.]+ ms tick $tick")
        }
        val selector = By.text(pattern)
        val found = device.wait(Until.findObject(selector), 20_000)
        if (found == null) {
            val texts = device.findObjects(By.text(Pattern.compile(".+"))).joinToString(" | ") { it.text }
            org.junit.Assert.fail("$name label for tick $tick did not appear. visible: $texts")
        }
        val text = device.findObject(selector).text
        assertTrue(text, text.contains("tick $tick"))
        return text
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out after ${timeoutMs}ms" }
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TAG = "GuestBench"
        const val REPORT = "guest-bench.txt"
    }
}
