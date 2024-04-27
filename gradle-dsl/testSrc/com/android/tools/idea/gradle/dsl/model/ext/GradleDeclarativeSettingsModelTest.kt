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
package com.android.tools.idea.gradle.dsl.model.ext

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.dsl.model.GradleFileModelTestCase
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class GradleDeclarativeSettingsModelTest : GradleFileModelTestCase() {

  @Before
  override fun before() {
    Assume.assumeTrue(isDeclarative)
    StudioFlags.DECLARATIVE_PLUGIN_STUDIO_SUPPORT.override(true)
    super.before()
  }

  @After
  fun clearFlags() {
    StudioFlags.DECLARATIVE_PLUGIN_STUDIO_SUPPORT.clearOverride()
  }

  @Test
  fun basicSettingsTest(){
    writeToSettingsFile("""
      include = [ ":$SUB_MODULE_NAME" ]
      [pluginManagement.plugins]
    """.trimIndent())

    val settingsModel = gradleSettingsModel

    assertTrue(settingsModel.pluginManagement().plugins().plugins().isEmpty())
    assertEquals(setOf(":", ":$SUB_MODULE_NAME"), settingsModel.modulePaths())
  }

}