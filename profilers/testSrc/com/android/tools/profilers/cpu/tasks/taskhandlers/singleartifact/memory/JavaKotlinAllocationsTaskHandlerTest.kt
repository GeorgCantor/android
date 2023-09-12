package com.android.tools.profilers.cpu.tasks.taskhandlers.singleartifact.memory

import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.transport.faketransport.FakeGrpcChannel
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Common.Process.ExposureLevel
import com.android.tools.profiler.proto.Trace
import com.android.tools.profilers.FakeIdeProfilerServices
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.cpu.tasks.taskhandlers.TaskHandlerTestUtils
import com.android.tools.profilers.cpu.tasks.taskhandlers.TaskHandlerTestUtils.createAllocationSessionArtifact
import com.android.tools.profilers.cpu.tasks.taskhandlers.TaskHandlerTestUtils.createLegacyAllocationsSessionArtifact
import com.android.tools.profilers.cpu.tasks.taskhandlers.TaskHandlerTestUtils.createSessionItem
import com.android.tools.profilers.event.FakeEventService
import com.android.tools.profilers.memory.AllocationStage
import com.android.tools.profilers.memory.HeapProfdSessionArtifact
import com.android.tools.profilers.memory.MainMemoryProfilerStage
import com.android.tools.profilers.sessions.SessionsManager
import com.android.tools.profilers.tasks.args.singleartifact.memory.JavaKotlinAllocationsTaskArgs
import com.android.tools.profilers.tasks.args.singleartifact.memory.LegacyJavaKotlinAllocationsTaskArgs
import com.android.tools.profilers.tasks.taskhandlers.singleartifact.memory.JavaKotlinAllocationsTaskHandler
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

class JavaKotlinAllocationsTaskHandlerTest {
  private val myTimer = FakeTimer()
  private val myTransportService = FakeTransportService(myTimer, false)

  @get:Rule
  var myGrpcChannel = FakeGrpcChannel("JavaKotlinAllocationsTaskHandlerTestChannel", myTransportService, FakeEventService())

  private lateinit var myProfilers: StudioProfilers
  private lateinit var ideProfilerServices: FakeIdeProfilerServices
  private lateinit var myManager: SessionsManager
  private lateinit var myJavaKotlinAllocationsTaskHandler: JavaKotlinAllocationsTaskHandler

  @Before
  fun setup() {
    ideProfilerServices = FakeIdeProfilerServices()
    myProfilers = StudioProfilers(
      ProfilerClient(myGrpcChannel.channel),
      ideProfilerServices,
      myTimer
    )
    myManager = myProfilers.sessionsManager
    myJavaKotlinAllocationsTaskHandler = JavaKotlinAllocationsTaskHandler(myManager)
    assertThat(myManager.sessionArtifacts).isEmpty()
    assertThat(myManager.selectedSession).isEqualTo(Common.Session.getDefaultInstance())
    assertThat(myManager.profilingSession).isEqualTo(Common.Session.getDefaultInstance())
    ideProfilerServices.enableTaskBasedUx(true)
  }

  @Test
  fun testSetupStageCalledOnEnterAndSetsStageCorrectly() {
    val allocationsSessionArtifact = createAllocationSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val allocationsTaskArgs = JavaKotlinAllocationsTaskArgs(allocationsSessionArtifact)
    // Verify that the stage is not set in the StudioProfilers stage management before the call to setupStage.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)
    // Verify that the current stage stored in the MemoryTaskHandler is not set before the call to setupStage.
    myJavaKotlinAllocationsTaskHandler.enter(allocationsTaskArgs)
    // Verify that the stage set in the StudioProfilers stage management is correct.
    assertThat(myProfilers.stage).isInstanceOf(AllocationStage::class.java)
  }

  @Test
  fun testSetupStageCalledOnEnterAndSetsStageCorrectlyWithLegacyAllocationsSessionArtifact() {
    val legacyAllocationsSessionArtifact = createLegacyAllocationsSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L,
                                                                                  100L)
    val legacyAllocationsTaskArgs = LegacyJavaKotlinAllocationsTaskArgs(legacyAllocationsSessionArtifact)
    // Verify that the stage is not set in the StudioProfilers stage management before the call to setupStage.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)
    // Verify that the current stage stored in the MemoryTaskHandler is not set before the call to setupStage.
    myJavaKotlinAllocationsTaskHandler.enter(legacyAllocationsTaskArgs)
    // Verify that the stage set in the StudioProfilers stage management is correct.
    assertThat(myProfilers.stage).isInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testSupportsArtifactWithAllocationsArtifact() {
    val allocationsSessionArtifact = createAllocationSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    assertThat(myJavaKotlinAllocationsTaskHandler.supportsArtifact(allocationsSessionArtifact)).isTrue()
  }

