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
package com.android.tools.idea.templates.recipe

import com.android.ide.common.repository.AgpVersion
import com.android.testutils.MockitoKt
import com.android.tools.idea.gradle.dsl.TestFileName
import com.android.tools.idea.gradle.dsl.model.GradleFileModelTestCase
import com.android.tools.idea.lint.common.getModuleDir
import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.ProjectTemplateData
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.SystemDependent
import org.junit.Before
import org.junit.Test
import java.io.File

class DefaultRecipeExecutorWithGradleModelTest : GradleFileModelTestCase("tools/adt/idea/android-templates/testData/recipe") {

  private val mockProjectTemplateData = MockitoKt.mock<ProjectTemplateData>()
  private val mockModuleTemplateData = MockitoKt.mock<ModuleTemplateData>()

  private val renderingContext by lazy {
    RenderingContext(
      project,
      module,
      "DefaultRecipeExecutor test with gradle model",
      mockModuleTemplateData,
      moduleRoot = module.getModuleDir(),
      dryRun = false,
      showErrors = true
    )
  }
  private val recipeExecutor by lazy {
    DefaultRecipeExecutor(renderingContext)
  }

  @Before
  fun init() {
    MockitoKt.whenever(mockModuleTemplateData.projectTemplateData).thenReturn(mockProjectTemplateData)
    MockitoKt.whenever(mockProjectTemplateData.agpVersion).thenReturn(AgpVersion.parse("8.0.0"))
  }

  private fun deleteVersionCatalogFile() {
    ApplicationManager.getApplication().runWriteAction {
      myVersionCatalogFile.delete("test")
    }
  }

