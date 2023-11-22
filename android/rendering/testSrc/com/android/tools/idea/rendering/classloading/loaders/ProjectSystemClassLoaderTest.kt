/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.rendering.classloading.loaders

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.writeChild
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URL
import java.nio.file.Files

private class TestVirtualFile(delegate: VirtualFile) : DelegateVirtualFile(delegate) {
  var injectedModificationStamp: Long? = null

  override fun getModificationStamp(): Long = injectedModificationStamp ?: super.getModificationStamp()
  override fun getTimeStamp(): Long = injectedModificationStamp ?: super.getTimeStamp()
}

class ProjectSystemClassLoaderTest {
  @get:Rule
  val projectRule = AndroidProjectRule.inMemory()

  @After
  fun tearDown() {
    StudioFlags.PROJECT_SYSTEM_CLASS_LOADER_CACHE_LIMIT.clearOverride()
  }

  @Test
  fun `check load from project`() {
    val outputDirectory = Files.createTempDirectory("out")
    val virtualFile1 = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file1", "contents1")
    val virtualFile2 = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file2", "contents2")
    val classes = mapOf(
      "a.class1" to virtualFile1,
      "a.class2" to virtualFile2
    )
    val loader = ProjectSystemClassLoader {
      classes[it]
    }

    assertNull(loader.loadClass("not.found.class"))
    assertEquals(0, loader.loadedVirtualFiles.count())
    assertArrayEquals(virtualFile1.contentsToByteArray(), loader.loadClass("a.class1"))
    assertArrayEquals(virtualFile2.contentsToByteArray(), loader.loadClass("a.class2"))
    assertEquals(2, loader.loadedVirtualFiles.count())

    // Invalidate and reload one class
    loader.invalidateCaches()
    assertEquals(0, loader.loadedVirtualFiles.count())
    assertArrayEquals(virtualFile1.contentsToByteArray(), loader.loadClass("a.class1"))
    assertEquals(1, loader.loadedVirtualFiles.count())
  }

  @Test
  fun `test files removed`() {
    val outputDirectory = Files.createTempDirectory("out")
    val virtualFile = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file1", "contents1")
    val loader = ProjectSystemClassLoader {
      if (it == "a.class1") virtualFile else null
    }
    assertArrayEquals(virtualFile.contentsToByteArray(), loader.loadClass("a.class1"))
    runWriteActionAndWait { virtualFile.delete(this) }
    assertNull(loader.loadClass("a.class1"))
  }

  @Test
  fun `verify loaded classes`() {
    val outputDirectory = Files.createTempDirectory("out")
    val virtualFile1 = TestVirtualFile(VfsUtil.findFile(outputDirectory, true)!!.writeChild("file1", "contents1"))
    val virtualFile2 = TestVirtualFile(VfsUtil.findFile(outputDirectory, true)!!.writeChild("file2", "contents2"))
    val classes = mapOf(
      "a.class1" to virtualFile1,
      "a.class2" to virtualFile2
    )
    val loader = ProjectSystemClassLoader {
      classes[it]
    }

    assertArrayEquals(virtualFile1.contentsToByteArray(), loader.loadClass("a.class1"))
    assertArrayEquals(virtualFile2.contentsToByteArray(), loader.loadClass("a.class2"))
    assertTrue(loader.loadedVirtualFiles.single { it.first == "a.class1" }.third.isUpToDate(virtualFile1))
    assertTrue(loader.loadedVirtualFiles.single { it.first == "a.class2" }.third.isUpToDate(virtualFile2))

    // Simulate a file modification via timestamp update
    virtualFile1.injectedModificationStamp = 111
    assertFalse(loader.loadedVirtualFiles.single { it.first == "a.class1" }.third.isUpToDate(virtualFile1))
    assertTrue(loader.loadedVirtualFiles.single { it.first == "a.class2" }.third.isUpToDate(virtualFile2))

    // Now invalidate and reload class1
    loader.invalidateCaches()
    assertEquals(0, loader.loadedVirtualFiles.count())
    assertArrayEquals(virtualFile1.contentsToByteArray(), loader.loadClass("a.class1"))
    assertTrue(loader.loadedVirtualFiles.single { it.first == "a.class1" }.third.isUpToDate(virtualFile1))
  }

  @Test
  fun `verify platform classes are not loaded`() {
    val outputDirectory = Files.createTempDirectory("out")
    val virtualFile1 = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file1", "contents1")
    val virtualFile2 = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file2", "contents2")
    val virtualFile3 = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file3", "contents3")
    val classes = mapOf(
      "_layoutlib_._internal_..class1" to virtualFile1,
      "java.lang.Test" to virtualFile2,
      "test.package.A" to virtualFile3
    )
    val loader = ProjectSystemClassLoader {
      classes[it]
    }

    assertNull(loader.loadClass("_layoutlib_._internal_..Class1"))
    assertNull(loader.loadClass("java.lang.Test"))
    assertArrayEquals(virtualFile3.contentsToByteArray(), loader.loadClass("test.package.A"))
    assertEquals(1, loader.loadedVirtualFiles.count())
  }

