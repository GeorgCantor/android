/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.gradle.something

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.highlightedAs
import com.android.tools.idea.testing.onEdt
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.RunsInEdt
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class SomethingAnnotatorTest: SomethingSchemaTestBase() {

  @get:Rule
  override val projectRule = AndroidProjectRule.onDisk().onEdt()

  private val fixture by lazy { projectRule.fixture }

  @Before
  fun before() = StudioFlags.GRADLE_DECLARATIVE_IDE_SUPPORT.override(true)

  @After
  fun onAfter() = StudioFlags.GRADLE_DECLARATIVE_IDE_SUPPORT.clearOverride()

  @Test
  fun checkFunctionIdentifier() {
    writeToSchemaFile(TestFile.DECLARATIVE_GENERATED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      plugins{
         ${"katlin" highlightedAs HighlightSeverity.ERROR}("something")
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }


  @Test
  fun dontCheckInFailedBlock() {
    writeToSchemaFile(TestFile.DECLARATIVE_GENERATED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      ${"plugins1" highlightedAs HighlightSeverity.ERROR} {
        kotlin("something")
        katlin("something")
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }

  @Test
  fun dontCheckInFailedBlock2() {
    writeToSchemaFile(TestFile.DECLARATIVE_ADVANCED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      android {
        ${"myDeps2" highlightedAs HighlightSeverity.ERROR } {
            debugImplementation("com.google.guava:guava:30.1.1-jre")
            wrongImplementation("com.google.guava:guava:30.1.1-jre")
        }
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }

  @Test
  fun checkRootBlockIdentifier() {
    writeToSchemaFile(TestFile.DECLARATIVE_GENERATED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      ${"androidAplication" highlightedAs HighlightSeverity.ERROR}{
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }

  @Test
  fun checkPropertyIdentifier() {
    writeToSchemaFile(TestFile.DECLARATIVE_GENERATED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      androidApplication {
           minSdk = 33
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }

  @Test
  fun stopCheckingWithUnknownNamedDomainObjects() {
    writeToSchemaFile(TestFile.DECLARATIVE_ADVANCED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      android {
        appBuildTypes {
          // not in schema
          staging {
            isMinifyEnabled = true
          }
        }
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }

  @Test
  fun checkingWithKnownNamedDomainObjects() {
    writeToSchemaFile(TestFile.DECLARATIVE_ADVANCED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      android {
        appBuildTypes {
          debug {
            ${"isMinifyEnableddd" highlightedAs HighlightSeverity.ERROR} = false
          }
        }
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }

  @Test
  fun checkAdvancedCase() {
    // DSL is different from AGP as we adopting to Declarative requirements.
    writeToSchemaFile(TestFile.DECLARATIVE_ADVANCED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
    plugins {
        id("com.android.application")
        id("org.jetbrains.kotlin.android")
    }
    android {
        myDeps {
            debugImplementation("com.google.guava:guava:30.1.1-jre")
            implementation("org.apache.commons:commons-lang3:3.12.0")
            testImplementation("junit:junit:4.13.2")
        }
        compileSdk = 34
        namespace = "org.gradle.experimental.android.app"
        buildFeatures {
            buildConfig = true
        }
        appBuildTypes {
            debug {
                isMinifyEnabled = false
            }
            release {
                isMinifyEnabled = true
            }
        }
        defaultConfig {
            minSdk = 24
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            proguardFile("asd.txt")
            buildConfigField("String", "MY_FIELD", "\"myValue\"")
            buildConfigField("Integer", "MY_FIELD", "0")
        }
        testCoverage {
            jacocoVersion = "1.1"
        }
        compileOptions {
            encoding = "utf-8"
        }
        installation {
            timeOutInMs = 100
            enableBaselineProfile = false
        }
    }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }

  @Test
  fun checkMultipleErrorAdvancedCase() {
    // DSL is different from AGP as we adopting to Declarative requirements.
    writeToSchemaFile(TestFile.DECLARATIVE_ADVANCED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
    plugins {
        id("com.android.application")
        ${"wrong" highlightedAs HighlightSeverity.ERROR }("org.jetbrains.kotlin.android")
    }
    android {
        myDeps {
            debugImplementation("com.google.guava:guava:30.1.1-jre")
            implementation("org.apache.commons:commons-lang3:3.12.0")
            testImplementation("junit:junit:4.13.2")
        }
        compileSdk = 34
        namespace = "org.gradle.experimental.android.app"
        ${"buildFeature" highlightedAs HighlightSeverity.ERROR} {
            buildConfig = true
        }
        appBuildTypes {
            debug {
                isMinifyEnabled = false
            }
            release {
                ${"minifyEnabled" highlightedAs HighlightSeverity.ERROR} = true
            }
        }
        defaultConfig {
            minSdk = 24
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            proguardFile("asd.txt")
            buildConfigField("String", "MY_FIELD", "\"myValue\"")
            ${"buildConfigFields" highlightedAs HighlightSeverity.ERROR}("Integer", "MY_FIELD", "0")
        }
        testCoverage {
            jacocoVersion = "1.1"
        }
        compileOptions {
            encoding = "utf-8"
        }
        installation {
            ${"timeoutinms" highlightedAs HighlightSeverity.ERROR} = 100
            enableBaselineProfile = false
        }
    }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }
  @Test
  fun checkAllCorrectCase() {
    writeToSchemaFile(TestFile.DECLARATIVE_GENERATED_SCHEMAS)
    val file = fixture.addFileToProject("build.gradle.something", """
      plugins {
        id("something")
      }
      androidApplication {
           minSdk = 33
      }
      declarativeDependencies {
        api("dependencies")
      }
    """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    fixture.checkHighlighting()
  }
}