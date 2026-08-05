package com.example.kernel

import io.github.dendygrobovshik.kardman.RDMA

@RDMA
class Person(val name: String, val age: Int) {
    var status: String = "alive"

    override fun toString(): String {
        return "Person(name='$name', age=$age, status=$status)"
    }

    fun greetVampier(vampier: SuperVeryOldVampier): String {
        return "$name greets ${vampier.grhhh()}"
    }

    fun greetMaybe(vampier: SuperVeryOldVampier?): String? {
        return vampier?.let { "$name greets ${it.grhhh()}" }
    }

    fun getFriend(): Person {
        return Person("Friend of $name", age + 1)
    }
}

@RDMA
class SuperVeryOldVampier(val name: String) {
    fun grhhh(): String {
        return "Vampier $name said grhhh..."
    }
}
