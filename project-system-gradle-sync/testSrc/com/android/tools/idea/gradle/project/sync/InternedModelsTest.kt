/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync

import com.android.builder.model.v2.ide.AndroidLibraryData
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.LibraryInfo
import com.android.builder.model.v2.ide.LibraryType
import com.android.builder.model.v2.ide.ProjectInfo
import com.android.ide.common.gradle.Component
import com.android.tools.idea.gradle.model.IdeArtifactLibrary
import com.android.tools.idea.gradle.model.IdeModuleWellKnownSourceSet
import com.android.tools.idea.gradle.model.LibraryReference
import com.android.tools.idea.gradle.model.impl.IdeAndroidLibraryImpl
import com.android.tools.idea.gradle.model.impl.IdeJavaLibraryImpl
import com.android.tools.idea.gradle.model.impl.IdePreResolvedModuleLibraryImpl
import com.android.tools.idea.gradle.model.impl.IdeUnresolvedModuleLibraryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val BUILD_ROOT = "/tmp/abc"

class InternedModelsTest {

  private val internedModels = InternedModels(File(BUILD_ROOT))

  private fun LibraryReference.lookup(): IdeArtifactLibrary = internedModels.lookup(this) as IdeArtifactLibrary

  @Test
  fun `intern string`() {
    val s1 = "123123".substring(0..2)
    val s2 = "123123".substring(3..5)
    val i1 = internedModels.intern(s1)
    val i2 = internedModels.intern(s2)
    assertTrue(s1 !== s2)
    assertTrue(i1 === i2)
  }

  @Test
  fun `create android library`() {
    val libRoot = "/tmp/libs/lib"
    val artifact = "$libRoot/artifactFile"
    val unnamed = ideAndroidLibrary(libRoot, "com.example:lib:1.0", artifact)

    val named = internedModels.internAndroidLibrary(LibraryIdentity.fromIdeModel(unnamed)) { unnamed }.lookup()

    assertTrue(named == unnamed.copy(name = "com.example:lib:1.0"))
  }

  @Test
  fun `get android library`() {
    val libRoot = "/tmp/libs/lib"
    val artifact = "$libRoot/artifactFile"
    val unnamed = ideAndroidLibrary(libRoot, "com.example:lib:1.0", artifact)

    val unnamedCopy = unnamed.copy()
    val namedRef = internedModels.internAndroidLibrary(LibraryIdentity.fromIdeModel(unnamed)) { unnamed }
    val namedCopyRef = internedModels.internAndroidLibrary(LibraryIdentity.fromIdeModel(unnamedCopy)) { unnamedCopy }

    assertTrue(unnamed !== unnamedCopy)
    assertTrue(unnamed == unnamedCopy)
    assertTrue(namedRef == namedCopyRef)
    assertTrue(namedRef.lookup() === namedCopyRef.lookup())
  }


  @Test
  fun `distinct v2 library keys map to same object if the ide models are same`() {
    val libRoot = "/tmp/libs/lib"
    val artifact = "$libRoot/artifactFile"
    val unnamed = ideAndroidLibrary(libRoot, "com.example:lib:1.0", artifact)

    val unnamedCopy = unnamed.copy()
    val namedRef = internedModels.internAndroidLibrary(LibraryIdentity.fromLibrary(FakeLibrary("key1", File("folder1")))) { unnamed }
    val namedCopyRef = internedModels.internAndroidLibrary(LibraryIdentity.fromLibrary(FakeLibrary("key2", File("folder2")))) { unnamedCopy }

    assertTrue(unnamed !== unnamedCopy)
    assertTrue(unnamed == unnamedCopy)
    assertTrue(namedRef == namedCopyRef)
    assertTrue(namedRef.lookup() === namedCopyRef.lookup())
  }

