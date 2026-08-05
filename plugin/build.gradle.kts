@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        jsMain {
            kotlin.setSrcDirs(listOf("build/rdma/transformed"))
        }
    }
}

val transformPluginSources = tasks.register("transformPluginSources") {
    notCompatibleWithConfigurationCache("Script reference transformCode()")
    dependsOn(":kernel:kspKotlinJvm")
    val inputDir = file("src/jsMain/kotlin")
    val outputDir = layout.buildDirectory.dir("rdma/transformed")
    val jsonFile = file("${rootProject.projectDir}/kernel/build/generated/ksp/jvm/jvmMain/resources/cpp/rdma_classes.json")

    inputs.dir(inputDir)
    inputs.file(jsonFile)
    outputs.dir(outputDir)

    doLast {
        val rdmaTypes = parseClassesJson(if (jsonFile.exists()) jsonFile.readText() else "[]")

        outputDir.get().asFile.deleteRecursively()
        outputDir.get().asFile.mkdirs()

        inputDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { src ->
            val transformed = transformCode(src.readText(), rdmaTypes)
            val relPath = src.relativeTo(inputDir).path
            val dst = outputDir.get().file(relPath).asFile
            dst.parentFile.mkdirs()
            dst.writeText(transformed)
        }
    }
}

tasks.named("compileKotlinJs") {
    dependsOn(transformPluginSources)
}

tasks.matching { it.name.startsWith("ksp") || it.name.startsWith("compile") || it.name.startsWith("js") }.configureEach {
    outputs.upToDateWhen { false }
}

// ---------- inline transform logic (same as RdmaTransformer) ----------

data class RdmaType(
    val simpleName: String,
    val constructorParams: List<Pair<String, String>>,
    val properties: List<Pair<String, Boolean>>,
)

fun parseClassesJson(json: String): List<RdmaType> {
    val result = mutableListOf<RdmaType>()
    val nameValueRegex = Regex(""""name":"([^"]+)"""")
    val typeValueRegex = Regex(""""type":"([^"]+)"""")
    val mutableRegex = Regex(""""isMutable":(true|false)""")
    var depth = 0; var start = -1
    for (i in json.indices) {
        when (json[i]) {
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    val block = json.substring(start, i + 1)
                    val name = nameValueRegex.find(block)?.groupValues?.get(1) ?: ""
                    val params = mutableListOf<Pair<String, String>>()
                    Regex(""""parameters":\[([^\]]*)\]""").find(block)?.let { cm ->
                        Regex("""\{[^}]*"type":"([^"]+)"[^}]*}""").findAll(cm.groupValues[1]).forEach { pm ->
                            val tn = typeValueRegex.find(pm.value)?.groupValues?.get(1) ?: "Any"
                            params.add("arg" to tn.substringAfterLast("."))
                        }
                    }
                    val props = mutableListOf<String>()
                    Regex(""""properties":\[([^\]]*)\]""").find(block)?.let { pm ->
                        nameValueRegex.findAll(pm.groupValues[1]).forEach { props.add(it.groupValues[1]) }
                    }
                    val mutableFlags = mutableListOf<Boolean>()
                    val propsBlock = Regex(""""properties":\[([^\]]*)\]""").find(block)?.groupValues?.get(1) ?: ""
                    mutableRegex.findAll(propsBlock).forEach { mutableFlags.add(it.groupValues[1] == "true") }
                    val propsWithMutable = props.mapIndexed { idx, name ->
                        name to (mutableFlags.getOrElse(idx) { false })
                    }
                    result.add(RdmaType(name, params, propsWithMutable))
                }
            }
        }
    }
    return result
}

fun transformCode(code: String, rdmaTypes: List<RdmaType>): String {
    var result = code
    for (type in rdmaTypes) {
        result = result.replace(Regex("""import\s+\S+\.${type.simpleName}\s*\n"""), "")
        val patterns = type.constructorParams.map { (_, pt) ->
            when (pt) { "String" -> """"([^"]*)""""; "Int" -> """(\d+)"""; "Boolean" -> """(true|false)"""; "Long" -> """(\d+L?)"""; "Float", "Double" -> """(\d+\.?\d*)"""; else -> """([^,\s)]+)""" }
        }
        val rx = Regex("""${type.simpleName}\s*\(\s*${patterns.joinToString("""\s*,\s*""")}\s*\)""")
        result = result.replace(rx) { m ->
            val args = type.constructorParams.mapIndexed { i, (_, pt) ->
                val v = m.groupValues[i + 1]; if (pt == "String") "'$v'" else v
            }
            "js(\"RDMA.create${type.simpleName}(${args.joinToString(", ")})\")"
        }
        for (prop in type.properties) {
            if (prop.second) {
                val setter = "set${prop.first.replaceFirstChar { it.uppercase() }}"
                result = result.replace(Regex("""\.${prop.first}\s*=\s*([^\n]+)""")) { ".$setter(${it.groupValues[1]})" }
            }
            val getter = "get${prop.first.replaceFirstChar { it.uppercase() }}"
            result = result.replace(Regex("""\.${prop.first}\b(?!\()""")) { ".$getter()" }
        }
    }
    return result
}
