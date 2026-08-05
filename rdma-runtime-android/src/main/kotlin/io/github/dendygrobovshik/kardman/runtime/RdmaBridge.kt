package io.github.dendygrobovshik.kardman.runtime

object RdmaBridge {
    init {
        System.loadLibrary("rdma_runtime")
    }

    external fun nativeInit()
    external fun nativeEval(jsCode: String): String
    external fun nativeDispatch(vtablePtr: Long, method: String, vararg args: Any?): Any?
}
