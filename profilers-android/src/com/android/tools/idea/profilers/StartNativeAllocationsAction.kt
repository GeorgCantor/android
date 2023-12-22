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
package com.android.tools.idea.profilers

import android.annotation.SuppressLint
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.profilers.memory.MainMemoryProfilerStage
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class StartNativeAllocationsAction : DumbAwareAction(
  "Start Native Allocations",
  "Start a native allocations in the current profiling session",
  null
) {

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabled =
      StudioFlags.PROFILER_TESTING_MODE.get() &&
      project != null &&
      AndroidProfilerToolWindowFactory
        .getProfilerToolWindow(project)?.profilers?.sessionsManager?.isSessionAlive == true
  }

  @SuppressLint("VisibleForTests")
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project!!
    val profilers = AndroidProfilerToolWindowFactory.getProfilerToolWindow(project)!!.profilers
    val stage = MainMemoryProfilerStage(profilers)
    profilers.stage = stage
    stage.startNativeAllocationCapture()
  }
}