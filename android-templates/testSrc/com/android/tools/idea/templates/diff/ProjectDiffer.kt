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
import com.google.common.truth.Truth
import com.intellij.util.containers.isEmpty
import com.intellij.util.io.isDirectory
import org.junit.Assert
import java.io.File
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path

class ProjectDiffer(template: Template, goldenDirName: String) :
  ProjectRenderer(template, goldenDirName) {
  override fun handleDirectories(moduleName: String, goldenDir: Path, projectDir: Path) {
    diffDirectories(goldenDir, projectDir, "")
  }

  override fun prepareProject(projectRoot: File) {
    prepareProjectImpl(projectRoot)
  }
}

/**
 * Recursively diffs the files in two directories, ignoring certain filenames specified by
 * FILES_TO_IGNORE
 */
private fun diffDirectories(goldenDir: Path, projectDir: Path, printPrefix: String = "") {
  val goldenFiles = getNonEmptyDirEntries(goldenDir, printPrefix)
  val projectFiles = getNonEmptyDirEntries(projectDir, printPrefix)
  projectFiles.removeAll(FILES_TO_IGNORE.toSet())

  Assert.assertEquals(goldenFiles, projectFiles)

  if (goldenFiles.isEmpty()) {
    return
  }

  for (filename in goldenFiles) {
    val projectFile = projectDir.resolve(filename)
    val goldenFile = goldenDir.resolve(filename)

    Assert.assertEquals(
      "$projectFile and $goldenFile are not of same file type",
      projectFile.isDirectory(),
      goldenFile.isDirectory()
    )

    if (projectFile.isDirectory()) {
      println("${printPrefix}$projectFile is a directory")
      diffDirectories(goldenFile, projectFile, "$printPrefix..")
      continue
    }

    println("${printPrefix}diffing $projectFile and $goldenFile")

    // Checking whether it's a text file is complicated, and we don't really need to check the text,
    // it's just for human readability, so
    // diffing all lines and only reading bytes if text fails is simpler.
    try {
      val goldenLines = Files.readAllLines(goldenFile)
      val projectLines = Files.readAllLines(projectFile)
      Truth.assertThat(projectLines).isEqualTo(goldenLines)
    } catch (error: MalformedInputException) {
      println("${printPrefix}reading lines failed, compare bytes instead")
      val goldenBytes = Files.readAllBytes(goldenFile)
      val projectBytes = Files.readAllBytes(projectFile)
      Truth.assertThat(projectBytes).isEqualTo(goldenBytes)
    }
  }
}

private fun getNonEmptyDirEntries(dir: Path, printPrefix: String = ""): MutableSet<String> {
  return Files.list(dir).use { pathStream ->
    pathStream
      .filter { !it.isDirectory() || !isDirectoryEffectivelyEmpty(it, printPrefix) }
      .map { it.fileName.toString() }
      .toList()
      .toMutableSet()
  }
}

/**
 * Returns whether the directory is effectively empty, i.e. if it itself is empty, or if it only
 * contains more directories that are also effectively empty. This is needed because Git doesn't
 * check in directories, only files. If there are many nested directories but no file at the end of
 * it, the checked out golden files wouldn't have those directories, so we need to skip them for
 * diffing.
 *
 * TODO: This isn't super efficient because subdirectories that return not empty would get checked
 *   again in the next level of diffing.
 */
private fun isDirectoryEffectivelyEmpty(dir: Path, printPrefix: String = ""): Boolean {
  val empty =
    Files.list(dir).use { pathStream ->
      pathStream
        // If there is any non-directory file, or if the directory is NOT empty...
        .filter { !it.isDirectory() || !isDirectoryEffectivelyEmpty(it, "$printPrefix..") }
        .isEmpty()
    }
  if (empty) {
    println("${printPrefix}Skipping empty $dir")
  }
  return empty
}
