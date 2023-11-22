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
package com.android.tools.idea.uibuilder.scene

import com.android.tools.idea.common.surface.SceneView
import com.android.tools.rendering.ExecuteCallbacksResult
import kotlinx.coroutines.future.await
import java.util.concurrent.CancellationException

/** Suspendable equivalent to [LayoutlibSceneManager.executeCallbacks]. */
suspend fun LayoutlibSceneManager.executeCallbacks(): ExecuteCallbacksResult =
  executeCallbacksAsync().await()

/**
 * Returns whether the [SceneView] has failed to render or has rendered with errors.
 *
 * Note that cancellations are not considered to be an error.
 */
fun SceneView.hasRenderErrors(): Boolean =
  (sceneManager as? LayoutlibSceneManager).hasRenderErrors()

fun LayoutlibSceneManager?.hasRenderErrors(): Boolean =
  this?.renderResult?.let {
    it.logger.hasErrors() && it.renderResult.exception !is CancellationException
  } == true
