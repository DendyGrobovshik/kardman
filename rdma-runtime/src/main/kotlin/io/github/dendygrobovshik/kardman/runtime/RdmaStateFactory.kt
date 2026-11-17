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
package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * JVM helpers backing the plugin's snapshot collections
 * (`mutableStateListOf`/`mutableStateMapOf`, docs/design/type_descriptors.md).
 *
 * The bridge materializes the guest values into a plain `ArrayList`/`LinkedHashMap`
 * first; these copy them into a `SnapshotStateList`/`SnapshotStateMap`. The proxy
 * handle over that map/list is what the plugin mutates, so every mutation goes
 * through the snapshot mutators and schedules a recomposition.
 */
object RdmaStateFactory {

    @JvmStatic
    fun newListFrom(source: ArrayList<Any?>?): Any {
        val list = mutableStateListOf<Any?>()
        if (source != null) list.addAll(source)
        return list
    }

    @JvmStatic
    fun newMapFrom(source: LinkedHashMap<Any?, Any?>?): Any {
        val map = mutableStateMapOf<Any?, Any?>()
        if (source != null) map.putAll(source)
        return map
    }
}
