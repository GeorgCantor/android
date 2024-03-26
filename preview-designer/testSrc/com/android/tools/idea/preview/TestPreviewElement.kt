/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.preview

import com.android.tools.preview.ConfigurablePreviewElement
import com.android.tools.preview.DisplayPositioning
import com.android.tools.preview.MethodPreviewElement
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer

internal class TestBasePreviewElement<T>(
  displayName: String = "",
  override val methodFqn: String = "TestMethod",
  groupName: String? = null,
  showDecorations: Boolean = false,
  showBackground: Boolean = false,
  backgroundColor: String? = null,
  displayPositioning: DisplayPositioning = DisplayPositioning.NORMAL,
) : MethodPreviewElement<T>, ConfigurablePreviewElement<T> {
  override val configuration = PreviewConfiguration.cleanAndGet()
  override val hasAnimations = false
  override val displaySettings =
    PreviewDisplaySettings(
      displayName,
      groupName,
      showDecorations,
      showBackground,
      backgroundColor,
      displayPositioning,
    )
  override val previewElementDefinition = null
  override val previewBody = null
}

internal typealias PsiTestPreviewElement =
  TestBasePreviewElement<SmartPsiElementPointer<PsiElement>>

internal typealias TestPreviewElement = TestBasePreviewElement<Unit>
