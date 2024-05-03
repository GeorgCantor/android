/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.profilers.taskbased.tabs.home.processlist.deviceselection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.tools.idea.IdeInfo
import com.android.tools.profiler.proto.Common
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxStrings.DEVICE_SELECTION_TOOLTIP
import com.android.tools.profilers.taskbased.common.dividers.ToolWindowHorizontalDivider
import com.android.tools.profilers.taskbased.home.selections.deviceprocesses.ProcessListModel.ProfilerDeviceSelection
import com.android.tools.profilers.taskbased.tabs.home.processlist.deviceselection.common.DeviceSelectionContent
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceSelection(deviceList: List<Common.Device>,
                    selectedDevice: ProfilerDeviceSelection?,
                    selectedDevicesCount: Int,
                    onDeviceSelection: (Common.Device) -> Unit) {
  Column (modifier = Modifier.fillMaxWidth()) {
    if (IdeInfo.isGameTool()) {
      DeviceSelectionDropdown(deviceList = deviceList, selectedDevice = selectedDevice, onDeviceSelection = onDeviceSelection)
    }
    else {
      Tooltip(
        { Text(DEVICE_SELECTION_TOOLTIP) }
      ) {
        DeviceSelectionContent(selectedDevice, selectedDevicesCount)
      }
      ToolWindowHorizontalDivider()
    }
  }
}