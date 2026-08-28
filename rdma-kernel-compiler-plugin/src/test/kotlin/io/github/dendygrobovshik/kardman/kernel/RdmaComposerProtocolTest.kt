package io.github.dendygrobovshik.kardman.kernel

import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RdmaComposerProtocolTest {

    @Test
    fun `base protocol covers structural groups and remember`() {
        val names = RdmaComposerProtocol.baseProtocol.map { it.jsName }.toSet()
        val expected = listOf(
            "startRestartGroup", "endRestartGroup",
            "startReplaceGroup", "endReplaceGroup",
            "startMovableGroup", "endMovableGroup",
            "startReusableGroup", "endReusableGroup",
            "skipCurrentGroup", "skipToGroupEnd",
            "rememberedValue", "updateRememberedValue", "changed", "shouldExecute",
        )
        for (name in expected) {
            assertTrue(name in names, "missing base-protocol method: $name")
        }
    }

    @Test
    fun `forbidden methods are excluded from the base protocol`() {
        val names = RdmaComposerProtocol.baseProtocol.map { it.jniName }.toSet()
        val forbidden = listOf(
            "insertMovableContent",
            "insertMovableContentReferences",
            "collectParameterInformation",
            "saveSlotTable",
            "restoreSlotTable",
        )
        for (name in forbidden) {
            assertFalse(name in names, "$name should be excluded from the base protocol")
        }
    }

    @Test
    fun `every cached method has a jni signature and a generated cache field`() {
        val generated = generate(RdmaComposerProtocol.baseProtocol)
        val header = generated["RdmaComposerProxy.h"] ?: error("RdmaComposerProxy.h not generated")
        val cpp = generated["RdmaComposerProxy.cpp"] ?: error("RdmaComposerProxy.cpp not generated")

        for (m in RdmaComposerProtocol.methodsNeedingCache) {
            assertContains(header, "jmethodID ${m.jniName}", message = "missing cache field for ${m.jniName}")
            assertContains(
                cpp,
                "GetMethodID(g_composeCache.composerClass, \"${m.jniName}\", \"${m.jniSignature}\")",
                message = "missing GetMethodID for ${m.jniName}",
            )
        }
    }

    @Test
    fun `generates a handler for every base protocol method`() {
        val cpp = generate(RdmaComposerProtocol.baseProtocol)["RdmaComposerProxy.cpp"] ?: error("not generated")
        for (m in RdmaComposerProtocol.baseProtocol) {
            assertContains(cpp, "n.rfind(\"${m.jsName}\", 0)", message = "missing handler for ${m.jsName}")
        }
    }

    @Test
    fun `no-op diagnostics methods do not get a cached method id`() {
        val cachedNames = RdmaComposerProtocol.methodsNeedingCache.map { it.jniName }.toSet()
        assertFalse("sourceInformation" in cachedNames)
        assertFalse("sourceInformationMarkerStart" in cachedNames)
        assertFalse("getRecomposeScope" in cachedNames)
        assertFalse("recordUsed" in cachedNames)
    }

    private fun generate(methods: List<ComposerMethod>): Map<String, String> {
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        RdmaComposerProxyGenerator { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(methods)
        return files.mapValues { it.value.use { it.toString("UTF-8") } }
    }
}
