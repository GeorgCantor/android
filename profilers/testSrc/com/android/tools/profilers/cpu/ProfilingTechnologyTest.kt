/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.profilers.cpu

import com.android.sdklib.AndroidVersion
import com.android.tools.profiler.proto.Trace
import com.android.tools.profiler.proto.Trace.TraceMode
import com.android.tools.profilers.TraceConfigOptionsUtils
import com.android.tools.profilers.cpu.config.ProfilingConfiguration.TraceType
import com.android.tools.profilers.cpu.config.ArtInstrumentedConfiguration
import com.android.tools.profilers.cpu.config.ArtSampledConfiguration
import com.android.tools.profilers.cpu.config.AtraceConfiguration
import com.android.tools.profilers.cpu.config.ImportedConfiguration
import com.android.tools.profilers.cpu.config.PerfettoSystemTraceConfiguration
import com.android.tools.profilers.cpu.config.SimpleperfConfiguration
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProfilingTechnologyTest {
  @Test
  fun fromTraceConfigurationArtSampled() {
    val config = Trace.TraceConfiguration.getDefaultInstance().toBuilder()
    TraceConfigOptionsUtils.addDefaultTraceOptions(config, TraceType.ART)
    config.artOptionsBuilder.traceMode = TraceMode.SAMPLED
    assertThat(ProfilingTechnology.fromTraceConfiguration(config.build())).isEqualTo(ProfilingTechnology.ART_SAMPLED)
  }

  @Test
  fun fromTraceConfigurationArtInstrumented() {
    val config = Trace.TraceConfiguration.getDefaultInstance().toBuilder()
    TraceConfigOptionsUtils.addDefaultTraceOptions(config, TraceType.ART)
    config.artOptionsBuilder.traceMode = TraceMode.INSTRUMENTED
    assertThat(ProfilingTechnology.fromTraceConfiguration(config.build())).isEqualTo(ProfilingTechnology.ART_INSTRUMENTED)
  }

  @Test
  fun fromTraceConfigurationArtUnspecified() {
    val config = Trace.TraceConfiguration.getDefaultInstance().toBuilder()
    TraceConfigOptionsUtils.addDefaultTraceOptions(config, TraceType.ART)
    assertThat(ProfilingTechnology.fromTraceConfiguration(config.build())).isEqualTo(ProfilingTechnology.ART_UNSPECIFIED)
  }

  @Test
  fun fromTraceConfigurationAtrace() {
    val config = Trace.TraceConfiguration.getDefaultInstance().toBuilder()
    TraceConfigOptionsUtils.addDefaultTraceOptions(config, TraceType.ATRACE)
    assertThat(ProfilingTechnology.fromTraceConfiguration(config.build())).isEqualTo(ProfilingTechnology.SYSTEM_TRACE)
  }

  @Test
  fun fromTraceConfigurationSimpleperf() {
    val config = Trace.TraceConfiguration.getDefaultInstance().toBuilder()
    TraceConfigOptionsUtils.addDefaultTraceOptions(config, TraceType.SIMPLEPERF)
    assertThat(ProfilingTechnology.fromTraceConfiguration(config.build())).isEqualTo(ProfilingTechnology.SIMPLEPERF)
  }

  @Test
  fun fromTraceConfigurationPerfetto() {
    val config = Trace.TraceConfiguration.getDefaultInstance().toBuilder()
    TraceConfigOptionsUtils.addDefaultTraceOptions(config, TraceType.PERFETTO)
    assertThat(ProfilingTechnology.fromTraceConfiguration(config.build())).isEqualTo(ProfilingTechnology.SYSTEM_TRACE)
  }

  @Test
  fun fromConfigArtSampled() {
    val artSampledConfiguration = ArtSampledConfiguration("MyConfiguration")
    assertThat(ProfilingTechnology.fromConfig(artSampledConfiguration)).isEqualTo(ProfilingTechnology.ART_SAMPLED)
  }

  @Test
  fun fromConfigArtInstrumented() {
    val artInstrumentedConfiguration = ArtInstrumentedConfiguration("MyConfiguration")
    assertThat(ProfilingTechnology.fromConfig(artInstrumentedConfiguration))
      .isEqualTo(ProfilingTechnology.ART_INSTRUMENTED)
  }

  @Test
  fun fromConfigSimpleperf() {
    val simpleperfConfiguration = SimpleperfConfiguration("MyConfiguration")
    assertThat(ProfilingTechnology.fromConfig(simpleperfConfiguration)).isEqualTo(ProfilingTechnology.SIMPLEPERF)
  }

  @Test
  fun fromConfigAtrace() {
    val atraceConfiguration = AtraceConfiguration("MyConfiguration")
    assertThat(ProfilingTechnology.fromConfig(atraceConfiguration)).isEqualTo(ProfilingTechnology.SYSTEM_TRACE)
  }

  @Test
  fun fromConfigPerfetto() {
    val perfettoSystemTraceConfiguration = PerfettoSystemTraceConfiguration("MyConfiguration", false)
    assertThat(ProfilingTechnology.fromConfig(perfettoSystemTraceConfiguration)).isEqualTo(ProfilingTechnology.SYSTEM_TRACE)
    assertThat(perfettoSystemTraceConfiguration.requiredDeviceLevel).isEqualTo(AndroidVersion.VersionCodes.P)
  }

  @Test(expected = IllegalStateException::class)
  fun fromConfigUnexpectedConfig() {
    val unexpectedConfiguration = ImportedConfiguration()
    assertThat(ProfilingTechnology.fromConfig(unexpectedConfiguration)).isEqualTo("any config. it should fail before.")
  }

  @Test
  fun getType() {
    assertThat(ProfilingTechnology.SIMPLEPERF.type).isEqualTo(TraceType.SIMPLEPERF)
    assertThat(ProfilingTechnology.SYSTEM_TRACE.type).isEqualTo(TraceType.ATRACE)

    assertThat(ProfilingTechnology.ART_INSTRUMENTED.type).isEqualTo(TraceType.ART)
    assertThat(ProfilingTechnology.ART_SAMPLED.type).isEqualTo(TraceType.ART)
    assertThat(ProfilingTechnology.ART_UNSPECIFIED.type).isEqualTo(TraceType.ART)
  }

  @Test
  fun getMode() {
    assertThat(ProfilingTechnology.SIMPLEPERF.mode).isEqualTo(TraceMode.SAMPLED)
    assertThat(ProfilingTechnology.SYSTEM_TRACE.mode).isEqualTo(TraceMode.INSTRUMENTED)

    assertThat(ProfilingTechnology.ART_INSTRUMENTED.mode).isEqualTo(TraceMode.INSTRUMENTED)
    assertThat(ProfilingTechnology.ART_SAMPLED.mode).isEqualTo(TraceMode.SAMPLED)
    assertThat(ProfilingTechnology.ART_UNSPECIFIED.mode).isEqualTo(TraceMode.UNSPECIFIED_MODE)
  }
}