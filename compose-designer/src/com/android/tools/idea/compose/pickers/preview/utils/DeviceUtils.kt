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
package com.android.tools.idea.compose.pickers.preview.utils

import com.android.resources.ScreenOrientation
import com.android.resources.ScreenRatio
import com.android.resources.ScreenRound
import com.android.resources.ScreenSize
import com.android.sdklib.devices.Device
import com.android.sdklib.devices.Hardware
import com.android.sdklib.devices.Screen
import com.android.sdklib.devices.Software
import com.android.sdklib.devices.State
import com.android.tools.configurations.Configuration
import com.android.tools.configurations.ConfigurationSettings
import com.android.tools.idea.avdmanager.AvdScreenData
import com.android.tools.idea.compose.pickers.preview.property.DeviceConfig
import com.android.tools.idea.compose.pickers.preview.property.DimUnit
import com.android.tools.idea.compose.pickers.preview.property.MutableDeviceConfig
import com.android.tools.idea.compose.pickers.preview.property.Orientation
import com.android.tools.idea.compose.pickers.preview.property.Shape
import com.android.tools.idea.compose.pickers.preview.property.toMutableConfig
import com.intellij.openapi.diagnostic.Logger
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Prefix used by device specs to find devices by id. */
internal const val DEVICE_BY_ID_PREFIX = "id:"

/** Prefix used by device specs to find devices by name. */
internal const val DEVICE_BY_NAME_PREFIX = "name:"

/** Prefix used by device specs to create devices by hardware specs. */
internal const val DEVICE_BY_SPEC_PREFIX = "spec:"

/** id for the default device when no device is specified by the user. */
internal const val DEFAULT_DEVICE_ID = "pixel_5"

/** Full declaration for the default device. */
internal const val DEFAULT_DEVICE_ID_WITH_PREFIX = DEVICE_BY_ID_PREFIX + DEFAULT_DEVICE_ID

/** Used for `Round Chin` devices. Or when DeviceConfig.shape == Shape.Chin */
internal const val CHIN_SIZE_PX_FOR_ROUND_CHIN = 30

internal fun Device.toDeviceConfig(): DeviceConfig {
  val config = MutableDeviceConfig().apply { dimUnit = DimUnit.px }
  val deviceState = this.defaultState
  val screen = deviceState.hardware.screen
  config.width = screen.xDimension.toFloat()
  config.height = screen.yDimension.toFloat()
  config.dpi = screen.pixelDensity.dpiValue
  config.orientation =
    when (deviceState.orientation) {
      ScreenOrientation.LANDSCAPE -> Orientation.landscape
      else -> Orientation.portrait
    }
  if (screen.screenRound == ScreenRound.ROUND) {
    config.shape = Shape.Round
    config.chinSize = screen.chin.toFloat()
  } else {
    config.shape = Shape.Normal
  }

  // Set the backing ID at the end, otherwise there's risk of deleting it by changing other
  // properties
  if (this.id != Configuration.CUSTOM_DEVICE_ID) {
    config.parentDeviceId = this.id
  }
  return config
}

internal fun DeviceConfig.createDeviceInstance(): Device {
  val deviceConfig =
    if (this !is MutableDeviceConfig) {
      this.toMutableConfig()
    } else {
      this
    }
  val customDevice =
    Device.Builder()
      .apply {
        setTagId("")
        setName("Custom")
        setId(Configuration.CUSTOM_DEVICE_ID)
        setManufacturer("")
        addSoftware(Software())
        addState(
          State().apply {
            isDefaultState = true
            hardware = Hardware()
          }
        )
      }
      .build()
  customDevice.defaultState.apply {
    orientation =
      when (deviceConfig.orientation) {
        Orientation.landscape -> ScreenOrientation.LANDSCAPE
        Orientation.portrait -> ScreenOrientation.PORTRAIT
      }
    hardware =
      Hardware().apply {
        screen =
          Screen().apply {
            // For "proper" conversions, the dpi in the DeviceConfig should be updated to the
            // resolved density. This is to guarantee that the
            // dimension as defined by the user reflects exactly in the Device (both the value and
            // the unit), since this change in density
            // may introduce an error when calculating the Screen dimensions
            val resolvedDensity =
              AvdScreenData.getCommonScreenDensity(false, deviceConfig.dpi.toDouble(), 0)
            deviceConfig.dpi = resolvedDensity.dpiValue
            deviceConfig.dimUnit = DimUnit.px // Transforms dimension to Pixels
            xDimension = deviceConfig.width.roundToInt()
            yDimension = deviceConfig.height.roundToInt()
            pixelDensity = resolvedDensity
            diagonalLength =
              sqrt((1.0 * xDimension * xDimension) + (1.0 * yDimension * yDimension)) /
                pixelDensity.dpiValue
            screenRound = if (deviceConfig.isRound) ScreenRound.ROUND else ScreenRound.NOTROUND
            chin =
              when {
                deviceConfig.shape == Shape.Chin -> CHIN_SIZE_PX_FOR_ROUND_CHIN
                deviceConfig.isRound -> deviceConfig.chinSize.roundToInt()
                else -> 0
              }
            size = ScreenSize.getScreenSize(diagonalLength)
            ratio = ScreenRatio.create(xDimension, yDimension)
          }
      }
  }
  return customDevice
}

/** Returns the [Device] used when there's no device specified by the user. */
internal fun ConfigurationSettings.getDefaultPreviewDevice(): Device? =
  devices.find { device -> device.id == DEFAULT_DEVICE_ID } ?: defaultDevice

/**
 * Based on [deviceDefinition], returns a [Device] from the collection that matches the name or id,
 * if it's a custom spec, returns a created custom [Device].
 *
 * Note that if it's a custom spec, the dimensions will be converted to pixels to instantiate the
 * custom [Device].
 *
 * @see createDeviceInstance
 */
internal fun Collection<Device>.findOrParseFromDefinition(
  deviceDefinition: String,
  logger: Logger = Logger.getInstance(MutableDeviceConfig::class.java)
): Device? {
  return when {
    deviceDefinition.isBlank() -> null
    deviceDefinition.startsWith(DEVICE_BY_SPEC_PREFIX) -> {
      val deviceBySpec =
        DeviceConfig.toMutableDeviceConfigOrNull(deviceDefinition, this)?.createDeviceInstance()
      if (deviceBySpec == null) {
        logger.warn("Unable to parse device configuration: $deviceDefinition")
      }
      return deviceBySpec
    }
    else -> findByIdOrName(deviceDefinition, logger)
  }
}

internal fun Collection<Device>.findByIdOrName(
  deviceDefinition: String,
  logger: Logger = Logger.getInstance(MutableDeviceConfig::class.java)
): Device? {
  val availableDevices = this
  return when {
    deviceDefinition.isBlank() -> null
    deviceDefinition.startsWith(DEVICE_BY_ID_PREFIX) -> {
      val id = deviceDefinition.removePrefix(DEVICE_BY_ID_PREFIX)
      val deviceById = availableDevices.firstOrNull { it.id == id }
      if (deviceById == null) {
        logger.warn("Unable to find device with id '$id'")
      }
      return deviceById
    }
    deviceDefinition.startsWith(DEVICE_BY_NAME_PREFIX) -> {
      val name = deviceDefinition.removePrefix(DEVICE_BY_NAME_PREFIX)
      val deviceByName = availableDevices.firstOrNull { it.displayName == name }
      if (deviceByName == null) {
        logger.warn("Unable to find device with name '$name'")
      }
      return deviceByName
    }
    else -> {
      logger.warn("Unsupported device definition: $deviceDefinition")
      null
    }
  }
}
