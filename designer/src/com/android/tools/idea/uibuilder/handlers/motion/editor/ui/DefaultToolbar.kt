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
package com.android.tools.idea.uibuilder.handlers.motion.editor.ui

import com.android.tools.adtui.util.ActionToolbarUtil
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import javax.swing.JComponent

/** [ActionToolbarImpl] with enabled navigation. */
class DefaultToolbarImpl(parent: JComponent, place: String, actions: List<AnAction>) :
  ActionToolbarImpl(place, DefaultActionGroup(actions), true) {
  init {
    targetComponent = parent
    ActionToolbarUtil.makeToolbarNavigable(this)
    layoutStrategy = ToolbarLayoutStrategy.HORIZONTAL_NOWRAP_STRATEGY
    setMinimumButtonSize(ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
  }
}
