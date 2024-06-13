/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.rendering

import com.android.resources.ResourceFolderType
import com.android.sdklib.AndroidVersion
import com.android.tools.configurations.Configuration
import com.android.tools.rendering.api.RenderModelModule

/**
 * Information required for rendering. Currently, this is [RenderModelModule] and [Configuration].
 */
class RenderContext(val module: RenderModelModule, val configuration: Configuration) {
  val minSdkVersion: AndroidVersion = module.info?.minSdkVersion ?: AndroidVersion.DEFAULT
  val targetSdkVersion: AndroidVersion = module.info?.targetSdkVersion ?: AndroidVersion.DEFAULT
  /**
   * Specifies the type of the resource if rendering is for resource file or null if it not for a
   * resource file.
   */
  var folderType: ResourceFolderType? = null
}
