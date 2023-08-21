/*
 * Copyright (C) 2019 The Android Open Source Project
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
package org.jetbrains.android.uipreview

import com.android.tools.idea.projectsystem.ProjectSystemBuildManager
import com.android.tools.idea.projectsystem.ProjectSystemService
import com.android.tools.idea.projectsystem.getHolderModule
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.util.androidFacet
import com.android.tools.rendering.ModuleRenderContext
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.rendering.classloading.ModuleClassLoadedDiagnosticsImpl
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.android.tools.rendering.classloading.NopModuleClassLoadedDiagnostics
import com.android.tools.rendering.classloading.combine
import com.android.tools.rendering.classloading.preload
import com.android.tools.rendering.log.LogAnonymizerUtil.anonymize
import com.android.utils.reflection.qualifiedName
import com.google.common.base.Charsets
import com.google.common.hash.Hashing
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.removeUserData
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService
import com.intellij.util.containers.MultiMap
import java.lang.ref.SoftReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.android.uipreview.StudioModuleClassLoader.NON_PROJECT_CLASSES_DEFAULT_TRANSFORMS
import org.jetbrains.android.uipreview.StudioModuleClassLoader.PROJECT_DEFAULT_TRANSFORMS
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting

private fun throwIfNotUnitTest(e: Exception) =
  if (!ApplicationManager.getApplication().isUnitTestMode) {
    throw e
  } else {
    Logger.getInstance(ModuleClassLoaderProjectHelperService::class.java)
      .info(
        "ModuleClassLoaderProjectHelperService is disabled for unit testing since there is no ProjectSystemBuildManager"
      )
  }

/** This helper service listens for builds and cleans the module cache after it finishes. */
@Service(Service.Level.PROJECT)
private class ModuleClassLoaderProjectHelperService(val project: Project) :
  ProjectSystemBuildManager.BuildListener, Disposable {
  init {
    try {
      ProjectSystemService.getInstance(project)
        .projectSystem
        .getBuildManager()
        .addBuildListener(this, this)
    } catch (e: IllegalStateException) {
      throwIfNotUnitTest(e)
    } catch (e: UnsupportedOperationException) {
      throwIfNotUnitTest(e)
    }
  }

  override fun beforeBuildCompleted(result: ProjectSystemBuildManager.BuildResult) {
    if (
      result.status == ProjectSystemBuildManager.BuildStatus.SUCCESS &&
        (result.mode == ProjectSystemBuildManager.BuildMode.COMPILE ||
          result.mode == ProjectSystemBuildManager.BuildMode.ASSEMBLE)
    ) {
      ModuleManager.getInstance(project).modules.forEach {
        ModuleClassLoaderManager.get().clearCache(it)
      }
    }
  }

  override fun dispose() {}
}

/**
 * This is a wrapper around a class preloading [CompletableFuture] that allows for the proper
 * disposal of the resources used.
 */
class Preloader(
  moduleClassLoader: StudioModuleClassLoader,
  classesToPreload: Collection<String> = emptyList()
) {
  private val classLoader = SoftReference(moduleClassLoader)
  private var isActive = AtomicBoolean(true)

  init {
    if (classesToPreload.isNotEmpty()) {
      preload(
        moduleClassLoader,
        { isActive.get() && !(classLoader.get()?.isDisposed ?: true) },
        classesToPreload,
        getAppExecutorService()
      )
    }
  }

  /** Cancels the on-going preloading. */
  fun cancel() {
    isActive.set(false)
  }

  fun dispose() {
    cancel()
    val classLoaderToDispose = classLoader.get()
    classLoader.clear()
    classLoaderToDispose?.dispose()
  }

  fun getClassLoader(): StudioModuleClassLoader? {
    cancel() // Stop preloading since we are going to use the class loader
    return classLoader.get()
  }

  /**
   * Checks if this [Preloader] loads classes for [cl] [ModuleClassLoader]. This allows for safe
   * check without the need for share the actual [classLoader] and prevent its use.
   */
  fun isLoadingFor(cl: StudioModuleClassLoader) = classLoader.get() == cl

  fun isForCompatible(
    parent: ClassLoader?,
    projectTransformations: ClassTransform,
    nonProjectTransformations: ClassTransform
  ) = classLoader.get()?.isCompatible(parent, projectTransformations, nonProjectTransformations) ?: false


  /**
   * Returns the number of currently loaded classes for the underlying [StudioModuleClassLoader].
   * Intended to be used for debugging and diagnostics.
   */
  fun getLoadedCount(): Int =
    classLoader.get()?.let { it.nonProjectLoadedClasses.size + it.projectLoadedClasses.size } ?: 0
}

