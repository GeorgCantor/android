/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.build.attribution.analyzers

import com.android.build.attribution.data.GradlePluginsData
import com.android.build.attribution.data.PluginData
import com.android.build.attribution.data.StudioProvidedInfo
import com.android.buildanalyzer.common.AndroidGradlePluginAttributionData
import com.android.ide.common.gradle.Component
import com.android.ide.common.gradle.Version
import com.android.ide.common.repository.AgpVersion
import kotlinx.collections.immutable.toImmutableMap
import org.gradle.util.GradleVersion

/** Minimal AGP version that supports configuration caching. */
private val minAGPVersion = AgpVersion.parse("7.0.0-alpha10")
private val minGradleVersionForStableConfigurationCache = GradleVersion.version("8.1")

class ConfigurationCachingCompatibilityAnalyzer : BaseAnalyzer<ConfigurationCachingCompatibilityProjectResult>(),
    BuildAttributionReportAnalyzer,
    KnownPluginsDataAnalyzer,
    PostBuildProcessAnalyzer {

  private val buildscriptClasspath = mutableListOf<Component>()
  private var appliedPlugins: Map<String, List<PluginData>> = emptyMap()
  private var knownPlugins: List<GradlePluginsData.PluginInfo> = emptyList()
  private var currentAgpVersion: AgpVersion? = null
  private var configurationCachingGradlePropertiesFlagState: String? = null
  private var configurationCacheInBuildState: Boolean? = null
  private var runningConfigurationCacheTestFlow: Boolean? = null
  private var currentGradleVersion: GradleVersion? = null

  override fun cleanupTempState() {
    buildscriptClasspath.clear()
    appliedPlugins = emptyMap()
    knownPlugins = emptyList()
    currentAgpVersion = null
    configurationCachingGradlePropertiesFlagState = null
    configurationCacheInBuildState = null
    runningConfigurationCacheTestFlow = null
    currentGradleVersion = null
  }

  override fun receiveBuildAttributionReport(androidGradlePluginAttributionData: AndroidGradlePluginAttributionData) {
    buildscriptClasspath.addAll(
      androidGradlePluginAttributionData.buildscriptDependenciesInfo.mapNotNull { Component.tryParse(it) }
    )
    configurationCacheInBuildState = androidGradlePluginAttributionData.buildInfo?.configurationCacheIsOn
    currentAgpVersion = androidGradlePluginAttributionData.buildInfo?.agpVersion?.let { AgpVersion.tryParse(it) }
    currentGradleVersion = androidGradlePluginAttributionData.buildInfo?.gradleVersion?.let { GradleVersion.version(it) }
  }

  override fun receiveKnownPluginsData(data: GradlePluginsData) {
    knownPlugins = data.pluginsInfo
  }

  override fun runPostBuildAnalysis(analyzersResult: BuildEventsAnalyzersProxy, studioProvidedInfo: StudioProvidedInfo) {
    appliedPlugins = analyzersResult.projectConfigurationAnalyzer.result.allAppliedPlugins.toImmutableMap()
    if (currentAgpVersion == null) currentAgpVersion = studioProvidedInfo.agpVersion
    if (currentGradleVersion == null) currentGradleVersion = studioProvidedInfo.gradleVersion
    configurationCachingGradlePropertiesFlagState = studioProvidedInfo.configurationCachingGradlePropertyState
    runningConfigurationCacheTestFlow = studioProvidedInfo.isInConfigurationCacheTestFlow
    ensureResultCalculated()
  }

  override fun calculateResult(): ConfigurationCachingCompatibilityProjectResult {
    return compute(appliedPlugins.values.flatten())
  }

  private fun compute(appliedPlugins: List<PluginData>): ConfigurationCachingCompatibilityProjectResult {
    val isFeatureConsideredStable = currentGradleVersion?.let { it >= minGradleVersionForStableConfigurationCache } ?: false
    if (runningConfigurationCacheTestFlow == true) return ConfigurationCacheCompatibilityTestFlow(isFeatureConsideredStable)
    if (configurationCacheInBuildState == true) return ConfigurationCachingTurnedOn
    if (configurationCachingGradlePropertiesFlagState != null) return ConfigurationCachingTurnedOff
    if (buildscriptClasspath.isEmpty()) {
      // Possible that we are using an old AGP. Need to check the known version from sync.
      currentAgpVersion?.let {
        if (it < minAGPVersion) return AGPUpdateRequired(it, appliedPlugins.filter { it.isAndroidPlugin() })
      }
    }

    val pluginsByPluginInfo: Map<GradlePluginsData.PluginInfo?, List<PluginData>> = appliedPlugins
        .filter { it.pluginType == PluginData.PluginType.BINARY_PLUGIN }
        .toSet()
        .groupBy { appliedPlugin -> knownPlugins.find { it.isThisPlugin(appliedPlugin) } }

    val incompatiblePluginWarnings = mutableListOf<IncompatiblePluginWarning>()
    val upgradePluginWarnings = mutableListOf<IncompatiblePluginWarning>()
    pluginsByPluginInfo.forEach { (pluginInfo, plugins) ->
      if (pluginInfo?.pluginArtifact == null) return@forEach
      val detectedVersion = buildscriptClasspath.find { it.isSameCoordinate(pluginInfo.pluginArtifact) }?.version
      if (detectedVersion != null) {
        when {
          pluginInfo.configurationCachingCompatibleFrom == null -> incompatiblePluginWarnings.addAll(
            plugins.map { IncompatiblePluginWarning(it, detectedVersion, pluginInfo) }
          )
          detectedVersion < pluginInfo.configurationCachingCompatibleFrom -> upgradePluginWarnings.addAll(
            plugins.map { IncompatiblePluginWarning(it, detectedVersion, pluginInfo) }
          )
        }
      }
    }
    return if (incompatiblePluginWarnings.isEmpty() && upgradePluginWarnings.isEmpty()) {
      NoIncompatiblePlugins(pluginsByPluginInfo[null]?.filterOutInternalPlugins() ?: emptyList(), isFeatureConsideredStable)
    }
    else {
      IncompatiblePluginsDetected(incompatiblePluginWarnings, upgradePluginWarnings)
    }
  }

  private fun Component.isSameCoordinate(dependencyCoordinates: GradlePluginsData.DependencyCoordinates) =
    dependencyCoordinates.group == group && dependencyCoordinates.name == name

  private fun List<PluginData>.filterOutInternalPlugins() = filter {
    // ignore Gradle, AGP and Kotlin plugins, buildscript
    !it.isAndroidPlugin() && !it.isGradlePlugin() && !it.isKotlinPlugin() && it.pluginType != PluginData.PluginType.SCRIPT
  }
}