  @Test
  fun `v2 libraries considered different if they have different extracted folders, but same key`() {
    val libRoot = "/tmp/libs/lib"
    val artifact = "$libRoot/artifactFile"
    val folder1 = File("parent1")
    val folder2 = File("parent2")
    val unnamed = ideAndroidLibrary(libRoot, "com.example:lib:1.0", artifact).copy(folder = folder1)
    val unnamedCopy = unnamed.copy(folder = folder2)
    val namedRef = internedModels.internAndroidLibrary(LibraryIdentity.fromLibrary(FakeLibrary("key1", folder1.resolve("resFolder")))) { unnamed }
    val namedCopyRef = internedModels.internAndroidLibrary(LibraryIdentity.fromLibrary(FakeLibrary("key1", folder2.resolve("resFolder")))) { unnamedCopy }

    assertTrue(unnamed !== unnamedCopy)
    assertTrue(unnamed != unnamedCopy)
    assertTrue(namedRef != namedCopyRef)
    assertTrue(namedRef.lookup() !== namedCopyRef.lookup())
  }

  @Test
  fun `create java library`() {
    val libRoot = "/tmp/libs/lib"
    val unnamed = IdeJavaLibraryImpl(
      artifactAddress = "com.example:lib:1.0",
      component = Component.parse("com.example:lib:1.0"),
      name = "",
      artifact = File("$libRoot/artifactFile"),
      srcJar = null,
      docJar = null,
      samplesJar = null
    )

    val named = internedModels.internJavaLibrary(LibraryIdentity.fromIdeModel(unnamed)) { unnamed }.lookup()

    assertTrue(named == unnamed.copy(name = "com.example:lib:1.0"))
  }

  @Test
  fun `get java library`() {
    val libRoot = "/tmp/libs/lib"
    val unnamed = IdeJavaLibraryImpl(
      artifactAddress = "com.example:lib:1.0",
      component = Component.parse("com.example:lib:1.0"),
      name = "",
      artifact = File("$libRoot/artifactFile"),
      srcJar = null,
      docJar = null,
      samplesJar = null
    )

    val unnamedCopy = unnamed.copy()
    val namedRef = internedModels.internJavaLibrary(LibraryIdentity.fromIdeModel(unnamed))  { unnamed }
    val namedCopyRef = internedModels.internJavaLibrary(LibraryIdentity.fromIdeModel(unnamedCopy))  { unnamedCopy }

    assertTrue(unnamed !== unnamedCopy)
    assertTrue(unnamed == unnamedCopy)
    assertTrue(namedRef == namedCopyRef)
    assertTrue(namedRef.lookup() === namedCopyRef.lookup())
  }

  @Test
  fun `get module library`() {
    val module = IdePreResolvedModuleLibraryImpl(
      buildId = "/tmp/build",
      projectPath = ":app",
      variant = "debug",
      lintJar = null,
      sourceSet = IdeModuleWellKnownSourceSet.MAIN
    )

    val copy = module.copy()
    val module1 = internedModels.internModuleLibrary(LibraryIdentity.fromIdeModel(module)) { module }
    val module2 = internedModels.internModuleLibrary(LibraryIdentity.fromIdeModel(copy)) { copy }

    assertTrue(module !== copy)
    assertTrue(module == copy)
    assertTrue(module1 === module2)
  }

  @Test
  fun `get unresolved module library`() {
    val module = IdeUnresolvedModuleLibraryImpl(
      buildId = "/tmp/build",
      projectPath = ":app",
      variant = "debug",
      lintJar = null,
      artifact = File("/tmp/a.jar")
    )

    val copy = module.copy()
    val module1 = internedModels.internModuleLibrary(LibraryIdentity.fromIdeModel(module)) { module }
    val module2 = internedModels.internModuleLibrary(LibraryIdentity.fromIdeModel(copy)) { copy }

    assertTrue(module !== copy)
    assertTrue(module == copy)
    assertTrue(module1 === module2)
  }

