package com.tencent.matrix.plugin.trace

import com.android.build.api.artifact.ScopedArtifact
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.trace.Configuration
import com.tencent.matrix.trace.MethodCollector
import com.tencent.matrix.trace.retrace.MappingCollector
import com.tencent.matrix.trace.retrace.MappingReader
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

abstract class MatrixMethodCollectTask : DefaultTask() {

    @get:InputFiles
    abstract val allDirectories: ListProperty<org.gradle.api.file.Directory>

    @get:InputFiles
    abstract val allJars: ListProperty<org.gradle.api.file.RegularFile>

    @get:OutputDirectory
    abstract val mappingDir: DirectoryProperty

    @get:Input
    abstract val traceConfig: Property<MatrixTraceExtensionConfig>

    @TaskAction
    fun execute() {
        Log.i("MatrixMethodCollectTask", "Start method collection")
        val executor = Executors.newFixedThreadPool(16)
        try {
            val extensionConfig = traceConfig.get()
            val mappingDirPath = mappingDir.get().asFile.absolutePath

            val configBuilder = Configuration.Builder()
                .setIgnoreMethodMapFilePath("$mappingDirPath/ignoreMethodMapping.txt")
                .setMethodMapFilePath("$mappingDirPath/methodMapping.txt")
                .setBaseMethodMap(extensionConfig.baseMethodMapFile)
                .setBlockListFile(extensionConfig.blackListFile)
                .setMappingPath(extensionConfig.mappingDir)
                .setSkipCheckClass(extensionConfig.isSkipCheckClass)

            val config = configBuilder.build()

            val mappingCollector = MappingCollector()
            val mappingFile = File(config.mappingDir, "mapping.txt")
            if (mappingFile.isFile) {
                val mappingReader = MappingReader(mappingFile)
                mappingReader.read(mappingCollector)
            }
            config.parseBlockFile(mappingCollector)

            val collectedMethodMap = ConcurrentHashMap<String, com.tencent.matrix.trace.item.TraceMethod>()
            val methodId = AtomicInteger(0)

            // Parse base method map if exists (simplified implementation)
            val baseMethodMapFile = File(config.baseMethodMapPath ?: "")
            if (baseMethodMapFile.exists()) {
                baseMethodMapFile.useLines { lines ->
                    for (line in lines) {
                        val tLine = line.trim()
                        if (tLine.isNotEmpty() && !tLine.startsWith("#")) {
                            val fields = tLine.split(",")
                            if (fields.size >= 3) {
                                val traceMethod = com.tencent.matrix.trace.item.TraceMethod()
                                traceMethod.id = fields[0].toInt()
                                traceMethod.accessFlag = fields[1].toInt()
                                val methodField = fields[2].split(" ")
                                traceMethod.className = methodField[0].replace("/", ".")
                                traceMethod.methodName = methodField[1]
                                if (methodField.size > 2) {
                                    traceMethod.desc = methodField[2].replace("/", ".")
                                }
                                collectedMethodMap[traceMethod.getMethodName()] = traceMethod
                                if (methodId.get() < traceMethod.id && traceMethod.id != com.tencent.matrix.trace.TraceBuildConstants.METHOD_ID_DISPATCH) {
                                    methodId.set(traceMethod.id)
                                }
                            }
                        }
                    }
                }
            }

            val methodCollector = MethodCollector(executor, mappingCollector, methodId, config, collectedMethodMap)

            val srcFolderList = mutableSetOf<File>()
            for (dir in allDirectories.get()) {
                srcFolderList.add(dir.asFile)
            }

            val dependencyJarList = mutableSetOf<File>()
            for (jar in allJars.get()) {
                dependencyJarList.add(jar.asFile)
            }

            methodCollector.collect(srcFolderList, dependencyJarList)
            
            // Serialize collectedClassExtendMap
            val classExtendMapFile = File(config.mappingDir, "classExtendMapping.txt")
            if (!classExtendMapFile.parentFile.exists()) {
                classExtendMapFile.parentFile.mkdirs()
            }
            classExtendMapFile.printWriter().use { pw ->
                for ((className, superName) in methodCollector.collectedClassExtendMap) {
                    pw.println("$className,$superName")
                }
            }

            Log.i("MatrixMethodCollectTask", "Finish method collection, generated mapping successfully.")

        } finally {
            executor.shutdown()
        }
    }
}

// Data class to pass extension config easily
data class MatrixTraceExtensionConfig(
    val baseMethodMapFile: String,
    val blackListFile: String,
    val mappingDir: String,
    val isSkipCheckClass: Boolean
) : java.io.Serializable
