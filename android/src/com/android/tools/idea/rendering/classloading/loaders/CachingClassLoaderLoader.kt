/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.rendering.classloading.loaders

import com.android.tools.idea.projectsystem.ClassContent
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import org.jetbrains.annotations.TestOnly

/** [DelegatingClassLoader.Loader] that caches the loaded classes and provides API to interact with that cache. */
interface CachingClassLoaderLoader : DelegatingClassLoader.Loader {
  /** Returns whether the cached classes are up-to-date. */
  fun isUpToDate(): Boolean = true

  /** Invalidates the cache of classes. */
  fun invalidateCaches() = Unit

  // TODO: remove this from the interface (it should not be needed) and move the interface next to
  // [DelegatingClassLoader]
  @TestOnly
  fun injectClassFile(fqcn: String, classContent: ClassContent) = Unit
}
