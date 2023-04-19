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
package com.android.tools.idea.configurations

import com.android.tools.idea.model.AndroidModuleInfo
import com.android.tools.idea.rendering.ModuleDependencies
import com.android.tools.layoutlib.LayoutlibContext
import com.android.tools.res.ResourceRepositoryManager
import com.android.tools.sdk.AndroidPlatform
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * Provides all module specific resources required for configuration
 */
interface ConfigurationModelModule : Disposable {
  val androidPlatform: AndroidPlatform?

  val resourceRepositoryManager: ResourceRepositoryManager?

  val configurationStateManager: ConfigurationStateManager

  val themeInfoProvider: ThemeInfoProvider

  val layoutlibContext: LayoutlibContext

  val androidModuleInfo: AndroidModuleInfo?

  val project: Project

  val name: String

  val dependencies: ModuleDependencies
}