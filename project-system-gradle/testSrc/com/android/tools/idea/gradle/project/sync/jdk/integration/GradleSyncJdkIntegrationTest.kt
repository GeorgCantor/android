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
import com.android.tools.idea.testing.JdkConstants.JDK_INVALID_PATH
import com.google.common.truth.Expect
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkException
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil.JAVA_HOME
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil.USE_JAVA_HOME
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.plugins.gradle.util.USE_GRADLE_JAVA_HOME
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@RunsInEdt
@Suppress("UnstableApiUsage")
class GradleSyncJdkIntegrationTest {

  @get:Rule
  val separateOldAgpTestsRule = SeparateOldAgpTestsRule()

  @get:Rule
  val projectRule: IntegrationTestEnvironmentRule = AndroidProjectRule.withIntegrationTestEnvironment()

  @get:Rule
  val expect: Expect = Expect.createAndEnableStackTrace()

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  private val jdkIntegrationTest = JdkIntegrationTest(projectRule, temporaryFolder, expect)

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given invalid userHomeGradlePropertiesJdkPath When import project Then throw exception`() =
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = USE_GRADLE_JAVA_HOME
      ),
      environment = TestEnvironment(
        userHomeGradlePropertiesJdkPath = "/invalid/jdk/path"
      )
    ) {
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given invalid gradlePropertiesJdkPath When import project Then throw exception`() =
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = USE_GRADLE_JAVA_HOME,
        gradlePropertiesJavaHome = "/invalid/jdk/path"
      )
    ) {
      sync()
    }

  @Test
  fun `Given invalid STUDIO_GRADLE_JDK env variable When import project Then sync ignored it and use the project jdk configuration`() {
    val invalidJdkPath = temporaryFolder.newFolder(JDK_INVALID_PATH)
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = JDK_17,
      ),
      environment = TestEnvironment(
        jdkTable = listOf(Jdk(JDK_17, JDK_17_PATH)),
        environmentVariables = mapOf(JDK_LOCATION_ENV_VARIABLE_NAME to invalidJdkPath.path)
      )
    ) {
      syncWithAssertion(
        expectedGradleJdkName = JDK_17,
        expectedProjectJdkName = JDK_17,
        expectedProjectJdkPath = JDK_17_PATH
      )
    }
  }

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

  @Test
  fun `Given USE_JAVA_HOME gradleJdk macro When import project Then sync used JAVA_HOME`() =
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = USE_JAVA_HOME,
        ideaProjectJdk = JDK_17
      ),
      environment = TestEnvironment(
        jdkTable = listOf(Jdk(JDK_11, JDK_11_PATH), Jdk(JDK_17, JDK_17_PATH)),
        environmentVariables = mapOf(JAVA_HOME to JDK_17_PATH)
      )
    ) {
      syncWithAssertion(
        expectedGradleJdkName = USE_JAVA_HOME,
        expectedProjectJdkName = JDK_17,
        expectedProjectJdkPath = JDK_17_PATH
      )
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given USE_GRADLE_JAVA_HOME gradleJdk macro without jdkPath defined When import project Then throw exception`() =
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = USE_GRADLE_JAVA_HOME
      ),
      environment = TestEnvironment(
        jdkTable = listOf(Jdk(JDK_17, JDK_17_PATH))
      )
    ) {
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given project with gradleJdk not present in jdkTable When import project Then throw exception`() =
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = "invalid"
      ),
      environment = TestEnvironment(
        jdkTable = listOf(Jdk(JDK_17, JDK_17_PATH))
      )
    ) {
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given project with gradleJdk present in jdkTable but invalid path When import project Then throw exception`() =
    jdkIntegrationTest.run(
      project = SimpleApplication(
        ideaGradleJdk = JDK_17,
        ideaProjectJdk = "any"
      ),
      environment = TestEnvironment(
        jdkTable = listOf(Jdk(JDK_17, JDK_INVALID_PATH))
      )
    ) {
      sync()
    }
}