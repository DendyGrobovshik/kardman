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
package com.example.kernel.internal.services

import io.github.dendygrobovshik.kardman.RDMA

/**
 * List bridge exercises: mutation (set/add/removeAt/clear) and nested-list
 * materialization. The plugin passes a list, the kernel echoes it back, and the
 * plugin then mutates the (materialized) proxy to prove mutations land in kernel
 * memory.
 */
@RDMA
fun echoIntList(list: List<Int>): List<Int> = list

@RDMA
fun echoIntListList(list: List<List<Int>>): List<List<Int>> = list

@RDMA
fun sumList(list: List<Int>): Int = list.sum()

@RDMA
fun appendToList(list: List<Int>, value: Int): List<Int> {
    (list as MutableList<Int>).add(value)
    return list
}

@RDMA
fun mutateInPlace(list: List<Int>) {
    (list as MutableList<Int>).add(100)
}
