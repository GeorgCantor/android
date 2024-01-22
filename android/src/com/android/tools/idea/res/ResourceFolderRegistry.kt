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
package com.android.tools.idea.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.tools.concurrency.AndroidIoManager
import com.android.tools.idea.model.Namespacing
import com.android.tools.idea.res.ResourceUpdateTracer.pathForLogging
import com.android.tools.idea.res.ResourceUpdateTracer.pathsForLogging
import com.android.utils.TraceUtils.simpleId
import com.android.utils.concurrency.getAndUnwrap
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.ImmutableList
import com.google.common.collect.Sets
import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbModeTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.util.Consumer
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.function.BiConsumer
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.facet.ResourceFolderManager.Companion.getInstance
import org.jetbrains.annotations.VisibleForTesting

/**
 * A project service that manages [ResourceFolderRepository] instances, creating them as necessary
 * and reusing repositories for the same directories when multiple modules need them. For every
 * directory a namespaced and non-namespaced repository may be created, if needed.
 */
class ResourceFolderRegistry(val project: Project) : Disposable {
  private val myNamespacedCache = buildCache()
  private val myNonNamespacedCache = buildCache()
  private val myCaches = ImmutableList.of(myNamespacedCache, myNonNamespacedCache)

  init {
    project.messageBus
      .connect(this)
      .subscribe(
        ModuleRootListener.TOPIC,
        object : ModuleRootListener {
          override fun rootsChanged(event: ModuleRootEvent) {
            removeStaleEntries()
          }
        },
      )
  }

  operator fun get(facet: AndroidFacet, dir: VirtualFile) =
    get(facet, dir, StudioResourceRepositoryManager.getInstance(facet).namespace)

  @VisibleForTesting
  operator fun get(
    facet: AndroidFacet,
    dir: VirtualFile,
    namespace: ResourceNamespace,
  ): ResourceFolderRepository {
    val cache =
      if (namespace === ResourceNamespace.RES_AUTO) myNonNamespacedCache else myNamespacedCache
    val repository = cache.getAndUnwrap(dir) { createRepository(facet, dir, namespace) }
    assert(repository.namespace == namespace)

    // TODO(b/80179120): figure out why this is not always true.
    // assert repository.getFacet().equals(facet);

    return repository.ensureLoaded()
  }

  /**
   * Returns the resource repository for the given directory, or null if such repository doesn't
   * already exist.
   */
  fun getCached(dir: VirtualFile, namespacing: Namespacing): ResourceFolderRepository? {
    val cache =
      if (namespacing === Namespacing.REQUIRED) myNamespacedCache else myNonNamespacedCache
    return cache.getIfPresent(dir)
  }

  fun reset(facet: AndroidFacet) {
    ResourceUpdateTracer.logDirect { "$simpleId.reset()" }
    reset(myNamespacedCache, facet)
    reset(myNonNamespacedCache, facet)
  }

  private fun reset(cache: Cache<VirtualFile, ResourceFolderRepository>, facet: AndroidFacet) {
    val cacheAsMap = cache.asMap()
    if (cacheAsMap.isEmpty()) {
      ResourceUpdateTracer.logDirect { "$simpleId.reset: cache is empty" }
      return
    }

    val keysToRemove =
      cacheAsMap.entries.mapNotNull { (virtualFile, repository) ->
        virtualFile.takeIf { repository.facet == facet }
      }

    keysToRemove.forEach(cache::invalidate)
  }

  private fun removeStaleEntries() {
    // TODO(namespaces): listen to changes in modules' namespacing modes and dispose repositories
    // which are no longer needed.
    ResourceUpdateTracer.logDirect { "$simpleId.removeStaleEntries()" }
    removeStaleEntries(myNamespacedCache)
    removeStaleEntries(myNonNamespacedCache)
  }

  private fun removeStaleEntries(cache: Cache<VirtualFile, ResourceFolderRepository>) {
    val cacheAsMap = cache.asMap()
    if (cacheAsMap.isEmpty()) {
      ResourceUpdateTracer.logDirect { "$simpleId.removeStaleEntries: cache is empty" }
      return
    }
    val facets: MutableSet<AndroidFacet> = Sets.newHashSetWithExpectedSize(cacheAsMap.size)
    val newResourceFolders: MutableSet<VirtualFile> =
      Sets.newHashSetWithExpectedSize(cacheAsMap.size)
    for (repository in cacheAsMap.values) {
      val facet = repository.facet
      if (!facet.isDisposed && facets.add(facet)) {
        val folderManager = getInstance(facet)
        newResourceFolders.addAll(folderManager.folders)
      }
    }
    ResourceUpdateTracer.logDirect {
      "$simpleId.removeStaleEntries retained ${pathsForLogging(newResourceFolders, project)}"
    }
    cacheAsMap.keys.retainAll(newResourceFolders)
  }

