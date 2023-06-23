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
package com.android.tools.idea.run.deployment.liveedit

import com.android.tools.idea.editors.liveedit.LiveEditAdvancedConfiguration
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import junit.framework.Assert
import org.jetbrains.kotlin.idea.util.psi.patternMatching.matches
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

internal fun compile(file: PsiFile?, functionName: String) =
  compile(file!!, findFunction(file, functionName))

internal fun compile(file: PsiFile, function: KtNamedFunction) =
  compile(listOf(LiveEditCompilerInput(file, function)))

internal fun compile(inputs: List<LiveEditCompilerInput>) : LiveEditCompilerOutput {
  // The real Live Edit / Fast Preview has a retry system should the compilation got cancelled.
  // We are going to use a simplified version of that here and continue to try until
  // compilation succeeds.
  var output: LiveEditCompilerOutput? = null
  while (output == null) {
    output = LiveEditCompiler(inputs.first().file.project).compile(inputs).get().compilerOutput
  }
  return output
}

/**
 * Look for the first named function with a given name.
 */
internal fun findFunction(file: PsiFile?, name: String): KtNamedFunction {
  return findFirst(file) { it.name?.contains(name) ?: false }
}

internal inline fun <reified T : PsiElement> findFirst(file: PsiFile?, crossinline match: (T) -> Boolean) : T {
  return runReadAction {
    file!!.collectDescendantsOfType<T>().first { match(it) }
  }
}