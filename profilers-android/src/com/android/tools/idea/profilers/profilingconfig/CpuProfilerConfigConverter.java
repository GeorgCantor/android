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
package com.android.tools.idea.profilers.profilingconfig;

import static com.android.tools.profilers.cpu.config.ProfilingConfiguration.SYSTEM_TRACE_BUFFER_SIZE_MB;

import com.android.sdklib.AndroidVersion;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.run.profiler.CpuProfilerConfig;
import com.android.tools.profilers.cpu.config.ArtInstrumentedConfiguration;
import com.android.tools.profilers.cpu.config.ArtSampledConfiguration;
import com.android.tools.profilers.cpu.config.AtraceConfiguration;
import com.android.tools.profilers.cpu.config.PerfettoConfiguration;
import com.android.tools.profilers.cpu.config.ProfilingConfiguration;
import com.android.tools.profilers.cpu.config.SimpleperfConfiguration;
import com.android.tools.profilers.cpu.config.UnspecifiedConfiguration;
import com.intellij.util.containers.ContainerUtil;
import java.util.List;

public class CpuProfilerConfigConverter {

  private CpuProfilerConfigConverter() { }

  /**
   * Converts from a {@link ProfilingConfiguration} to a {@link CpuProfilerConfig}
   */
  public static CpuProfilerConfig fromProfilingConfiguration(ProfilingConfiguration config) {
    CpuProfilerConfig cpuProfilerConfig = null;

    switch (config.getTraceType()) {
      case ART:
        if (config instanceof ArtSampledConfiguration) {
          ArtSampledConfiguration artSampledConfiguration = (ArtSampledConfiguration)config;
          cpuProfilerConfig = new CpuProfilerConfig(artSampledConfiguration.getName(), CpuProfilerConfig.Technology.SAMPLED_JAVA);
          cpuProfilerConfig.setSamplingIntervalUs(artSampledConfiguration.getProfilingSamplingIntervalUs());
          cpuProfilerConfig.setBufferSizeMb(artSampledConfiguration.getProfilingBufferSizeInMb());
        }
        else {
          ArtInstrumentedConfiguration artInstrumentedConfiguration = (ArtInstrumentedConfiguration)config;
          cpuProfilerConfig = new CpuProfilerConfig(artInstrumentedConfiguration.getName(), CpuProfilerConfig.Technology.INSTRUMENTED_JAVA);
          cpuProfilerConfig.setBufferSizeMb(artInstrumentedConfiguration.getProfilingBufferSizeInMb());
        }
        break;
      case SIMPLEPERF:
        SimpleperfConfiguration simpleperfConfiguration = (SimpleperfConfiguration)config;
        cpuProfilerConfig = new CpuProfilerConfig(simpleperfConfiguration.getName(), CpuProfilerConfig.Technology.SAMPLED_NATIVE);
        cpuProfilerConfig.setSamplingIntervalUs(simpleperfConfiguration.getProfilingSamplingIntervalUs());
        break;
      case ATRACE:
        AtraceConfiguration atraceConfiguration = (AtraceConfiguration)config;
        cpuProfilerConfig = new CpuProfilerConfig(atraceConfiguration.getName(), CpuProfilerConfig.Technology.SYSTEM_TRACE);
        cpuProfilerConfig.setBufferSizeMb(SYSTEM_TRACE_BUFFER_SIZE_MB);
        break;
      case PERFETTO:
        PerfettoConfiguration perfettoConfiguration = (PerfettoConfiguration)config;
        cpuProfilerConfig = new CpuProfilerConfig(perfettoConfiguration.getName(), CpuProfilerConfig.Technology.SYSTEM_TRACE);
        cpuProfilerConfig.setBufferSizeMb(SYSTEM_TRACE_BUFFER_SIZE_MB);
        break;
      case UNSPECIFIED:
        UnspecifiedConfiguration unspecifiedConfiguration = (UnspecifiedConfiguration)config;
        cpuProfilerConfig = new CpuProfilerConfig(unspecifiedConfiguration.getName(), CpuProfilerConfig.Technology.SAMPLED_JAVA);
        break;
    }

    return cpuProfilerConfig;
  }

  /**
   * Converts from a {@link CpuProfilerConfig} to a {@link ProfilingConfiguration}
   */
  public static ProfilingConfiguration toProfilingConfiguration(CpuProfilerConfig config, int deviceApi) {
    ProfilingConfiguration configuration = null;

    String name = config.getName();

    switch (config.getTechnology()) {
      case SAMPLED_JAVA:
        configuration = new ArtSampledConfiguration(name);
        ((ArtSampledConfiguration)configuration).setProfilingBufferSizeInMb(config.getBufferSizeMb());
        ((ArtSampledConfiguration)configuration).setProfilingSamplingIntervalUs(config.getSamplingIntervalUs());
        break;
      case INSTRUMENTED_JAVA:
        configuration = new ArtInstrumentedConfiguration(name);
        ((ArtInstrumentedConfiguration)configuration).setProfilingBufferSizeInMb(config.getBufferSizeMb());
        break;
      case SAMPLED_NATIVE:
        configuration = new SimpleperfConfiguration(name);
        ((SimpleperfConfiguration)configuration).setProfilingSamplingIntervalUs(config.getSamplingIntervalUs());
        break;
      case SYSTEM_TRACE:
        if (StudioFlags.PROFILER_TRACEBOX.get()) {
          if (deviceApi >= AndroidVersion.VersionCodes.M) {
            configuration = new PerfettoConfiguration(name, true);
            break;
          }
        }
        if (deviceApi >= AndroidVersion.VersionCodes.P) {
          configuration = new PerfettoConfiguration(name, false);
        }
        else {
          configuration = new AtraceConfiguration(name);
        }
        break;
    }

    return configuration;
  }

  /**
   * Converts from list of {@link CpuProfilerConfig} to a list of {@link ProfilingConfiguration}
   */
  public static List<ProfilingConfiguration> toProfilingConfiguration(List<CpuProfilerConfig> configs, int deviceApi) {
    return ContainerUtil.map(configs, config -> toProfilingConfiguration(config, deviceApi));
  }
}
