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
package com.android.tools.idea.annotations

import com.android.tools.idea.AndroidPsiUtils
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.concurrency.getPsiFileSafely
import com.android.tools.idea.kotlin.getQualifiedName
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.isRejected
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType

/**
 * Finds if [vFile] in [project] has any of the given [annotationFqn] FQCN or the given
 * [shortAnnotationName] with the properties enforced by the [filter].
 */
private fun hasAnnotationsUncached(
  project: Project,
  vFile: VirtualFile,
  annotationFqn: String,
  shortAnnotationName: String,
  filter: (KtAnnotationEntry) -> Boolean,
): Boolean = runReadAction {
  if (DumbService.isDumb(project)) {
    return@runReadAction false
  }
  // This method can not call any methods that require smart mode.
  fun isFullNameAnnotation(annotation: KtAnnotationEntry) =
    // We use text() to avoid obtaining the FQN as that requires smart mode
    // In brackets annotations don't start with '@', but typical annotations do. Normalize them by
    // removing it
    annotation.text.removePrefix("@").startsWith(annotationFqn)

  val psiFile = PsiManager.getInstance(project).findFile(vFile)
  // Look into the imports first to avoid resolving the class name into all methods.
  val hasAnnotationImport =
    PsiTreeUtil.findChildrenOfType(psiFile, KtImportDirective::class.java).any {
      annotationFqn == it.importedFqName?.asString()
    }

  return@runReadAction PsiTreeUtil.findChildrenOfType(psiFile, KtAnnotationEntry::class.java).any {
    val shortName =
      it.getQualifiedName()?.let { qualifiedName -> StringUtilRt.getShortName(qualifiedName) }
    ((shortName == shortAnnotationName && hasAnnotationImport) || isFullNameAnnotation(it)) &&
      filter(it)
  }
}

/**
 * A mapping to keep track of the cache [Key]s for the annotation combinations. Each [Key] instance
 * is unique by implementation and [Key] declares [hashCode] and [equals] methods as final. Thus,
 * this mapping allows to reuse the same [Key] instance for the same [localKey].
 */
@Service
class CacheKeysManager {
  companion object {
    @JvmStatic
    fun getInstance(project: Project): CacheKeysManager =
      project.getService(CacheKeysManager::class.java)
  }

  private val annotationCacheKeys = ConcurrentHashMap<Any, Key<out CachedValue<out Any>>>()

  fun <T : Any> getKey(localKey: Any): Key<CachedValue<T>> {
    return annotationCacheKeys.getOrPut(localKey) { Key<CachedValue<T>>(localKey.toString()) }
      as Key<CachedValue<T>>
  }

  @VisibleForTesting fun map() = annotationCacheKeys
}

private val ANY_KT_ANNOTATION: (KtAnnotationEntry) -> Boolean = { true }

private data class HasFilteredAnnotationsKey(
  val annotationFqn: String,
  val shortAnnotationName: String,
  val filter: (KtAnnotationEntry) -> Boolean,
)

fun <T> CachedValuesManager.getCachedValue(
  dataHolder: UserDataHolder,
  key: Key<CachedValue<T>>,
  provider: CachedValueProvider<T>,
): T = runReadAction { this.getCachedValue(dataHolder, key, provider, false) }

/**
 * Finds if [vFile] in [project] has any of the given [annotationFqn] FQCN or the given
 * [shortAnnotationName]. To benefit from caching make sure the same parameters are passed to the
 * function call as all the parameters constitute the key.
 */
fun hasAnnotation(
  project: Project,
  vFile: VirtualFile,
  annotationFqn: String,
  shortAnnotationName: String,
  filter: (KtAnnotationEntry) -> Boolean = ANY_KT_ANNOTATION,
): Boolean {
  val psiFile = AndroidPsiUtils.getPsiFileSafely(project, vFile) ?: return false
  return CachedValuesManager.getManager(project).getCachedValue(
    psiFile,
    CacheKeysManager.getInstance(project)
      .getKey(HasFilteredAnnotationsKey(annotationFqn, shortAnnotationName, filter)),
  ) {
    CachedValueProvider.Result.create(
      hasAnnotationsUncached(project, vFile, annotationFqn, shortAnnotationName, filter),
      psiFile,
      DumbService.getInstance(project).modificationTracker,
    )
  }
}

/** Finds all the [KtAnnotationEntry] in [vFile] in [project] with [shortAnnotationName] as name. */
fun findAnnotations(
  project: Project,
  vFile: VirtualFile,
  shortAnnotationName: String,
): Collection<KtAnnotationEntry> {
  if (DumbService.isDumb(project)) {
    Logger.getInstance(AnnotatedMethodsFinder::class.java)
      .debug(
        "findAnnotations for @$shortAnnotationName called while indexing. No annotations will be found"
      )
    return emptyList()
  }

  val psiFile = AndroidPsiUtils.getPsiFileSafely(project, vFile) ?: return emptyList()
  return CachedValuesManager.getManager(project).getCachedValue(
    psiFile,
    CacheKeysManager.getInstance(project).getKey(shortAnnotationName),
  ) {
    val kotlinAnnotations: Sequence<PsiElement> =
      ReadAction.compute<Sequence<PsiElement>, Throwable> {
        val scope = GlobalSearchScope.fileScope(project, vFile)
        KotlinAnnotationsIndex[shortAnnotationName, project, scope].asSequence()
      }

    val annotations = kotlinAnnotations.filterIsInstance<KtAnnotationEntry>().toList()

    CachedValueProvider.Result.create(annotations.distinct(), psiFile)
  }
}