  /**
   * Tests that a project which did not already use Version Catalog can still have a dependency added to it.
   */
  @Test
  fun testAddDependencyWithoutVersionCatalog() {
    deleteVersionCatalogFile()
    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myBuildFile, TestFile.NO_VERSION_CATALOG_ADD_DEPENDENCY)
  }

  @Test
  fun testAddDependencyWithVersionCatalog() {
    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle-runtime-ktx" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY)
  }

  @Test
  fun testAddDependencyWithVersionCatalog_alreadyExists() {
    writeToVersionCatalogFile("""
[versions]
lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle-runtime-ktx" }
    """)
    writeToBuildFile(TestFile.VERSION_CATALOG_ADD_DEPENDENCY)

    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Verify library is not duplicated in the toml file and the build file
    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle-runtime-ktx" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY)
  }

  @Test
  fun testAddDependencyWithVersionCatalog_alreadyExists_asModuleRepresentation() {
    writeToVersionCatalogFile("""
[versions]
lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle-runtime-ktx" }
    """)
    writeToBuildFile(TestFile.VERSION_CATALOG_ADD_DEPENDENCY)

    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Verify library is not duplicated in the toml file and the build file
    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle-runtime-ktx" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY)
  }

  @Test
  fun testAddDependencyWithVersionCatalog_sameNameExist() {
    writeToVersionCatalogFile("""
[versions]
lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
    """)

    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Verify avoiding the same name with different module
    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME)
  }

  @Test
  fun testAddDependencyWithVersionCatalog_sameNameExist_includingGroupName() {
    writeToVersionCatalogFile("""
[versions]
lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "fake.group", name = "fake.name", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
    """)

    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Verify avoiding the same name with different module
    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx231 = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "fake.group", name = "fake.name", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx231 = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx231" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME_WITH_GROUP)
  }

  @Test
  fun testAddDependencyWithVersionCatalog_sameNameExist_finalFallback() {
    writeToVersionCatalogFile("""
[versions]
lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx231 = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "fake.group", name = "fake.name", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx231 = { group = "fake.group2", name = "fake.name2", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx231" }
    """)

    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Verify avoiding the same name with different module
    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx231 = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx2312 = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "fake.group", name = "fake.name", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx231 = { group = "fake.group2", name = "fake.name2", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx231" }
androidx-lifecycle-lifecycle-runtime-ktx2312 = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx2312" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME_FINAL_FALLBACK)
  }

  @Test
  fun testAddDependencyWithVersionCatalog_sameNameExist_finalFallback_secondLoop() {
    writeToVersionCatalogFile("""
[versions]
lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx231 = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx2312 = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "fake.group", name = "fake.name", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx231 = { group = "fake.group2", name = "fake.name2", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx231" }
androidx-lifecycle-lifecycle-runtime-ktx2312 = { group = "fake.group3", name = "fake.name3", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx2312" }
    """)

    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Verify avoiding the same name with different module.
    // The final fallback loop when picking the name in the catalog goes into the second loop
    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx231 = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx2312 = "2.3.1"
androidx-lifecycle-lifecycle-runtime-ktx2313 = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "group", name = "name", version.ref = "lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx = { group = "fake.group", name = "fake.name", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
androidx-lifecycle-lifecycle-runtime-ktx231 = { group = "fake.group2", name = "fake.name2", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx231" }
androidx-lifecycle-lifecycle-runtime-ktx2312 = { group = "fake.group3", name = "fake.name3", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx2312" }
androidx-lifecycle-lifecycle-runtime-ktx2313 = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx2313" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME_FINAL_FALLBACK_SECOND_LOOP)
  }

  @Test
  fun testAddDependencyWithVersionCatalog_differentName_between_versionAndLibrary() {
    // Only the version has the name that results in having different names between the versions and libraries section
    // after addDependency is called
    writeToVersionCatalogFile("""
[versions]
lifecycle-runtime-ktx = "2.5.0"
[libraries]
    """)

    recipeExecutor.addDependency("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
lifecycle-runtime-ktx = "2.5.0"
androidx-lifecycle-lifecycle-runtime-ktx = "2.3.1"
[libraries]
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "androidx-lifecycle-lifecycle-runtime-ktx" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_DEPENDENCY)
  }

  @Test
  fun testAddPlatformDependencyWithVersionCatalog() {
    recipeExecutor.addPlatformDependency("androidx.compose:compose-bom:2022.10.00")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
compose-bom = "2022.10.00"
[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
    """)
    verifyFileContents(myBuildFile, TestFile.VERSION_CATALOG_ADD_PLATFORM_DEPENDENCY)
  }

  @Test
  fun testGetExtVar_valueFound() {
    writeToProjectBuildFile(TestFile.GET_EXT_VAR_INITIAL)

    val version = recipeExecutor.getExtVar("wear_compose_version", "1.0.0")

    assertEquals("3.0.0", version)
  }

  @Test
  fun testGetExtVar_valueNotFound() {
    writeToProjectBuildFile(TestFile.GET_EXT_VAR_INITIAL)

    val version = recipeExecutor.getExtVar("fake_variable", "1.0.0")

    assertEquals("1.0.0", version)
  }

  /**
   * Tests that a project which did not already use Version Catalog can still have a plugin added to it.
   */
  @Test
  fun testApplyKotlinPluginWithoutVersionCatalog() {
    deleteVersionCatalogFile()

    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)

    recipeExecutor.applyPlugin("org.jetbrains.kotlin.android", "1.7.20")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(mySettingsFile, TestFile.NO_VERSION_CATALOG_APPLY_KOTLIN_PLUGIN_SETTING_FILE)
    verifyFileContents(myBuildFile, TestFile.NO_VERSION_CATALOG_APPLY_KOTLIN_PLUGIN_BUILD_FILE)
  }

  @Test
  fun testApplyKotlinPluginWithVersionCatalog() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)

    recipeExecutor.applyPlugin("org.jetbrains.kotlin.android", "1.7.20")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
kotlin = "1.7.20"
[libraries]
[plugins]
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
    """)
    verifyFileContents(mySettingsFile, TestFile.APPLY_KOTLIN_PLUGIN_SETTING_FILE)
    verifyFileContents(myBuildFile, TestFile.APPLY_KOTLIN_PLUGIN_BUILD_FILE)
  }

  @Test
  fun testApplyKotlinPluginWithVersionCatalog_sameVersionNameExists() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)
    writeToVersionCatalogFile("""
[versions]
kotlin = "100"
[libraries]
[plugins]
fakePlugin = { id = "fake.plugin", version.ref = "kotlin" }
    """)

    recipeExecutor.applyPlugin("org.jetbrains.kotlin.android", "1.7.20")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
kotlin = "100"
kotlin1720 = "1.7.20"
[libraries]
[plugins]
fakePlugin = { id = "fake.plugin", version.ref = "kotlin" }
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin1720" }
    """)
    verifyFileContents(mySettingsFile, TestFile.APPLY_KOTLIN_PLUGIN_SETTING_FILE)
    verifyFileContents(myBuildFile, TestFile.APPLY_KOTLIN_PLUGIN_BUILD_FILE)
  }

  @Test
  fun testApplyNonCommonPlugin() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)

    recipeExecutor.applyPlugin("not.common.plugin", "1.0.2")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
not-common-plugin = "1.0.2"
[libraries]
[plugins]
not-common-plugin = { id = "not.common.plugin", version.ref = "not-common-plugin" }
    """)
    verifyFileContents(mySettingsFile, TestFile.APPLY_NOT_COMMON_PLUGIN_SETTING_FILE)
    verifyFileContents(myBuildFile, TestFile.APPLY_NOT_COMMON_PLUGIN_BUILD_FILE)
  }

  @Test
  fun testAddAgpPlugin_noAgpPluginHasNotDeclared() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
