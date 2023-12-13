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
package com.android.tools.idea.lint.common

import com.android.tools.lint.detector.api.Location
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.intention.AddAnnotationFix
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils.prepareElementForWrite
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeParameter

class AnnotateQuickFix(
  project: Project,
  displayName: String?,
  familyName: String?,
  private val annotationSource: String,
  private val replace: Boolean,
  range: Location?
) : DefaultLintQuickFix(displayName ?: "Annotate", familyName) {
  private val rangePointer = LintIdeFixPerformer.getRangePointer(project, range)

  private fun findContainer(element: PsiElement): PsiElement? {
    return when (element.language) {
      JavaLanguage.INSTANCE -> findJavaAnnotationTarget(element)
      KotlinLanguage.INSTANCE -> findKotlinAnnotationTarget(element)
      else -> null
    }
  }

  override fun apply(
    element: PsiElement,
    endElement: PsiElement,
    context: AndroidQuickfixContexts.Context
  ) {
    val rangeFile = rangePointer?.element?.containingFile
    @Suppress("NAME_SHADOWING") var element: PsiElement = element

    // Make sure we don't try to use the endElement in any way (if we do,
    // we also need to look that up from the text range, similar to the startOffset
    // handling below)
    @Suppress("NAME_SHADOWING", "UNUSED_VARIABLE") val endElement: PsiElement? = null

    if (
      rangeFile != null &&
        !(rangeFile.containingFile != element.containingFile &&
          element.containingFile.originalFile == rangeFile.containingFile)
    ) {
      val range = rangePointer?.range
      val newStartElement = rangeFile.findElementAt(range!!.startOffset)
      if (newStartElement != null) {
        element = newStartElement
      }
    }

    val language = element.language
    val container = findContainer(element) ?: return

    if (!prepareElementForWrite(container)) {
      return
    }

    when (language) {
      JavaLanguage.INSTANCE -> {
        val owner = container as PsiModifierListOwner
        val project = element.project
        val factory = JavaPsiFacade.getInstance(project).elementFactory
        val newAnnotation = factory.createAnnotationFromText(annotationSource, element)
        val annotationName = newAnnotation.qualifiedName ?: return
        val annotation = AnnotationUtil.findAnnotation(owner, annotationName)
        if (annotation != null && annotation.isPhysical && replace) {
          annotation.replace(newAnnotation)
        } else {
          val attributes = newAnnotation.parameterList.attributes
          AddAnnotationFix(annotationName, container, attributes)
            .invoke(project, null, element.containingFile)
        }
      }
      KotlinLanguage.INSTANCE -> {
        if (container !is KtModifierListOwner) return
        val psiFactory = KtPsiFactory(container.project, markGenerated = true)
        val annotationEntry = psiFactory.createAnnotationEntry(annotationSource)
        val fqName =
          FqName(annotationSource.removePrefix("@").substringAfter(':').substringBefore('('))
        val existing = container.findAnnotation(fqName)
        val addedAnnotation =
          if (existing != null && existing.isPhysical && replace) {
            existing.replace(annotationEntry) as KtAnnotationEntry
          } else {
            container.addAnnotationEntry(annotationEntry)
          }
        ShortenReferencesFacility.getInstance().shorten(addedAnnotation)
      }
    }
  }

  override fun isApplicable(
    startElement: PsiElement,
    endElement: PsiElement,
    contextType: AndroidQuickfixContexts.ContextType
  ): Boolean {
    return findContainer(startElement) != null
  }
}

fun PsiElement.isAnnotationTarget(): Boolean {
  return this is KtClassOrObject ||
    (this is KtFunction && this !is KtFunctionLiteral) ||
    (this is KtProperty && !isLocal && hasBackingField()) ||
    this is KtPropertyAccessor
}

fun KtElement.isNewLineNeededForAnnotation(): Boolean {
  return !(this is KtParameter || this is KtTypeParameter || this is KtPropertyAccessor)
}

fun findJavaAnnotationTarget(element: PsiElement?): PsiModifierListOwner? {
  val modifier = PsiTreeUtil.getParentOfType(element, PsiModifierListOwner::class.java, false)
  return if (modifier is PsiClassInitializer || modifier is PsiAnonymousClass) {
    findJavaAnnotationTarget(modifier.parent)
  } else {
    modifier
  }
}

private fun findKotlinAnnotationTarget(element: PsiElement) =
  PsiTreeUtil.findFirstParent(element, false) { it.isAnnotationTarget() }
