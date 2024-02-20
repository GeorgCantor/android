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
package com.android.tools.profilers.taskbased.tabs.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.tools.profilers.IdeProfilerComponents
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxDimensions.SELECTION_PANEL_MAX_RATIO_FLOAT
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxDimensions.SELECTION_PANEL_MIN_RATIO_FLOAT
import com.android.tools.profilers.taskbased.home.TaskHomeTabModel
import com.android.tools.profilers.taskbased.tabs.TaskTabComponent
import com.android.tools.profilers.taskbased.tabs.home.processlist.ProcessList
import com.android.tools.profilers.taskbased.tabs.taskgridandbars.TaskGridAndBars
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout

@Composable
fun TaskHomeTab(taskHomeTabModel: TaskHomeTabModel, ideProfilerComponents: IdeProfilerComponents) {
  Column(
    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    val taskGridModel = taskHomeTabModel.taskGridModel
    val taskHandlers = taskHomeTabModel.taskHandlers
    val isSelectedDeviceProcessTaskValid = taskHomeTabModel::isSelectedDeviceProcessTaskValid
    val isProfilingFromProcessStart by taskHomeTabModel.isProfilingFromProcessStart.collectAsState()
    val processListModel = taskHomeTabModel.processListModel
    val isPreferredProcessSelected by processListModel.isPreferredProcessSelected.collectAsState()
    val profilers = taskHomeTabModel.profilers

    HorizontalSplitLayout(
      minRatio = SELECTION_PANEL_MIN_RATIO_FLOAT,
      maxRatio = SELECTION_PANEL_MAX_RATIO_FLOAT,
      first = {
        ProcessList(processListModel, it)
      },
      second = {
        TaskGridAndBars(taskGridModel, isProfilingFromProcessStart, isPreferredProcessSelected,
                        taskHomeTabModel::setIsProfilingFromProcessStart, isSelectedDeviceProcessTaskValid,
                        taskHomeTabModel::onEnterTaskButtonClick, taskHandlers, profilers, ideProfilerComponents, it)
      }
    )
  }
}

class TaskHomeTabComponent(taskHomeTabModel: TaskHomeTabModel, ideProfilerComponents: IdeProfilerComponents) : TaskTabComponent(
  { TaskHomeTab(taskHomeTabModel, ideProfilerComponents) })