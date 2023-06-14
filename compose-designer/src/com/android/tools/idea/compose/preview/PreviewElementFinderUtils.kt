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
package com.android.tools.idea.compose.preview

import com.android.tools.compose.COMPOSABLE_ANNOTATION_FQ_NAME
import com.android.tools.compose.COMPOSE_PREVIEW_ANNOTATION_FQN
import com.android.tools.compose.COMPOSE_PREVIEW_ANNOTATION_NAME
import com.android.tools.compose.COMPOSE_PREVIEW_PARAMETER_ANNOTATION_FQN
import com.android.tools.idea.annotations.getContainingUMethodAnnotatedWith
import com.android.tools.idea.annotations.getUAnnotations
import com.android.tools.idea.annotations.isAnnotatedWith
import com.android.tools.idea.compose.preview.analytics.MultiPreviewNode
import com.android.tools.idea.compose.preview.analytics.MultiPreviewNodeImpl
import com.android.tools.idea.compose.preview.analytics.MultiPreviewNodeInfo
import com.android.tools.idea.compose.preview.util.toSmartPsiPointer
import com.android.tools.idea.kotlin.getQualifiedName
import com.android.tools.idea.preview.PreviewDisplaySettings
import com.android.tools.idea.preview.PreviewNode
import com.google.wireless.android.sdk.stats.ComposeMultiPreviewEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.impl.compiled.ClsClassImpl
import com.intellij.psi.impl.compiled.ClsMethodImpl
import com.intellij.util.containers.sequenceOfNotNull
import com.intellij.util.text.nullize
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.tryResolve

/**
 * In Multipreview, every annotation is traversed in the DFS for finding Previews. This list is used
 * as an optimization to avoid traversing annotations which fqcn starts with any of these prefixes,
 * as those annotations will never lead to a Preview.
 */
private val NON_MULTIPREVIEW_PREFIXES =
  listOf(
    "android.",
    "kotlin.",
    "kotlinx.",
    "java.",
  )

/**
 * Returns true if the MultiPreview flag is enabled and one of the following is true:
 * 1. This annotation's class is defined in androidx (i.e. its fqcn starts with 'androidx.'), and it
 *    contains 'preview' as one of its subpackages (e.g. 'package androidx.example.preview' or
 *    'package androidx.preview.example')
 * 2. This annotation's fqcn doesn't start with 'androidx.' nor with any of the prefixes in
 *    [NON_MULTIPREVIEW_PREFIXES].
 */
private fun UAnnotation.couldBeMultiPreviewAnnotation(): Boolean {
  return (this.tryResolve() as? PsiClass)?.qualifiedName?.let { fqcn ->
    if (fqcn.startsWith("androidx.")) fqcn.contains(".preview.")
    else NON_MULTIPREVIEW_PREFIXES.none { fqcn.startsWith(it) }
  } == true
}

/** Returns true if the [KtAnnotationEntry] is a `@Preview` annotation. */
internal fun KtAnnotationEntry.isPreviewAnnotation() =
  ReadAction.compute<Boolean, Throwable> {
    // getQualifiedName is fairly expensive, so we check first that short name matches before
    // calling it.
    shortName?.identifier == COMPOSE_PREVIEW_ANNOTATION_NAME &&
      COMPOSE_PREVIEW_ANNOTATION_FQN == getQualifiedName()
  }

/** Returns true if the [UAnnotation] is a `@Preview` annotation. */
internal fun UAnnotation.isPreviewAnnotation() =
  ReadAction.compute<Boolean, Throwable> { COMPOSE_PREVIEW_ANNOTATION_FQN == qualifiedName }

/**
 * Returns true if the [uMethod] is annotated with a @Preview annotation, taking in consideration
 * indirect annotations with multipreview when the flag is enabled
 */
internal fun UMethod?.hasPreviewElements() =
  this?.let { getPreviewElements(it).firstOrNull() } != null

/**
 * Returns true if this is not a Preview annotation, but a MultiPreview annotation, i.e. an
 * annotation that is annotated with @Preview or with other MultiPreview.
 */
