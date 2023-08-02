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
package com.android.tools.idea.wear.preview

import com.android.tools.idea.common.editor.ActionManager
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.modes.essentials.EssentialsMode
import com.android.tools.idea.preview.actions.EnableInteractiveAction
import com.android.tools.idea.preview.actions.PreviewStatusIcon
import com.android.tools.idea.preview.actions.createStatusIcon
import com.android.tools.idea.preview.actions.disabledIf
import com.android.tools.idea.preview.actions.hideIfRenderErrors
import com.android.tools.idea.preview.actions.isPreviewRefreshing
import com.android.tools.idea.preview.actions.visibleOnlyInStaticPreview
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.scene.hasRenderErrors
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/** Wear tile specific [ActionManager] for the [DesignSurface]. */
internal class WearTilePreviewActionManager(
  private val surface: DesignSurface<LayoutlibSceneManager>
) : ActionManager<DesignSurface<LayoutlibSceneManager>>(surface) {
  override fun registerActionsShortcuts(component: JComponent) {}

  override fun getPopupMenuActions(leafComponent: NlComponent?) = DefaultActionGroup()

  override fun getToolbarActions(selection: MutableList<NlComponent>) = DefaultActionGroup()

  override fun getSceneViewStatusIcon(sceneView: SceneView) =
    createStatusIcon(PreviewStatusIcon(sceneView), surface)

  override fun getSceneViewContextToolbar(sceneView: SceneView) =
    ActionManagerEx.getInstanceEx()
      .createActionToolbar(
        "sceneView",
        DefaultActionGroup(
          listOf(Separator()) +
            listOfNotNull(
                EnableInteractiveAction(isEssentialsModeEnabled = EssentialsMode::isEnabled) {
                  sceneView.scene.sceneManager.model.dataContext
                },
              )
              .disabledIf { context -> isPreviewRefreshing(context) || sceneView.hasRenderErrors() }
              .hideIfRenderErrors(sceneView)
              .visibleOnlyInStaticPreview()
        ),
        horizontal = true,
        decorateButtons = false
      )
      .apply {
        // Do not allocate space for the "see more" chevron if not needed
        setReservePlaceAutoPopupIcon(false)
        setShowSeparatorTitles(true)
        targetComponent = surface
      }
      .component
      .apply {
        isOpaque = false
        border = JBUI.Borders.empty()
      }
}
