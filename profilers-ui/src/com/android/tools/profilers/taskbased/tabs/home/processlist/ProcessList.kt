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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.android.tools.profilers.taskbased.home.selections.deviceprocesses.ProcessListModel

@Composable
fun ProcessList(processListModel: ProcessListModel) {
  Column(modifier = Modifier.fillMaxSize()) {
    val deviceList by processListModel.deviceList.collectAsState()
    val selectedDevice by processListModel.selectedDevice.collectAsState()
    DeviceDropdownAndRestartButtons(deviceList = deviceList, selectedDevice = selectedDevice,
                                    onDeviceSelection = processListModel::onDeviceSelection,
                                    processListModel.profilers.ideServices::buildAndLaunchAction)

    val selectedProcess by processListModel.selectedProcess.collectAsState()
    val deviceToProcessList by processListModel.deviceToProcesses.collectAsState()
    val processList = deviceToProcessList[selectedDevice] ?: listOf()
    ProcessTable(processList = processList, selectedProcess = selectedProcess, onProcessSelection = processListModel::onProcessSelection)
  }
}