fun UAnnotation?.isMultiPreviewAnnotation() =
  this?.let {
    !it.isPreviewAnnotation() && it.getPreviewNodes(includeAllNodes = false).firstOrNull() != null
  } == true

/**
 * Given a Composable method, return a sequence of [ComposePreviewElement] corresponding to its
 * Preview annotations
 */
internal fun getPreviewElements(uMethod: UMethod, overrideGroupName: String? = null) =
  getPreviewNodes(uMethod, overrideGroupName, false).mapNotNull { it as? ComposePreviewElement }

/**
 * Given a Composable method, return a sequence of [PreviewNode] that are part of the method's
 * MultiPreview graph. Notes:
 * - The leaf nodes that correspond to Preview annotations will be not just a [PreviewNode], but
 *   specifically a [ComposePreviewElement].
 * - When [includeAllNodes] is true, the returned sequence will also include nodes corresponding to
 *   the MultiPreview annotations and the root composable [uMethod]. These nodes, will be not just a
 *   [PreviewNode], but specifically a [MultiPreviewNode]
 */
fun getPreviewNodes(uMethod: UMethod, overrideGroupName: String? = null, includeAllNodes: Boolean) =
  runReadAction {
    if (uMethod.isComposable()) {
      val visitedAnnotationClasses = mutableMapOf<String, MultiPreviewNodeInfo?>()

      sequence {
          val nDirectPreviews = uMethod.uAnnotations.count { it.isPreviewAnnotation() }
          val nonPreviewTraversedChildrenFqcn = mutableListOf<String?>()
          // First, traverse over the whole MultiPreview graph for this Composable
          yield(
            uMethod.uAnnotations.asSequence().flatMap {
              if (it.shouldTraverse(visitedAnnotationClasses) && !it.isPreviewAnnotation()) {
                nonPreviewTraversedChildrenFqcn.add((it.tryResolve() as? PsiClass)?.qualifiedName)
              }
              it.getPreviewNodes(
                visitedAnnotationClasses,
                uMethod,
                it,
                overrideGroupName,
                includeAllNodes
              )
            }
          )
          // Then, add this root composable node if wanted
          yield(
            if (includeAllNodes) {
              // Set the corresponding MultiPreviewNodeInfo
              val node =
                MultiPreviewNodeImpl(
                  MultiPreviewNodeInfo(
                      ComposeMultiPreviewEvent.ComposeMultiPreviewNodeInfo.NodeType
                        .ROOT_COMPOSABLE_FUNCTION_NODE
                    )
                    .withChildNodes(
                      nonPreviewTraversedChildrenFqcn.filterNotNull().map {
                        visitedAnnotationClasses[it]
                      },
                      nDirectPreviews
                    )
                    .withDepthLevel(0)
                    .withComposableFqn(uMethod.qualifiedName)
                )
              sequenceOf(node)
            } else emptySequence()
          )
        }
        .flatten()
    } else emptySequence() // for non-composable methods, return an empty sequence
}

