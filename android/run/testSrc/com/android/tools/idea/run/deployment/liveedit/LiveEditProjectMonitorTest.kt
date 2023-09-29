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
package com.android.tools.idea.run.deployment.liveedit

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.android.testutils.MockitoKt
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.analytics.TestUsageTracker
import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.editors.liveedit.LiveEditService
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.wireless.android.sdk.stats.LiveEditEvent
import com.intellij.openapi.project.Project
import junit.framework.Assert
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(JUnit4::class)
class LiveEditProjectMonitorTest {
  private lateinit var myProject: Project
  private val usageTracker = TestUsageTracker(VirtualTimeScheduler())

  private fun hasMetricStatus(status : LiveEditEvent.Status) = usageTracker.usages.any() {
    it.studioEvent.liveEditEvent.status == status
  }

  @get:Rule
  var projectRule = AndroidProjectRule.inMemory()

  @Before
  fun setUp() {
    UsageTracker.setWriterForTest(usageTracker)
    myProject = projectRule.project
    setUpComposeInProjectFixture(projectRule)
  }

  @After
  fun tearDown() {
    Assert.assertFalse(hasMetricStatus(LiveEditEvent.Status.UNKNOWN))
    UsageTracker.cleanAfterTesting()
  }

  @Test
  fun manualModeCompileError() {
    var monitor = LiveEditProjectMonitor(
      LiveEditService.getInstance(myProject), myProject);
    var file = projectRule.fixture.configureByText("A.kt", "fun foo() : String { return 1}")
    var foo = findFunction(file, "foo")
    monitor.handleChangedMethods(myProject, listOf(EditEvent(file, foo)))
    monitor.doOnManualLETrigger()
    Assert.assertEquals(1, monitor.numFilesWithCompilationErrors())
  }

  @Test
  fun autoModeCompileSuccess() {
    var monitor = LiveEditProjectMonitor(
      LiveEditService.getInstance(myProject), myProject);
    var file = projectRule.fixture.configureByText("A.kt", "fun foo() : Int { return 1}")
    var foo = findFunction(file, "foo")
    monitor.processChanges(myProject, listOf(EditEvent(file, foo)), LiveEditEvent.Mode.AUTO)
    monitor.onPsiChanged(EditEvent(file, foo))
    Assert.assertEquals(0, monitor.numFilesWithCompilationErrors())
  }

  @Test
  fun autoModeCompileError() {
    var monitor = LiveEditProjectMonitor(
      LiveEditService.getInstance(myProject), myProject);
    var file = projectRule.fixture.configureByText("A.kt", "fun foo() : String { return 1}")
    var foo = findFunction(file, "foo")
    monitor.processChanges(myProject, listOf(EditEvent(file, foo)), LiveEditEvent.Mode.AUTO)
    monitor.onPsiChanged(EditEvent(file, foo))
    Assert.assertEquals(1, monitor.numFilesWithCompilationErrors())
  }

  @Test
  fun autoModeCompileErrorInOtherFile() {
    var monitor = LiveEditProjectMonitor(
      LiveEditService.getInstance(myProject), myProject);
    var file = projectRule.fixture.configureByText("A.kt", "fun foo() : String { return 1}")
    var foo = findFunction(file, "foo")
    monitor.processChanges(myProject, listOf(EditEvent(file, foo)), LiveEditEvent.Mode.AUTO)
    monitor.onPsiChanged(EditEvent(file, foo))

    var file2 = projectRule.fixture.configureByText("B.kt", "fun foo2() {}")
    var foo2 = findFunction(file2, "foo2")
    monitor.processChanges(myProject, listOf(EditEvent(file2, foo2)), LiveEditEvent.Mode.AUTO)
    monitor.onPsiChanged(EditEvent(file2, foo2))
    Assert.assertEquals(1, monitor.numFilesWithCompilationErrors())
  }

