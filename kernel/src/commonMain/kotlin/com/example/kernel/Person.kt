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
}