private fun UAnnotation.getPreviewNodes(
  visitedAnnotationClasses: MutableMap<String, MultiPreviewNodeInfo?> = mutableMapOf(),
  uMethod: UMethod? = getContainingComposableUMethod(),
  rootAnnotation: UAnnotation = this,
  overrideGroupName: String? = null,
  includeAllNodes: Boolean,
  depthLevel: Int = 1,
  parentAnnotationInfo: String? = null
): Sequence<PreviewNode> = runReadAction {
  // MultiPreview nodes are always associated with a composable method
  if (!uMethod.isComposable() || !this.shouldTraverse(visitedAnnotationClasses))
    return@runReadAction emptySequence()

  // Preview annotations are leaf nodes, just return the corresponding PreviewElement
  if (this.isPreviewAnnotation()) {
    return@runReadAction sequenceOfNotNull(
      this.toPreviewElement(uMethod, rootAnnotation, overrideGroupName, parentAnnotationInfo)
    )
  }

  val annotationClassFqcn = (this.tryResolve() as PsiClass).qualifiedName!!
  visitedAnnotationClasses[annotationClassFqcn] =
    null // The MultiPreviewNodeInfo will be set later if needed
  val curAnnotationName = (this.tryResolve() as PsiClass).name
  val annotations = this.getUAnnotations()

  val nDirectPreviews = annotations.count { it.isPreviewAnnotation() }
  var nxtDirectPreviewId = 1
  val nonPreviewTraversedChildrenFqcn = mutableListOf<String?>()

  sequence {
      // First, traverse over my children
      yield(
        annotations.asSequence().flatMap {
          if (it.isPreviewAnnotation()) {
            it.getPreviewNodes(
              visitedAnnotationClasses,
              uMethod,
              rootAnnotation,
              overrideGroupName,
              includeAllNodes,
              depthLevel + 1,
              buildParentAnnotationInfo(curAnnotationName, nxtDirectPreviewId++, nDirectPreviews)
            )
          } else if (it.shouldTraverse(visitedAnnotationClasses)) {
            nonPreviewTraversedChildrenFqcn.add((it.tryResolve() as? PsiClass)?.qualifiedName)
            it.getPreviewNodes(
              visitedAnnotationClasses,
              uMethod,
              rootAnnotation,
              overrideGroupName,
              includeAllNodes,
              depthLevel + 1
            )
          } else emptySequence()
        }
      )

      // Then, add this non-preview node if wanted
      yield(
        if (includeAllNodes) {
          // Set the corresponding MultiPreviewNodeInfo
          val node =
            MultiPreviewNodeImpl(
              MultiPreviewNodeInfo(
                  ComposeMultiPreviewEvent.ComposeMultiPreviewNodeInfo.NodeType.MULTIPREVIEW_NODE
                )
                .withChildNodes(
                  nonPreviewTraversedChildrenFqcn.filterNotNull().map {
                    visitedAnnotationClasses[it]
                  },
                  nDirectPreviews
                )
                .withDepthLevel(depthLevel)
                .withComposableFqn(uMethod!!.qualifiedName)
            )
          visitedAnnotationClasses[annotationClassFqcn] = node.nodeInfo
          sequenceOf(node)
        } else emptySequence()
      )
    }
    .flatten()
}

/**
 * Returns true when [this] annotation is @Preview, or when it is a potential MultiPreview that
 * hasn't been traversed yet according to the data in [visitedAnnotationClasses].
 */
private fun UAnnotation.shouldTraverse(
  visitedAnnotationClasses: MutableMap<String, MultiPreviewNodeInfo?>
): Boolean {
  if (!this.isPsiValid) return false
  val annotationClassFqcn = (this.tryResolve() as? PsiClass)?.qualifiedName
  return this.isPreviewAnnotation() ||
    (this.couldBeMultiPreviewAnnotation() &&
      annotationClassFqcn != null &&
      !visitedAnnotationClasses.contains(annotationClassFqcn))
}

private fun buildParentAnnotationInfo(name: String?, id: Int, maxRelatedId: Int) =
  "$name ${id.toString().padStart(maxRelatedId.toString().length, '0')}"

/**
 * Converts the [UAnnotation] to a [ComposePreviewElement] if the annotation is a `@Preview`
 * annotation or returns null if it's not.
 */
internal fun UAnnotation.toPreviewElement(
  uMethod: UMethod? = getContainingComposableUMethod(),
  rootAnnotation: UAnnotation = this,
  overrideGroupName: String? = null,
  parentAnnotationInfo: String? = null
) = runReadAction {
  if (this.isPreviewAnnotation()) {
    uMethod?.let {
      previewAnnotationToPreviewElement(
        this,
        it,
        rootAnnotation,
        overrideGroupName,
        parentAnnotationInfo
      )
    }
  } else null
}

/**
 * Returns the Composable [UMethod] annotated by this annotation, or null if it is not annotating a
 * method, or if the method is not also annotated with @Composable
 */
