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
