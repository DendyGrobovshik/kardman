package com.example.plugin

import com.example.kernel.Person
import com.example.kernel.Person3
import com.example.kernel.SuperVeryOldVampier

fun main() {
    val p = Person("Эдвард", 104)
    println(p.name)
    println(p.toString())

    val vamp = SuperVeryOldVampier("Петир")
    println(p.greetVampier(vamp))

    val friend = p.getFriend()
    println(friend.name)
    println(friend.toString())
}
