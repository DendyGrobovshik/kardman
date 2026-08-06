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

    val friends = obi.getFriends()
    println("Friends count: ${friends.size}")

    val grievFriends = listOf(Person("dart", 100), Cyborg("tony", 7))
    griev.nameFriends(grievFriends)
    println("plugin: ${grievFriends[0].name} ${grievFriends[0].status}")
    grievFriends.forEach { it.status = "boomer" }
    griev.nameFriends(grievFriends)
}
