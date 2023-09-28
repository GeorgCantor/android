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
package com.android.tools.profilers.tasks.taskhandlers.singleartifact.memory

import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Common.Process.ExposureLevel
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.tasks.taskhandlers.TaskHandlerTestUtils.createHeapProfdSessionArtifact
import com.android.tools.profilers.tasks.taskhandlers.TaskHandlerTestUtils.createHprofSessionArtifact
import com.android.tools.profilers.tasks.taskhandlers.TaskHandlerTestUtils.createSessionItem
import com.android.tools.profilers.event.FakeEventService
import com.android.tools.profilers.memory.MainMemoryProfilerStage
import com.android.tools.profilers.sessions.SessionsManager
import com.android.tools.profilers.tasks.ProfilerTaskType
import com.android.tools.profilers.tasks.args.singleartifact.memory.NativeAllocationsTaskArgs
import com.android.tools.profilers.tasks.taskhandlers.TaskHandlerTestUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertFailsWith

@RunWith(Parameterized::class)
class NativeAllocationsTaskHandlerTest(private val myExposureLevel: ExposureLevel){
  private val myTimer = FakeTimer()
  private val myTransportService = FakeTransportService(myTimer, false)

  @get:Rule
  var myGrpcChannel = FakeGrpcChannel("NativeAllocationsTaskHandlerTestChannel", myTransportService, FakeEventService())

  private lateinit var myProfilers: StudioProfilers
  private lateinit var ideProfilerServices: FakeIdeProfilerServices
  private lateinit var myManager: SessionsManager
  private lateinit var myNativeAllocationsTaskHandler: NativeAllocationsTaskHandler

  @Before
  fun setup() {
    ideProfilerServices = FakeIdeProfilerServices()
    myProfilers = StudioProfilers(
      ProfilerClient(myGrpcChannel.channel),
      ideProfilerServices,
      myTimer
    )
    myManager = myProfilers.sessionsManager
    myNativeAllocationsTaskHandler = NativeAllocationsTaskHandler(myManager)
    myProfilers.addTaskHandler(ProfilerTaskType.NATIVE_ALLOCATIONS, myNativeAllocationsTaskHandler)
    assertThat(myManager.sessionArtifacts).isEmpty()
    assertThat(myManager.selectedSession).isEqualTo(Common.Session.getDefaultInstance())
    assertThat(myManager.profilingSession).isEqualTo(Common.Session.getDefaultInstance())
    ideProfilerServices.enableTaskBasedUx(true)
  }

  @Test
  fun testSetupStageCalledOnEnterAndSetsStageCorrectly() {
    val heapProfdSessionArtifact = createHeapProfdSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val nativeAllocationsTaskArgs = NativeAllocationsTaskArgs(heapProfdSessionArtifact)
    // Verify that the stage is not set in the StudioProfilers stage management before the call to setupStage.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)
    // Verify that the current stage stored in the MemoryTaskHandler is not set before the call to setupStage.
    myNativeAllocationsTaskHandler.enter(nativeAllocationsTaskArgs)
    // Verify that the stage set in the StudioProfilers stage management is correct.
    assertThat(myProfilers.stage).isInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testSupportsArtifactWithHeapProfdArtifact() {
    val heapProfdSessionArtifact = createHeapProfdSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    assertThat(myNativeAllocationsTaskHandler.supportsArtifact(heapProfdSessionArtifact)).isTrue()
  }

  @Test
  fun testSupportsArtifactWithNonHeapProfdArtifact() {
    val hprofSessionArtifact = createHprofSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    assertThat(myNativeAllocationsTaskHandler.supportsArtifact(hprofSessionArtifact)).isFalse()
  }

  @Test
  fun testStartTaskInvokedOnEnterWithAliveSession() {
    TaskHandlerTestUtils.startSession(myExposureLevel, myProfilers, myTransportService, myTimer, Common.ProfilerTaskType.NATIVE_ALLOCATIONS)
    val heapProfdSessionArtifact = createHeapProfdSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val nativeAllocationsTaskArgs = NativeAllocationsTaskArgs(heapProfdSessionArtifact)
    myNativeAllocationsTaskHandler.enter(nativeAllocationsTaskArgs)
    // The session is alive, so startTask and thus startCapture should be called.
    assertThat(myNativeAllocationsTaskHandler.stage!!.recordingOptionsModel.isRecording)
  }

  @Test
  fun testStartTaskWithSetStage() {
    TaskHandlerTestUtils.startSession(myExposureLevel, myProfilers, myTransportService, myTimer, Common.ProfilerTaskType.NATIVE_ALLOCATIONS)
    // To start the task and thus the capture, the stage must be set up before. This will be taken care of via the setupStage() method call,
    // on enter of the task handler, but this test is testing the explicit invocation of startTask.
    myNativeAllocationsTaskHandler.setupStage()
    myNativeAllocationsTaskHandler.startTask()
    assertThat(myNativeAllocationsTaskHandler.stage!!.recordingOptionsModel.isRecording).isTrue()
  }

  @Test
  fun testStartTaskWithUnsetStage() {
    // To start the task and thus the capture, the stage must be set up before. Here we will test the case where startTask is invoked
    // without the stage being set precondition being met.
    val exception = assertFailsWith<Throwable> {
      myNativeAllocationsTaskHandler.startTask()
    }
    assertThat(myNativeAllocationsTaskHandler.stage).isNull()
    assertThat(exception.message).isEqualTo(
      "There was an error with the Native Allocations task. Error message: Cannot start the task as the InterimStage was null.")
  }

  @Test
  fun testStopTaskSuccessfullyTerminatesRecording() {
    TaskHandlerTestUtils.startSession(myExposureLevel, myProfilers, myTransportService, myTimer, Common.ProfilerTaskType.NATIVE_ALLOCATIONS)
    // Start the task successfully. No need to configure the StartTrace event's status to be SUCCESS as the Fake StartTrace command for
    // memory profiler assumes a successful start trace status event.
    myNativeAllocationsTaskHandler.setupStage()
    myNativeAllocationsTaskHandler.startTask()
    assertThat(myNativeAllocationsTaskHandler.stage!!.recordingOptionsModel.isRecording).isTrue()

    // Wait for successful start event to be consumed.
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    // Stop the task successfully. No need to configure the StopTrace event's status to be SUCCESS as the Fake StopTrace command for
    // memory profiler assumes a successful stop trace status event.
    myNativeAllocationsTaskHandler.stopTask()
    // Wait for successful end event to be consumed.
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS)
    assertThat(myNativeAllocationsTaskHandler.stage!!.recordingOptionsModel.isRecording).isFalse()
  }