  @Test
  fun testSupportsArtifactWithLegacyAllocationsSessionArtifact() {
    val legacyAllocationsSessionArtifact = createLegacyAllocationsSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L,
                                                                                  100L)
    assertThat(myJavaKotlinAllocationsTaskHandler.supportsArtifact(legacyAllocationsSessionArtifact)).isTrue()
  }

  @Test
  fun testSupportsArtifactWithNonAllocationsArtifact() {
    val heapProfdSessionArtifact = HeapProfdSessionArtifact(myProfilers, Common.Session.getDefaultInstance(),
                                                            Common.SessionMetaData.getDefaultInstance(),
                                                            Trace.TraceInfo.getDefaultInstance())
    assertThat(myJavaKotlinAllocationsTaskHandler.supportsArtifact(heapProfdSessionArtifact)).isFalse()
  }

  @Test
  fun testStartTaskInvokedOnEnterWithAliveSession() {
    TaskHandlerTestUtils.startSession(ExposureLevel.DEBUGGABLE, myProfilers, myTransportService, myTimer)
    val allocationsSessionArtifact = createAllocationSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val allocationsTaskArgs = JavaKotlinAllocationsTaskArgs(allocationsSessionArtifact)
    myJavaKotlinAllocationsTaskHandler.enter(allocationsTaskArgs)
    // The session is alive, so startTask and thus startCapture should be called.
    assertThat(myJavaKotlinAllocationsTaskHandler.stage!!.recordingOptionsModel.isRecording)
  }

  @Test
  fun testStartTaskWithSetStage() {
    TaskHandlerTestUtils.startSession(ExposureLevel.DEBUGGABLE, myProfilers, myTransportService, myTimer)
    // To start the task and thus the capture, the stage must be set up before. This will be taken care of via the setupStage() method call,
    // on enter of the task handler, but this test is testing the explicit invocation of startTask.
    myJavaKotlinAllocationsTaskHandler.setupStage()
    myJavaKotlinAllocationsTaskHandler.startTask()
    assertThat(myJavaKotlinAllocationsTaskHandler.stage!!.recordingOptionsModel.isRecording).isTrue()
  }

  @Test
  fun testStartTaskWithUnsetStage() {
    // To start the task and thus the capture, the stage must be set up before. Here we will test the case where startTask is invoked
    // without the stage being set precondition being met.
    val exception = assertFailsWith<Throwable> {
      myJavaKotlinAllocationsTaskHandler.startTask()
    }
    assertThat(myJavaKotlinAllocationsTaskHandler.stage).isNull()
    assertThat(exception.message).isEqualTo(
      "There was an error with the Java/Kotlin Allocations task. Error message: Cannot start the task as the InterimStage was null.")
  }

  @Test
  fun testLoadTaskInvokedOnEnterWithDeadSession() {
    TaskHandlerTestUtils.startAndStopSession(ExposureLevel.DEBUGGABLE, myProfilers, myManager, myTransportService, myTimer)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    // Create a fake AllocationSessionArtifact.
    val allocationsSessionArtifact = createAllocationSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val allocationsTaskArgs = JavaKotlinAllocationsTaskArgs(allocationsSessionArtifact)
    // The session is not alive (dead) so loadTask and thus loadCapture should be called.
    val argsSuccessfullyUsed = myJavaKotlinAllocationsTaskHandler.enter(allocationsTaskArgs)
    assertThat(argsSuccessfullyUsed).isTrue()

    // Verify that the artifact doSelect behavior is called by checking if the stage was set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isInstanceOf(AllocationStage::class.java)
  }

  @Test
  fun testLoadTaskInvokedOnEnterWithDeadSessionAndLegacyArgs() {
    TaskHandlerTestUtils.startAndStopSession(ExposureLevel.DEBUGGABLE, myProfilers, myManager, myTransportService, myTimer)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    // Create a fake LegacyAllocationsSessionArtifact.
    val legacyAllocationsSessionArtifact = createLegacyAllocationsSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L,
                                                                                  100L)
    val legacyAllocationsTaskArgs = LegacyJavaKotlinAllocationsTaskArgs(legacyAllocationsSessionArtifact)
    // The session is not alive (dead) so loadTask and thus loadCapture should be called.
    val argsSuccessfullyUsed = myJavaKotlinAllocationsTaskHandler.enter(legacyAllocationsTaskArgs)
    assertThat(argsSuccessfullyUsed).isTrue()

    // Verify that the artifact doSelect behavior is called by checking if the stage was set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testLoadTaskWithNonNullTaskArgs() {
    TaskHandlerTestUtils.startAndStopSession(ExposureLevel.DEBUGGABLE, myProfilers, myManager, myTransportService, myTimer)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    val allocationsSessionArtifact = createAllocationSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L, 100L)
    val allocationsTaskArgs = JavaKotlinAllocationsTaskArgs(allocationsSessionArtifact)
    val argsSuccessfullyUsed = myJavaKotlinAllocationsTaskHandler.loadTask(allocationsTaskArgs)
    assertThat(argsSuccessfullyUsed).isTrue()

    // Verify that the artifact doSelect behavior was called by checking if the stage was set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isInstanceOf(AllocationStage::class.java)
  }

  @Test
  fun testLoadTaskWithNonNullLegacyTaskArgs() {
    TaskHandlerTestUtils.startAndStopSession(ExposureLevel.DEBUGGABLE, myProfilers, myManager, myTransportService, myTimer)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    val legacyAllocationsSessionArtifact = createLegacyAllocationsSessionArtifact(myProfilers, Common.Session.getDefaultInstance(), 1L,
                                                                                  100L)
    val legacyAllocationsTaskArgs = LegacyJavaKotlinAllocationsTaskArgs(legacyAllocationsSessionArtifact)
    val argsSuccessfullyUsed = myJavaKotlinAllocationsTaskHandler.loadTask(legacyAllocationsTaskArgs)
    assertThat(argsSuccessfullyUsed).isTrue()

    // Verify that the artifact doSelect behavior was called by checking if the stage was set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testLoadTaskWithNullTaskArgs() {
    TaskHandlerTestUtils.startAndStopSession(ExposureLevel.DEBUGGABLE, myProfilers, myManager, myTransportService, myTimer)

    // Before enter + loadTask, the stage should not be set yet.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)

    val exception = assertFailsWith<Throwable> {
      myJavaKotlinAllocationsTaskHandler.loadTask(null)
    }

    assertThat(exception.message).isEqualTo(
      "There was an error with the Java/Kotlin Allocations task. Error message: The task arguments (TaskArgs) supplied are not of the " +
      "expected type (JavaKotlinAllocationTaskArgs).")

    // Verify that the artifact doSelect behavior was not called by checking if the stage was not set to MainMemoryProfilerStage.
    assertThat(myProfilers.stage).isNotInstanceOf(MainMemoryProfilerStage::class.java)
  }

  @Test
  fun testCreateArgsSuccessfully() {
    val selectedSession = Common.Session.newBuilder().setSessionId(1).setEndTimestamp(100).build()
    val sessionIdToSessionItems = mapOf(
      1L to createSessionItem(myProfilers, selectedSession, 1, listOf(
        createAllocationSessionArtifact(myProfilers, selectedSession, 1, 100))),
    )

    val allocationsTaskArgs = myJavaKotlinAllocationsTaskHandler.createArgs(sessionIdToSessionItems, selectedSession)
    assertThat(allocationsTaskArgs).isNotNull()
    assertThat(allocationsTaskArgs).isInstanceOf(JavaKotlinAllocationsTaskArgs::class.java)
    assertThat(allocationsTaskArgs!!.getAllocationSessionArtifact()).isNotNull()
    assertThat(allocationsTaskArgs.getAllocationSessionArtifact().artifactProto.startTime).isEqualTo(1L)
    assertThat(allocationsTaskArgs.getAllocationSessionArtifact().artifactProto.endTime).isEqualTo(100L)
  }

  @Test
  fun testCreateArgsSuccessfullyWithLegacyArtifact() {
    val selectedSession = Common.Session.newBuilder().setSessionId(1).setEndTimestamp(100).build()
    val sessionIdToSessionItems = mapOf(
      1L to createSessionItem(myProfilers, selectedSession, 1, listOf(
        createLegacyAllocationsSessionArtifact(myProfilers, selectedSession, 1, 100))),
    )

    val legacyAllocationsTaskArgs = myJavaKotlinAllocationsTaskHandler.createArgs(sessionIdToSessionItems, selectedSession)
    assertThat(legacyAllocationsTaskArgs).isNotNull()
    assertThat(legacyAllocationsTaskArgs).isInstanceOf(LegacyJavaKotlinAllocationsTaskArgs::class.java)
    assertThat(legacyAllocationsTaskArgs!!.getAllocationSessionArtifact()).isNotNull()
    assertThat(legacyAllocationsTaskArgs.getAllocationSessionArtifact().artifactProto.startTime).isEqualTo(1L)
    assertThat(legacyAllocationsTaskArgs.getAllocationSessionArtifact().artifactProto.endTime).isEqualTo(100L)
  }

  @Test
  fun testCreateArgsFails() {
    // By setting a session id that does not match any of the session items, the task artifact will not be found in the call to createArgs
    // will fail to be constructed.
    val selectedSession = Common.Session.newBuilder().setSessionId(0).setEndTimestamp(100).build()
    val sessionIdToSessionItems = mapOf(
      1L to createSessionItem(myProfilers, selectedSession, 1, listOf(
        createAllocationSessionArtifact(myProfilers, selectedSession, 1, 100))),
    )

    val allocationsTaskArgs = myJavaKotlinAllocationsTaskHandler.createArgs(sessionIdToSessionItems, selectedSession)
    // A return value of null indicates the task args were not constructed correctly (the underlying artifact was not found or supported by
    // the task).
    assertThat(allocationsTaskArgs).isNull()
  }

  @Test
  fun testGetTaskName() {
    assertThat(myJavaKotlinAllocationsTaskHandler.getTaskName()).isEqualTo("Java/Kotlin Allocations")
  }
}