  @Test
  fun `name library with matching artifact name`() {
    val unnamed1 = let {
      val libRoot = "/tmp/libs/lib1"
      val artifact = "$libRoot/artifactFile"
      ideAndroidLibrary(libRoot, "com.example:lib:1.0", artifact)
    }

    val unnamed2 = let {
      val libRoot = "/tmp/libs/lib2" // A different directory.
      val artifact = "$libRoot/artifactFile"
      ideAndroidLibrary(libRoot, "com.example:lib:1.0", artifact)
    }

    val named1 = internedModels.internAndroidLibrary(LibraryIdentity.fromIdeModel(unnamed1)) { unnamed1 }.lookup()
    val named2 = internedModels.internAndroidLibrary(LibraryIdentity.fromIdeModel(unnamed2)) { unnamed2 }.lookup()

    assertTrue(unnamed1.artifactAddress == unnamed2.artifactAddress)
    assertTrue(named1.artifactAddress == named2.artifactAddress)
    assertTrue(named1.name != named2.name)

    assertEquals("com.example:lib:1.0 (1)", named2.name)
  }

  @Test
  fun `name local aar library`() {
    val unnamed = let {
      val libRoot = "$BUILD_ROOT/app/libs"
      val artifact = "$libRoot/artifactFile"
      ideAndroidLibrary(libRoot, "${ModelCache.LOCAL_AARS}:$artifact", artifact, component = null)
    }

    val named = internedModels.internAndroidLibrary(LibraryIdentity.fromIdeModel(unnamed)) { unnamed }.lookup()

    assertTrue(named.artifactAddress == unnamed.artifactAddress)
    assertEquals("./app/libs/artifactFile", named.name)
  }

  private fun ideAndroidLibrary(
    libRoot: String,
    address: String,
    artifact: String,
    component: Component? = Component.parse(address)
  ) = IdeAndroidLibraryImpl.create(
    artifactAddress = address,
    component = component,
    name = "",
    folder = File(libRoot),
    manifest = "$libRoot/AndroidManifest.xml",
    compileJarFiles = listOf("$libRoot/file.jar"),
    runtimeJarFiles = listOf("$libRoot/api.jar"),
    resFolder = "$libRoot/res",
    resStaticLibrary = File("$libRoot/res.apk"),
    assetsFolder = "$libRoot/assets",
    jniFolder = "$libRoot/jni",
    aidlFolder = "$libRoot/aidl",
    renderscriptFolder = "$libRoot/renderscriptFolder",
    proguardRules = "$libRoot/proguardRules",
    lintJar = "$libRoot/lint.jar",
    srcJar = "$libRoot/srcJar.jar",
    docJar = "$libRoot/docJar.jar",
    samplesJar = "$libRoot/samplesJar.jar",
    externalAnnotations = "$libRoot/externalAnnotations",
    publicResources = "$libRoot/publicResources",
    artifact = File(artifact),
    symbolFile = "$libRoot/symbolFile",
    deduplicate = internedModels::intern
  )

  class FakeLibrary(override val key: String, val resFolder: File) : Library {
    override val androidLibraryData = FakeAndroidLibraryData(resFolder)
    override val type: LibraryType get() = error("unused")
    override val projectInfo: ProjectInfo get() = error("unused")
    override val libraryInfo: LibraryInfo get() = error("unused")
    override val artifact: File get() = error("unused")
    override val lintJar: File get() = error("unused")
    override val srcJar: File get() = error("unused")
    override val docJar: File get() = error("unused")
    override val samplesJar get() = error("unused")

    class FakeAndroidLibraryData(override val resFolder: File) : AndroidLibraryData {
      override val manifest: File get() = error("unused")
      override val compileJarFiles: List<File> get() = error("unused")
      override val runtimeJarFiles: List<File> get() = error("unused")
      override val resStaticLibrary: File get() = error("unused")
      override val assetsFolder: File get() = error("unused")
      override val jniFolder: File get() = error("unused")
      override val aidlFolder: File get() = error("unused")
      override val renderscriptFolder: File get() = error("unused")
      override val proguardRules: File get() = error("unused")
      override val externalAnnotations: File get() = error("unused")
      override val publicResources: File get() = error("unused")
      override val symbolFile: File get() = error("unused")
    }
  }
}
