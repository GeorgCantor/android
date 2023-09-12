/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.profilers.tasks.taskhandlers.singleartifact.memory

import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Memory
import com.android.tools.profilers.memory.AllocationSessionArtifact
import com.android.tools.profilers.memory.LegacyAllocationsSessionArtifact
import com.android.tools.profilers.memory.MainMemoryProfilerStage
import com.android.tools.profilers.sessions.SessionArtifact
import com.android.tools.profilers.sessions.SessionItem
import com.android.tools.profilers.sessions.SessionsManager
import com.android.tools.profilers.tasks.args.TaskArgs
import com.android.tools.profilers.tasks.args.singleartifact.memory.AllocationsTaskArgs
import com.android.tools.profilers.tasks.args.singleartifact.memory.JavaKotlinAllocationsTaskArgs
import com.android.tools.profilers.tasks.args.singleartifact.memory.LegacyJavaKotlinAllocationsTaskArgs
import com.android.tools.profilers.tasks.taskhandlers.TaskHandlerUtils.findTaskArtifact
import com.intellij.util.asSafely

/**
 * This class defines the task handler to perform a java/kotlin allocations task.
 */
class JavaKotlinAllocationsTaskHandler(sessionsManager: SessionsManager) : MemoryTaskHandler(sessionsManager) {
  override fun startCapture(stage: MainMemoryProfilerStage) {
    stage.startJavaKotlinAllocationCapture()
  }

  override fun stopCapture(stage: MainMemoryProfilerStage) {
    // Stopping a Java/Kotlin Allocations capture is invoked and only accessible within the Allocations stage itself, thus we leave it to
    // the respective stage to handle stopping of the allocations, and leave this method definition empty.
  }

  override fun loadTask(args: TaskArgs?): Boolean {
    if (args !is LegacyJavaKotlinAllocationsTaskArgs && args !is JavaKotlinAllocationsTaskArgs) {
      handleError("The task arguments (TaskArgs) supplied are not of the expected type (JavaKotlinAllocationTaskArgs)")
      return false
    }
    val javaKotlinAllocationTaskArgs = args as? LegacyJavaKotlinAllocationsTaskArgs ?: args as JavaKotlinAllocationsTaskArgs
    val javaKotlinAllocationsTaskArtifact = javaKotlinAllocationTaskArgs.getAllocationSessionArtifact()
    loadCapture(javaKotlinAllocationsTaskArtifact)
    return true
  }

  override fun createArgs(
    sessionItems: Map<Long, SessionItem>,
    selectedSession: Common.Session
  ): AllocationsTaskArgs<out SessionArtifact<Memory.AllocationsInfo>>? {
    // Finds the artifact that backs the task identified via its corresponding unique session (selectedSession).
    val artifact = findTaskArtifact(selectedSession, sessionItems, ::supportsArtifact)

    // Only if the underlying artifact is non-null should the TaskArgs be non-null.
    return if (supportsArtifact(artifact)) {
      return artifact.asSafely<LegacyAllocationsSessionArtifact>()?.let { LegacyJavaKotlinAllocationsTaskArgs(it) }
             ?: artifact.asSafely<AllocationSessionArtifact>()?.let { JavaKotlinAllocationsTaskArgs(it) }
    }
    else {
      null
    }
  }

  override fun supportsArtifact(artifact: SessionArtifact<*>?): Boolean {
    return artifact is AllocationSessionArtifact || artifact is LegacyAllocationsSessionArtifact
  }

  override fun getTaskName(): String {
    return "Java/Kotlin Allocations"
  }
}