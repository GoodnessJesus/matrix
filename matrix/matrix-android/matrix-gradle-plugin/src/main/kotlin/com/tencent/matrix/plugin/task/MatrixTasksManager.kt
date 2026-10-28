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

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.javalib.util.Util
import com.tencent.matrix.plugin.compat.CreationConfig
import com.tencent.matrix.plugin.extension.MatrixRemoveUnusedResExtension
import com.tencent.matrix.trace.extension.MatrixTraceExtension
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class MatrixTasksManager {

    companion object {
        const val TAG = "Matrix.TasksManager"
    }

    fun createMatrixTasks(
            android: ApplicationExtension,
            androidComponents: ApplicationAndroidComponentsExtension,
            project: Project,
            traceExtension: MatrixTraceExtension,
            removeUnusedResourcesExtension: MatrixRemoveUnusedResExtension
    ) {
        androidComponents.onVariants { variant ->
            val signingConfig = variant.buildType?.let {
                android.buildTypes.getByName(it).signingConfig
            }
            val creationConfig = CreationConfig(variant, project, signingConfig)
            if (traceExtension.isEnable) {
                createMatrixTraceTask(androidComponents, project, creationConfig, traceExtension)
            }
            if (removeUnusedResourcesExtension.enable &&
                    (Util.isNullOrNil(removeUnusedResourcesExtension.variant) ||
                            variant.name.equals(removeUnusedResourcesExtension.variant, true))) {
                createRemoveUnusedResourcesTask(project, creationConfig, removeUnusedResourcesExtension)
            }
        }
    }

    private fun createMatrixTraceTask(
            androidComponents: ApplicationAndroidComponentsExtension,
            project: Project,
            creationConfig: CreationConfig,
            extension: MatrixTraceExtension
    ) {
        val action = MatrixTraceTask.CreationAction(
                creationConfig,
                extension,
                project.files(androidComponents.sdkComponents.bootClasspath)
        )
        val traceTask = project.tasks.register(action.name, action.type, action)
        creationConfig.variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                .use(traceTask)
                .toTransform(
                        ScopedArtifact.CLASSES,
                        MatrixTraceTask::inputJars,
                        MatrixTraceTask::inputDirs,
                        MatrixTraceTask::output
                )
    }

    private fun createRemoveUnusedResourcesTask(
            project: Project,
            creationConfig: CreationConfig,
            extension: MatrixRemoveUnusedResExtension
    ) {
        Log.i(TAG, "RemoveUnusedResourcesExtension: %s", extension)
        if (extension.v2) {
            val action = RemoveUnusedResourcesTaskV2.CreationAction(creationConfig, extension)
            val task = project.tasks.register(action.name, action.type, action)
            val transformationRequest = creationConfig.variant.artifacts.use(task)
                    .wiredWithDirectories(
                            RemoveUnusedResourcesTaskV2::inputApkDirectory,
                            RemoveUnusedResourcesTaskV2::outputApkDirectory
                    )
                    .toTransformMany(SingleArtifact.APK)
            task.configure {
                it.transformationRequest = transformationRequest
            }
        } else {
            val action = RemoveUnusedResourcesTask.CreationAction(creationConfig, extension)
            val task = project.tasks.register(action.name, action.type, action)
            val transformationRequest = creationConfig.variant.artifacts.use(task)
                    .wiredWithDirectories(
                            RemoveUnusedResourcesTask::inputApkDirectory,
                            RemoveUnusedResourcesTask::outputApkDirectory
                    )
                    .toTransformMany(SingleArtifact.APK)
            task.configure {
                it.transformationRequest = transformationRequest
            }
        }
    }
}
