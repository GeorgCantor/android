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
package com.android.tools.idea.gradle.project

import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.android.tools.idea.gradle.project.model.GradleAndroidModelData
import com.android.tools.idea.gradle.project.sync.InternedModels
import com.android.tools.idea.gradle.project.sync.idea.data.service.AndroidProjectKeys
import com.android.tools.idea.serverflags.protos.RecommendedVersions
import com.android.tools.idea.serverflags.protos.StudioVersionRecommendation
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.buildAndroidProjectStub
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.ChannelStatus
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val PROJECT_ROOT = File("/")
private val APP_MODULE_ROOT = File("/app")

@RunsInEdt
class AndroidSdkCompatibilityCheckerTest {
  private val projectRule = AndroidProjectRule.withAndroidModels().onEdt()

  @get:Rule val rule = RuleChain(projectRule, HeadlessDialogRule())

  private val checker: AndroidSdkCompatibilityChecker = AndroidSdkCompatibilityChecker()
  private val serverFlag: MutableMap<Int, RecommendedVersions> = mutableMapOf(
    1000 to RecommendedVersions.newBuilder().apply {
      canaryChannel = StudioVersionRecommendation.getDefaultInstance()
      betaRcChannel = StudioVersionRecommendation.getDefaultInstance()
      stableChannel = StudioVersionRecommendation.getDefaultInstance()
    }.build()
  )
  private val timeout: Duration = 1.seconds