  /**
   * Regression test for b/216309775. If a class is not found, but then added by a build, the project class loader should pick it up.
   */
  @Test
  fun `verify failed class loads are not cached`() {
    val outputDirectory = Files.createTempDirectory("out")
    val virtualFile1 = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file1", "contents1")
    val virtualFile2 = VfsUtil.findFile(outputDirectory, true)!!.writeChild("file2", "contents2")
    val classes = mutableMapOf(
      "test.package.A" to virtualFile1
    )
    val loader = ProjectSystemClassLoader {
      classes[it]
    }

    assertNull(loader.loadClass("test.package.B"))
    classes["test.package.B"] = virtualFile2
    assertNotNull(loader.loadClass("test.package.B"))
  }

  /**
   * Regression test for b/218453131 where we need to check that we get the latest version of the class file and not the
   * VFS cached version.
   */
  @Test
  fun `ensure latest version of the class file is accessed`() {
    val tempDirectory = VfsUtil.findFileByIoFile(Files.createTempDirectory("out").toFile(), true)!!

    val classFile = runWriteActionAndWait {
      val classFile = tempDirectory.createChildData(this, "A.class")
      val classFileContents = "Initial content"
      classFile.setBinaryContent(classFileContents.toByteArray())
      classFile
    }

    val classes = mutableMapOf(
      "test.package.A" to classFile
    )
    val loader = ProjectSystemClassLoader {
      classes[it]
    }

    assertEquals("Initial content", String(loader.loadClass("test.package.A")!!))

    // Write the file externally (not using VFS) and check the contents
    Files.writeString(classFile.toNioPath(), "Updated content")
    assertEquals("Updated content", String(loader.loadClass("test.package.A")!!))
  }

  @Test
  fun `loading classes from jar sources`() {
    val tempDirectory = VfsUtil.findFileByIoFile(Files.createTempDirectory("out").toFile(), true)!!
    val outputJar = tempDirectory.toNioPath().resolve("classes.jar")

    val outputJarPath = createJarFile(outputJar, mapOf(
      "ClassA.class" to "contents1".encodeToByteArray(),
      "ClassB.class" to "contents2".encodeToByteArray(),
      "test/package/ClassC.class" to "contents3".encodeToByteArray(),
    ))

    val classes = mutableMapOf(
      "A" to VfsUtil.findFileByURL(URL("jar:file:$outputJarPath!/ClassA.class")),
      "B" to VfsUtil.findFileByURL(URL("jar:file:$outputJarPath!/ClassB.class")),
      "test.package.C" to VfsUtil.findFileByURL(URL("jar:file:$outputJarPath!/test/package/ClassC.class"))
    )
    val loader = ProjectSystemClassLoader {
      classes[it]
    }
    assertEquals("contents1", String(loader.loadClass("A")!!))
    assertEquals("contents2", String(loader.loadClass("B")!!))
    assertEquals("contents3", String(loader.loadClass("test.package.C")!!))
  }

  @Test
  fun `jar is evicted from cache if bigger than maximum size`() {
    val tempDirectory = VfsUtil.findFileByIoFile(Files.createTempDirectory("out").toFile(), true)!!
    val outputJar = tempDirectory.toNioPath().resolve("classes.jar")

    val outputJarPath = createJarFile(outputJar, mapOf(
      "ClassA.class" to "contents1".encodeToByteArray(),
      "ClassB.class" to "contents2".encodeToByteArray(),
      "test/package/ClassC.class" to "contents3".encodeToByteArray(),
    ))

    val classes = mutableMapOf(
      "A" to VfsUtil.findFileByURL(URL("jar:file:$outputJarPath!/ClassA.class")),
      "B" to VfsUtil.findFileByURL(URL("jar:file:$outputJarPath!/ClassB.class")),
      "test.package.C" to VfsUtil.findFileByURL(URL("jar:file:$outputJarPath!/test/package/ClassC.class"))
    )

    run {
      var cacheMissCount = 0
      val jarManager = JarManager.forTesting(cacheMissCallback = { cacheMissCount++ })
      // Default maximum weight
      val loader = ProjectSystemClassLoader(jarManager = jarManager) {
        classes[it]
      }

      assertEquals("contents1", String(loader.loadClass("A")!!))
      assertEquals("contents2", String(loader.loadClass("B")!!))
      assertEquals("contents3", String(loader.loadClass("test.package.C")!!))
      // We only miss the cache on the first call. Following calls to loadClass rely on the cached value.
      assertEquals(1, cacheMissCount)
    }

    run {
      var cacheMissCount = 0
      StudioFlags.PROJECT_SYSTEM_CLASS_LOADER_CACHE_LIMIT.override(1)
      val jarManager = JarManager.forTesting(cacheMissCallback = { cacheMissCount++ })
      // Default maximum weight
      val tinyCacheLoader = ProjectSystemClassLoader(jarManager = jarManager) {
        classes[it]
      }

      assertEquals("contents1", String(tinyCacheLoader.loadClass("A")!!))
      assertEquals("contents2", String(tinyCacheLoader.loadClass("B")!!))
      assertEquals("contents3", String(tinyCacheLoader.loadClass("test.package.C")!!))
      // We miss the cache on every loadClass call, because the JAR is evicted from the cache for being bigger than its maximum weight.
      assertEquals(3, cacheMissCount)
    }
  }
}