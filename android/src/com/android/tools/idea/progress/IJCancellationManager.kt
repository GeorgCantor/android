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
package com.android.tools.idea.progress

import com.android.tools.environment.CancellationManager
import com.android.tools.environment.cancellation.ExecutionCancellationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager

/** IntelliJ specific [CancellationManager] that checks for cancellation with [ProgressManager]. */
class IJCancellationManager : CancellationManager {
  override fun doThrowIfCancelled() {
    try {
      ProgressManager.checkCanceled()
    } catch (ex: ProcessCanceledException) {
      // We are replacing an intellij-specific exception with an intellij independent one.
      throw ExecutionCancellationException(ex)
    }
  }
}