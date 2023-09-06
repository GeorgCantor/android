/*
 * Copyright (C) 2016 The Android Open Source Project
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
package org.jetbrains.android.refactoring

import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.actions.BaseRefactoringAction
import org.jetbrains.android.util.AndroidUtils

/**
 * Deletes unused resources, if any.
 *
 *
 * Possible improvements:
 *
 *  *  If resource declarations are preceded by comments, remove those too?
 *  *  Do textual scans of source sets for other variants to make sure this doesn't
 * remove unused resources referenced in other variants
 *  *  Unused resources corresponding to Gradle model resValues don't have corresponding
 * source locations, so these are currently not removed.
 *
 */
class UnusedResourcesAction : BaseRefactoringAction() {
  override fun update(e: AnActionEvent) {
    val project = e.getData(CommonDataKeys.PROJECT)
    e.presentation.setEnabledAndVisible(project != null && AndroidUtils.hasAndroidFacets(project))
  }

  override fun isEnabledOnDataContext(dataContext: DataContext): Boolean {
    return true
  }

  public override fun isAvailableInEditorOnly(): Boolean {
    return false
  }

  override fun isAvailableForLanguage(language: Language): Boolean {
    return true
  }

  public override fun isEnabledOnElements(elements: Array<PsiElement>): Boolean {
    return true
  }

  public override fun getHandler(dataContext: DataContext): RefactoringActionHandler? {
    return UnusedResourcesHandler()
  }
}
