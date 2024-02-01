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
package com.android.tools.profilers.taskbased.tabs.pastrecordings.recordinglist

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.android.tools.profilers.IdeProfilerComponents
import com.android.tools.profilers.taskbased.home.selections.recordings.RecordingListModel

@Composable
fun RecordingList(recordingListModel: RecordingListModel, ideProfilerComponents: IdeProfilerComponents, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    val isRecordingExportable = recordingListModel.isSelectedRecordingExportable()
    val isRecordingSelected = recordingListModel.isRecordingSelected()
    val selectedRecording by recordingListModel.selectedRecording.collectAsState()
    RecordingListActionsBar(artifact = recordingListModel.exportableArtifact, isRecordingExportable = isRecordingExportable,
                            isRecordingSelected = isRecordingSelected,
                            doDeleteSelectedRecording = recordingListModel::doDeleteSelectedRecording,
                            profilers = recordingListModel.profilers, ideProfilerComponents = ideProfilerComponents)

    val recordingList by recordingListModel.recordingList.collectAsState()
    RecordingTable(recordingList = recordingList, selectedRecording = selectedRecording,
                   onRecordingSelection = recordingListModel::onRecordingSelection,
                   createStringOfSupportedTasks = recordingListModel::createStringOfSupportedTasks)
  }
}