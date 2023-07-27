/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.actions

import com.android.tools.idea.compose.preview.COMPOSE_PREVIEW_ELEMENT_INSTANCE
import com.android.tools.idea.compose.preview.essentials.ComposePreviewEssentialsModeManager
import com.android.tools.idea.compose.preview.message
import com.android.tools.idea.preview.modes.PreviewMode
import com.android.tools.idea.preview.modes.PreviewModeManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.ui.AnActionButton
import icons.StudioIcons.Compose.Toolbar.INTERACTIVE_PREVIEW

/**
 * Action that controls when to enable the Interactive mode.
 *
 * @param dataContextProvider returns the [DataContext] containing the Compose Preview associated
 *   information.
 */
internal class EnableInteractiveAction(private val dataContextProvider: () -> DataContext) :
  AnActionButton(
    message("action.interactive.title"),
    message("action.interactive.description"),
    INTERACTIVE_PREVIEW
  ) {

  override fun updateButton(e: AnActionEvent) {
    super.updateButton(e)
    val isEssentialsModeEnabled = ComposePreviewEssentialsModeManager.isEssentialsModeEnabled
    e.presentation.isVisible = true
    e.presentation.isEnabled = !isEssentialsModeEnabled
    e.presentation.text = if (isEssentialsModeEnabled) null else message("action.interactive.title")
    e.presentation.description =
      if (isEssentialsModeEnabled) message("action.interactive.essentials.mode.description")
      else message("action.interactive.description")
  }

  override fun actionPerformed(e: AnActionEvent) {
    val modelDataContext = dataContextProvider()
    val manager = modelDataContext.getData(PreviewModeManager.KEY) ?: return
    val instanceId = modelDataContext.getData(COMPOSE_PREVIEW_ELEMENT_INSTANCE) ?: return

    manager.setMode(PreviewMode.Interactive(instanceId))
  }
}