private val PRELOADER: Key<Preloader> = Key.create(::PRELOADER.qualifiedName<StudioModuleClassLoaderManager>())
val HATCHERY: Key<ModuleClassLoaderHatchery> = Key.create(::HATCHERY.qualifiedName<StudioModuleClassLoaderManager>())

private fun calculateTransformationsUniqueId(
  projectClassesTransformationProvider: ClassTransform,
  nonProjectClassesTransformationProvider: ClassTransform
): String? {
  return Hashing.goodFastHash(64)
    .newHasher()
    .putString(projectClassesTransformationProvider.id, Charsets.UTF_8)
    .putString(nonProjectClassesTransformationProvider.id, Charsets.UTF_8)
    .hash()
    .toString()
}

fun StudioModuleClassLoader.areTransformationsUpToDate(
  projectClassesTransformationProvider: ClassTransform,
  nonProjectClassesTransformationProvider: ClassTransform
): Boolean {
  return (calculateTransformationsUniqueId(
    this.projectClassesTransform,
    this.nonProjectClassesTransform
  ) ==
    calculateTransformationsUniqueId(
      projectClassesTransformationProvider,
      nonProjectClassesTransformationProvider
    ))
}

/**
 * Checks if the [StudioModuleClassLoader] has the same transformations and parent [ClassLoader]
 * making it compatible but not necessarily up-to-date because it does not check the state of user
 * project files. Compatibility means that the [StudioModuleClassLoader] can be used if it did not
 * load any classes from the user source code. This allows for pre-loading the classes from
 * dependencies (which are usually more stable than user code) and speeding up the preview update
 * when user changes the source code (but not dependencies).
 */
fun StudioModuleClassLoader.isCompatible(
  parent: ClassLoader?,
  projectTransformations: ClassTransform,
  nonProjectTransformations: ClassTransform
) =
  when {
    !this.isCompatibleParentClassLoader(parent) -> {
      StudioModuleClassLoaderManager.LOG.debug("Parent has changed, discarding ModuleClassLoader")
      false
    }
    !this.areTransformationsUpToDate(projectTransformations, nonProjectTransformations) -> {
      StudioModuleClassLoaderManager.LOG.debug(
        "Transformations have changed, discarding ModuleClassLoader"
      )
      false
    }
    !this.areDependenciesUpToDate() -> {
      StudioModuleClassLoaderManager.LOG.debug("Files have changed, discarding ModuleClassLoader")
      false
    }
    else -> {
      StudioModuleClassLoaderManager.LOG.debug("ModuleClassLoader is up to date")
      true
    }
  }

private fun <T> UserDataHolder.getOrCreate(key: Key<T>, factory: () -> T): T {
  getUserData(key)?.let {
    return it
  }
  return factory().also { putUserData(key, it) }
}

@VisibleForTesting
private fun Module.getOrCreateHatchery() =
  getOrCreate(HATCHERY) {
    if (!isDisposed) ModuleClassLoaderHatchery(parentDisposable = this) else throw AlreadyDisposedException("Module was already disposed")
  }

/** A [ClassLoader] for the [Module] dependencies. */
class StudioModuleClassLoaderManager : ModuleClassLoaderManager<StudioModuleClassLoader>, Disposable {
  // MutableSet is backed by the WeakHashMap in prod so we do not retain the holders
  /**
   * Creates a [MultiMap] to be used as a storage of [ModuleClassLoader] holders. We would like
   * the implementation to be different in prod and in tests:
   *
   * In Prod, it should be a Set of value WEAK references. So that in case we do not release the holder
   * (due to some unexpected flow) it is not retained by the [StudioModuleClassLoaderManager]
   *
   * In Tests, we would like it to be a Set of STRONG references. So that any unreleased references
   * got caught by the LeakHunter.
   */
  private val holders: MultiMap<StudioModuleClassLoader, ModuleClassLoaderManager.Reference<*>> = if (ApplicationManager.getApplication().isUnitTestMode)
    WeakMultiMap.create()
  else
    WeakMultiMap.createWithWeakValues()

  override fun dispose() {
    holders.keySet().mapNotNull { it.module }.forEach { clearCache(it) }
  }

  @TestOnly
  fun assertNoClassLoadersHeld() {
    if (!holders.isEmpty) {
      val referencesString =
        holders.entrySet().joinToString { "${it.key} held by ${it.value.joinToString(", ")}" }
      throw AssertionError("Class loaders were not released correctly by the tests\n$referencesString\n")
    }
  }

