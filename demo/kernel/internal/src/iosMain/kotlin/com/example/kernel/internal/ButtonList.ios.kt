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
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.kernel.internal

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.UIKit.UIImage
import platform.UIKit.UITabBar
import platform.UIKit.UITabBarAppearance
import platform.UIKit.UITabBarDelegateProtocol
import platform.UIKit.UITabBarItem
import platform.darwin.NSObject

private class ButtonListTabDelegate(val onSelect: (Int) -> Unit) : NSObject(), UITabBarDelegateProtocol {
    override fun tabBar(tabBar: UITabBar, didSelectItem: UITabBarItem) {
        onSelect(didSelectItem.tag.toInt())
    }
}

@Composable
internal actual fun PlatformButtonList(labels: List<String>, actions: List<() -> Unit>, selectedIndex: Int, icons: List<String>) {
    val delegate = remember(labels) { ButtonListTabDelegate { i -> actions[i]() } }
    UIKitView(
        factory = {
            UITabBar().apply {
                val items = labels.mapIndexed { i, label ->
                    UITabBarItem(
                        title = label,
                        image = icons.getOrNull(i)?.let { UIImage.systemImageNamed(it) },
                        tag = i.toLong(),
                    )
                }
                this.items = items
                if (selectedIndex >= 0 && selectedIndex < items.size) {
                    this.selectedItem = items[selectedIndex]
                }
                this.delegate = delegate
                val appearance = UITabBarAppearance().apply { configureWithTransparentBackground() }
                this.standardAppearance = appearance
                this.scrollEdgeAppearance = appearance
            }
        },
        update = { view ->
            val tabBar = view as? UITabBar ?: return@UIKitView
            val items = tabBar.items ?: return@UIKitView
            if (selectedIndex >= 0 && selectedIndex < items.size) {
                tabBar.selectedItem = items[selectedIndex] as? UITabBarItem
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
