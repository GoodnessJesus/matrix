/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2018 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.matrix.plugin.task

import com.tencent.matrix.javalib.util.IOUtil
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.plugin.compat.CreationConfig
import com.tencent.matrix.plugin.trace.MatrixTrace
import com.tencent.matrix.trace.extension.MatrixTraceExtension
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

abstract class MatrixTraceTask : DefaultTask() {
    companion object {
        private const val TAG = "Matrix.TraceTask"
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirs: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bootClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseMethodMapFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val blockListFile: RegularFileProperty

    @get:Internal
    abstract val mappingDir: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:OutputFile
    abstract val ignoreMethodMapFileOutput: RegularFileProperty

    @get:OutputFile
    abstract val methodMapFileOutput: RegularFileProperty

    @get:Input
    abstract val skipCheckClass: Property<Boolean>

    @TaskAction
    fun execute() {
        val start = System.currentTimeMillis()
        val outputFile = output.get().asFile
        val temporaryOutput = outputFile.parentFile.resolve("matrix-trace-tmp")
        temporaryOutput.deleteRecursively()
        temporaryOutput.mkdirs()

        val inputs = inputDirs.get().map { it.asFile } + inputJars.get().map { it.asFile }
        MatrixTrace(
                ignoreMethodMapFilePath = ignoreMethodMapFileOutput.get().asFile.absolutePath,
                methodMapFilePath = methodMapFileOutput.get().asFile.absolutePath,
                baseMethodMapPath = baseMethodMapFile.asFile.orNull?.absolutePath,
                blockListFilePath = blockListFile.asFile.orNull?.absolutePath,
                mappingDir = mappingDir.get(),
                bootClasspath = bootClasspath.files
        ).doTransform(
                classInputs = inputs,
                changedFiles = emptyMap(),
                isIncremental = false,
                skipCheckClass = skipCheckClass.get(),
                traceClassDirectoryOutput = temporaryOutput,
                inputToOutput = emptyMap(),
                legacyReplaceChangedFile = null,
                legacyReplaceFile = null,
                uniqueOutputName = true
        )

        outputFile.parentFile.mkdirs()
        JarOutputStream(outputFile.outputStream().buffered()).use { outputJar ->
            val entries = HashSet<String>()
            temporaryOutput.walkTopDown().filter { it.isFile }.forEach { file ->
                if (IOUtil.isRealZipOrJar(file)) {
                    ZipFile(file).use { inputJar ->
                        inputJar.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory) {
                                outputJar.addEntry(entry.name, entries) {
                                    inputJar.getInputStream(entry).use { input -> input.copyTo(it) }
                                }
                            }
                        }
                    }
                } else {
                    val entryName = file.relativeTo(temporaryOutput).invariantSeparatorsPath
                    outputJar.addEntry(entryName, entries) { file.inputStream().use { input -> input.copyTo(it) } }
                }
            }
        }
        temporaryOutput.deleteRecursively()
        Log.i(TAG, " Insert matrix trace instrumentations cost time: %sms.", System.currentTimeMillis() - start)
    }

    private fun JarOutputStream.addEntry(name: String, entries: MutableSet<String>, write: (JarOutputStream) -> Unit) {
        if (entries.add(name)) {
            putNextEntry(JarEntry(name))
            write(this)
            closeEntry()
        }
    }

    class CreationAction(
            private val creationConfig: CreationConfig,
            private val extension: MatrixTraceExtension,
            private val bootClasspath: FileCollection
    ) : Action<MatrixTraceTask>, BaseCreationAction<MatrixTraceTask>(creationConfig) {

        override val name = computeTaskName("matrix", "Trace")
        override val type = MatrixTraceTask::class.java

        override fun execute(task: MatrixTraceTask) {
            val project = creationConfig.project
            val mappingOut = project.layout.buildDirectory.dir("outputs/mapping/${creationConfig.variant.name}")

            extension.baseMethodMapFile?.takeIf { it.isNotBlank() }?.let {
                val file = project.file(it)
                if (file.exists()) task.baseMethodMapFile.set(file)
            }
            extension.blackListFile?.takeIf { it.isNotBlank() }?.let {
                val file = project.file(it)
                if (file.exists()) task.blockListFile.set(file)
            }
            task.bootClasspath.setFrom(bootClasspath)
            task.mappingDir.set(mappingOut.map { it.asFile.absolutePath })
            task.skipCheckClass.set(extension.isSkipCheckClass)
            task.ignoreMethodMapFileOutput.set(mappingOut.map { it.file("ignoreMethodMapping.txt") })
            task.methodMapFileOutput.set(mappingOut.map { it.file("methodMapping.txt") })
        }
    }
}