internal fun UAnnotation.getContainingComposableUMethod() =
  this.getContainingUMethodAnnotatedWith(COMPOSABLE_ANNOTATION_FQ_NAME)

/** Returns true when the UMethod is not null, and it is annotated with @Composable */
private fun UMethod?.isComposable() = this.isAnnotatedWith(COMPOSABLE_ANNOTATION_FQ_NAME)

internal fun UAnnotation.findPreviewDefaultValues(): Map<String, String?> =
  when (val resolvedImplementation = this.resolve()) {
    is ClsClassImpl ->
      resolvedImplementation.methods
        .map { psiMethod ->
          Pair(
            psiMethod.name,
            (psiMethod as ClsMethodImpl).defaultValue?.text?.trim('"')?.nullize()
          )
        }
        .toMap()
    is KtLightClass ->
      resolvedImplementation.methods
        .map { psiMethod ->
          Pair(
            psiMethod.name,
            (psiMethod as KtLightMethod).defaultValue?.text?.trim('"')?.nullize()
          )
        }
        .toMap()
    else -> mapOf()
  }

private fun UAnnotation.findClassNameValue(name: String) =
  (findAttributeValue(name) as? UClassLiteralExpression)?.type?.canonicalText

/**
 * Reads the `@Preview` annotation parameters and returns a [PreviewConfiguration] containing the
 * values.
 */
private fun attributesToConfiguration(
  node: UAnnotation,
  defaultValues: Map<String, String?>
): PreviewConfiguration {
  val apiLevel = node.getIntAttribute(PARAMETER_API_LEVEL, defaultValues)
  val theme = node.getStringAttribute(PARAMETER_THEME, defaultValues)
  // Both width and height have to support old ("width") and new ("widthDp") conventions
  val width =
    node.getIntAttribute(PARAMETER_WIDTH, defaultValues)
      ?: node.getIntAttribute(PARAMETER_WIDTH_DP, defaultValues)
  val height =
    node.getIntAttribute(PARAMETER_HEIGHT, defaultValues)
      ?: node.getIntAttribute(PARAMETER_HEIGHT_DP, defaultValues)
  val fontScale = node.getFloatAttribute(PARAMETER_FONT_SCALE, defaultValues)
  val uiMode = node.getIntAttribute(PARAMETER_UI_MODE, defaultValues)
  val device = node.getStringAttribute(PARAMETER_DEVICE, defaultValues)
  val locale = node.getStringAttribute(PARAMETER_LOCALE, defaultValues)
  val wallpaper = node.getIntAttribute(PARAMETER_WALLPAPER, defaultValues)

  return PreviewConfiguration.cleanAndGet(
    apiLevel,
    theme,
    width,
    height,
    locale,
    fontScale,
    uiMode,
    device,
    wallpaper,
  )
}

private val UMethod.qualifiedName: String
  get() = "${(this.uastParent as UClass).qualifiedName}.${this.name}"

