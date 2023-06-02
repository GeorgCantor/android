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
package com.android.tools.rendering.api

import com.android.ide.common.resources.Locale
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.devices.Device
import com.android.tools.configurations.AdaptiveIconShape

/** Facade on decoupling rendering from [com.android.tools.configurations.Configuration]. */
interface RenderConfiguration {
  val activity: String?

  val resourceResolver: ResourceResolver

  val target: IAndroidTarget?

  val realTarget: IAndroidTarget?

  val device: Device?

  val fullConfig: FolderConfiguration

  val locale: Locale

  val adaptiveShape: AdaptiveIconShape

  val useThemedIcon: Boolean

  val wallpaperPath: String?

  val fontScale: Float

  val uiModeFlagValue: Int
}