  @Test
  @Ignore("Test was written assuming a world where the class differ doesn't exist")
  fun `Auto Mode with Private and Public Inline`() {
    var monitor = LiveEditProjectMonitor(
      LiveEditService.getInstance(myProject), myProject);
    var file = projectRule.fixture.configureByText("A.kt", "public inline fun foo() : Int { return 1}")
    var foo = findFunction(file, "foo")
    monitor.processChanges(myProject, listOf(EditEvent(file, foo)), LiveEditEvent.Mode.AUTO)
    monitor.onPsiChanged(EditEvent(file, foo))

    var file2 = projectRule.fixture.configureByText("B.kt", "private inline fun foo2() : Int { return 1}")
    var foo2 = findFunction(file2, "foo2")
    monitor.processChanges(myProject, listOf(EditEvent(file2, foo2)), LiveEditEvent.Mode.AUTO)
    monitor.onPsiChanged(EditEvent(file2, foo2))
    Assert.assertEquals(1, monitor.numFilesWithCompilationErrors())
    Assert.assertTrue(hasMetricStatus(LiveEditEvent.Status.NON_PRIVATE_INLINE_FUNCTION))
  }

  /**
   * This test needs to be updated when multi-deploy is supported (its failure will signify so).
   */
  @Test
  fun testMultiDeploy() {
    val monitor = LiveEditProjectMonitor(LiveEditService.getInstance(myProject), myProject)
    val device1: IDevice = MockitoKt.mock()
    MockitoKt.whenever(device1.version).thenReturn(AndroidVersion(AndroidVersion.VersionCodes.R))
    val device2: IDevice = MockitoKt.mock()
    MockitoKt.whenever(device2.version).thenReturn(AndroidVersion(AndroidVersion.VersionCodes.R))

    val manager = monitor.liveEditDevices
    manager.addDevice(device1, LiveEditStatus.UpToDate)
    assertFalse(manager.isDisabled())

    // Make sure that adding a second device doesn't affect the state.
    manager.addDevice(device2, LiveEditStatus.DebuggerAttached)
    assertEquals(manager.getInfo(device1)!!.status, LiveEditStatus.UpToDate)
    assertEquals(manager.getInfo(device2)!!.status, LiveEditStatus.DebuggerAttached)

    // Ensure running on one device makes the other device's status NoMultiDeploy.
    monitor.notifyExecution(listOf(device2))
    assertEquals(manager.getInfo(device1)!!.status, LiveEditStatus.NoMultiDeploy)
    assertEquals(manager.getInfo(device2)!!.status, LiveEditStatus.DebuggerAttached)

    // Make sure running on the other device will force the status of other device to Disabled,
    // since showing NoMultiDeploy would be weird for the device we're deploying to. Ensure the
    // first device (that we're NOT deploying to) is now set to NoMultiDeploy.
    monitor.notifyExecution(listOf(device1))
    assertEquals(manager.getInfo(device1)!!.status, LiveEditStatus.Disabled)
    assertEquals(manager.getInfo(device2)!!.status, LiveEditStatus.NoMultiDeploy)

    // Make sure if we're running on both devices, then they preserve the previous state.
    manager.update(LiveEditStatus.UpToDate)
    monitor.notifyExecution(listOf(device1, device2))
    assertEquals(manager.getInfo(device1)!!.status, LiveEditStatus.UpToDate)
    assertEquals(manager.getInfo(device2)!!.status, LiveEditStatus.UpToDate)
  }

  @Test
  fun nonKotlin() {
    var monitor = LiveEditProjectMonitor(
      LiveEditService.getInstance(myProject), myProject);
    var file = projectRule.fixture.configureByText("A.java", "class A() { }")
    var event = EditEvent(file)
    event.unsupportedPsiEvents.add(UnsupportedPsiEvent.NON_KOTLIN)
    monitor.processChanges(myProject, listOf(event), LiveEditEvent.Mode.AUTO)
    Assert.assertTrue(hasMetricStatus(LiveEditEvent.Status.NON_KOTLIN))
  }
}
