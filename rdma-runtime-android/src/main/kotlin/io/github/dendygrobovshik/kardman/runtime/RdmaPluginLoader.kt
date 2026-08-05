package io.github.dendygrobovshik.kardman.runtime

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object RdmaPluginLoader {

    fun loadAndEval(context: Context, assetPath: String): String {
        val jsCode = context.assets.open(assetPath).bufferedReader().use(BufferedReader::readText)
        return RdmaBridge.nativeEval(jsCode)
    }

    fun loadAndEval(jsCode: String): String {
        return RdmaBridge.nativeEval(jsCode)
    }
}