  @Test
  fun testLoadTaskInvokedOnEnterWithDeadSession() {
    TaskHandlerTestUtils.startAndStopSession(myExposureLevel, myProfilers, myManager, myTransportService, myTimer,
                                             Common.ProfilerTaskType.NATIVE_ALLOCATIONS)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    // Create a fake HeapProfdSessionArtifact.
    val heapProfdSessionArtifact = createHeapProfdSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val nativeAllocationsTaskArgs = NativeAllocationsTaskArgs(heapProfdSessionArtifact)
    // The session is not alive (dead) so loadTask and thus loadCapture should be called.
    val argsSuccessfullyUsed = myNativeAllocationsTaskHandler.enter(nativeAllocationsTaskArgs)
    assertThat(argsSuccessfullyUsed).isTrue()

    // Verify that the artifact doSelect behavior is called by checking if the stage was set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testLoadTaskWithNonNullTaskArgs() {
    TaskHandlerTestUtils.startAndStopSession(myExposureLevel, myProfilers, myManager, myTransportService, myTimer,
                                             Common.ProfilerTaskType.NATIVE_ALLOCATIONS)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    val heapProfdSessionArtifact = createHeapProfdSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val nativeAllocationsTaskArgs = NativeAllocationsTaskArgs(heapProfdSessionArtifact)
    val argsSuccessfullyUsed = myNativeAllocationsTaskHandler.loadTask(nativeAllocationsTaskArgs)
    assertThat(argsSuccessfullyUsed).isTrue()

    // Verify that the artifact doSelect behavior was called by checking if the stage was set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testLoadTaskWithNullTaskArgs() {
    TaskHandlerTestUtils.startAndStopSession(myExposureLevel, myProfilers, myManager, myTransportService, myTimer,
                                             Common.ProfilerTaskType.NATIVE_ALLOCATIONS)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    val exception = assertFailsWith<Throwable> {
      myNativeAllocationsTaskHandler.loadTask(null)
    }

    assertThat(exception.message).isEqualTo(
      "There was an error with the Native Allocations task. Error message: The task arguments (TaskArgs) supplied are not of the " +
      "expected type (NativeAllocationsTaskArgs).")

    // Verify that the artifact doSelect behavior was not called by checking if the stage was not set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testCreateArgsSuccessfully() {
    val selectedSession = Common.Session.newBuilder().setSessionId(1).setEndTimestamp(100).build()
    val sessionIdToSessionItems = mapOf(
      1L to createSessionItem(myProfilers, selectedSession, 1,
                              listOf(createHeapProfdSessionArtifact(myProfilers, selectedSession, 1, 100))),
    )

    val nativeAllocationsTaskArgs = myNativeAllocationsTaskHandler.createArgs(sessionIdToSessionItems, selectedSession)
    assertThat(nativeAllocationsTaskArgs).isNotNull()
    assertThat(nativeAllocationsTaskArgs).isInstanceOf(NativeAllocationsTaskArgs::class.java)
    assertThat(nativeAllocationsTaskArgs!!.getMemoryCaptureArtifact()).isNotNull()
    assertThat(nativeAllocationsTaskArgs.getMemoryCaptureArtifact().artifactProto.fromTimestamp).isEqualTo(1L)
    assertThat(nativeAllocationsTaskArgs.getMemoryCaptureArtifact().artifactProto.toTimestamp).isEqualTo(100L)
  }

  @Test
  fun testCreateArgsFails() {
    // By setting a session id that does not match any of the session items, the task artifact will not be found in the call to createArgs
    // will fail to be constructed.
    val selectedSession = Common.Session.newBuilder().setSessionId(0).setEndTimestamp(100).build()
    val sessionIdToSessionItems = mapOf(
      1L to createSessionItem(myProfilers, selectedSession, 1, listOf(
        createHeapProfdSessionArtifact(myProfilers, selectedSession, 1, 100))),
    )

    val heapProfdSessionArtifact = myNativeAllocationsTaskHandler.createArgs(sessionIdToSessionItems, selectedSession)
    // A return value of null indicates the task args were not constructed correctly (the underlying artifact was not found or supported by
    // the task).
    assertThat(heapProfdSessionArtifact).isNull()
  }

  @Test
  fun testGetTaskName() {
    assertThat(myNativeAllocationsTaskHandler.getTaskName()).isEqualTo("Native Allocations")
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters
    fun data(): Collection<ExposureLevel> {
      return listOf(ExposureLevel.DEBUGGABLE, ExposureLevel.PROFILEABLE)
    }
  }
}