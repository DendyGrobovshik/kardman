package com.example.kernel

import io.github.dendygrobovshik.kardman.RDMA

@RDMA
open class Button(val text: String) : Widget() {
    open fun onClick() {}
}
