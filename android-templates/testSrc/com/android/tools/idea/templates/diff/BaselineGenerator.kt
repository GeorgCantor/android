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
package com.android.tools.idea.templates.diff

import com.android.tools.idea.wizard.template.Template
import com.android.utils.FileUtils
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Generates files from a template and performs checks on them to ensure they're valid and can be checked in as golden files, then copies
 * the validated files to the output directory.
 */
class BaselineGenerator(template: Template) : ProjectRenderer(template) {
  override fun handleDirectories(moduleName: String, goldenDir: Path, projectDir: Path) {
    val outputDir = getOutputDir(moduleName, goldenDir)

    FileUtils.deleteRecursivelyIfExists(outputDir.toFile())
    FileUtils.copyDirectory(projectDir.toFile(), outputDir.toFile())
    FILES_TO_IGNORE.forEach { FileUtils.deleteRecursivelyIfExists(goldenDir.resolve(it).toFile()) }
  }

  // TODO: build
  // TODO: lint
  // TODO: other checks

  /**
   * Gets the output directory where we should put the generated golden files. If this is run from Bazel, TEST_UNDECLARED_OUTPUTS_DIR will
   * be defined, and we can put the files there. Otherwise, it's run from IDEA, where we can replace the golden files in the source tree.
   */
  private fun getOutputDir(moduleName: String, goldenDir: Path): Path {
    val outputDir: Path
    val undeclaredOutputs = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR")

    println("\n----------------------------------------")
    if (undeclaredOutputs == null) {
      outputDir = goldenDir
      println("Updating generated golden files in place at $goldenDir")
    }
    else {
      outputDir = Paths.get(undeclaredOutputs).resolve(moduleName)
      println("Outputting generated golden files to $outputDir\n\n" +
              "To update these files, unzip outputs.zip to the android-templates/testData/golden directory and remove idea.log.\n" +
              "For a remote invocation, download and unzip outputs.zip:\n" +
              "    unzip -d $(bazel info workspace)/tools/adt/idea/android-templates/testData/golden -o outputs.zip\n" +
              "    rm $(bazel info workspace)/tools/adt/idea/android-templates/testData/golden/idea.log\n" +
              "\n" +
              "For a local invocation, outputs.zip will be in bazel-testlogs:\n" +
              "    unzip -d $(bazel info workspace)/tools/adt/idea/android-templates/testData/golden -o \\\n" +
              "    $(bazel info bazel-testlogs)/tools/adt/idea/android-templates/intellij.android.templates.tests_tests__TemplateDiffTest/test.outputs/outputs.zip\n" +
              "    rm $(bazel info workspace)/tools/adt/idea/android-templates/testData/golden/idea.log"
      )
    }
    println("----------------------------------------\n")
    return outputDir
  }
}