  /**
   * Returns a project class loader to use for rendering. May cache instances across render
   * sessions.
   */
  @Synchronized
  override fun getShared(parent: ClassLoader?, moduleRenderContext: ModuleRenderContext,
                         additionalProjectTransformation: ClassTransform,
                         additionalNonProjectTransformation: ClassTransform,
                         onNewModuleClassLoader: Runnable): ModuleClassLoaderManager.Reference<StudioModuleClassLoader> {
    val module: Module = moduleRenderContext.module
    var moduleClassLoader = module.getUserData(PRELOADER)?.getClassLoader()
    val combinedProjectTransformations: ClassTransform by lazy {
      combine(PROJECT_DEFAULT_TRANSFORMS, additionalProjectTransformation)
    }
    val combinedNonProjectTransformations: ClassTransform by lazy {
      combine(NON_PROJECT_CLASSES_DEFAULT_TRANSFORMS, additionalNonProjectTransformation)
    }

    var oldClassLoader: StudioModuleClassLoader? = null
    if (moduleClassLoader != null) {
      val invalidate =
        moduleClassLoader.isDisposed ||
        !moduleClassLoader.isCompatible(parent, combinedProjectTransformations, combinedNonProjectTransformations) ||
        !moduleClassLoader.isUserCodeUpToDate

      if (invalidate) {
        oldClassLoader = moduleClassLoader
        moduleClassLoader = null
      }
    }

    if (moduleClassLoader == null) {
      // Make sure the helper service is initialized
      moduleRenderContext.module.project.getService(ModuleClassLoaderProjectHelperService::class.java)
      if (LOG.isDebugEnabled) {
        LOG.debug { "Loading new class loader for module ${anonymize(AndroidFacetRenderModelModule(module.androidFacet!!))}" }
      }
      val preloadedClassLoader: StudioModuleClassLoader? =
        moduleRenderContext.module.getOrCreateHatchery().requestClassLoader(
          parent, combinedProjectTransformations, combinedNonProjectTransformations)
      moduleClassLoader = preloadedClassLoader ?: StudioModuleClassLoader(parent,
                                                                          moduleRenderContext,
                                                                          combinedProjectTransformations,
                                                                          combinedNonProjectTransformations,
                                                                          createDiagnostics())
      module.putUserData(PRELOADER, Preloader(moduleClassLoader))
      onNewModuleClassLoader.run()
    }

    oldClassLoader?.let {
      if (stopManagingIfNotHeld(it)) {
        it.dispose()
      }
    }
    val newModuleClassLoaderReference = ModuleClassLoaderManager.Reference(this, moduleClassLoader).also {
      LOG.debug { "New ModuleClassLoader reference $it to $moduleClassLoader" }
    }
    holders.putValue(moduleClassLoader, newModuleClassLoaderReference)
    return newModuleClassLoaderReference
  }

  @Synchronized
  override fun getShared(parent: ClassLoader?, moduleRenderContext: ModuleRenderContext) =
    getShared(parent, moduleRenderContext, ClassTransform.identity, ClassTransform.identity) {}

  /**
   * Return a [StudioModuleClassLoader] for a [Module] to be used for rendering. Similar to [getShared] but guarantees that the returned
   * [StudioModuleClassLoader] is not shared and the caller has full ownership of it.
   */
  @Synchronized
  override fun getPrivate(parent: ClassLoader?,
                          moduleRenderContext: ModuleRenderContext,
                          additionalProjectTransformation: ClassTransform,
                          additionalNonProjectTransformation: ClassTransform): ModuleClassLoaderManager.Reference<StudioModuleClassLoader> {
    // Make sure the helper service is initialized
    moduleRenderContext.module.project.getService(ModuleClassLoaderProjectHelperService::class.java)

    val combinedProjectTransformations = combine(PROJECT_DEFAULT_TRANSFORMS, additionalProjectTransformation)
    val combinedNonProjectTransformations = combine(NON_PROJECT_CLASSES_DEFAULT_TRANSFORMS, additionalNonProjectTransformation)
    val preloadedClassLoader: StudioModuleClassLoader? =
      moduleRenderContext.module.getOrCreateHatchery().requestClassLoader(
        parent, combinedProjectTransformations, combinedNonProjectTransformations)
    return (preloadedClassLoader ?: StudioModuleClassLoader(parent, moduleRenderContext,
                                                            combinedProjectTransformations,
                                                            combinedNonProjectTransformations,
                                                            createDiagnostics()))
      .let {
        val newModuleClassLoaderReference = ModuleClassLoaderManager.Reference(this, it)
        LOG.debug { "New ModuleClassLoader reference $newModuleClassLoaderReference to $it" }
        holders.putValue(it, newModuleClassLoaderReference)
        newModuleClassLoaderReference
      }
  }

