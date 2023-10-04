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
package com.android.tools.compose.code.state

import com.android.tools.compose.ComposeBundle
import com.android.tools.compose.composableScope
import com.android.tools.idea.flags.StudioFlags
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import icons.StudioIcons
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter

const val COMPOSE_STATE_READ_TEXT_ATTRIBUTES_NAME = "ComposeStateReadTextAttributes"

val COMPOSE_STATE_READ_TEXT_ATTRIBUTES_KEY: TextAttributesKey =
  TextAttributesKey.createTextAttributesKey(
    COMPOSE_STATE_READ_TEXT_ATTRIBUTES_NAME,
    DefaultLanguageHighlighterColors.FUNCTION_CALL
  )

/**
 * Annotator that highlights reads of `androidx.compose.runtime.State` variables inside
 * `@Composable` functions.
 *
 * TODO(b/225218822): Before productionizing this, depending on whether we want a gutter icon,
 *   highlighting, or both, we must change this to use `KotlinHighlightingVisitorExtension` (to
 *   avoid race conditions), or use a `RelatedItemLineMarkerProvider` for the gutter icon so it can
 *   be disabled with a setting.
 */
class ComposeStateReadAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (!StudioFlags.COMPOSE_STATE_READ_HIGHLIGHTING_ENABLED.get()) return
    if (element !is KtNameReferenceExpression) return
    val scopeName =
      when (val scope = element.composableScope()) {
        is KtParameter ->
          ComposeBundle.message("compose.state.read.recompose.target.enclosing.lambda")
        else -> scope?.name ?: return
      }
    element.getStateReadElement()?.let {
      holder
        .newAnnotation(HighlightSeverity.INFORMATION, createMessage(it.text, scopeName))
        .textAttributes(COMPOSE_STATE_READ_TEXT_ATTRIBUTES_KEY)
        .gutterIconRenderer(ComposeStateReadGutterIconRenderer(it.text, scopeName))
        .create()
    }
  }

  private data class ComposeStateReadGutterIconRenderer(
    private val stateName: String,
    private val functionName: String
  ) : GutterIconRenderer() {
    override fun getIcon() = StudioIcons.Common.INFO

    override fun getTooltipText() = createMessage(stateName, functionName)
  }
}
