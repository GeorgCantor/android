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
package com.android.tools.idea.gradle.project.sync.jdk.integration

import com.android.testutils.junit4.OldAgpTest
import com.android.testutils.junit4.SeparateOldAgpTestsRule
import com.android.tools.idea.gradle.project.sync.snapshots.JdkIntegrationTest
import com.android.tools.idea.gradle.project.sync.snapshots.JdkIntegrationTest.TestEnvironment
import com.android.tools.idea.gradle.project.sync.snapshots.JdkTestProject.SimpleApplication
import com.android.tools.idea.gradle.project.sync.utils.JdkTableUtils.Jdk
import com.android.tools.idea.sdk.IdeSdks.JDK_LOCATION_ENV_VARIABLE_NAME
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor.AGP_74
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IntegrationTestEnvironmentRule
import com.android.tools.idea.testing.JdkConstants.JDK_11
import com.android.tools.idea.testing.JdkConstants.JDK_11_PATH
import com.android.tools.idea.testing.JdkConstants.JDK_17
import com.android.tools.idea.testing.JdkConstants.JDK_17_PATH
import com.google.common.truth.Expect
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@RunsInEdt
class SingleGradleRootSyncUseStudioGradleJdkIntegrationTest {

  @get:Rule
  val separateOldAgpTestsRule = SeparateOldAgpTestsRule()

  @get:Rule
  val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @get:Rule
  val expect: Expect = Expect.createAndEnableStackTrace()

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  private val jdkIntegrationTest = JdkIntegrationTest(projectRule, temporaryFolder, expect)

  @Test
  @OldAgpTest(agpVersions = ["7.4.0"], gradleVersions = ["7.5"])
  fun `Given valid STUDIO_GRADLE_JDK env variable When import project Then sync used its path and the gradle jdk configuration doesn't change`() =
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = JDK_17,
        agpVersion = AGP_74, // Later versions of AGP (8.0 and beyond) require JDK17
      ),
      environment = TestEnvironment(
        jdkTable = listOf(Jdk(JDK_17, JDK_17_PATH)),
        environmentVariables = mapOf(JDK_LOCATION_ENV_VARIABLE_NAME to JDK_11_PATH)
      )
    ) {
      syncWithAssertion(
        expectedGradleJdkName = JDK_17,
        expectedProjectJdkName = JDK_11,
        expectedProjectJdkPath = JDK_11_PATH
      )
    }
}