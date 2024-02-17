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
package com.android.tools.profilers.taskbased.tabs.home.processlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.tools.idea.IdeInfo
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxStrings
import com.android.tools.profilers.taskbased.home.selections.deviceprocesses.ProcessListModel
import com.android.tools.profilers.taskbased.tabs.home.processlist.deviceselection.DeviceSelection
import org.jetbrains.jewel.ui.component.Text

@Composable
fun ProcessList(processListModel: ProcessListModel, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    val deviceList by processListModel.deviceList.collectAsState()
    val selectedDevice by processListModel.selectedDevice.collectAsState()
    val selectedDevicesCount by processListModel.selectedDevicesCount.collectAsState()
    DeviceSelection(deviceList = deviceList, selectedDevice = selectedDevice, selectedDevicesCount = selectedDevicesCount,
                    onDeviceSelection = processListModel::onDeviceSelection)

    val selectedProcess by processListModel.selectedProcess.collectAsState()
    val deviceToProcessList by processListModel.deviceToProcesses.collectAsState()
    val processList = if (selectedDevice != null && deviceToProcessList.containsKey(selectedDevice!!.device)) {
      deviceToProcessList[selectedDevice!!.device]!!
    }
    else {
      listOf()
    }

    if (selectedDevicesCount == 1) {
      ProcessTable(processList = processList, selectedProcess = selectedProcess, onProcessSelection = processListModel::onProcessSelection)
    }
    else {
      val processListMessage =
        if (selectedDevicesCount == 0) TaskBasedUxStrings.NO_DEVICE_SELECTED_MESSAGE
        else TaskBasedUxStrings.MULTIPLE_DEVICES_SELECTED_MESSAGE
      InvalidDeviceSelectionProcessListMessageText(processListMessage)
    }
  }
}

@Composable
fun InvalidDeviceSelectionProcessListMessageText(text: String) {
  Box(modifier = Modifier.fillMaxSize().padding(40.dp)) {
   Text(text = text, modifier = Modifier.align(Alignment.Center), textAlign = TextAlign.Center)
  }
}