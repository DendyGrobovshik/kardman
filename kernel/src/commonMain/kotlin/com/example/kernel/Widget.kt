package com.example.kernel

import androidx.compose.runtime.mutableStateListOf
import io.github.dendygrobovshik.kardman.RDMA

object RdmaUiRoot {
    @Volatile
    var root: Widget? = null
}

@RDMA
open class Widget {
    private val _children = mutableStateListOf<Widget>()

    val children: List<Widget> get() = _children

    fun add(child: Widget) {
        _children.add(child)
    }

    fun remove(child: Widget) {
        _children.remove(child)
    }

    fun mount() {
        RdmaUiRoot.root = this
    }
}
