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
package com.android.tools.idea.device.explorer.files.ui.menu.item

import com.android.tools.idea.device.explorer.files.DeviceFileEntryNode
import com.android.tools.idea.device.explorer.files.ui.DeviceFileExplorerActionListener
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import java.util.stream.Collectors
import javax.swing.Icon

/**
 * A popup menu item that works for both single and multi-element selections.
 */
abstract class TreeMenuItem(val listener: DeviceFileExplorerActionListener) : PopupMenuItem {
  override val text: String
    get() {
      var nodes = listener.selectedNodes
      if (nodes == null) {
        nodes = emptyList()
      }
      return getText(nodes)
    }

  override val icon: Icon?
    get() = null

  override val isEnabled: Boolean
    get() {
      val nodes = listener.selectedNodes ?: return false
      return isEnabled(nodes)
    }

  override val isVisible: Boolean
    get() {
      val nodes = listener.selectedNodes ?: return false
      return isVisible(nodes)
    }

  override val action: AnAction = object : ToggleAction() {
    override fun update(e: AnActionEvent) {
      val presentation = e.presentation
      presentation.text = text
      presentation.isEnabled = isEnabled
      presentation.isVisible = isVisible
      presentation.icon = icon
      Toggleable.setSelected(presentation, isSelected(e))
    }

    override fun actionPerformed(e: AnActionEvent) {
      run()
      setSelected(e, !isSelected())
    }

    override fun isSelected(e: AnActionEvent): Boolean {
      return isSelected()
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      setSelected(state)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
  }

  override fun run() {
    var nodes = listener.selectedNodes ?: return
    nodes = nodes.stream().filter { node: DeviceFileEntryNode ->
      this.isEnabled(node)
    }.collect(Collectors.toList())
    if (nodes.isNotEmpty()) {
      run(nodes)
    }
  }

  open fun isEnabled(nodes: List<DeviceFileEntryNode>): Boolean =
    nodes.stream().anyMatch { node: DeviceFileEntryNode -> this.isEnabled(node) }

  open fun isVisible(nodes: List<DeviceFileEntryNode>): Boolean =
    nodes.stream().anyMatch { node: DeviceFileEntryNode -> this.isVisible(node) }

  open fun isVisible(node: DeviceFileEntryNode): Boolean = true

  open fun isEnabled(node: DeviceFileEntryNode): Boolean = isVisible(node)

  abstract fun getText(nodes: List<DeviceFileEntryNode>): String

  abstract fun run(nodes: List<DeviceFileEntryNode>)

  abstract fun isSelected():Boolean
  abstract fun setSelected(selected: Boolean)
}