  @Before
  fun setUp() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.EAP // canary
  }

  @After
  fun tearDown() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.MILESTONE
  }

  @Test
  fun `test dialog is not shown when the server flag does not exist`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
    )
    val androidModels = getGradleAndroidModels(projectRule.project)

    assertThrows(TimeoutException::class.java) {
      createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, null) }, timeout) {
        throw Exception("Should not have created a dialog")
      }
    }
  }

  @Test
  fun `test dialog is not shown when there are no android modules`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
    )
    val androidModels = getGradleAndroidModels(projectRule.project)

    assertThrows(TimeoutException::class.java) {
      createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }, timeout) {
        throw Exception("Should not have created a dialog")
      }
    }
  }

  @Test
  fun `test dialog is not shown when there is a module which does not violate rules`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-33"),
    )
    val androidModels = getGradleAndroidModels(projectRule.project)

    assertThrows(TimeoutException::class.java) {
      createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }, timeout) {
        throw Exception("Should not have created a dialog")
      }
    }
  }

  @Test
  fun `test dialog is not shown when there is a module that violate rules but use invalid sdk`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "random-sdk-value"),
    )
    val androidModels = getGradleAndroidModels(projectRule.project)

    assertThrows(TimeoutException::class.java) {
      createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }, timeout) {
        throw Exception("Should not have created a dialog")
      }
    }
  }

  @Test
  fun `test dialog shown when only doNotAskAgainIdeLevel is set`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
      libModuleBuilder(compileSdk = "android-1000")
    )
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainIdeLevel = true
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainProjectLevel = false

    val androidModels = getGradleAndroidModels(projectRule.project)

    createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }) {
      assertThat(it is AndroidSdkCompatibilityDialog)
      assertThat((it as AndroidSdkCompatibilityDialog).modulesViolatingSupportRules).hasSize(2)
    }
  }

  @Test
  fun `test dialog shown when only doNotAskAgainProjectLevel is set`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainIdeLevel = false
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainProjectLevel = true

    val androidModels = getGradleAndroidModels(projectRule.project)

    createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }) {
      assertThat(it is AndroidSdkCompatibilityDialog)
      assertThat((it as AndroidSdkCompatibilityDialog).modulesViolatingSupportRules).hasSize(1)
    }
  }

  @Test
  fun `test dialog not shown when channel is dev`() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.MILESTONE
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )

    val androidModels = getGradleAndroidModels(projectRule.project)

    assertThrows(TimeoutException::class.java) {
      createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }, timeout) {
        throw Exception("Should not have created a dialog")
      }
    }
  }

  @Test
  fun `test dialog not shown when both properties are set`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainIdeLevel = true
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainProjectLevel = true

    val androidModels = getGradleAndroidModels(projectRule.project)

    assertThrows(TimeoutException::class.java) {
      createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }, timeout) {
        throw Exception("Should not have created a dialog")
      }
    }
  }

  @Test
  fun `test dialog shown with released canary channel`() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.EAP
    serverFlag[1000] = RecommendedVersions.newBuilder().apply {
      canaryChannel = StudioVersionRecommendation.newBuilder().apply {
        versionReleased = true
        buildDisplayName = "Android Studio Canary X"
      }.build()
    }.build()
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )
    val androidModels = getGradleAndroidModels(projectRule.project)

    createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }) {
      assertThat(it is AndroidSdkCompatibilityDialog)
      assertThat((it as AndroidSdkCompatibilityDialog).modulesViolatingSupportRules).hasSize(1)
      assertThat(it.recommendedVersion.versionReleased).isTrue()
      assertThat(it.recommendedVersion.buildDisplayName).isEqualTo("Android Studio Canary X")
      assertThat(it.potentialFallbackVersion).isNull()
    }
  }

  @Test
  fun `test dialog shown with released beta channel`() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.BETA
    serverFlag[1000] = RecommendedVersions.newBuilder().apply {
      betaRcChannel = StudioVersionRecommendation.newBuilder().apply {
        versionReleased = true
        buildDisplayName = "Android Studio Beta Y"
      }.build()
    }.build()
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )

    val androidModels = getGradleAndroidModels(projectRule.project)

    createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }) {
      assertThat(it is AndroidSdkCompatibilityDialog)
      assertThat((it as AndroidSdkCompatibilityDialog).modulesViolatingSupportRules).hasSize(1)
      assertThat(it.recommendedVersion.versionReleased).isTrue()
      assertThat(it.recommendedVersion.buildDisplayName).isEqualTo("Android Studio Beta Y")
      assertThat(it.potentialFallbackVersion).isNull()
    }
  }

  @Test
  fun `test dialog shown with released stable version`() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.RELEASE
    serverFlag[1000] = RecommendedVersions.newBuilder().apply {
      stableChannel = StudioVersionRecommendation.newBuilder().apply {
        versionReleased = true
        buildDisplayName = "Android Studio Stable Z"
      }.build()
    }.build()
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )

    val androidModels = getGradleAndroidModels(projectRule.project)

    createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }) {
      assertThat(it is AndroidSdkCompatibilityDialog)
      assertThat((it as AndroidSdkCompatibilityDialog).modulesViolatingSupportRules).hasSize(1)
      assertThat(it.recommendedVersion.versionReleased).isTrue()
      assertThat(it.recommendedVersion.buildDisplayName).isEqualTo("Android Studio Stable Z")
      assertThat(it.potentialFallbackVersion).isNull()
    }
  }

  @Test
  fun `test dialog shown with unreleased beta channel recommending canary`() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.BETA
    serverFlag[1000] = RecommendedVersions.newBuilder().apply {
      canaryChannel = StudioVersionRecommendation.newBuilder().apply {
        versionReleased = true
        buildDisplayName = "Android Studio Canary X"
      }.build()
      betaRcChannel = StudioVersionRecommendation.newBuilder().apply {
        versionReleased = false
        buildDisplayName = "Android Studio Beta Y"
      }.build()
    }.build()
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )

    val androidModels = getGradleAndroidModels(projectRule.project)

    createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }) {
      assertThat(it is AndroidSdkCompatibilityDialog)
      assertThat((it as AndroidSdkCompatibilityDialog).modulesViolatingSupportRules).hasSize(1)
      assertThat(it.recommendedVersion.versionReleased).isFalse()
      assertThat(it.recommendedVersion.buildDisplayName).isEqualTo("Android Studio Beta Y")
      assertThat(it.potentialFallbackVersion).isNotNull()
      assertThat(it.potentialFallbackVersion!!.versionReleased).isTrue()
      assertThat(it.potentialFallbackVersion!!.buildDisplayName).isEqualTo("Android Studio Canary X")
    }
  }

  @Test
  fun `test dialog shown with unreleased alpha channel no recommendation`() {
    UpdateSettings.getInstance().selectedChannelStatus = ChannelStatus.EAP
    serverFlag[1000] = RecommendedVersions.newBuilder().apply {
      canaryChannel = StudioVersionRecommendation.newBuilder().apply {
        versionReleased = false
        buildDisplayName = "Android Studio Canary X"
      }.build()
    }.build()
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )

    val androidModels = getGradleAndroidModels(projectRule.project)

    createModalDialogAndInteractWithIt({ checker.checkAndroidSdkVersion(androidModels, projectRule.project, serverFlag) }) {
      assertThat(it is AndroidSdkCompatibilityDialog)
      assertThat((it as AndroidSdkCompatibilityDialog).modulesViolatingSupportRules).hasSize(1)
      assertThat(it.recommendedVersion.versionReleased).isFalse()
      assertThat(it.recommendedVersion.buildDisplayName).isEqualTo("Android Studio Canary X")
      assertThat(it.potentialFallbackVersion).isNull()
    }
  }

  private fun getGradleAndroidModels(project: Project): List<DataNode<GradleAndroidModelData>> {
    val externalInfo = ProjectDataManager.getInstance().getExternalProjectData(
      project, GradleConstants.SYSTEM_ID, project.basePath!!
    )
    val projectStructure = externalInfo!!.externalProjectStructure

    return ExternalSystemApiUtil.findAllRecursively(projectStructure!!) { node ->
      AndroidProjectKeys.ANDROID_MODEL == node.key
    }.map {
      DataNode<GradleAndroidModelData>(
        AndroidProjectKeys.ANDROID_MODEL, it.data as GradleAndroidModelData, null
      )
    }
  }

  private fun appModuleBuilder(
    appPath: String = ":myapp",
    selectedVariant: String = "debug",
    compileSdk: String
  ) =
    AndroidModuleModelBuilder(
      appPath,
      selectedVariant,
      AndroidProjectBuilder().withAndroidProject { buildProjectWithCompileSdk(compileSdk) }
    )

  private fun libModuleBuilder(
    libPath: String = ":mylib",
    selectedVariant: String = "debug",
    compileSdk: String
  ) =
    AndroidModuleModelBuilder(
      libPath,
      selectedVariant,
      AndroidProjectBuilder(projectType = { IdeAndroidProjectType.PROJECT_TYPE_LIBRARY })
        .withAndroidProject { buildProjectWithCompileSdk(compileSdk) }
    )

  private fun buildProjectWithCompileSdk(compileSdk: String): IdeAndroidProjectImpl {
    return AndroidProjectBuilder(
        androidProject = {
          buildAndroidProjectStub().copy(
            compileTarget = compileSdk,
          )
        }
      ).build().invoke(
        "projectName", ":app", PROJECT_ROOT, APP_MODULE_ROOT, "8.0.0", InternedModels(null)
      ).androidProject
  }
}