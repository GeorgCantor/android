/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.intellij.psi.PsiFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

open class TestComposePreviewManager(
  initialInteractiveMode: ComposePreviewManager.InteractiveMode =
    ComposePreviewManager.InteractiveMode.DISABLED
) : ComposePreviewManager {

  var currentStatus =
    ComposePreviewManager.Status(
      hasRuntimeErrors = false,
      hasSyntaxErrors = false,
      isOutOfDate = false,
      areResourcesOutOfDate = false,
      isRefreshing = false,
      interactiveMode = initialInteractiveMode
    )
  override fun status(): ComposePreviewManager.Status = currentStatus

  override val availableGroupsFlow: StateFlow<Set<PreviewGroup.Named>> =
    MutableStateFlow(emptySet())
  override val allPreviewElementsInFileFlow: StateFlow<Collection<ComposePreviewElementInstance>> =
    MutableStateFlow(emptySet())
  override var groupFilter: PreviewGroup = PreviewGroup.All
  override val hasDesignInfoProviders: Boolean = false
  override val previewedFile: PsiFile? = null

  override fun invalidate() {}

  override var isInspectionTooltipEnabled: Boolean = false

  override var isFilterEnabled: Boolean = false

  override var atfChecksEnabled: Boolean = false

  override var mode: PreviewMode = PreviewMode.Default
  override fun setMode(newMode: PreviewMode.Settable) {
    mode = newMode
  }

  override fun dispose() {}
}