/**
 * A [ModificationTracker] that tracks a [Promise] and can be used as a dependency for
 * [CachedValuesManager.getCachedValue]. [CachedValuesManager.getCachedValue] can use objects
 * implementing some interfaces (see [com.intellij.util.CachedValueBase.getTimeStamp] for the
 * interfaces list) as dependencies to invalidate the cache when the interface methods indicate so.
 * Here we implement one of the interfaces, namely [ModificationTracker]. If the promise is rejected
 * or fails, the count is updated, the cache is invalidated, and the result is not cached.
 */
private class PromiseModificationTracker(private val promise: Promise<*>) : ModificationTracker {
  private var modificationCount = 0L

  override fun getModificationCount(): Long =
    when {
      promise.isRejected -> ++modificationCount // The promise failed so we ensure it is not cached
      else -> modificationCount
    }
}

fun UMethod?.isAnnotatedWith(annotationFqn: String) = runReadAction {
  this?.uAnnotations?.any { annotation -> annotationFqn == annotation.qualifiedName } ?: false
}

/**
 * Returns the [UMethod] annotated by this [UAnnotation], or null if it is not annotating a method,
 * or if the method is not also annotated with [annotationFqn].
 */
fun UAnnotation.getContainingUMethodAnnotatedWith(annotationFqn: String): UMethod? = runReadAction {
  val uMethod =
    getContainingUMethod() ?: javaPsi?.parentOfType<PsiMethod>()?.toUElement(UMethod::class.java)
  if (uMethod.isAnnotatedWith(annotationFqn)) uMethod else null
}

/**
 * Returns a [CachedValueProvider] that provides values of type [T] from the methods annotated with
 * [annotationFqn] and [shortAnnotationName], with the properties enforced by the
 * [annotationFilter], from [vFile] of [project]. Technically, this function could just return a
 * collection of methods, but [toValues] might be slow to calculate so caching the values rather
 * than methods is more useful. To benefit from caching make sure the same parameters are passed to
 * the function call as all the parameters constitute the key.
 */
private fun <T> findAnnotatedMethodsCachedValues(
  project: Project,
  vFile: VirtualFile,
  annotationFqn: String,
  shortAnnotationName: String,
  annotationFilter: (UAnnotation) -> Boolean,
  toValues: (methods: List<UMethod>) -> Sequence<T>,
): CachedValueProvider<CompletableDeferred<Collection<T>>> = CachedValueProvider {
  // This Deferred should not be needed, the promise could be returned directly. However, it seems
  // there is a compiler issue that
  // causes the findAnnotatedMethodsValues to fail when using the "dist" build (not from source).
  // Using the deferred seems to avoid the problem. b/222843951.
  val deferred = CompletableDeferred<Collection<T>>()

  val promise =
    ReadAction.nonBlocking(
        Callable<Collection<T>> {
          val uMethods =
            findAnnotations(project, vFile, shortAnnotationName)
              .mapNotNull { it.psiOrParent.toUElementOfType<UAnnotation>() }
              .filter(annotationFilter)
              .mapNotNull { it.getContainingUMethodAnnotatedWith(annotationFqn) }
              .distinct() // avoid looking more than once per method

          toValues(uMethods).toList()
        }
      )
      .inSmartMode(project)
      .submit(AppExecutorUtil.getAppExecutorService())
      .onSuccess { deferred.complete(it) }
      .onError { deferred.completeExceptionally(it) }

  val kotlinJavaModificationTracker =
    PsiModificationTracker.getInstance(project).forLanguages { lang ->
      lang.`is`(KotlinLanguage.INSTANCE) || lang.`is`(JavaLanguage.INSTANCE)
    }
  CachedValueProvider.Result.create(
    deferred,
    kotlinJavaModificationTracker,
    PromiseModificationTracker(promise),
  )
}

private data class CachedValuesKey<T>(
  val annotationFqn: String,
  val shortAnnotationName: String,
  val filter: (UAnnotation) -> Boolean,
  val toValues: (methods: List<UMethod>) -> Sequence<T>,
)

private val ANY_U_ANNOTATION: (UAnnotation) -> Boolean = { true }

/**
 * Finds all the values calculated by [toValues] associated with the methods annotated with
 * [annotationFqn] and [shortAnnotationName] from [vFile] in [project].
 */
suspend fun <T> findAnnotatedMethodsValues(
  project: Project,
  vFile: VirtualFile,
  annotationFqn: String,
  shortAnnotationName: String,
  annotationFilter: (UAnnotation) -> Boolean = ANY_U_ANNOTATION,
  toValues: (methods: List<UMethod>) -> Sequence<T>,
): Collection<T> {
  val psiFile = getPsiFileSafely(project, vFile) ?: return emptyList()
  return withContext(AndroidDispatchers.workerThread) {
    val promiseResult = runReadAction {
      CachedValuesManager.getManager(project)
        .getCachedValue(
          psiFile,
          CacheKeysManager.getInstance(project)
            .getKey(
              CachedValuesKey(annotationFqn, shortAnnotationName, annotationFilter, toValues)
            ),
          findAnnotatedMethodsCachedValues(
            project,
            vFile,
            annotationFqn,
            shortAnnotationName,
            annotationFilter,
            toValues,
          ),
        )
    }
    try {
      return@withContext promiseResult.await()
    } catch (_: Throwable) {
      return@withContext emptyList()
    }
  }
}

private object AnnotatedMethodsFinder
