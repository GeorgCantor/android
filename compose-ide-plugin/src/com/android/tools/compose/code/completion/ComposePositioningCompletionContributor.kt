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
package com.android.tools.compose.code.completion

import com.android.tools.compose.COMPOSE_ALIGNMENT
import com.android.tools.compose.COMPOSE_ALIGNMENT_HORIZONTAL
import com.android.tools.compose.COMPOSE_ALIGNMENT_VERTICAL
import com.android.tools.compose.COMPOSE_ARRANGEMENT
import com.android.tools.compose.COMPOSE_ARRANGEMENT_HORIZONTAL
import com.android.tools.compose.COMPOSE_ARRANGEMENT_VERTICAL
import com.android.tools.compose.matchingParamTypeFqName
import com.android.tools.compose.isClassOrExtendsClass
import com.android.tools.compose.isComposeEnabled
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.DefaultLookupItemRenderer
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.contextOfType
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.idea.base.util.allScope
import org.jetbrains.kotlin.idea.caches.resolve.resolveImportReference
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.idea.util.ImportInsertHelper
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.nj2k.postProcessing.type
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Suggests and orders completion for the Alignment and Arrangement interfaces. Both interfaces have Horizontal, Vertical, and other
 * variants which the default auto-completion intermixes, even though only one subset is generally applicable in any given completion.
 */
class ComposePositioningCompletionContributor : CompletionContributor() {

  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val elementToComplete = parameters.position
    if (!isComposeEnabled(elementToComplete) || parameters.originalFile !is KtFile) {
      return
    }
    val elementToCompleteTypeFqName = elementToComplete.argumentTypeFqName ?: elementToComplete.propertyTypeFqName
    val project = elementToComplete.project
    val (elementsToSuggest, classForImport) = when (elementToCompleteTypeFqName) {
      COMPOSE_ALIGNMENT_HORIZONTAL -> Pair(getAlignments(project, COMPOSE_ALIGNMENT_HORIZONTAL), COMPOSE_ALIGNMENT)
      COMPOSE_ALIGNMENT_VERTICAL -> Pair(getAlignments(project, COMPOSE_ALIGNMENT_VERTICAL), COMPOSE_ALIGNMENT)
      COMPOSE_ARRANGEMENT_HORIZONTAL -> Pair(getArrangements(project, COMPOSE_ARRANGEMENT_HORIZONTAL), COMPOSE_ARRANGEMENT)
      COMPOSE_ARRANGEMENT_VERTICAL -> Pair(getArrangements(project, COMPOSE_ARRANGEMENT_VERTICAL), COMPOSE_ARRANGEMENT)
      else -> return
    }

    val isNewElement = elementToComplete.parentOfType<KtDotQualifiedExpression>() == null
    val lookupElements = elementsToSuggest.map {
      getStaticPropertyLookupElement(it, classForImport, elementToCompleteTypeFqName, isNewElement)
    }
    result.addAllElements(lookupElements)

    if (!isNewElement) {
      val addedElementsNames = elementsToSuggest.mapNotNull { it.name }
      result.runRemainingContributors(parameters) { completionResult ->
        val skipResult = (completionResult.lookupElement.psiElement as? KtProperty)?.name?.let { addedElementsNames.contains(it) }
        if (skipResult != true) {
          result.passResult(completionResult)
        }
      }
    }
  }

  private fun getKotlinClass(project: Project, classFqName: String): KtClassOrObject? {
    return KotlinFullClassNameIndex.getInstance()
      .get(classFqName, project, project.allScope())
      .firstOrNull()
  }

  private fun getAlignments(project: Project, alignmentFqName: String): Collection<KtDeclaration> {
    val alignmentClass = getKotlinClass(project, alignmentFqName) ?: return emptyList()
    return CachedValuesManager.getManager(project).getCachedValue(alignmentClass) {
      val alignmentTopLevelClass = getKotlinClass(project, COMPOSE_ALIGNMENT)!!
      val companionObject = alignmentTopLevelClass.companionObjects.firstOrNull()
      val alignments = companionObject?.declarations?.filter {
        it is KtProperty && it.type()?.isClassOrExtendsClass(alignmentFqName) == true
      }
      CachedValueProvider.Result.create(alignments, ProjectRootModificationTracker.getInstance(project))
    }
  }

  private fun getArrangements(project: Project, arrangementFqName: String): Collection<KtDeclaration> {
    val arrangementClass = getKotlinClass(project, arrangementFqName) ?: return emptyList()
    return CachedValuesManager.getManager(project).getCachedValue(arrangementClass) {
      val arrangementTopLevelClass = getKotlinClass(project, COMPOSE_ARRANGEMENT)!!
      val arrangements = arrangementTopLevelClass.declarations.filter {
        it is KtProperty && it.type()?.isClassOrExtendsClass(arrangementFqName) == true
      }
      CachedValueProvider.Result.create(arrangements, ProjectRootModificationTracker.getInstance(project))
    }
  }

  private fun getStaticPropertyLookupElement(psiElement: KtDeclaration, ktClassName: String, elementToCompleteTypeFqName: String, isNewElement: Boolean): LookupElement {
    val fqName = FqName(ktClassName)
    val mainLookupString = if (isNewElement) "${fqName.shortName()}.${psiElement.name}" else psiElement.name!!

    // For all the cases handled by this contributor, the classes involved are in the form
    // "longpackage.(Arrangement|Alignment).(Vertical|Horizontal)". We want to show those last two tokens to help the user understand what
    // they're choosing.
    val typeText = elementToCompleteTypeFqName.split('.').takeLast(2).joinToString(".")

    val builder = LookupElementBuilder
      .create(psiElement, mainLookupString)
      .withLookupString(psiElement.name!!)
      .bold()
      .withTailText(" (${ktClassName.substringBeforeLast('.')})", true)
      .withTypeText(typeText)
      .withInsertHandler lambda@{ context, item ->
        //Add import.
        val psiDocumentManager = PsiDocumentManager.getInstance(context.project)
        val ktFile = context.file as KtFile
        val modifierDescriptor = ktFile.resolveImportReference(fqName).singleOrNull() ?: return@lambda
        ImportInsertHelper.getInstance(context.project).importDescriptor(ktFile, modifierDescriptor)
        psiDocumentManager.commitAllDocuments()
        psiDocumentManager.doPostponedOperationsAndUnblockDocument(context.document)
      }

    return object : LookupElementDecorator<LookupElement>(builder) {
      override fun renderElement(presentation: LookupElementPresentation) {
        super.renderElement(presentation)
        presentation.icon = DefaultLookupItemRenderer.getRawIcon(builder)
      }
    }
  }

  private val PsiElement.propertyTypeFqName: String?
    get() {
      val property = contextOfType<KtProperty>() ?: return null
      return property.type()?.fqName?.asString()
    }

  private val PsiElement.argumentTypeFqName: String?
    get() {
      val argument = contextOfType<KtValueArgument>().takeIf { it !is KtLambdaArgument } ?: return null

      val callExpression = argument.parentOfType<KtCallElement>() ?: return null
      val callee = callExpression.calleeExpression?.mainReference?.resolve() as? KtNamedFunction ?: return null

      val argumentTypeFqName = argument.matchingParamTypeFqName(callee)

      return argumentTypeFqName?.asString()
    }

}