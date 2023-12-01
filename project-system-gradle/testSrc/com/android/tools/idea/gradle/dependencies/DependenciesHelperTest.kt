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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KOIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.gradle.dependencies

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.model.dependencies.ArtifactDependencySpecImpl
import com.android.tools.idea.testing.AndroidGradleTestCase
import com.android.tools.idea.testing.BuildEnvironment
import com.android.tools.idea.testing.TestProjectPaths.SIMPLE_APPLICATION
import com.android.tools.idea.testing.TestProjectPaths.SIMPLE_APPLICATION_PLUGINS_DSL
import com.android.tools.idea.testing.TestProjectPaths.SIMPLE_APPLICATION_VERSION_CATALOG
import com.android.tools.idea.testing.findModule
import com.android.tools.idea.testing.getTextForFile
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import org.apache.commons.lang.StringUtils.countMatches
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@RunWith(JUnit4::class)
class DependenciesHelperTest: AndroidGradleTestCase() {

  @Test
  fun testSimpleAddWithCatalog() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { _, moduleModel, helper ->
             val updates = helper.addDependency("api", "com.example.libs:lib2:1.0", moduleModel)
             assertThat(updates.size).isEqualTo(2)
           },
           {
             assertThat(project.getTextForFile("gradle/libs.versions.toml"))
               .contains("{ group = \"com.example.libs\", name = \"lib2\", version.ref = \"")
             assertThat(project.getTextForFile("app/build.gradle"))
               .contains("api libs.lib2")
           })
  }

  @Test
  fun testAddToBuildscriptWithCatalog() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { _, moduleModel, helper ->
             val updates = helper.addClasspathDependency("com.example.libs:lib2:1.0")
             assertThat(updates.size).isEqualTo(2)
           },
           {
             assertThat(project.getTextForFile("gradle/libs.versions.toml"))
               .contains("{ group = \"com.example.libs\", name = \"lib2\", version.ref = \"")
             assertThat(project.getTextForFile("build.gradle"))
               .contains("classpath libs.lib2")
           })
  }

  @Test
  fun testSimpleAddNoCatalog() {
    doTest(SIMPLE_APPLICATION,
           { _, moduleModel, helper ->
             val updates = helper.addDependency("api", "com.example.libs:lib2:1.0", moduleModel)
             assertThat(updates.size).isEqualTo(1)
           },
           {
             assertThat(project.getTextForFile("app/build.gradle"))
               .contains("api \'com.example.libs:lib2:1.0\'")
           })
  }

  @Test
  fun testAddToClasspathNoCatalog() {
    doTest(SIMPLE_APPLICATION,
           { _, moduleModel, helper ->
             val updates = helper.addClasspathDependency("com.example.libs:lib2:1.0")
             assertThat(updates.size).isEqualTo(1)
           },
           {
             assertThat(project.getTextForFile("build.gradle"))
               .contains("classpath \'com.example.libs:lib2:1.0\'")
           })
  }

  @Test
  fun testAddDependencyWithExceptions() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { _, moduleModel, helper ->
             val updates = helper.addDependency("api",
                                  "com.example.libs:lib2:1.0",
                                  listOf(ArtifactDependencySpecImpl.create("com.example.libs:lib3")!!),
                                  moduleModel,
                                  ExactDependencyMatcher("api", "com.example.libs:lib2:1.0"))
             assertThat(updates.size).isEqualTo(2)
           },
           {
             assertThat(project.getTextForFile("gradle/libs.versions.toml"))
               .contains("{ group = \"com.example.libs\", name = \"lib2\", version.ref = \"")
             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(buildFileContent).contains("api libs.lib2")
             assertThat(buildFileContent).contains("exclude group: 'com.example.libs', module: 'lib3'")
           })
  }

  @Test
  fun testAddToBuildScriptWithExceptions() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { _, moduleModel, helper ->
             val updates = helper.addClasspathDependency(
               "com.example.libs:lib2:1.0",
               listOf(ArtifactDependencySpecImpl.create("com.example.libs:lib3")!!),
               ExactDependencyMatcher("classpath", "com.example.libs:lib2:1.0"),
             )
             assertThat(updates.size).isEqualTo(2)
           },
           {
             assertThat(project.getTextForFile("gradle/libs.versions.toml"))
               .contains("{ group = \"com.example.libs\", name = \"lib2\", version.ref = \"")
             val buildFileContent = project.getTextForFile("build.gradle")
             assertThat(buildFileContent).contains("classpath libs.lib2")
             assertThat(buildFileContent).contains("exclude group: 'com.example.libs', module: 'lib3'")
           })
  }

  @Test
  fun testAddToBuildScriptWithNoVersion() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { _, moduleModel, helper ->
             val updates = helper.addDependency("implementation", "com.example.libs:lib2", moduleModel)
             assertThat(updates.size).isEqualTo(2)
           },
           {
             assertThat(project.getTextForFile("gradle/libs.versions.toml"))
               .contains("{ group = \"com.example.libs\", name = \"lib2\"")
             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(buildFileContent).contains("implementation libs.lib2")
           })
  }

  /**
   * In this test we verify that with GroupName matcher we ignore version when looking for
   * suitable dependency in toml catalog file.
   * So we'll switch to existing junit declaration
   */
  @Test
  fun testAddToBuildScriptWithExistingDependency() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { _, moduleModel, helper ->
             val updates = helper.addDependency("implementation",
                                                "junit:junit:999",
                                                listOf(),
                                                moduleModel,
                                                GroupNameDependencyMatcher("implementation", "junit:junit:999")
             )
             assertThat(updates.size).isEqualTo(1)
           },
           {
             assertThat(project.getTextForFile("gradle/libs.versions.toml"))
               .doesNotContain("= \"999\"")
             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(buildFileContent).contains("implementation libs.junit")
           })
  }

  @Test
  fun testSimpleAddNoCatalogWithExceptions() {
    doTest(SIMPLE_APPLICATION,
           { _, moduleModel, helper ->
             val updates = helper.addDependency("api",
                                                "com.example.libs:lib2:1.0",
                                                listOf(ArtifactDependencySpecImpl.create("com.example.libs:lib3")!!),
                                                moduleModel,
                                                ExactDependencyMatcher("api", "com.example.libs:lib2:1.0"))
              assertThat(updates.size).isEqualTo(1)
           },
           {
             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(buildFileContent).contains("api \'com.example.libs:lib2:1.0\'")
             assertThat(buildFileContent).contains("exclude group: 'com.example.libs', module: 'lib3'")
           })
  }

  @Test
  fun testSimpleAddPlugin() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val updates = helper.addPlugin("com.example.foo", "10.0", false, projectModel!!, moduleModel)
             assertThat(updates.size).isEqualTo(3)
           },
           {
             val catalogContent = project.getTextForFile("gradle/libs.versions.toml")
             assertThat(catalogContent).contains("example-foo = \"10.0\"")
             assertThat(catalogContent).contains("exampleFoo = { id = \"com.example.foo\", version.ref = \"example-foo\"")

             val projectBuildContent = project.getTextForFile("build.gradle")
             assertThat(projectBuildContent).contains("alias(libs.plugins.exampleFoo) apply false")

             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(buildFileContent).contains("alias(libs.plugins.exampleFoo)")
           })
  }

  @Test
  fun testSimpleAddPluginNoCatalog() {
    doTest(SIMPLE_APPLICATION,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val updates = helper.addPlugin("com.example.foo", "10.0", false, projectModel!!, moduleModel)
             assertThat(updates.size).isEqualTo(2)
           },
           {
             val projectBuildContent = project.getTextForFile("build.gradle")
             assertThat(projectBuildContent).contains("id 'com.example.foo' version '10.0' apply false")

             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(buildFileContent).contains("apply plugin: 'com.example.foo'")
           })
  }

  @Test
  fun testSimpleAddPluginWithExistingPlugin() {
    val env = BuildEnvironment.getInstance()
    val version = env.gradlePluginVersion
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           {
             FileUtil.appendToFile(
               File(project.basePath, "gradle/libs.versions.toml"),
               "\n[plugins]\nexample = \"com.android.application:${version}\"\n"
             )
           },
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val updates = helper.addPlugin("com.android.application",
                                            version,
                                            false,
                                            projectModel!!,
                                            moduleModel,
                                            IdPluginMatcher("com.android.application"))
             assertThat(updates.size).isEqualTo(0)
           },
           {
             val catalogContent = project.getTextForFile("gradle/libs.versions.toml")
             assertThat(catalogContent).doesNotContain("agp = \"${version}\"") // no version
             assertThat(catalogContent).doesNotContain("androidApplication = { id = \"com.android.application\"")

             val projectBuildContent = project.getTextForFile("build.gradle")
             assertThat(projectBuildContent).doesNotContain("alias(libs.plugins.androidApplication)") // no new alias

             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(buildFileContent).doesNotContain("alias(libs.plugins.androidApplication)")
           })
  }

  @Test
  fun testAddDuplicatedPlatformDependencyWithCatalog() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val matcher = GroupNameDependencyMatcher("implementation", "com.google.protobuf:protobuf-bom:3.21.8")

             helper.addPlatformDependency("implementation",
                                  "com.google.protobuf:protobuf-bom:3.21.8",
                                  false,
                                  moduleModel,
                                  matcher)
             helper.addPlatformDependency("implementation",
                                  "com.google.protobuf:protobuf-bom:3.21.8",
                                  false,
                                  moduleModel,
                                  matcher)
           },
           {
             val catalogContent = project.getTextForFile("gradle/libs.versions.toml")
             assertThat(countMatches(catalogContent, "= \"3.21.8\"")).isEqualTo(1)
             assertThat(countMatches(catalogContent,"= { group = \"com.google.protobuf\", name = \"protobuf-bom\"")).isEqualTo(1)

             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(countMatches(buildFileContent,"implementation platform(libs.protobuf.bom)")).isEqualTo(1)
           })
  }

  @Test
  fun testAddDuplicatedPlatformDependencyNoCatalog() {
    doTest(SIMPLE_APPLICATION,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val dependency = "com.google.protobuf:protobuf-bom:3.21.8"
             val matcher = GroupNameDependencyMatcher("implementation", dependency)

             helper.addPlatformDependency("implementation", dependency, false, moduleModel, matcher)
             helper.addPlatformDependency("implementation", dependency, false, moduleModel, matcher)
           },
           {
             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(countMatches(buildFileContent,"implementation platform('com.google.protobuf:protobuf-bom:3.21.8')")).isEqualTo(1)
           })
  }

  @Test
  fun testAddDuplicatedDependencyWithCatalog() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val matcher = GroupNameDependencyMatcher("api", "com.example.libs:lib2:1.0")

             helper.addDependency("api", "com.example.libs:lib2:1.0", listOf(), moduleModel, matcher)
             helper.addDependency("api", "com.example.libs:lib2:1.0", listOf(), moduleModel, matcher)
           },
           {
             val catalogContent = project.getTextForFile("gradle/libs.versions.toml")
             assertThat(countMatches(catalogContent, "{ group = \"com.example.libs\", name = \"lib2\", version.ref = \"")).isEqualTo(1)

             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(countMatches(buildFileContent,"api libs.lib2")).isEqualTo(1)
           })
  }

  @Test
  fun testAddDuplicatedDependencyNoCatalog() {
    doTest(SIMPLE_APPLICATION,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val matcher = GroupNameDependencyMatcher("api", "com.example.libs:lib2:1.0")

             helper.addDependency("api", "com.example.libs:lib2:1.0", listOf(), moduleModel, matcher)
             helper.addDependency("api", "com.example.libs:lib2:1.0", listOf(), moduleModel, matcher)
           },
           {
             val buildFileContent = project.getTextForFile("app/build.gradle")
             assertThat(countMatches(buildFileContent,"api 'com.example.libs:lib2:1.0'")).isEqualTo(1)
           })
  }

  @Test
  fun testAddClasspathDuplicatedDependencyWithCatalog() {
    doTest(SIMPLE_APPLICATION_VERSION_CATALOG,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val matcher = GroupNameDependencyMatcher("classpath", "com.example.libs:lib2:1.0")

             helper.addClasspathDependency("com.example.libs:lib2:1.0", listOf(), matcher)
             helper.addClasspathDependency("com.example.libs:lib2:1.0", listOf(), matcher)
           },
           {
             val catalogContent = project.getTextForFile("gradle/libs.versions.toml")
             assertThat(countMatches(catalogContent,"{ group = \"com.example.libs\", name = \"lib2\", version.ref = \"")).isEqualTo(1)

             val buildFileContent = project.getTextForFile("build.gradle")
             assertThat(countMatches(buildFileContent,"classpath libs.lib2")).isEqualTo(1)
           })
  }

  @Test
  fun testAddClasspathDuplicatedDependencyNoCatalog() {
    doTest(SIMPLE_APPLICATION,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val matcher = GroupNameDependencyMatcher("classpath", "com.example.libs:lib2:1.0")

             helper.addClasspathDependency("com.example.libs:lib2:1.0", listOf(), matcher)
             helper.addClasspathDependency("com.example.libs:lib2:1.0", listOf(), matcher)           },
           {
             val buildFileContent = project.getTextForFile("build.gradle")
             assertThat(countMatches(buildFileContent,"classpath 'com.example.libs:lib2:1.0'")).isEqualTo(1)
           })
  }

  @Test
  fun testSimpleAddPluginNoCatalogWithExistingPlugin() {
    val env = BuildEnvironment.getInstance()
    val version = env.gradlePluginVersion
    doTest(SIMPLE_APPLICATION_PLUGINS_DSL,
           { projectBuildModel, moduleModel, helper ->
             val projectModel = projectBuildModel.projectBuildModel
             assertThat(projectModel).isNotNull()
             val updated = helper.addPlugin("com.android.application",
                              version,
                              false,
                              projectModel!!,
                              moduleModel,
                              IdPluginMatcher("com.android.application"))
             assertThat(updated.size).isEqualTo(0)
           },
           {
             val projectBuildContent = project.getTextForFile("build.gradle")
             // once test is imported it updates agp version and adds updates as comments on the bottom of file
             // this comment affects simple counter with countMatches
             val regex = "\\R\\s*id 'com.android.application' version '${version}' apply false".toRegex()
             assertThat(regex.findAll(projectBuildContent).toList().size).isEqualTo(1)

             val buildFileContent = project.getTextForFile("app/build.gradle")

             assertThat(countMatches(buildFileContent,"id 'com.android.application'")).isEqualTo(1)
           })
  }

  private fun doTest(projectPath: String,
                     change: (projectBuildModel: ProjectBuildModel, model: GradleBuildModel, helper: DependenciesHelper) -> Unit,
                     assert: () -> Unit) {
    doTest(projectPath, {}, change, assert)
  }

  private fun doTest(projectPath: String,
                     updateFiles: () -> Unit,
                     change: (projectBuildModel: ProjectBuildModel, model: GradleBuildModel, helper: DependenciesHelper) -> Unit,
                     assert: () -> Unit) {
    prepareProjectForImport(projectPath)
    updateFiles()
    importProject()
    prepareProjectForTest(project, null)
    myFixture.allowTreeAccessForAllFiles()

    val projectBuildModel = ProjectBuildModel.get(project)
    val moduleModel: GradleBuildModel? = projectBuildModel.getModuleBuildModel(project.findModule("app"))
    assertThat(moduleModel).isNotNull()
    val helper = DependenciesHelper(projectBuildModel)
    change.invoke(projectBuildModel, moduleModel!!, helper)
    WriteCommandAction.runWriteCommandAction(project) {
      projectBuildModel.applyChanges()
      moduleModel.applyChanges()
    }
    assert.invoke()
  }

}