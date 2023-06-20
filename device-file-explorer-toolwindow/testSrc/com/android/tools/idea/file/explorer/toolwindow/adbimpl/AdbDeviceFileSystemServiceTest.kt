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
package com.android.tools.idea.file.explorer.toolwindow.adbimpl

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.sdklib.deviceprovisioner.DeviceState.Connected
import com.android.sdklib.deviceprovisioner.receiveUntilPassing
import com.android.sdklib.deviceprovisioner.testing.DeviceProvisionerRule
import com.android.tools.idea.file.explorer.toolwindow.fs.DeviceFileSystem
import com.android.tools.idea.file.explorer.toolwindow.fs.DeviceState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

class AdbDeviceFileSystemServiceTest {

  @JvmField
  @Rule
  val deviceProvisionerRule = DeviceProvisionerRule()

  val service by lazy { AdbDeviceFileSystemService(deviceProvisionerRule.deviceProvisioner) }

  /**
   * Verify that we do not pass DeviceHandles for devices that are not connected to ADB.
   */
  @Test
  fun onlyConnectedDevicesProvided() = runBlockingWithTimeout {

    val deviceHandle = deviceProvisionerRule.deviceProvisionerPlugin.addNewDevice()

    val channel = Channel<List<DeviceFileSystem>>()
    val job = launch(Dispatchers.IO) { service.devices.collect { channel.send(it) } }

    // Device is offline
    assertThat(channel.receive()).isEmpty()

    // Activate the device; it shows up
    deviceHandle.activationAction.activate()

    // As the device activates, we may encounter various transitional states as the
    // OfflineDeviceHandle becomes ONLINE and then removed, and the expected handle
    // comes online.
    channel.receiveUntilPassing {
      assertThat(it).hasSize(1)
      assertThat(it[0].deviceState).isEqualTo(DeviceState.ONLINE)
      assertThat(it[0]).isInstanceOf(AdbDeviceFileSystem::class.java)
      val handle = (it[0] as AdbDeviceFileSystem).deviceHandle
      assertThat(handle).isSameAs(deviceHandle)
    }

    val state = deviceHandle.state
    assertThat(state.properties.title).isEqualTo("Google Pixel 6")
    assertThat(state).isInstanceOf(Connected::class.java)

    // Deactivate the device; it goes away
    deviceHandle.deactivationAction.deactivate()

    assertThat(channel.receive()).isEmpty()

    job.cancel()
  }

  @Test
  fun unauthorizedDevicesProvided() =
    runBlockingWithTimeout {

      val channel = Channel<List<DeviceFileSystem>>()
      val job = launch(Dispatchers.IO) {
        service.devices.collect {
          channel.send(it)
        }
      }

      // Receive initial empty state
      assertThat(channel.receive()).isEmpty()

      val deviceState =
        deviceProvisionerRule.fakeAdb.connectDevice(
          deviceId = "test_device_01",
          manufacturer = "Google",
          deviceModel = "Pixel 10",
          release = "8.0",
          sdk = "31",
          hostConnectionType = com.android.fakeadbserver.DeviceState.HostConnectionType.USB
        )
      deviceState.deviceStatus = com.android.fakeadbserver.DeviceState.DeviceStatus.ONLINE

      var deviceFileSystems = channel.receive()
      if (deviceFileSystems.isEmpty()) {
        deviceFileSystems = channel.receive()
      }
      assertThat(deviceFileSystems).hasSize(1)
      assertThat((deviceFileSystems[0] as AdbDeviceFileSystem).deviceHandle.state.isOnline()).isTrue()

      // Disconnect the device; it goes away
      deviceProvisionerRule.fakeAdb.disconnectDevice(deviceState.deviceId)

      assertThat(channel.receive()).isEmpty()

      job.cancel()
    }
}