  @Synchronized
  override fun getPrivate(parent: ClassLoader?, moduleRenderContext: ModuleRenderContext, ) =
    getPrivate(parent, moduleRenderContext, ClassTransform.identity, ClassTransform.identity)

  @VisibleForTesting
  fun createCopy(mcl: StudioModuleClassLoader): StudioModuleClassLoader? = mcl.copy(createDiagnostics())

  @Synchronized
  override fun clearCache(module: Module) {
    holders.keySet().toList().filter { it.module?.getHolderModule() == module.getHolderModule() }.forEach { holders.remove(it) }
    setOf(module.getHolderModule(), module).forEach { mdl ->
      mdl.removeUserData(PRELOADER)?.dispose()
      mdl.getUserData(HATCHERY)?.destroy()
    }
  }

  @Synchronized
  private fun unHold(moduleClassLoader: ModuleClassLoaderManager.Reference<*>) {
    holders.remove(moduleClassLoader.classLoader as StudioModuleClassLoader, moduleClassLoader)
  }

  @Synchronized
  private fun stopManagingIfNotHeld(moduleClassLoader: StudioModuleClassLoader): Boolean {
    if (holders.containsKey(moduleClassLoader)) {
      return false
    }
    // If that was a shared ModuleClassLoader that is no longer used, we have to destroy the old one to free the resources, but we also
    // recreate a new one for faster load next time
    moduleClassLoader.module?.let { module ->
      if (module.isDisposed) {
        return@let
      }
      if (module.getUserData(PRELOADER)?.isLoadingFor(moduleClassLoader) != true) {
        if (!holders.isEmpty) {
          StudioModuleClassLoaderCreationContext.fromClassLoader(moduleClassLoader)?.let {
            module.getOrCreateHatchery().incubateIfNeeded(it) { donorInformation ->
              StudioModuleClassLoader(
                donorInformation.parent,
                donorInformation.moduleRenderContext,
                donorInformation.projectTransform,
                donorInformation.nonProjectTransformation,
                createDiagnostics()
              )
            }
          }
        }
      } else {
        module.removeUserData(PRELOADER)?.cancel()
        val newClassLoader = createCopy(moduleClassLoader) ?: return@let
        // We first load dependencies classes and then project classes since the latter reference the former and not vice versa
        val classesToLoad = moduleClassLoader.nonProjectLoadedClasses + moduleClassLoader.projectLoadedClasses
        module.putUserData(PRELOADER, Preloader(newClassLoader, classesToLoad))
      }
      if (holders.isEmpty) { // If there are no more users of ModuleClassLoader destroy the hatchery to free the resources
        module.getUserData(HATCHERY)?.destroy()
      }
    }
    return true
  }

  /**
   * Inform [StudioModuleClassLoaderManager] that [ModuleClassLoader] is not used anymore and
   * therefore can be disposed if no longer managed.
   */
  override fun release(moduleClassLoaderReference: ModuleClassLoaderManager.Reference<*>) {
    LOG.debug { "release reference $moduleClassLoaderReference"}
    val classLoader = moduleClassLoaderReference.classLoader as StudioModuleClassLoader
    unHold(moduleClassLoaderReference)
    if (stopManagingIfNotHeld(classLoader)) {
      classLoader.dispose()
    }
  }

  companion object {
    @JvmStatic val LOG = Logger.getInstance(StudioModuleClassLoaderManager::class.java)

    private var captureDiagnostics = false

    /**
     * If set to true, any class loaders instantiated after this call will record diagnostics about
     * the load time and load counts.
     */
    @TestOnly
    @Synchronized
    fun setCaptureClassLoadingDiagnostics(enabled: Boolean) {
      captureDiagnostics = enabled
    }

    internal fun createDiagnostics() =
      if (captureDiagnostics) ModuleClassLoadedDiagnosticsImpl()
      else NopModuleClassLoadedDiagnostics

    @JvmStatic
    fun get(): StudioModuleClassLoaderManager =
      ApplicationManager.getApplication().getService(ModuleClassLoaderManager::class.java) as StudioModuleClassLoaderManager
  }
}