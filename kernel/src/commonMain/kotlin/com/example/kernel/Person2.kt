package com.example.kernel

import io.github.dendygrobovshik.kardman.RDMA

@RDMA
class Person3(val name3: String, val age3: Int) {
     fun toString3(): String {
        return "Person3(name2='$name3', age2=$age3)"
    }
}