sealed class ConfigurationCachingCompatibilityProjectResult : AnalyzerResult

data class AGPUpdateRequired(
  val currentVersion: AgpVersion,
  val appliedPlugins: List<PluginData>
) : ConfigurationCachingCompatibilityProjectResult() {
  val recommendedVersion = AgpVersion.parse("7.0.0")
  val dependencyCoordinates = GradlePluginsData.DependencyCoordinates("com.android.tools.build", "gradle")
}

/**
 * Analyzer result returned when all recognised plugins are compatible with configuration caching.
 * There still might be problems in unknown plugins or buildscript and buildSrc plugins.
 */
data class NoIncompatiblePlugins(
  val unrecognizedPlugins: List<PluginData>,
  val configurationCacheIsStableFeature: Boolean
) : ConfigurationCachingCompatibilityProjectResult()

/**
 * Analyzer result returned when there are incompatible plugins detected.
 * [incompatiblePluginWarnings] contain the list of warnings for plugins known to be incompatible.
 * [upgradePluginWarnings] contain the list of warnings for plugins that need upgrade to higher version.
 */
data class IncompatiblePluginsDetected(
  val incompatiblePluginWarnings: List<IncompatiblePluginWarning>,
  val upgradePluginWarnings: List<IncompatiblePluginWarning>
) : ConfigurationCachingCompatibilityProjectResult()

data class IncompatiblePluginWarning(
  val plugin: PluginData,
  val currentVersion: Version,
  val pluginInfo: GradlePluginsData.PluginInfo,
) {
  val requiredVersion: Version?
    get() = pluginInfo.configurationCachingCompatibleFrom
}

/** Analyzer result when build is running with configuration caching enabled. */
object ConfigurationCachingTurnedOn : ConfigurationCachingCompatibilityProjectResult()

/** Analyzer result when build is running with configuration caching disabled explicitly. */
object ConfigurationCachingTurnedOff : ConfigurationCachingCompatibilityProjectResult()

/** Analyzer result for test CC builds started from Build Analyzer. */
data class ConfigurationCacheCompatibilityTestFlow(
  val configurationCacheIsStableFeature: Boolean
) : ConfigurationCachingCompatibilityProjectResult()

object NoDataFromSavedResult : ConfigurationCachingCompatibilityProjectResult()
