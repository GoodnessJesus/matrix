package com.tencent.matrix.plugin.trace

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.plugin.compat.AgpCompat
import com.tencent.matrix.trace.MethodTracer
import com.tencent.matrix.trace.item.TraceMethod
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassVisitor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface MatrixTraceParameters : InstrumentationParameters {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    val mappingDir: DirectoryProperty

    @get:Input
    val skipCheckClass: Property<Boolean>

    @get:Input
    val blockSet: org.gradle.api.provider.SetProperty<String>
}

abstract class MatrixTraceClassVisitorFactory : AsmClassVisitorFactory<MatrixTraceParameters> {

    companion object {
        var cachedCollectedMethodMap: ConcurrentHashMap<String, TraceMethod>? = null
        var cachedCollectedClassExtendMap: ConcurrentHashMap<String, String>? = null
        var lastMappingDir: String? = null

        @Synchronized
        fun loadMapping(mappingDir: File) {
            val dirPath = mappingDir.absolutePath
            if (lastMappingDir == dirPath && cachedCollectedMethodMap != null) {
                return
            }
            Log.i("MatrixTraceVisitor", "Loading mapping from $dirPath")
            
            val methodMap = ConcurrentHashMap<String, TraceMethod>()
            val methodMapFile = File(mappingDir, "methodMapping.txt")
            if (methodMapFile.exists()) {
                methodMapFile.useLines { lines ->
                    for (line in lines) {
                        val tLine = line.trim()
                        if (tLine.isNotEmpty() && !tLine.startsWith("#") && !tLine.startsWith("ignore methods:")) {
                            val fields = tLine.split(",")
                            if (fields.size >= 3) {
                                val traceMethod = TraceMethod()
                                traceMethod.id = fields[0].toInt()
                                traceMethod.accessFlag = fields[1].toInt()
                                val methodField = fields[2].split(" ")
                                traceMethod.className = methodField[0].replace("/", ".")
                                traceMethod.methodName = methodField[1]
                                if (methodField.size > 2) {
                                    traceMethod.desc = methodField[2].replace("/", ".")
                                }
                                methodMap[traceMethod.getMethodName()] = traceMethod
                            }
                        }
                    }
                }
            }
            
            val classExtendMap = ConcurrentHashMap<String, String>()
            val classExtendMapFile = File(mappingDir, "classExtendMapping.txt")
            if (classExtendMapFile.exists()) {
                classExtendMapFile.useLines { lines ->
                    for (line in lines) {
                        val tLine = line.trim()
                        if (tLine.isNotEmpty()) {
                            val fields = tLine.split(",")
                            if (fields.size == 2) {
                                classExtendMap[fields[0]] = fields[1]
                            }
                        }
                    }
                }
            }

            cachedCollectedMethodMap = methodMap
            cachedCollectedClassExtendMap = classExtendMap
            lastMappingDir = dirPath
        }
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        loadMapping(parameters.get().mappingDir.get().asFile)
        
        // Wait, TraceClassAdapter is currently an inner class of MethodTracer in the java code.
        // I will create a standalone TraceClassAdapter in Kotlin to wrap it, 
        // OR we can decouple TraceClassAdapter from MethodTracer.
        // Actually, let's create our own visitor derived from what TraceClassAdapter does, 
        // to avoid touching MethodTracer.java too much if we don't need to.
        return MatrixTraceClassAdapter(
            AgpCompat.getAsmApi(),
            nextClassVisitor,
            cachedCollectedMethodMap ?: ConcurrentHashMap(),
            cachedCollectedClassExtendMap ?: ConcurrentHashMap(),
            parameters.get().blockSet.get()
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return true
    }
}
