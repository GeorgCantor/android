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

import com.android.tools.idea.gradle.model.IdeAndroidProjectType
import com.android.tools.idea.gradle.model.impl.IdeAndroidProjectImpl
import com.android.tools.idea.gradle.project.model.GradleAndroidModelData
import com.android.tools.idea.gradle.project.sync.InternedModels
import com.android.tools.idea.gradle.project.sync.idea.data.service.AndroidProjectKeys
import com.android.tools.idea.testing.AndroidModuleModelBuilder
import com.android.tools.idea.testing.AndroidProjectBuilder
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.JavaModuleModelBuilder
import com.android.tools.idea.testing.buildAndroidProjectStub
import com.android.tools.idea.testing.onEdt
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.Rule
import org.junit.Test
import java.io.File

private val PROJECT_ROOT = File("/")
private val APP_MODULE_ROOT = File("/app")

@RunsInEdt
class AndroidSdkCompatibilityCheckerTest {

  @get:Rule val projectRule = AndroidProjectRule.withAndroidModels().onEdt()
  @get:Rule val edtRule = EdtRule()

  private val checker: AndroidSdkCompatibilityChecker = AndroidSdkCompatibilityChecker()
  private val dialogMessages = mutableListOf<String>()

  @Test
  fun `test dialog is not shown when there are no android modules`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
    )
    val androidModels = getGradleAndroidModels(projectRule.project)
    responseToDialog(false) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(0)
  }

  @Test
  fun `test dialog is not shown when there is a module which does not violate rules`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-33"),
    )
    val androidModels = getGradleAndroidModels(projectRule.project)

    responseToDialog(false) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(0)
  }

  @Test
  fun `test dialog is not shown when there is a module that violate rules but use invalid sdk`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "random-sdk-value"),
    )
    val androidModels = getGradleAndroidModels(projectRule.project)
    responseToDialog(false) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(0)
  }

  @Test
  fun `test dialog is shown when there is a module which violates rules`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )
    val androidModels = getGradleAndroidModels(projectRule.project)
    responseToDialog(true) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(1)
    assertThat(dialogMessages[0]).startsWith("Your project is configured with a compile SDK version that is not supported by this version of Android Studio")
    assertThat(dialogMessages[0]).contains(".myapp")
  }

  @Test
  fun `test dialog is shown when there is are more than 5 modules which violates rules`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
      appModuleBuilder(compileSdk = "android-1000"),
      appModuleBuilder(compileSdk = "android-1000"),
      libModuleBuilder(compileSdk = "android-1000"),
      libModuleBuilder(compileSdk = "android-1000"),
      libModuleBuilder(compileSdk = "android-1000"),
    )
    val androidModels = getGradleAndroidModels(projectRule.project)
    responseToDialog(true) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(1)
    assertThat(dialogMessages[0]).startsWith("Your project is configured with a compile SDK version that is not supported by this version of Android Studio")
    assertThat(dialogMessages[0]).contains(".myapp")
    assertThat(dialogMessages[0]).contains(".mylib")
    assertThat(dialogMessages[0]).contains("(compileSdk=1000)")
    assertThat(dialogMessages[0]).contains("(and 1 more)")
  }

  @Test
  fun `test dialog shown when only doNotAskAgainIdeLevel is set`() {
    projectRule.setupProjectFrom(
      JavaModuleModelBuilder.rootModuleBuilder,
      appModuleBuilder(compileSdk = "android-1000"),
    )
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainIdeLevel = true
    AndroidSdkCompatibilityChecker.StudioUpgradeReminder(projectRule.project).doNotAskAgainProjectLevel = false

    val androidModels = getGradleAndroidModels(projectRule.project)
    responseToDialog(true) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(1)
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
    responseToDialog(true) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(1)
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
    responseToDialog(false) {
      checker.checkAndroidSdkVersion(androidModels, projectRule.project)
    }
    assertThat(dialogMessages).hasSize(0)
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

  private fun responseToDialog(expectedToBeShown: Boolean, replyWith: Int = 0, action: () -> Unit) {
    val myTestDialog = TestDialog { message ->
      if (!expectedToBeShown) throw AssertionError("This should not be invoked")
      dialogMessages.add(message.trim())
      replyWith
    }
    val oldDialog = TestDialogManager.setTestDialog(myTestDialog)
    try {
      action()
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    } finally {
      TestDialogManager.setTestDialog(oldDialog)
    }
  }
}