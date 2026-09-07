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
package com.example.kernel

import io.github.dendygrobovshik.kardman.RDMA

@RDMA
open class Person(val name: String, val age: Int) {
    var status: String = "alive"

    open fun greet(): String {
        return "Hello, I'm $name"
    }

    override fun toString(): String {
        return "(name='$name', age=$age, status=$status) said '${greet()}'"
    }

    fun fight(opponent: Person): Unit {
        println("Fight between ${name} and ${opponent.name}")
    }

    fun getFriends(): List<Person> {
        return listOf(Person("Alice", 25), Person("Bob", 30))
    }

    fun nameFriends(friends: List<Person>): Unit {
        println("nameFriends: ${friends.size} ${friends[0].status}")
        friends.forEach {  println(it.name); it.status = "noobik" }
    }
}
