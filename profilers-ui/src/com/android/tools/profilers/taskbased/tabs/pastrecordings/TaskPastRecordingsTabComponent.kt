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
package com.android.tools.profilers.taskbased.tabs.pastrecordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.tools.profilers.IdeProfilerComponents
import com.android.tools.profilers.taskbased.home.TaskHomeTabModel
import com.android.tools.profilers.taskbased.pastrecordings.PastRecordingsTabModel
import com.android.tools.profilers.taskbased.tabs.TaskTabComponent
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun TaskPastRecordingsTab(pastRecordingsTabModel: PastRecordingsTabModel, ideProfilerComponents: IdeProfilerComponents) {
  // TODO(b/277797248): Replace sample Compose UI with real UI content.
  Column(
    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    DefaultButton(onClick = {}) {
      Text("Task-Based UX Profiler Past Recordings Tab")
    }
  }
}

class TaskPastRecordingsTabComponent(pastRecordingsTabModel: PastRecordingsTabModel,
                                     ideProfilerComponents: IdeProfilerComponents) : TaskTabComponent(
  { TaskPastRecordingsTab(pastRecordingsTabModel, ideProfilerComponents) })
