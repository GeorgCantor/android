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
package com.android.tools.idea.preview.modes

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = Logger.getInstance(CommonPreviewModeManager::class.java)

/**
 * Common implementation of a [PreviewModeManager] which uses a [PreviewMode.Switching] mode to
 * transition between the current mode and the new mode. Switching from one mode to another can take
 * some time.
 *
 * @param onEnter a function that will be called with the new mode when switching to a new mode.
 * @param onExit a function that will be called with the current mode when switching to a new mode.
 */
class CommonPreviewModeManager(
  scope: CoroutineScope,
  private val onEnter: suspend (PreviewMode) -> Unit,
  private val onExit: suspend (PreviewMode) -> Unit,
) : PreviewModeManager {

  /**
   * Flow representing the [CommonPreviewModeManager]'s current [PreviewMode]. This flow is used by
   * [mode] in order to implement the [PreviewModeManager] interface. The flow is collected and, if
   * the current mode in the flow is [PreviewMode.Switching], then the [onExit] and [onEnter]
   * methods are called.
   */
  private val modeFlow = MutableStateFlow<PreviewMode>(PreviewMode.Default)

  /**
   * When entering one of the [PreviewMode.Focus] modes (interactive, animation, etc.), the previous
   * mode is saved into [restoreMode]. After exiting the special mode [restoreMode] is set.
   *
   * TODO(b/293257529) Need to restore selected tab as well in Gallery mode.
   */
  private var restoreMode: PreviewMode.Settable? = null

  init {
    // Keep track of the last mode that was set to ensure it is correctly disposed
    var lastMode = modeFlow.value

    // Launch handling of Preview modes
    scope.launch {
      modeFlow.collectLatest {
        when (it) {
          is PreviewMode.Switching -> {
            // We can not interrupt the state change to ensure the change is done correctly
            withContext(NonCancellable) {
              onExit(lastMode)
              onEnter(it.newMode)
              lastMode = it.newMode
              restoreMode = it.currentMode
            }
            modeFlow.value = it.newMode
          }
          else -> Unit
        }
      }
    }
  }

  override val mode: PreviewMode
    get() = modeFlow.value

  override fun setMode(newMode: PreviewMode.Settable) {
    val currentMode = currentOrNextMode
    if (currentMode == newMode) {
      log.debug("Mode was already $newMode")
      return
    }

    modeFlow.value = PreviewMode.Switching(currentMode, newMode)
  }

  override fun restorePrevious() {
    restoreMode?.let {
      setMode(it)
      restoreMode = null
    }
  }
}
