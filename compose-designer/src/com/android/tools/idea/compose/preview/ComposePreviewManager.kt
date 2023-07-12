/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.compose.preview

import com.intellij.openapi.Disposable
import com.intellij.psi.PsiFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.annotations.ApiStatus

/** Interface that provides access to the Compose Preview logic. */
interface ComposePreviewManager : Disposable, PreviewModeManager {
  /**
   * Enum that determines the current status of the interactive preview.
   *
   * The transitions are like:
   *
   * DISABLED -> STARTED -> READY -> STOPPING
   *
   * ```
   *    ^                               +
   *    |                               |
   *    +-------------------------------+
   * ```
   */
  enum class InteractiveMode {
    DISABLED,
    /** Status when interactive has been started but the first render has not happened yet. */
    STARTING,
    /** Interactive is ready and running. */
    READY,
    /** The interactive preview is stopping but it has not been fully disposed yet. */
    STOPPING;

    fun isStartingOrReady() = this == STARTING || this == READY
    fun isStoppingOrDisabled() = this == STOPPING || this == DISABLED
    fun isStartingOrStopping() = this == STARTING || this == STOPPING
  }
  /**
   * Status of the preview.
   *
   * @param hasRuntimeErrors true if the project has any runtime errors that prevent the preview
   *   being up to date. For example missing classes.
   * @param hasSyntaxErrors true if the preview is displaying content of a file that has syntax
   *   errors.
   * @param isOutOfDate true if the preview needs a refresh to be up to date.
   * @param areResourcesOutOfDate true if the preview needs a build to be up to date because
   *   resources are out of date.
   * @param isRefreshing true if the view is currently refreshing.
   * @param interactiveMode represents current state of preview interactivity.
   */
  data class Status(
    val hasRuntimeErrors: Boolean,
    val hasSyntaxErrors: Boolean,
    val isOutOfDate: Boolean,
    val areResourcesOutOfDate: Boolean,
    val isRefreshing: Boolean,
    val interactiveMode: InteractiveMode
  ) {
    /** True if the preview has errors that will need a refresh */
    val hasErrors = hasRuntimeErrors || hasSyntaxErrors
  }

  fun status(): Status

  /**
   * [StateFlow] of available named groups in this preview. The editor can contain multiple groups
   * and only one will be displayed at a given time.
   */
  val availableGroupsFlow: StateFlow<Set<PreviewGroup.Named>>

  /** [StateFlow] of available elements in this preview with no filters applied. */
  val allPreviewElementsInFileFlow: StateFlow<Collection<ComposePreviewElementInstance>>

  /**
   * Currently selected group from [availableGroupsFlow] or [PreviewGroup.All] if none is selected.
   */
  var groupFilter: PreviewGroup

  /**
   * When true, the ComposeViewAdapter will search for Composables that can return a DesignInfo
   * object.
   */
  val hasDesignInfoProviders: Boolean

  /**
   * The [PsiFile] that this preview is representing, if any. For cases where the preview is
   * rendering synthetic previews or elements from multiple files, this can be null.
   */
  val previewedFile: PsiFile?

  /** Flag to indicate the inspection tooltips is enabled. */
  var isInspectionTooltipEnabled: Boolean

  /** Flag to indicate if the preview filter is enabled or not. */
  var isFilterEnabled: Boolean

  /** Flag to indicate whether ATF checks should be run on the preview. */
  val atfChecksEnabled: Boolean
    get() = (currentOrNextMode as? PreviewMode.UiCheck)?.atfChecksEnabled ?: false

  /** Flag to indicate whether Visual Lint checks should be run on the preview. */
  val visualLintingEnabled: Boolean
    get() = (currentOrNextMode as? PreviewMode.UiCheck)?.visualLintingEnabled ?: false

  /**
   * Indicates whether the preview is in its default mode by opposition to one of the special modes
   * (interactive, animation, UI check, essential).
   */
  val isInNormalMode: Boolean
    get() = mode is PreviewMode.Default

  val isUiCheckPreview: Boolean
    get() = mode is PreviewMode.UiCheck

  /**
   * Invalidates the cached preview status. This ensures that the @Preview annotations lookup
   * happens again to find any possible new annotations.
   */
  fun invalidate()
}

class NopComposePreviewManager : ComposePreviewManager {
  override fun status() =
    ComposePreviewManager.Status(
      hasRuntimeErrors = false,
      hasSyntaxErrors = false,
      isOutOfDate = false,
      areResourcesOutOfDate = false,
      isRefreshing = false,
      ComposePreviewManager.InteractiveMode.DISABLED
    )

  override val availableGroupsFlow: StateFlow<Set<PreviewGroup.Named>> =
    MutableStateFlow(emptySet())
  override val allPreviewElementsInFileFlow: StateFlow<Collection<ComposePreviewElementInstance>> =
    MutableStateFlow(emptySet())
  override var groupFilter: PreviewGroup = PreviewGroup.All
  override val hasDesignInfoProviders: Boolean = false
  override val previewedFile: PsiFile? = null
  override var isInspectionTooltipEnabled: Boolean = false
  override var isFilterEnabled: Boolean = false
  override var mode: PreviewMode = PreviewMode.Default
  override fun setMode(newMode: PreviewMode.Settable) {
    mode = newMode
  }

  override fun invalidate() {}
  override fun dispose() {}
}

/**
 * Interface that provides access to the Compose Preview logic that is not stable or meant for
 * public use. This interface contains only temporary or experimental methods.
 */
@ApiStatus.Experimental
interface ComposePreviewManagerEx : ComposePreviewManager {
  /**
   * If enabled, the bounds for the different `@Composable` elements will be displayed in the
   * surface.
   */
  var showDebugBoundaries: Boolean
}
