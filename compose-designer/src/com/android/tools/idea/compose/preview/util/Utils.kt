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
package com.android.tools.idea.compose.preview.util

import com.android.tools.compose.COMPOSE_VIEW_ADAPTER_FQN
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.common.surface.SceneView
import com.intellij.openapi.util.Segment

fun Segment?.containsOffset(offset: Int) =
  this?.let { it.startOffset <= offset && offset <= it.endOffset } ?: false

/**
 * For a SceneView that contains a valid Compose Preview, get its root component, that must be a
 * ComposeViewAdapter.
 */
fun SceneView.getRootComponent(): NlComponent? {
  val root = sceneManager.model.components.firstOrNull()
  assert(root == null || root.tagName == COMPOSE_VIEW_ADAPTER_FQN) {
    "Expected the root component of a Compose Preview to be a $COMPOSE_VIEW_ADAPTER_FQN, but found ${root!!.tagName}"
  }
  return root
}

/** Returns true if the ComposeViewAdapter component of this SceneView is currently selected. */
fun SceneView.isRootComponentSelected() =
  getRootComponent()?.let { surface.selectionModel.isSelected(it) } == true
