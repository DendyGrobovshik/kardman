package com.example.kernel

/**
 * Loads the user-side native bridge (librdma_user.so) and registers it with the
 * generic framework runtime. Must be initialized before `RdmaBridge.nativeInit(...)`.
 */
object UserBridge {
    init {
        System.loadLibrary("rdma_user")
    }

    external fun nativeInstall()
}