/** Converts the given [previewAnnotation] to a [ComposePreviewElement]. */
private fun previewAnnotationToPreviewElement(
  previewAnnotation: UAnnotation,
  annotatedMethod: UMethod,
  rootAnnotation: UAnnotation,
  overrideGroupName: String? = null,
  parentAnnotationInfo: String? = null
): ComposePreviewElement? {
  fun getPreviewName(nameParameter: String?) =
    when {
      nameParameter != null -> "${annotatedMethod.name} - $nameParameter"
      parentAnnotationInfo != null -> "${annotatedMethod.name} - $parentAnnotationInfo"
      else -> annotatedMethod.name
    }

  val composableMethod = annotatedMethod.qualifiedName
  val previewName = getPreviewName(previewAnnotation.getDeclaredAttributeValue(PARAMETER_NAME))
  val defaultValues = previewAnnotation.findPreviewDefaultValues()

  val groupName = overrideGroupName ?: previewAnnotation.getDeclaredAttributeValue(PARAMETER_GROUP)
  val showDecorations =
    previewAnnotation.getBooleanAttribute(PARAMETER_SHOW_DECORATION, defaultValues)
      ?: (previewAnnotation.getBooleanAttribute(PARAMETER_SHOW_SYSTEM_UI, defaultValues)) ?: false
  val showBackground =
    previewAnnotation.getBooleanAttribute(PARAMETER_SHOW_BACKGROUND, defaultValues) ?: false
  // We don't use the library's default value for BackgroundColor and instead use a value defined
  // here, see PreviewElement#toPreviewXml.
  val backgroundColor = previewAnnotation.getDeclaredAttributeValue<Any>(PARAMETER_BACKGROUND_COLOR)
  val backgroundColorString =
    when (backgroundColor) {
      is Int -> backgroundColor.toString(16)
      is Long -> backgroundColor.toString(16)
      else -> null
    }?.let { "#$it" }

  // If the same composable functions is found multiple times, only keep the first one. This usually
  // will happen during
  // copy & paste and both the compiler and Studio will flag it as an error.
  val displaySettings =
    PreviewDisplaySettings(
      previewName,
      groupName,
      showDecorations,
      showBackground,
      backgroundColorString
    )

  val parameters = getPreviewParameters(annotatedMethod.uastParameters)
  val basePreviewElement =
    SingleComposePreviewElementInstance(
      composableMethod,
      displaySettings,
      rootAnnotation.toSmartPsiPointer(),
      annotatedMethod.uastBody.toSmartPsiPointer(),
      attributesToConfiguration(previewAnnotation, defaultValues)
    )
  return if (!parameters.isEmpty()) {
    ParametrizedComposePreviewElementTemplate(basePreviewElement, parameters)
  } else {
    basePreviewElement
  }
}

/**
 * Returns a list of [PreviewParameter] for the given [Collection<UParameter>]. If the parameters
 * are annotated with `PreviewParameter`, then they will be returned as part of the collection.
 */
public fun getPreviewParameters(parameters: Collection<UParameter>): Collection<PreviewParameter> =
  parameters.mapIndexedNotNull { index, parameter ->
    val annotation =
      parameter.uAnnotations.firstOrNull {
        COMPOSE_PREVIEW_PARAMETER_ANNOTATION_FQN == it.qualifiedName
      }
        ?: return@mapIndexedNotNull null
    val providerClassFqn =
      (annotation.findClassNameValue("provider")) ?: return@mapIndexedNotNull null
    val limit = annotation.getAttributeValue("limit") ?: Int.MAX_VALUE
    PreviewParameter(parameter.name, index, providerClassFqn, limit)
  }

private inline fun <reified T> UExpression.getValueOfType(): T? {
  val value = this.evaluate() as? T
  // Cast to literal as fallback. Needed for example for MultiPreview imported from a binary file
  return value ?: (this.sourcePsi as? PsiLiteralExpression)?.value as? T
}

private inline fun <reified T> UAnnotation.getDeclaredAttributeValue(attributeName: String): T? {
  val expression = this.findDeclaredAttributeValue(attributeName)
  return expression?.getValueOfType() as T?
}

private inline fun <reified T> UAnnotation.getAttributeValue(attributeName: String): T? {
  val expression = this.findAttributeValue(attributeName)
  return expression?.getValueOfType()
}

private fun UAnnotation.getBooleanAttribute(
  attributeName: String,
  defaultValues: Map<String, String?>
) = this.getAttributeValue(attributeName) ?: defaultValues[attributeName]?.toBoolean()

private fun UAnnotation.getIntAttribute(
  attributeName: String,
  defaultValues: Map<String, String?>
) = this.getAttributeValue(attributeName) ?: defaultValues[attributeName]?.toInt()

private fun UAnnotation.getFloatAttribute(
  attributeName: String,
  defaultValues: Map<String, String?>
) = this.getAttributeValue(attributeName) ?: defaultValues[attributeName]?.toFloat()

private fun UAnnotation.getStringAttribute(
  attributeName: String,
  defaultValues: Map<String, String?>
) = this.getAttributeValue<String>(attributeName)?.nullize() ?: defaultValues[attributeName]
