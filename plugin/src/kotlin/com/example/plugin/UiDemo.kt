package com.example.plugin

import com.example.kernel.Button
import com.example.kernel.Text
import com.example.kernel.Widget

class MyButton(text: String) : Button(text) {
    override fun onClick() = println("clicked")
}

fun buildUi() {
    val root = Widget()
    root.add(Text("Hello, RDMA UI!"))
    root.add(MyButton("Click me"))
    root.mount()
}
