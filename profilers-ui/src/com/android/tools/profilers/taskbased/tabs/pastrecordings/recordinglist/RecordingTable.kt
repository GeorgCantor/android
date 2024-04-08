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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.android.tools.adtui.model.formatter.TimeFormatter
import com.android.tools.profilers.sessions.SessionItem
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxColors.TABLE_HEADER_BACKGROUND_COLOR
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxColors.TABLE_ROW_SELECTION_BACKGROUND_COLOR
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxColors.TABLE_SEPARATOR_COLOR
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxDimensions.RECORDING_TASKS_COL_WIDTH_DP
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxDimensions.RECORDING_TIME_COL_WIDTH_DP
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxDimensions.TABLE_ROW_HEIGHT_DP
import com.android.tools.profilers.taskbased.common.constants.TaskBasedUxDimensions.TABLE_ROW_HORIZONTAL_PADDING_DP
import com.android.tools.profilers.taskbased.common.table.leftAlignedColumnText
import com.android.tools.profilers.taskbased.common.table.rightAlignedColumnText
import com.android.tools.profilers.tasks.ProfilerTaskType
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

@Composable
fun RecordingListRow(selectedRecording: SessionItem?,
                     onRecordingSelection: (SessionItem?) -> Unit,
                     recording: SessionItem,
                     supportedTask: ProfilerTaskType) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(TABLE_ROW_HEIGHT_DP)
      .background(
        if (recording == selectedRecording)
          TABLE_ROW_SELECTION_BACKGROUND_COLOR
        else
          Color.Transparent
      )
      .padding(horizontal = TABLE_ROW_HORIZONTAL_PADDING_DP)
      .selectable(
        selected = recording == selectedRecording,
        onClick = {
          val newSelectedRecording = if (recording != selectedRecording) recording else null
          onRecordingSelection(newSelectedRecording)
        })
      .testTag("RecordingListRow")
  ) {
    leftAlignedColumnText(recording.name, rowScope = this)
    rightAlignedColumnText(text = TimeFormatter.getLocalizedDateTime(recording.sessionMetaData.startTimestampEpochMs),
                           colWidth = RECORDING_TIME_COL_WIDTH_DP)
    rightAlignedColumnText(text = supportedTask.description, colWidth = RECORDING_TASKS_COL_WIDTH_DP)
  }
}

@Composable
fun RecordingListHeader() {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(TABLE_ROW_HEIGHT_DP)
      .background(TABLE_HEADER_BACKGROUND_COLOR)
      .padding(horizontal = TABLE_ROW_HORIZONTAL_PADDING_DP)
  ) {
    leftAlignedColumnText(text = "Recording name", rowScope = this)
    Divider(thickness = 1.dp, orientation = Orientation.Vertical)
    rightAlignedColumnText(text = "Recording time", colWidth = RECORDING_TIME_COL_WIDTH_DP)
    Divider(thickness = 1.dp, orientation = Orientation.Vertical)
    rightAlignedColumnText(text = "Recorded tasks", colWidth = RECORDING_TASKS_COL_WIDTH_DP)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingTable(recordingList: List<SessionItem>, selectedRecording: SessionItem?, onRecordingSelection: (SessionItem?) -> Unit,
                   getSupportedTask: (SessionItem) -> ProfilerTaskType) {
  val listState = rememberLazyListState()

  Box {
    LazyColumn(
      state = listState
    ) {
      stickyHeader {
        RecordingListHeader()
        Divider(color = TABLE_SEPARATOR_COLOR, modifier = Modifier.fillMaxWidth(), thickness = 1.dp, orientation = Orientation.Horizontal)
      }
      items(items = recordingList) { recording ->
        RecordingListRow(selectedRecording, onRecordingSelection, recording, getSupportedTask(recording))
      }
    }

    VerticalScrollbar(
      adapter = rememberScrollbarAdapter(listState),
      modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd),
    )
  }
}