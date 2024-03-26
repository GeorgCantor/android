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
package com.android.tools.idea.glance.preview

import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.modes.essentials.EssentialsMode
import com.android.tools.idea.preview.PreviewElementModelAdapter
import com.android.tools.idea.preview.PreviewElementProvider
import com.android.tools.idea.preview.representation.CommonPreviewRepresentation
import com.android.tools.idea.preview.views.CommonNlDesignSurfacePreviewView
import com.android.tools.idea.uibuilder.editor.multirepresentation.PreviewRepresentation
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.idea.uibuilder.surface.NlSupportedActions
import com.android.tools.preview.PreviewElement
import com.android.tools.rendering.RenderAsyncActionExecutor
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer

private val GLANCE_APPWIDGET_SUPPORTED_ACTIONS = setOf(NlSupportedActions.TOGGLE_ISSUE_PANEL)

/** A [PreviewRepresentation] for glance [PreviewElement]s */
internal class GlancePreviewRepresentation(
  adapterViewFqcn: String,
  psiFile: PsiFile,
  previewProviderConstructor:
    (SmartPsiElementPointer<PsiFile>) -> PreviewElementProvider<PsiGlancePreviewElement>,
  previewElementModelAdapterDelegate: PreviewElementModelAdapter<PsiGlancePreviewElement, NlModel>,
) :
  CommonPreviewRepresentation<PsiGlancePreviewElement>(
    adapterViewFqcn,
    psiFile,
    previewProviderConstructor,
    previewElementModelAdapterDelegate,
    ::CommonNlDesignSurfacePreviewView,
    ::GlancePreviewViewModel,
    NlDesignSurface.Builder::configureDesignSurface,
    renderingTopic = RenderAsyncActionExecutor.RenderingTopic.GLANCE_PREVIEW,
    useCustomInflater = false,
    isEssentialsModeEnabled = EssentialsMode::isEnabled,
  )

private fun NlDesignSurface.Builder.configureDesignSurface() {
  setActionManagerProvider(::GlancePreviewActionManager)
  setSupportedActions(GLANCE_APPWIDGET_SUPPORTED_ACTIONS)
  setScreenViewProvider(GLANCE_SCREEN_VIEW_PROVIDER, false)
}
