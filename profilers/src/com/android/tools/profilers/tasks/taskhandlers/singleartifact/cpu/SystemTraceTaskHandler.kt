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
package com.android.tools.profilers.tasks.taskhandlers.singleartifact.cpu

import com.android.tools.profilers.cpu.CpuCaptureSessionArtifact
import com.android.tools.profilers.cpu.config.PerfettoConfiguration
import com.android.tools.profilers.sessions.SessionArtifact
import com.android.tools.profilers.sessions.SessionsManager

class SystemTraceTaskHandler(sessionsManager: SessionsManager) : CpuTaskHandler(sessionsManager) {
  override fun getCpuRecordingConfig() = PerfettoConfiguration(getTaskName(), false)

  override fun supportsArtifact(artifact: SessionArtifact<*>?) =
    artifact is CpuCaptureSessionArtifact
    && artifact.artifactProto.hasConfiguration()
    && (artifact.artifactProto.configuration.hasPerfettoOptions() || artifact.artifactProto.configuration.hasAtraceOptions())

  override fun getTaskName() = "System Trace"
}