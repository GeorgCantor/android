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
package com.android.tools.idea.device.explorer.files.adbimpl

import com.android.adblib.ConnectedDevice
import com.android.adblib.scope
import com.android.ddmlib.FileListingService
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.tools.idea.concurrency.FutureCallbackExecutor
import com.android.tools.idea.device.explorer.files.fs.DeviceFileEntry
import com.android.tools.idea.device.explorer.files.fs.DeviceFileSystem
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

class AdbDeviceFileSystem(
  deviceHandle: DeviceHandle,
  val device: ConnectedDevice,
  edtExecutor: Executor,
  val dispatcher: CoroutineDispatcher
) : DeviceFileSystem {

  private val scope: CoroutineScope = device.scope + SupervisorJob(device.scope.coroutineContext.job)
  private val myEdtExecutor = FutureCallbackExecutor(edtExecutor)
  val capabilities = AdbDeviceCapabilities(scope + dispatcher, deviceHandle.state.properties.title, device)
  val adbFileListing = AdbFileListing(device, capabilities, dispatcher)
  val adbFileOperations = AdbFileOperations(device, capabilities, dispatcher)
  val adbFileTransfer = AdbFileTransfer(device, adbFileOperations, myEdtExecutor, dispatcher)

  override val name = deviceHandle.state.properties.title

  override suspend fun rootDirectory(): DeviceFileEntry {
    return AdbDeviceDefaultFileEntry(this, adbFileListing.getRoot(), null)
  }

  suspend fun resolveMountPoint(entry: AdbDeviceFileEntry): AdbDeviceFileEntry =
    withContext(dispatcher) {
      when {
        // Root devices or "su 0" devices don't need mount points
        capabilities.supportsSuRootCommand() || capabilities.isRoot() -> createDirectFileEntry(entry)
        // The "/data" folder has directories where we need to use "run-as"
        entry.fullPath == "/data" -> AdbDeviceDataDirectoryEntry(entry)
        else -> createDirectFileEntry(entry)
      }
    }

  companion object {
    private fun createDirectFileEntry(entry: AdbDeviceFileEntry): AdbDeviceDirectFileEntry {
      return AdbDeviceDirectFileEntry(entry.fileSystem, entry.myEntry, entry.parent, null)
    }
  }
}
