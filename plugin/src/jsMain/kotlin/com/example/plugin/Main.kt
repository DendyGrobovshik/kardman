package com.example.plugin

import com.example.kernel.Person

class Cyborg(name: String, age: Int) : Person(name, age) {
    override fun greet(): String = "I am a cyborg!"
}

fun main() {
    val obi = Person("Оби-Ван Кеноби", 38)
    println(obi.greet())
    println(obi.toString())

    val griev = Cyborg("Генерал Гривус", 42)
    griev.status = "superposition"
    println(griev.greet())
    println(griev.toString())

    obi.fight(griev)
}
