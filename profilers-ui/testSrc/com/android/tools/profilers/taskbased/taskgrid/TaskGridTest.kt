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
package com.android.tools.profilers.taskbased.taskgrid

import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.singleWindowApplication
import com.android.testutils.ignore.IgnoreTestRule
import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Trace
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.SessionArtifactUtils
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.JewelThemedComposableWrapper
import com.android.tools.profilers.event.FakeEventService
import com.android.tools.profilers.sessions.SessionsManager
import com.android.tools.profilers.taskbased.tabs.taskgridandbars.taskgrid.TaskGrid
import com.android.tools.profilers.taskbased.task.TaskGridModel
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.android.tools.profilers.tasks.taskhandlers.ProfilerTaskHandlerFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class TaskGridTest {
  private val myTimer = FakeTimer()
  private val myTransportService = FakeTransportService(myTimer, false)

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val ignoreTestRule = IgnoreTestRule()

  @get:Rule
  var myGrpcChannel = FakeGrpcChannel("TaskGridTestChannel", myTransportService, FakeEventService())

  private lateinit var myProfilers: StudioProfilers
  private lateinit var myManager: SessionsManager
  private lateinit var ideProfilerServices: FakeIdeProfilerServices
  private lateinit var taskGridModel: TaskGridModel

  @Before
  fun setup() {
    ideProfilerServices = FakeIdeProfilerServices()
    myProfilers = StudioProfilers(
      ProfilerClient(myGrpcChannel.channel),
      ideProfilerServices,
      myTimer
    )
    myManager = myProfilers.sessionsManager
    taskGridModel = TaskGridModel()
    ideProfilerServices.enableTaskBasedUx(true)
    val taskHandlers = ProfilerTaskHandlerFactory.createTaskHandlers(myManager)
    taskHandlers.forEach { myProfilers.addTaskHandler(it.key, it.value) }
  }

  @Ignore("b/309566948")
  @Test
  fun `visual test, light theme, process-selection based`() {
    singleWindowApplication(
      title = "Testing TaskGrid in Dark Theme",
    ) {
      JewelThemedComposableWrapper(isDark = false) {
        TaskGrid(taskGridModel, {}, myProfilers.taskHandlers.keys.toList(), myProfilers)
      }
    }
  }

  @Ignore("b/309566948")
  @Test
  fun `visual test, light theme, recording-selection based`() {
    singleWindowApplication(
      title = "Testing TaskGrid in Dark Theme",
    ) {
      JewelThemedComposableWrapper(isDark = false) {
        val heapDumpArtifact = SessionArtifactUtils.createHprofSessionArtifact(myProfilers,
                                                                               Common.Session.newBuilder().setSessionId(1L).build(), 0L, 1L)
        val sessionItem = SessionArtifactUtils.createSessionItem(myProfilers, heapDumpArtifact.session, heapDumpArtifact.session.sessionId,
                                                                 listOf(heapDumpArtifact))
        TaskGrid(taskGridModel, sessionItem, myProfilers.taskHandlers)
      }
    }
  }

  @Ignore("b/309566948")
  @Test
  fun `visual test, dark theme, process-selection based`() {
    singleWindowApplication(
      title = "Testing TaskGrid in Dark Theme",
    ) {
      JewelThemedComposableWrapper(isDark = true) {
        TaskGrid(taskGridModel, {}, myProfilers.taskHandlers.keys.toList(), myProfilers)
      }
    }
  }

  @Ignore("b/309566948")
  @Test
  fun `visual test, dark theme, recording-selection based`() {
    singleWindowApplication(
      title = "Testing TaskGrid in Dark Theme",
    ) {
      JewelThemedComposableWrapper(isDark = true) {
        val heapDumpArtifact = SessionArtifactUtils.createHprofSessionArtifact(myProfilers,
                                                                               Common.Session.newBuilder().setSessionId(1L).build(), 0L, 1L)
        val sessionItem = SessionArtifactUtils.createSessionItem(myProfilers, heapDumpArtifact.session, heapDumpArtifact.session.sessionId,
                                                                 listOf(heapDumpArtifact))
        TaskGrid(taskGridModel, sessionItem, myProfilers.taskHandlers)
      }
    }
  }

  @Test
  fun `correct number of task grid items are displayed and clickable`() {
    // There should be one task grid item for every task handler. Seven task handlers were added in the setup step of this test.
    composeTestRule.setContent {
      JewelThemedComposableWrapper(isDark = false) {
        TaskGrid(taskGridModel, {}, myProfilers.taskHandlers.keys.toList(), myProfilers)
      }
    }

    composeTestRule.onAllNodesWithTag(testTag = "TaskGridItem").assertCountEquals(8)

    composeTestRule.onNodeWithText("System Trace").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("Callstack Sample").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("Java/Kotlin Method Trace").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("Java/Kotlin Method Sample (legacy)").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("Java/Kotlin Allocations").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("Heap Dump").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("Native Allocations").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("Live View").assertIsDisplayed().assertIsEnabled()
  }

  @Test
  fun `clicking task registers task type selection in model`() {
    composeTestRule.setContent {
      JewelThemedComposableWrapper(isDark = false) {
        TaskGrid(taskGridModel, {}, myProfilers.taskHandlers.keys.toList(), myProfilers)
      }
    }

    composeTestRule.onAllNodesWithTag(testTag = "TaskGridItem").assertCountEquals(8)

    composeTestRule.onNodeWithText("System Trace").assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText("System Trace").performClick()

    assertThat(taskGridModel.selectedTaskType.value).isEqualTo(ProfilerTaskType.SYSTEM_TRACE)
  }

  @Test
  fun `only supported tasks show up on recording selection (single supported task)`() {
    composeTestRule.setContent {
      JewelThemedComposableWrapper(isDark = false) {
        val heapDumpArtifact = SessionArtifactUtils.createHprofSessionArtifact(myProfilers,
                                                                               Common.Session.newBuilder().setSessionId(1L).build(), 0L, 1L)
        val sessionItem = SessionArtifactUtils.createSessionItem(myProfilers, heapDumpArtifact.session, heapDumpArtifact.session.sessionId,
                                                                 listOf(heapDumpArtifact))
        TaskGrid(taskGridModel, sessionItem, myProfilers.taskHandlers)
      }
    }

    // Heap dump recording only has one supported task (Heap Dump task).
    composeTestRule.onAllNodesWithTag("TaskGridItem").assertCountEquals(1)
    // If a task is displayed post-recording selection, then it must be enabled.
    composeTestRule.onAllNodesWithTag("TaskGridItem").assertAll(isEnabled())
  }

  @Test
  fun `only supported tasks show up on recording selection (multiple supported tasks)`() {
    composeTestRule.setContent {
      JewelThemedComposableWrapper(isDark = false) {
        val javaKotlinMethodArtifact = SessionArtifactUtils.createCpuCaptureSessionArtifactWithConfig(
          myProfilers, Common.Session.newBuilder().setSessionId(1L).build(), 1L, 1L,
          Trace.TraceConfiguration.newBuilder().setArtOptions(Trace.ArtOptions.getDefaultInstance()).build())
        val sessionItem = SessionArtifactUtils.createSessionItem(myProfilers, javaKotlinMethodArtifact.session,
                                                                 javaKotlinMethodArtifact.session.sessionId,
                                                                 listOf(javaKotlinMethodArtifact))
        TaskGrid(taskGridModel, sessionItem, myProfilers.taskHandlers)
      }
    }

    // Java/Kotlin ART-based recording has two supported tasks (Java/Kotlin Method Sample and Java/Kotlin Method Trace tasks).
    composeTestRule.onAllNodesWithTag("TaskGridItem").assertCountEquals(2)
    // If a task is displayed post-recording selection, then it must be enabled.
    composeTestRule.onAllNodesWithTag("TaskGridItem").assertAll(isEnabled())
  }
}