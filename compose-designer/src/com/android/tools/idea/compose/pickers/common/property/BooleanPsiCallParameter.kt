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
package com.android.tools.idea.compose.pickers.common.property

import com.android.tools.idea.compose.pickers.base.model.PsiCallPropertiesModel
import com.android.tools.idea.compose.pickers.base.property.PsiCallParameterPropertyItem
import com.android.tools.idea.compose.pickers.common.editingsupport.BooleanValidator
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument

/** A [PsiCallParameterPropertyItem] for three state Boolean parameters. */
internal class BooleanPsiCallParameter(
  project: Project,
  model: PsiCallPropertiesModel,
  addNewArgumentToResolvedCall: (KtValueArgument, KtPsiFactory) -> KtValueArgument?,
  parameterName: Name,
  parameterTypeNameIfStandard: Name?,
  argumentExpression: KtExpression?,
  initialValue: String?,
) :
  PsiCallParameterPropertyItem(
    project,
    model,
    addNewArgumentToResolvedCall,
    parameterName,
    parameterTypeNameIfStandard,
    argumentExpression,
    initialValue,
    BooleanValidator,
  )
