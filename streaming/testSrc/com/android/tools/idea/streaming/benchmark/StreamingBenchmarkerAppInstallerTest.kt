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
package com.android.tools.idea.streaming.benchmark

import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.argumentCaptor
import com.android.testutils.MockitoKt.eq
import com.android.testutils.MockitoKt.inOrder
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.testing.mockStatic
import com.android.tools.idea.util.StudioPathManager
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import io.ktor.util.encodeBase64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.io.InputStream
import java.nio.file.Paths
import kotlin.time.Duration.Companion.hours

private const val SERIAL_NUMBER = "abc123"
private const val DISABLE_IMMERSIVE_CONFIRMATION_COMMAND = "settings put secure immersive_mode_confirmations confirmed"
private const val START_COMMAND = "am start -n com.android.tools.screensharing.benchmark/.InputEventRenderingActivity -f 65536"

/** Tests the [StreamingBenchmarkerAppInstaller] class. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class StreamingBenchmarkerAppInstallerTest {
  @get:Rule
  val projectRule = ProjectRule()
  private val adb: StreamingBenchmarkerAppInstaller.AdbWrapper = mock()
  private val urlFileCache: UrlFileCache = mock()
  private val testRootDisposable
    get() = projectRule.disposable

  private lateinit var installer: StreamingBenchmarkerAppInstaller

  @Before
  fun setUp() {
    installer = StreamingBenchmarkerAppInstaller(projectRule.project, SERIAL_NUMBER, adb)
    projectRule.project.replaceService(UrlFileCache::class.java, urlFileCache, testRootDisposable)
  }

  @Test
  fun installationFromDownload() = runBlockingTest {
    val downloadPath = Paths.get("/help/i/am/trapped/in/a/unit/test/factory.apk")
    val relativePath = "common/streaming-benchmarker/streaming-benchmarker.apk"
    val basePrebuiltsUrl = "https://android.googlesource.com/platform/prebuilts/tools/+/refs/heads/mirror-goog-studio-main/"
    val apkUrl = "$basePrebuiltsUrl$relativePath?format=TEXT"  // Base-64 encoded

    val studioPathManager = mockStatic<StudioPathManager>(testRootDisposable)
    studioPathManager.whenever<Any?> { StudioPathManager.isRunningFromSources() }.thenReturn(false)
    val indicator: ProgressIndicator = mock()
    whenever(urlFileCache.get(eq(apkUrl), anyLong(), any(), any())).thenReturn(downloadPath)
    whenever(adb.install(SERIAL_NUMBER, downloadPath)).thenReturn(true)

    assertThat(installer.installBenchmarkingApp(indicator)).isTrue()

    inOrder(indicator, urlFileCache, adb) {
      // Download
      verify(indicator).isIndeterminate = true
      verify(indicator).text = "Installing benchmarking app"
      val transformCaptor: ArgumentCaptor<(InputStream) -> InputStream> = argumentCaptor()
      verify(urlFileCache).get(eq(apkUrl), eq(12.hours.inWholeMilliseconds), eq(indicator), transformCaptor.capture())
      val helloWorld = "Hello World"
      assertThat(String(transformCaptor.value.invoke(helloWorld.encodeBase64().byteInputStream()).readBytes()))
        .isEqualTo(helloWorld)

      // Installation
      verify(indicator).isIndeterminate = true
      verify(indicator).text = "Installing benchmarking app"
      verify(adb).install(SERIAL_NUMBER, downloadPath)
    }
  }

  @Test
  fun installationFromPrebuilts_success() = runBlockingTest {
    val prebuiltPath = StudioPathManager.resolvePathFromSourcesRoot(
      "prebuilts/tools/common/streaming-benchmarker/streaming-benchmarker.apk")
    whenever(adb.install(SERIAL_NUMBER, prebuiltPath)).thenReturn(true)
    assertThat(installer.installBenchmarkingApp(null)).isTrue()

    verify(adb).install(SERIAL_NUMBER, prebuiltPath)
  }

  @Test
  fun installationFromPrebuilts_failure() = runBlockingTest {
    val prebuiltPath = StudioPathManager.resolvePathFromSourcesRoot(
      "prebuilts/tools/common/streaming-benchmarker/streaming-benchmarker.apk")
    whenever(adb.install(SERIAL_NUMBER, prebuiltPath)).thenReturn(false)

    assertThat(installer.installBenchmarkingApp(null)).isFalse()

    verify(adb).install(SERIAL_NUMBER, prebuiltPath)
  }

  @Test
  fun launchBenchmarkingApp_disableBannerFailure() = runBlockingTest {
    whenever(adb.shellCommand(SERIAL_NUMBER, DISABLE_IMMERSIVE_CONFIRMATION_COMMAND)).thenReturn(false)
    whenever(adb.shellCommand(SERIAL_NUMBER, START_COMMAND)).thenReturn(true)

    assertThat(installer.launchBenchmarkingApp(null)).isFalse()
    verify(adb).shellCommand(SERIAL_NUMBER, DISABLE_IMMERSIVE_CONFIRMATION_COMMAND)
    verify(adb, never()).shellCommand(SERIAL_NUMBER, START_COMMAND)
  }

  @Test
  fun launchBenchmarkingApp_launchAppFailure() = runBlockingTest {
    whenever(adb.shellCommand(SERIAL_NUMBER, DISABLE_IMMERSIVE_CONFIRMATION_COMMAND)).thenReturn(true)
    whenever(adb.shellCommand(SERIAL_NUMBER, START_COMMAND)).thenReturn(false)

    assertThat(installer.launchBenchmarkingApp(null)).isFalse()
    verify(adb).shellCommand(SERIAL_NUMBER, DISABLE_IMMERSIVE_CONFIRMATION_COMMAND)
    verify(adb).shellCommand(SERIAL_NUMBER, START_COMMAND)
  }

  @Test
  fun launchBenchmarkingApp_success() = runBlockingTest {
    whenever(adb.shellCommand(SERIAL_NUMBER, DISABLE_IMMERSIVE_CONFIRMATION_COMMAND)).thenReturn(true)
    whenever(adb.shellCommand(SERIAL_NUMBER, START_COMMAND)).thenReturn(true)

    assertThat(installer.launchBenchmarkingApp(null)).isTrue()
    verify(adb).shellCommand(SERIAL_NUMBER, DISABLE_IMMERSIVE_CONFIRMATION_COMMAND)
    verify(adb).shellCommand(SERIAL_NUMBER, START_COMMAND)
  }

  @Test
  fun uninstallBenchmarkingApp() = runBlockingTest {
    installer.uninstallBenchmarkingApp()

    verify(adb).uninstall(SERIAL_NUMBER)
  }
}
