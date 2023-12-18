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
package com.android.tools.idea.res

import com.android.tools.res.MultiResourceRepository
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.LowMemoryWatcher
import com.intellij.openapi.vfs.VirtualFile

/**
 * [MultiResourceRepository] that adjusts memory usage when the memory consumption becomes critical.
 */
abstract class MemoryTrackingMultiResourceRepository
protected constructor(parentDisposable: Disposable, displayName: String) :
  MultiResourceRepository<VirtualFile>(displayName), Disposable {
  init {
    Disposer.register(parentDisposable, this)
    LowMemoryWatcher.register({ onLowMemory() }, this)
  }

  override fun dispose() {
    super.release()
  }
}
