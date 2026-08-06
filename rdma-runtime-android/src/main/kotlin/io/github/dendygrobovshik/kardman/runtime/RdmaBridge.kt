package io.github.dendygrobovshik.kardman.runtime

import android.content.res.AssetManager

object RdmaBridge {
    init {
        System.loadLibrary("rdma_runtime")
    }

    external fun nativeInit(assetManager: AssetManager)
    external fun nativeEvalAsset(assetPath: String): String
}
