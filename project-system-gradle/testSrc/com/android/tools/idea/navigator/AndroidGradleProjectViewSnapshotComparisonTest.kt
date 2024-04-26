/*
 * Copyright (C) 2019 The Android Open Source Project
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
@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

package com.android.tools.idea.navigator

import com.android.tools.idea.gradle.project.sync.snapshots.SyncedProjectTestDef
import com.android.tools.idea.gradle.project.sync.snapshots.TestProject
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor.AGP_42
import com.android.tools.idea.testing.AgpVersionSoftwareEnvironmentDescriptor.Companion.AGP_CURRENT
import com.android.tools.idea.testing.ProjectViewSettings
import com.android.tools.idea.testing.SnapshotComparisonTest
import com.android.tools.idea.testing.SnapshotContext
import com.android.tools.idea.testing.assertIsEqualToSnapshot
import com.android.tools.idea.testing.dumpAndroidProjectView
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Snapshot tests for 'Android Project View'.
 *
 * These tests convert the Android project view to a stable text format which does not depend on local
 * environment (and ideally should not depend on the versions of irrelevant libraries) and compare them to pre-recorded golden
 * results.
 *
 * The pre-recorded sync results can be found in [snapshotDirectoryWorkspaceRelativePath] *.txt files.
 *
 * For instructions on how to update the snapshot files see [SnapshotComparisonTest] and if running from the command-line use
 * target as "//tools/adt/idea/android:intellij.android.core.tests_tests__navigator.AndroidGradleProjectViewSnapshotComparisonTest".
 */
data class AndroidProjectViewSnapshotComparisonTestDef(
  override val testProject: TestProject,
  override val agpVersion: AgpVersionSoftwareEnvironmentDescriptor = AGP_CURRENT,
  val compatibleWith: Set<AgpVersionSoftwareEnvironmentDescriptor> = setOf(AGP_CURRENT),
  private val projectViewSettings: ProjectViewSettings = ProjectViewSettings()
) : SyncedProjectTestDef {
  override val name: String = testProject.projectName

  override fun toString(): String = testProject.projectName

  override fun withAgpVersion(agpVersion: AgpVersionSoftwareEnvironmentDescriptor): SyncedProjectTestDef {
    return copy(agpVersion = agpVersion)
  }

  override fun isCompatible(): Boolean {
    return agpVersion in compatibleWith
  }

  override fun runTest(root: File, project: Project) {
    val text = project.dumpAndroidProjectView(projectViewSettings, Unit, { _, _ -> Unit })
    val suffix = buildString {
      if (projectViewSettings.hideEmptyPackages) append("_hide")
      if (projectViewSettings.flattenPackages) append("_flatten")
    }
    val snapshotContext = SnapshotContext(testProject.projectName + suffix, agpVersion, "tools/adt/idea/android/testData/snapshots/projectViews")
    snapshotContext.assertIsEqualToSnapshot(text)
  }

  companion object {
    val tests: List<AndroidProjectViewSnapshotComparisonTestDef> = listOf(
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.SIMPLE_APPLICATION),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.SIMPLE_APPLICATION_VIA_SYMLINK),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.SIMPLE_APPLICATION_APP_VIA_SYMLINK),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.SIMPLE_APPLICATION_NOT_AT_ROOT),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.SIMPLE_APPLICATION_MULTIPLE_ROOTS),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.APP_WITH_ML_MODELS),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.MULTI_FLAVOR),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.COMPATIBILITY_TESTS_AS_36),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.COMPATIBILITY_TESTS_AS_36_NO_IML),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.COMPOSITE_BUILD),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.BUILDSRC_WITH_COMPOSITE, compatibleWith = setOf(AGP_42, AGP_CURRENT)),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.APP_WITH_BUILDSRC),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.TEST_FIXTURES),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.SIMPLE_APPLICATION_VERSION_CATALOG),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.KOTLIN_GRADLE_DSL),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.KOTLIN_MULTIPLATFORM),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.NON_STANDARD_SOURCE_SET_DEPENDENCIES),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.NON_STANDARD_SOURCE_SETS),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.CUSTOM_SOURCE_TYPE),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.ANDROID_KOTLIN_MULTIPLATFORM),
      AndroidProjectViewSnapshotComparisonTestDef(
        TestProject.MULTI_FLAVOR,
        projectViewSettings = ProjectViewSettings(hideEmptyPackages = true, flattenPackages = true)
      ),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.NAVIGATOR_PACKAGEVIEW_SIMPLE),
      AndroidProjectViewSnapshotComparisonTestDef(
        TestProject.NAVIGATOR_PACKAGEVIEW_SIMPLE,
        projectViewSettings = ProjectViewSettings(hideEmptyPackages = true, flattenPackages = true)
      ),
      AndroidProjectViewSnapshotComparisonTestDef(
        TestProject.NAVIGATOR_PACKAGEVIEW_COMMONROOTS,
        projectViewSettings = ProjectViewSettings(hideEmptyPackages = false)
      ),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.PSD_SAMPLE_GROOVY),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.APP_WITH_BUILD_FEATURES_ENABLED),
      AndroidProjectViewSnapshotComparisonTestDef(TestProject.GRADLE_DECLARATIVE)
    )
  }
}

