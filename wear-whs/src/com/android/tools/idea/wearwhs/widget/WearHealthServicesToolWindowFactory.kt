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
package com.android.tools.idea.wearwhs.widget

import com.android.tools.idea.adblib.AdbLibService
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.wearwhs.WearWhsBundle.message
import com.android.tools.idea.wearwhs.communication.ContentProviderDeviceManager
import com.android.tools.idea.wearwhs.view.WearHealthServicesToolWindow
import com.android.tools.idea.wearwhs.view.WearHealthServicesToolWindowStateManagerImpl
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Provides the Wear Health Services panel.
 */
class WearHealthServicesToolWindowFactory : DumbAware, ToolWindowFactory {

  override fun isApplicable(project: Project): Boolean {
    return StudioFlags.SYNTHETIC_HAL_PANEL.get()
  }

  override fun init(window: ToolWindow) {
    window.stripeTitle = message("wear.whs.panel.title")
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    toolWindow.displayWearHealthServices()
  }

  companion object {
    const val ID = "Wear Health Services"
  }
}

/**
 * Display Wear Health Services tool.
 */
private fun ToolWindow.displayWearHealthServices() {
  contentManager.removeAllContents(true)
  val deviceManager = ContentProviderDeviceManager(AdbLibService.getSession(project))
  val stateManager = WearHealthServicesToolWindowStateManagerImpl(deviceManager)
  val view = WearHealthServicesToolWindow(stateManager)

  Disposer.register(disposable, stateManager)
  Disposer.register(disposable, view)

  val content = contentManager.factory.createContent(view, null, true)
  contentManager.addContent(content)
}
