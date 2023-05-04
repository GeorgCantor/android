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
package com.android.tools.idea.devicemanagerv2

import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.google.wireless.android.sdk.stats.DeviceManagerEvent.EventKind.VIRTUAL_SHOW_ON_DISK_ACTION
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import kotlinx.coroutines.launch

class ShowAction : AnAction("Show", "Show this device", AllIcons.Actions.Show) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.updateFromDeviceAction(DeviceHandle::showAction)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val deviceHandle = e.deviceHandle()
    val showAction = deviceHandle?.showAction ?: return

    // TODO: generalize to non-AVDs when they implement it
    DeviceManagerUsageTracker.logDeviceManagerEvent(VIRTUAL_SHOW_ON_DISK_ACTION)

    deviceHandle.scope.launch { showAction.show() }
  }
}
