package com.example.plugin

import com.example.kernel.Person
import com.example.kernel.Person3
import com.example.kernel.SuperVeryOldVampier

fun main() {
    val p = Person("Эдвард", 104)
    println(p.name)
    p.status = "undead"
    println(p.toString())

    val vamp = SuperVeryOldVampier("Петир")
    println(p.greetVampier(vamp))

    val maybe = p.greetMaybe(vamp)
    println("Greet maybe: $maybe")

    val nullResult = p.greetMaybe(null)
    println("Greet null: $nullResult")
}
