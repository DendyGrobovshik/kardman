package com.example.kernel

import io.github.dendygrobovshik.kardman.RDMA

@RDMA
class Person(val name: String, val age: Int) {
    override fun toString(): String {
        return "Person(name='$name', age=$age)"
    }

    fun greetVampier(vampier: SuperVeryOldVampier): String {
        return "$name greets ${vampier.grhhh()}"
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
