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
package com.android.tools.idea.streaming.device

import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.intellij.openapi.util.SystemInfo
import icons.StudioIcons
import org.junit.Assume.assumeTrue

/**
 * Checks if the current platform is suitable for tests depending on the FFmpeg library.
 * For some unclear reason FFmpeg-dependent tests fail on Windows with
 * "UnsatisfiedLinkError: no jniavcodec in java.library.path".
 */
fun isFFmpegAvailableToTest(): Boolean = !SystemInfo.isWindows

/** Makes the test run only when the FFmpeg library is functional in the test environment. */
fun assumeFFmpegAvailable() = assumeTrue(isFFmpegAvailableToTest())

/**
 * Creates a [DeviceConfiguration] for testing purposes.
 */
fun createDeviceConfiguration(propertyMap: Map<String, String>): DeviceConfiguration {
  val properties = DeviceProperties.Builder()
  properties.readCommonProperties(propertyMap)
  properties.icon = StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_PHONE
  return DeviceConfiguration(properties.buildBase())
}

val emptyDeviceConfiguration: DeviceConfiguration = createDeviceConfiguration(mapOf())
