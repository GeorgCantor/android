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

import com.android.SdkConstants
import com.android.build.attribution.data.StudioProvidedInfo
import com.android.buildanalyzer.common.CheckJetifierResult
import com.android.ide.common.repository.AgpVersion
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.project.build.attribution.getAgpAttributionFileDir
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.utils.FileUtils
import java.io.File

const val CHECK_JETIFIER_TASK_NAME = "checkJetifier"

fun checkJetifierResultFile(buildRequestData: GradleBuildInvoker.Request.RequestData): File = FileUtils.join(
  getAgpAttributionFileDir(buildRequestData),
  SdkConstants.FD_BUILD_ATTRIBUTION,
  "checkJetifierResult.json"
)

/** Minimal AGP version that supports running checkJetifier task. */
private val minAGPVersion = AgpVersion.parse("7.1.0-beta01")

class JetifierUsageAnalyzer(cachedCheckJetifierResultData: CheckJetifierResultData?) : BaseAnalyzer<JetifierUsageAnalyzerResult>(), PostBuildProcessAnalyzer {
  private var enableJetifierFlagState: Boolean? = null
  private var useAndroidXFlagState: Boolean? = null
  private var shouldAnalyzerRun: Boolean = false
  private var isCheckJetifierBuild: Boolean = false
  var checkJetifierResultData: CheckJetifierResultData? = cachedCheckJetifierResultData

  override fun runPostBuildAnalysis(analyzersResult: BuildEventsAnalyzersProxy, studioProvidedInfo: StudioProvidedInfo) {
    shouldAnalyzerRun = shouldAnalyzerRun(studioProvidedInfo.agpVersion)
    if (!shouldAnalyzerRun) return
    enableJetifierFlagState = studioProvidedInfo.enableJetifierPropertyState
    useAndroidXFlagState = studioProvidedInfo.useAndroidXPropertyState

    checkJetifierResultFile(studioProvidedInfo.buildRequestHolder.buildRequest.data).let {
      if (it.exists()) {
        checkJetifierResultData = CheckJetifierResultData(
          checkJetifierResult = CheckJetifierResult.load(it),
          lastCheckJetifierBuildTimestamp = System.currentTimeMillis()
        )
        isCheckJetifierBuild = true
      }
    }
  }

  override fun calculateResult(): JetifierUsageAnalyzerResult {
    if (!shouldAnalyzerRun) return JetifierUsageAnalyzerResult(AnalyzerNotRun, checkJetifierResultData?.lastCheckJetifierBuildTimestamp, false)
    if (enableJetifierFlagState == true && useAndroidXFlagState == true) {
      return checkJetifierResultData?.checkJetifierResult?.let {
        if (it.isEmpty()) JetifierUsageAnalyzerResult(JetifierCanBeRemoved, checkJetifierResultData?.lastCheckJetifierBuildTimestamp, isCheckJetifierBuild)
        else JetifierUsageAnalyzerResult(JetifierRequiredForLibraries(it), checkJetifierResultData?.lastCheckJetifierBuildTimestamp, isCheckJetifierBuild)
      } ?: JetifierUsageAnalyzerResult(JetifierUsedCheckRequired, checkJetifierResultData?.lastCheckJetifierBuildTimestamp, false)
    }
    return JetifierUsageAnalyzerResult(JetifierNotUsed, checkJetifierResultData?.lastCheckJetifierBuildTimestamp, false)
  }

  override fun cleanupTempState() {
    // Leave checkJetifierResult and lastCheckJetifierBuildTimestamp for future reports to not load it on every build.
    enableJetifierFlagState = null
    useAndroidXFlagState = null
    shouldAnalyzerRun = false
    isCheckJetifierBuild = false
  }

}

private fun shouldAnalyzerRun(currentAgpVersion: AgpVersion?): Boolean {
  return StudioFlags.BUILD_ANALYZER_JETIFIER_ENABLED.get() && currentAgpVersion != null && currentAgpVersion >= minAGPVersion
}

data class CheckJetifierResultData(
  val lastCheckJetifierBuildTimestamp: Long?,
  val checkJetifierResult: CheckJetifierResult?
)

data class JetifierUsageAnalyzerResult(
  val projectStatus: JetifierUsageProjectStatus,
  val lastCheckJetifierBuildTimestamp: Long? = null,
  /** If current build was a checkJetifier task request. */
  val checkJetifierBuild: Boolean = false
) : AnalyzerResult

sealed class JetifierUsageProjectStatus

object AnalyzerNotRun : JetifierUsageProjectStatus()
object JetifierNotUsed : JetifierUsageProjectStatus()
object JetifierUsedCheckRequired : JetifierUsageProjectStatus()
object JetifierCanBeRemoved : JetifierUsageProjectStatus()
data class JetifierRequiredForLibraries(val checkJetifierResult: CheckJetifierResult) : JetifierUsageProjectStatus()