  override fun dispose() {
    myNamespacedCache.invalidateAll()
    myNonNamespacedCache.invalidateAll()
  }

  fun dispatchToRepositories(
    file: VirtualFile,
    handler: BiConsumer<ResourceFolderRepository, VirtualFile>,
  ) {
    ResourceUpdateTracer.log {
      "ResourceFolderRegistry.dispatchToRepositories(${pathForLogging(file)}, ...) VFS change"
    }
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    var dir = if (file.isDirectory) file else file.parent
    while (dir != null) {
      for (cache in myCaches) {
        cache.getIfPresent(dir)?.let { handler.accept(it, file) }
      }
      dir = dir.parent
    }
  }

  fun dispatchToRepositories(file: VirtualFile, invokeCallback: Consumer<PsiTreeChangeListener>) {
    ResourceUpdateTracer.log {
      "ResourceFolderRegistry.dispatchToRepositories(${pathForLogging(file)}, ...) PSI change"
    }
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    var dir = if (file.isDirectory) file else file.parent
    while (dir != null) {
      for (cache in myCaches) {
        cache.getIfPresent(dir)?.let { invokeCallback.consume(it.psiListener) }
      }
      dir = dir.parent
    }
  }

  private fun buildCache(): Cache<VirtualFile, ResourceFolderRepository> {
    return CacheBuilder.newBuilder().build()
  }

  private fun createRepository(
    facet: AndroidFacet,
    dir: VirtualFile,
    namespace: ResourceNamespace,
  ): ResourceFolderRepository {
    // Don't create a persistent cache in tests to avoid unnecessary overhead.
    val executor =
      if (ApplicationManager.getApplication().isUnitTestMode) Executor { _: Runnable? -> }
      else AndroidIoManager.getInstance().getBackgroundDiskIoExecutor()
    val cachingData =
      ResourceFolderRepositoryFileCacheService.get()
        .getCachingData(facet.module.project, dir, executor)
    return ResourceFolderRepository.create(facet, dir, namespace, cachingData)
  }

  companion object {

    @JvmStatic
    fun getInstance(project: Project): ResourceFolderRegistry = project.service()
  }

  /** Populate the registry's in-memory ResourceFolderRepository caches (if not already cached). */
  class PopulateCachesTask(private val myProject: Project) : DumbModeTask() {

    override fun tryMergeWith(taskFromQueue: DumbModeTask): DumbModeTask? =
      if (taskFromQueue is PopulateCachesTask && taskFromQueue.myProject == myProject) this
      else null

    override fun performInDumbMode(indicator: ProgressIndicator) {
      val facets = ProjectFacetManager.getInstance(myProject).getFacets(AndroidFacet.ID)
      if (facets.isEmpty()) {
        return
      }
      // Some directories in the registry may already be populated by this point, so filter them
      // out.
      indicator.text = "Indexing resources"
      indicator.isIndeterminate = false
      val resDirectories = getResourceDirectoriesForFacets(facets)
      // Might already be done, as there can be a race for filling the memory caches.
      if (resDirectories.isEmpty()) {
        return
      }

      // Make sure the cache root is created before parallel execution to avoid racing to create the
      // root.
      try {
        ResourceFolderRepositoryFileCacheService.get().createDirForProject(myProject)
      } catch (e: IOException) {
        return
      }
      val application = ApplicationManager.getApplication()
      assert(!application.isWriteAccessAllowed)
      var numDone = 0
      val parallelExecutor = AndroidIoManager.getInstance().getBackgroundDiskIoExecutor()
      val repositoryJobs: MutableList<Future<ResourceFolderRepository>> = ArrayList()
      for ((dir, facet) in resDirectories) {
        val registry = getInstance(myProject)
        repositoryJobs.add(
          parallelExecutor.submit<ResourceFolderRepository> { registry[facet, dir] }
        )
      }
      for (job in repositoryJobs) {
        if (indicator.isCanceled) {
          break
        }
        indicator.fraction = numDone.toDouble() / resDirectories.size
        try {
          job.get()
        } catch (e: ExecutionException) {
          // If we get an exception, that's okay -- we stop pre-populating the cache, which is just
          // for performance.
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
        }
        ++numDone
      }
    }
  }
}
