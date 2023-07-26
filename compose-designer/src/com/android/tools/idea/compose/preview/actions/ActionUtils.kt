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

import com.android.tools.idea.common.actions.ActionButtonWithToolTipDescription
import com.android.tools.idea.common.surface.SceneView
import com.android.tools.idea.compose.preview.COMPOSE_PREVIEW_MANAGER
import com.android.tools.idea.compose.preview.PreviewMode
import com.android.tools.idea.compose.preview.PreviewModeManager
import com.android.tools.idea.compose.preview.findComposePreviewManagersForContext
import com.android.tools.idea.compose.preview.isAnyPreviewRefreshing
import com.android.tools.idea.uibuilder.scene.hasRenderErrors
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction

private class ComposePreviewNonInteractiveActionWrapper(actions: List<AnAction>) :
  DefaultActionGroup(actions) {
  override fun update(e: AnActionEvent) {
    super.update(e)

    e.getData(COMPOSE_PREVIEW_MANAGER)?.let { e.presentation.isVisible = it.isInNormalMode }
  }
}

// TODO(b/292057010) Enable group filtering for Gallery mode.
private class ComposePreviewDefaultWrapper(actions: List<AnAction>) : DefaultActionGroup(actions) {
  override fun update(e: AnActionEvent) {
    super.update(e)

    e.getData(COMPOSE_PREVIEW_MANAGER)?.let {
      e.presentation.isVisible = it.mode is PreviewMode.Default
    }
  }
}

/**
 * Helper method that sets the [PreviewMode] of all [PreviewModeManager]s in the given
 * [AnActionEvent]'s [DataContext] to [PreviewMode.Default].
 *
 * @param e the [AnActionEvent] holding the context of the action
 */
internal fun setDefaultPreviewMode(e: AnActionEvent) {
  findComposePreviewManagersForContext(e.dataContext).forEach { it.setMode(PreviewMode.Default) }
}

/**
 * Makes the given list of actions only visible when the Compose preview is not in interactive or
 * animation modes. Returns an [ActionGroup] that handles the visibility.
 */
internal fun List<AnAction>.visibleOnlyInComposeStaticPreview(): ActionGroup =
  ComposePreviewNonInteractiveActionWrapper(this)

/**
 * Makes the given action only visible when the Compose preview is not in interactive or animation
 * modes. Returns an [ActionGroup] that handles the visibility.
 */
internal fun AnAction.visibleOnlyInComposeStaticPreview(): ActionGroup =
  listOf(this).visibleOnlyInComposeStaticPreview()

/**
 * Makes the given action only visible when the Compose preview is not in interactive or animation
 * modes. Returns an [ActionGroup] that handles the visibility.
 */
internal fun AnAction.visibleOnlyInComposeDefaultPreview(): ActionGroup =
  ComposePreviewDefaultWrapper(listOf(this))

/**
 * The given disables the actions if any surface is refreshing or if the [sceneView] contains
 * errors.
 */
fun List<AnAction>.disabledIfRefreshingOrRenderErrors(sceneView: SceneView): List<AnAction> = map {
  EnableUnderConditionWrapper(it) { context ->
    !(isAnyPreviewRefreshing(context) || sceneView.hasRenderErrors())
  }
}

/** Hide the given actions if the [sceneView] contains render errors. */
fun List<AnAction>.hideIfRenderErrors(sceneView: SceneView): List<AnAction> = map {
  ShowUnderConditionWrapper(it) { !sceneView.hasRenderErrors() }
}

/**
 * Wrapper that delegates whether the given action is enabled or not to the passed condition. If
 * [isEnabled] returns true, the `delegate` action will be shown as disabled.
 */
private class EnableUnderConditionWrapper(
  delegate: AnAction,
  private val isEnabled: (context: DataContext) -> Boolean
) : AnActionWrapper(delegate), CustomComponentAction {

  override fun update(e: AnActionEvent) {
    super.update(e)
    val delegateEnabledStatus = e.presentation.isEnabled
    e.presentation.isEnabled = delegateEnabledStatus && isEnabled(e.dataContext)
  }

  override fun createCustomComponent(presentation: Presentation, place: String) =
    ActionButtonWithToolTipDescription(delegate, presentation, place)
}

/** Wrapper that delegates whether the given action is visible or not to the passed condition. */
private class ShowUnderConditionWrapper(delegate: AnAction, private val isVisible: () -> Boolean) :
  AnActionWrapper(delegate), CustomComponentAction {

  override fun update(e: AnActionEvent) {
    super.update(e)
    val curVisibleStatus = e.presentation.isVisible
    e.presentation.isVisible = curVisibleStatus && isVisible()
  }

  override fun createCustomComponent(presentation: Presentation, place: String) =
    ActionButtonWithToolTipDescription(delegate, presentation, place)
}
