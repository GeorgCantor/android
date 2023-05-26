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

import com.android.tools.idea.model.StudioAndroidModuleInfo
import com.android.tools.idea.rendering.StudioLayoutlibContext
import com.android.tools.idea.rendering.StudioModuleDependencies
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.layoutlib.LayoutlibContext
import com.android.tools.module.AndroidModuleInfo
import com.android.tools.module.ModuleDependencies
import com.android.tools.rendering.ModuleKey
import com.android.tools.rendering.ModuleKeyManager
import com.android.tools.res.ResourceRepositoryManager
import com.android.tools.sdk.AndroidPlatform
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.android.sdk.getInstance

/** Studio-specific [ConfigurationModelModule] constructed using Android module. */
class StudioConfigurationModelModule(val module: Module): ConfigurationModelModule {
  override val androidPlatform: AndroidPlatform?
    get() = getInstance(module)
  override val resourceRepositoryManager: ResourceRepositoryManager?
    get() = StudioResourceRepositoryManager.getInstance(module)
  override val configurationStateManager: ConfigurationStateManager
    get() = StudioConfigurationStateManager.get(module.project)
  override val themeInfoProvider: ThemeInfoProvider = StudioThemeInfoProvider(module)
  override val androidModuleInfo: AndroidModuleInfo? = StudioAndroidModuleInfo.getInstance(module)
  override val project: Project = module.project
  override val name: String = module.name
  override val layoutlibContext: LayoutlibContext = StudioLayoutlibContext(module.project)
  override val dependencies: ModuleDependencies = StudioModuleDependencies(module)

  override val moduleKey: ModuleKey
    get() = ModuleKeyManager.getKey(module)

  override fun dispose() {
  }
}