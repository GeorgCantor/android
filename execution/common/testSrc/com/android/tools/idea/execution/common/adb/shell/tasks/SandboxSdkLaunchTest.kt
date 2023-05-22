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
package com.android.tools.idea.execution.common.adb.shell.tasks

import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.testutils.MockitoKt
import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.execution.common.AndroidExecutionException
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.fail

class SandboxSdkLaunchTest {
  @get:Rule
  val projectRule = AndroidProjectRule.withSdk()

  private val device = mock<IDevice>()

  @Test
  fun successful() = runBlocking(AndroidDispatchers.workerThread) {
    val packageID = "testPackageID"
    launchSandboxSdk(device, packageID)
    Mockito.verify(device).executeShellCommand(MockitoKt.eq("cmd sdk_sandbox start $packageID"), any())
  }

  @Test
  fun sandboxSdkIsDisabled() = runBlocking(AndroidDispatchers.workerThread) {
    val error = "Error: SDK sandbox is disabled."
    val output = error.toByteArray(Charsets.UTF_8)
    whenever(device.executeShellCommand(any(), any())).thenAnswer {
      val outputReceiver = it.arguments[1] as CollectingOutputReceiver
      outputReceiver.addOutput(output, 0, output.size)
    }

    try {
      launchSandboxSdk(device, "testPackageID")
      fail("Run should fail")
    } catch (e: AndroidExecutionException) {
      Truth.assertThat(e.errorId).isEqualTo(SANDBOX_IS_DISABLED)
      Truth.assertThat(e.message).isEqualTo(error)
    }
  }

  @Test
  fun packageDoesNotExist() = runBlocking(AndroidDispatchers.workerThread) {
    val packageName = "com.android.test.testPackageID"
    val error = "Error: No such package $packageName for user 0"
    val output = error.toByteArray(Charsets.UTF_8)

    whenever(device.executeShellCommand(any(), any())).thenAnswer {
      val outputReceiver = it.arguments[1] as CollectingOutputReceiver
      outputReceiver.addOutput(output, 0, output.size)
    }

    try {
      launchSandboxSdk(device, packageName)
      fail("Run should fail")
    } catch (e: AndroidExecutionException) {
      Truth.assertThat(e.errorId).isEqualTo(PACKAGE_NOT_FOUND)
      Truth.assertThat(e.message).isEqualTo(error)
    }
  }
}