"""
    )
    writeToVersionCatalogFile("""
[versions]
[libraries]
[plugins]
    """)

    recipeExecutor.applyPlugin("com.android.application", "8.0.0")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
agp = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
    """)
    verifyFileContents(mySettingsFile, TestFile.APPLY_AGP_PLUGIN_SETTING_FILE)
    verifyFileContents(myBuildFile, TestFile.APPLY_AGP_PLUGIN_BUILD_FILE)
  }

  @Test
  fun testAddAgpPlugin_samePluginNameExists() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
"""
    )
    writeToVersionCatalogFile("""
[versions]
fake = "100"
[libraries]
[plugins]
androidApplication = { id = "fake.plugin", version.ref = "fake" }
    """)

    recipeExecutor.applyPlugin("com.android.application", "8.0.0")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
fake = "100"
agp = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "fake.plugin", version.ref = "fake" }
androidApplication800 = { id = "com.android.application", version.ref = "agp" }
    """)
    verifyFileContents(mySettingsFile, TestFile.APPLY_AGP_PLUGIN_WITH_REVISION_SETTING_FILE)
    verifyFileContents(myBuildFile, TestFile.APPLY_AGP_PLUGIN_WITH_REVISION_BUILD_FILE)
  }

  @Test
  fun testAddAgpPlugin_anotherAgpPluginHasDeclared() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)
    writeToVersionCatalogFile("""
[versions]
agp = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
    """)

    recipeExecutor.applyPlugin("com.android.library", "8.0.0")

    applyChanges(recipeExecutor.projectBuildModel!!)

    verifyFileContents(myVersionCatalogFile, """
[versions]
agp = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
    """)
  }

  @Test
  fun testAddAgpPlugin_anotherAgpPluginHasDeclaredInDifferentVersion() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)
    writeToVersionCatalogFile("""
[versions]
agp = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
    """)

    // Apply a plugin with the different version from the existing agp version in the catalog
    recipeExecutor.applyPlugin("com.android.library", "8.0.0-beta04")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Existing agp version is respected
    verifyFileContents(myVersionCatalogFile, """
[versions]
agp = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
    """)
  }

  @Test
  fun testAddAgpPlugin_anotherAgpPluginHasDeclared_withDifferentNameFromDefault() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)
    // Another AGP plugin is declared with the version name different from the default
    writeToVersionCatalogFile("""
[versions]
agp-version = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp-version" }
    """)

    recipeExecutor.applyPlugin("com.android.library", "8.0.0-beta04")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // The new declared plugin will use the same version name as the existing one
    verifyFileContents(myVersionCatalogFile, """
[versions]
agp-version = "8.0.0"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp-version" }
androidLibrary = { id = "com.android.library", version.ref = "agp-version" }
    """)
  }

  @Test
  fun testAddAgpPlugin_anotherAgpPluginHasDeclared_inLiteral() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)
    // Existing AGP plugin's version is written as a string literal
    writeToVersionCatalogFile("""
[versions]
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version = "8.0.0" }
    """)

    recipeExecutor.applyPlugin("com.android.library", "8.0.0-beta04")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Version in literal isn't touched. The new version entry named "agp" is created instead.
    verifyFileContents(myVersionCatalogFile, """
[versions]
agp = "8.0.0-beta04"
[libraries]
[plugins]
androidApplication = { id = "com.android.application", version = "8.0.0" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
    """)
  }

  @Test
  fun testAddAgpPlugin_differentPluginUseDefaultNameForAgp() {
    writeToSettingsFile("""
pluginManagement {
  plugins {
  }
}
    """)
    // Another plugin already use the default name ("agp") for AGP plugins
    writeToVersionCatalogFile("""
[versions]
agp = "1.0.0"
[libraries]
[plugins]
fake-plugin = { id = "fake.plugin", version.ref = "agp" }
    """)

    recipeExecutor.applyPlugin("com.android.library", "8.0.0")

    applyChanges(recipeExecutor.projectBuildModel!!)

    // Different name is picked for the declared AGP plugin
    verifyFileContents(myVersionCatalogFile, """
[versions]
agp = "1.0.0"
agp2 = "8.0.0"
[libraries]
[plugins]
fake-plugin = { id = "fake.plugin", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp2" }
    """)
  }

  enum class TestFile(private val path: @SystemDependent String) : TestFileName {
    NO_VERSION_CATALOG_ADD_DEPENDENCY("noVersionCatalogAddDependency"),
    VERSION_CATALOG_ADD_DEPENDENCY("versionCatalogAddDependency"),
    VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME("versionCatalogAddDependencyAvoidSameName"),
    VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME_WITH_GROUP("versionCatalogAddDependencyAvoidSameNameWithGroup"),
    VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME_FINAL_FALLBACK("versionCatalogAddDependencyAvoidSameNameFinalFallback"),
    VERSION_CATALOG_ADD_DEPENDENCY_AVOID_SAME_NAME_FINAL_FALLBACK_SECOND_LOOP("versionCatalogAddDependencyAvoidSameNameFinalFallbackSecondLoop"),
    VERSION_CATALOG_ADD_PLATFORM_DEPENDENCY("versionCatalogAddPlatformDependency"),
    GET_EXT_VAR_INITIAL("getExtVarInitial"),
    NO_VERSION_CATALOG_APPLY_KOTLIN_PLUGIN_BUILD_FILE("noVersionCatalogApplyKotlinPlugin"),
    NO_VERSION_CATALOG_APPLY_KOTLIN_PLUGIN_SETTING_FILE("noVersionCatalogApplyKotlinPlugin.settings"),
    APPLY_KOTLIN_PLUGIN_BUILD_FILE("versionCatalogApplyKotlinPlugin"),
    APPLY_KOTLIN_PLUGIN_SETTING_FILE("versionCatalogApplyKotlinPlugin.settings"),
    APPLY_NOT_COMMON_PLUGIN_BUILD_FILE("versionCatalogApplyNotCommonPlugin"),
    APPLY_NOT_COMMON_PLUGIN_SETTING_FILE("versionCatalogApplyNotCommonPlugin.settings"),
    APPLY_AGP_PLUGIN_BUILD_FILE("versionCatalogApplyAgpPlugin"),
    APPLY_AGP_PLUGIN_SETTING_FILE("versionCatalogApplyAgpPlugin.settings"),
    APPLY_AGP_PLUGIN_WITH_REVISION_BUILD_FILE("versionCatalogApplyAgpPluginWithRevision"),
    APPLY_AGP_PLUGIN_WITH_REVISION_SETTING_FILE("versionCatalogApplyAgpPluginWithRevision.settings"),
    ;

    override fun toFile(basePath: @SystemDependent String, extension: String): File {
      return super.toFile("$basePath/defaultRecipeExecutor/$path", extension)
    }
  }
}