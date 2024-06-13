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
package com.android.tools.idea.uibuilder.handlers.motion.editor.actions

import com.android.tools.idea.uibuilder.handlers.motion.editor.adapters.MEIcons
import com.android.tools.idea.uibuilder.handlers.motion.editor.createDialogs.CreateOnClick
import com.android.tools.idea.uibuilder.handlers.motion.editor.createDialogs.CreateOnSwipe
import com.android.tools.idea.uibuilder.handlers.motion.editor.ui.MotionEditor
import com.intellij.openapi.actionSystem.ActionUpdateThread

/** Click or Swipe Handler action. */
open class ClickOrSwipeAction(motionEditor: MotionEditor) :
  OpenPopUpAction("Create Click or Swipe Handler", MEIcons.CREATE_ON_STAR) {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override val actions =
    listOf(PanelAction(CreateOnClick(), motionEditor), PanelAction(CreateOnSwipe(), motionEditor))
}
