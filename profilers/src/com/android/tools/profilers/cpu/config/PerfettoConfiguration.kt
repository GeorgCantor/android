/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.profilers.cpu.config

import com.android.sdklib.AndroidVersion
import com.android.tools.profiler.proto.Trace
import com.android.tools.profilers.perfetto.config.PerfettoTraceConfigBuilders
import perfetto.protos.PerfettoConfig.TraceConfig

/**
 * Configuration for Perfetto traces.
 */
class PerfettoConfiguration(name: String, private val isTraceboxEnabled: Boolean) : ProfilingConfiguration(name) {
  override fun getOptions(): TraceConfig {
    return PerfettoTraceConfigBuilders.getCpuTraceConfig(SYSTEM_TRACE_BUFFER_SIZE_MB)
  }

  private fun setAppPkgName(optionsBuilder: TraceConfig.Builder, appPkgName: String) {
    val ftraceDataSourceIndex = optionsBuilder.dataSourcesList.indexOfFirst { it.config.name.equals("linux.ftrace") }

    if (ftraceDataSourceIndex == -1) {
      return
    }

    optionsBuilder.dataSourcesBuilderList[ftraceDataSourceIndex].configBuilder.perfEventConfigBuilder.addTargetCmdline(appPkgName)
  }

  override fun addOptions(configBuilder: Trace.TraceConfiguration.Builder, additionalOptions: Map<AdditionalOptions, Any>) {
    val perfettoOptionsBuilder = options.toBuilder()

    val appPkgName = additionalOptions.getOrDefault(AdditionalOptions.APP_PKG_NAME, null) as String?
    appPkgName?.let { setAppPkgName(perfettoOptionsBuilder, it) }

    configBuilder.perfettoOptions = perfettoOptionsBuilder.build()
  }

  override fun getTraceType(): TraceType {
    return TraceType.PERFETTO
  }

  override fun getRequiredDeviceLevel(): Int = if (isTraceboxEnabled) AndroidVersion.VersionCodes.M else AndroidVersion.VersionCodes.P
}