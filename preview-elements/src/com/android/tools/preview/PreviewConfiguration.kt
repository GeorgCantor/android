/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.preview

import com.android.ide.common.resources.Locale
import com.android.resources.Density
import com.android.sdklib.AndroidDpCoordinate
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.devices.Device
import com.android.tools.configurations.Configuration
import com.android.tools.configurations.Wallpaper
import com.android.tools.configurations.updateScreenSize
import com.android.tools.preview.config.findOrParseFromDefinition
import com.android.tools.sdk.CompatibilityRenderTarget
import org.jetbrains.annotations.TestOnly
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.util.function.Consumer
import kotlin.math.max

const val UNDEFINED_API_LEVEL = -1
const val UNDEFINED_DIMENSION = -1

const val MAX_WIDTH = 2000
const val MAX_HEIGHT = 2000

/** Value to use for the wallpaper attribute when none has been specified. */
private const val NO_WALLPAPER_SELECTED = -1

/** Empty device spec when the user has not specified any. */
private const val NO_DEVICE_SPEC = ""

/** Contains settings for rendering. */
data class PreviewConfiguration
internal constructor(
  val apiLevel: Int,
  val theme: String?,
  val width: Int,
  val height: Int,
  val locale: String,
  val fontScale: Float,
  val uiMode: Int,
  val deviceSpec: String,
  val wallpaper: Int,
  val imageTransformation: Consumer<BufferedImage>?,
) {
  companion object {
    /**
     * Cleans the given values and creates a PreviewConfiguration. The cleaning ensures that the
     * user inputted value are within reasonable values before the PreviewConfiguration is created
     */
    @JvmStatic
    fun cleanAndGet(
      apiLevel: Int? = null,
      theme: String? = null,
      width: Int? = null,
      height: Int? = null,
      locale: String? = null,
      fontScale: Float? = null,
      uiMode: Int? = null,
      device: String? = null,
      wallpaper: Int? = null,
      imageTransformation: Consumer<BufferedImage>? = null,
    ): PreviewConfiguration =
    // We only limit the sizes. We do not limit the API because using an incorrect API level will
    // throw an exception that
      // we will handle and any other error.
      PreviewConfiguration(
        apiLevel = apiLevel ?: UNDEFINED_API_LEVEL,
        theme = theme,
        width = width?.takeIf { it != UNDEFINED_DIMENSION }?.coerceIn(1, MAX_WIDTH)
                ?: UNDEFINED_DIMENSION,
        height = height?.takeIf { it != UNDEFINED_DIMENSION }?.coerceIn(1, MAX_HEIGHT)
                 ?: UNDEFINED_DIMENSION,
        locale = locale ?: "",
        fontScale = fontScale ?: 1f,
        uiMode = uiMode ?: 0,
        deviceSpec = device ?: NO_DEVICE_SPEC,
        wallpaper = wallpaper ?: NO_WALLPAPER_SELECTED,
        imageTransformation = imageTransformation,
      )
  }
}

/**
 * Applies the [PreviewConfiguration] to the given [Configuration].
 *
 * [highestApiTarget] should return the highest api target available for a given [Configuration].
 * [devicesProvider] should return all the devices available for a [Configuration].
 * [defaultDeviceProvider] should return which device to use for a [Configuration] if the device
 * specified in the [PreviewConfiguration.deviceSpec] is not available or does not exist in the
 * devices returned by [devicesProvider].
 *
 * If [customSize] is not null, the dimensions will be forced in the resulting configuration.
 */
internal fun PreviewConfiguration.applyTo(
  renderConfiguration: Configuration,
  highestApiTarget: (Configuration) -> IAndroidTarget?,
  devicesProvider: (Configuration) -> Collection<Device>,
  defaultDeviceProvider: (Configuration) -> Device?,
  @AndroidDpCoordinate customSize: Dimension? = null,
) {
  fun updateRenderConfigurationTargetIfChanged(newTarget: IAndroidTarget) {
    if (renderConfiguration.target?.hashString() != newTarget.hashString()) {
      renderConfiguration.target = newTarget
    }
  }

  renderConfiguration.startBulkEditing()
  renderConfiguration.imageTransformation = imageTransformation
  if (apiLevel != UNDEFINED_API_LEVEL) {
    val newTarget =
      renderConfiguration.settings.targets.firstOrNull { it.version.apiLevel == apiLevel }
    highestApiTarget(renderConfiguration)?.let {
      updateRenderConfigurationTargetIfChanged(CompatibilityRenderTarget(it, apiLevel, newTarget))
    }
  } else {
    // Use the highest available one when not defined.
    highestApiTarget(renderConfiguration)?.let { updateRenderConfigurationTargetIfChanged(it) }
  }

  if (theme != null) {
    renderConfiguration.setTheme(theme)
  }

  renderConfiguration.locale = Locale.create(locale)
  renderConfiguration.uiModeFlagValue = uiMode
  renderConfiguration.fontScale = max(0f, fontScale)
  renderConfiguration.setWallpaper(Wallpaper.values().getOrNull(wallpaper))

  val allDevices = devicesProvider(renderConfiguration)
  val device =
    allDevices.findOrParseFromDefinition(deviceSpec) ?: defaultDeviceProvider(renderConfiguration)
  if (device != null) {
    // Ensure the device is reset
    renderConfiguration.setEffectiveDevice(null, null)
    // If the user is not using the device frame, we never want to use the round frame around. See
    // b/215362733
    renderConfiguration.setDevice(device, false)
    // If there is no application theme set, we might need to change the theme when changing the
    // device, because different devices might
    // have different default themes.
    renderConfiguration.setTheme(renderConfiguration.getPreferredTheme())
  }

  customSize?.let {
    // When the device frame is not being displayed and the user has given us some specific sizes,
    // we want to apply those to the
    // device itself.
    // This is to match the intuition that those sizes always determine the size of the composable.
    renderConfiguration.device?.let { device ->
      // The PX are converted to DP by multiplying it by the dpiFactor that is the ratio of the
      // current dpi vs the default dpi (160).
      val dpiFactor = 1.0 * renderConfiguration.density.dpiValue / Density.DEFAULT_DENSITY
      renderConfiguration.updateScreenSize((it.width * dpiFactor).toInt(), (it.height * dpiFactor).toInt(), device)
    }
  }
  renderConfiguration.finishBulkEditing()
}

@TestOnly
fun PreviewConfiguration.applyConfigurationForTest(
  renderConfiguration: Configuration,
  highestApiTarget: (Configuration) -> IAndroidTarget?,
  devicesProvider: (Configuration) -> Collection<Device>,
  defaultDeviceProvider: (Configuration) -> Device?,
) {
  applyTo(renderConfiguration, highestApiTarget, devicesProvider, defaultDeviceProvider)
}
