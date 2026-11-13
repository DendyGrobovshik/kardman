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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.dendygrobovshik.kardman.RDMA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ModifierApplyTest {

    @Test
    fun `boundary types carry the RDMA annotation`() {
        val types = listOf(
            io.github.dendygrobovshik.kardman.compose.Modifier::class.java,
            Dp::class.java,
            Color::class.java,
            Alignment::class.java,
            HorizontalAlignment::class.java,
            VerticalAlignment::class.java,
            Shape::class.java,
            Brush::class.java,
            Offset::class.java,
            TileMode::class.java,
            Indication::class.java,
            IntrinsicSize::class.java,
        )
        for (type in types) {
            assertTrue(type.isAnnotationPresent(RDMA::class.java), type.name)
        }
    }

    @Test
    fun `padding fill and tag become compose modifiers in chain order`() = runComposeUiTest {
        setContent {
            val applied = io.github.dendygrobovshik.kardman.compose.Modifier.Empty
                .paddingAll(Dp(16.0))
                .fillMaxWidth()
                .testTag("title")
                .toComposeUi()
            val text = applied.foldIn(StringBuilder()) { acc, element ->
                acc.append(element).append('\n')
            }.toString()
            assertTrue(text.contains("PaddingValuesElement"), text)
            assertTrue(text.contains("FillElement"), text)
            assertTrue(text.contains("TestTagElement"), text)
            val paddingAt = text.indexOf("PaddingValuesElement")
            val fillAt = text.indexOf("FillElement")
            val tagAt = text.indexOf("TestTagElement")
            assertTrue(paddingAt < fillAt && fillAt < tagAt, text)
        }
    }

    @Test
    fun `weight is applied inside column and skipped outside it`() = runComposeUiTest {
        lateinit var inside: String
        lateinit var outside: String
        setContent {
            outside = io.github.dendygrobovshik.kardman.compose.Modifier.Empty.weight(1.0).toComposeUi().foldText()
            Column {
                provideModifierScope {
                    inside = io.github.dendygrobovshik.kardman.compose.Modifier.Empty.weight(1.0).toComposeUi().foldText()
                }
            }
        }
        assertFalse(outside.contains("Weight", ignoreCase = true), outside)
        assertTrue(inside.contains("Weight", ignoreCase = true) || inside.contains("weight"), inside)
    }

    @Test
    fun `align is applied inside row and box`() = runComposeUiTest {
        lateinit var row: String
        lateinit var box: String
        setContent {
            Row {
                provideModifierScope {
                    row = io.github.dendygrobovshik.kardman.compose.Modifier.Empty
                        .alignVertically(VerticalAlignment.Bottom)
                        .toComposeUi()
                        .foldText()
                }
            }
            Box {
                provideModifierScope {
                    box = io.github.dendygrobovshik.kardman.compose.Modifier.Empty
                        .align(Alignment.Center)
                        .toComposeUi()
                        .foldText()
                }
            }
        }
        assertTrue(row.contains("Align", ignoreCase = true) || row.contains("align"), row)
        assertTrue(box.contains("Align", ignoreCase = true) || box.contains("BoxChildData") || box.contains("align"), box)
    }

    @Test
    fun `shimmer and reuse do not change the compose modifier`() = runComposeUiTest {
        lateinit var shimmer: String
        lateinit var empty: String
        setContent {
            empty = Modifier.foldText()
            shimmer = io.github.dendygrobovshik.kardman.compose.Modifier.Empty.shimmer().reuse().toComposeUi().foldText()
        }
        assertEquals(empty, shimmer)
    }

    @Test
    fun `clickable reports the action id`() = runComposeUiTest {
        var clicked = -1
        setContent {
            CompositionLocalProvider(LocalModifierClickHandler provides ModifierClickHandler { clicked = it }) {
                Box(
                    io.github.dendygrobovshik.kardman.compose.Modifier.Empty
                        .size(Dp(48.0), Dp(48.0))
                        .clickable(7, true, Indication.None)
                        .testTag("hit")
                        .toComposeUi(),
                )
            }
        }
        onNodeWithTag("hit").performClick()
        assertEquals(7, clicked)
    }

    @Test
    fun `clickable runs the action registered for its id`() = runComposeUiTest {
        ClickActions.clear()
        try {
            var clicked = -1
            setContent {
                ClickAction(7) { clicked = 7 }
                Box(
                    io.github.dendygrobovshik.kardman.compose.Modifier.Empty
                        .size(Dp(48.0), Dp(48.0))
                        .clickable(7, true, Indication.None)
                        .testTag("hit")
                        .toComposeUi(),
                )
            }
            onNodeWithTag("hit").performClick()
            assertEquals(7, clicked)
        } finally {
            ClickActions.clear()
        }
    }
}

private fun Modifier.foldText(): String =
    foldIn(StringBuilder()) { acc, element -> acc.append(element).append('\n') }.toString()
