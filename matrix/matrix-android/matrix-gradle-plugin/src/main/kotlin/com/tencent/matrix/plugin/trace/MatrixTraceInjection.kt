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

package com.tencent.matrix.plugin.trace

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.AppExtension
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.trace.extension.ITraceSwitchListener
import com.tencent.matrix.trace.extension.MatrixTraceExtension
import org.gradle.api.Project
import java.io.File

class MatrixTraceInjection : ITraceSwitchListener {

    companion object {
        const val TAG = "Matrix.TraceInjection"
    }

    private var traceEnable = false

    override fun onTraceEnabled(enable: Boolean) {
        traceEnable = enable
    }

    fun inject(appExtension: AppExtension,
               project: Project,
               extension: MatrixTraceExtension) {

        project.afterEvaluate {
            if (!extension.isEnable) {
                return@afterEvaluate
            }
        }

        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        
        androidComponents.onVariants { variant ->
            // Use local variable to capture extension properties safely
            val baseMethodMapFile = extension.baseMethodMapFile ?: ""
            val blackListFile = extension.blackListFile ?: ""
            val mappingDir = extension.mappingDir ?: ""
            val skipCheckClass = extension.isSkipCheckClass

            val collectTaskProvider = variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                .use(project.tasks.register("matrixMethodCollect${variant.name.capitalize()}", MatrixMethodCollectTask::class.java))
                .toGet(
                    ScopedArtifact.CLASSES,
                    MatrixMethodCollectTask::allDirectories,
                    MatrixMethodCollectTask::allJars
                )

            collectTaskProvider.configure { task ->
                val config = MatrixTraceExtensionConfig(
                    baseMethodMapFile = baseMethodMapFile,
                    blackListFile = blackListFile,
                    mappingDir = mappingDir,
                    isSkipCheckClass = skipCheckClass
                )
                task.traceConfig.set(config)
            }

            variant.instrumentation.transformClassesWith(
                MatrixTraceClassVisitorFactory::class.java,
                InstrumentationScope.ALL
            ) { params ->
                params.mappingDir.set(collectTaskProvider.flatMap { it.mappingDir })
                params.skipCheckClass.set(skipCheckClass)
                
                val blockSet = mutableSetOf<String>()
                val blockFile = File(blackListFile)
                if (blockFile.exists()) {
                    blockFile.useLines { lines ->
                        for (line in lines) {
                            val tLine = line.trim()
                            if (tLine.isNotEmpty() && !tLine.startsWith("#")) {
                                blockSet.add(tLine)
                            }
                        }
                    }
                }
                params.blockSet.set(blockSet)
            }

            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )
            Log.i(TAG, "MatrixTraceClassVisitorFactory configured for variant: ${variant.name}")
        }
    }
}

