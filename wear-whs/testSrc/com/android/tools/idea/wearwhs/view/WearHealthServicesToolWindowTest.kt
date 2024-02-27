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
package com.android.tools.idea.wearwhs.view

import com.android.testutils.ImageDiffUtil
import com.android.testutils.TestUtils
import com.android.testutils.waitForCondition
import com.android.tools.adtui.stdui.menu.CommonDropDownButton
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.findDescendant
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.wearwhs.EVENT_TRIGGER_GROUPS
import com.android.tools.idea.wearwhs.WHS_CAPABILITIES
import com.android.tools.idea.wearwhs.communication.FakeDeviceManager
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.awt.Dimension
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JTextField
import kotlin.time.Duration.Companion.seconds

@RunsInEdt
class WearHealthServicesToolWindowTest {
  companion object {
    const val TEST_MAX_WAIT_TIME_SECONDS = 5L
    const val TEST_POLLING_INTERVAL_MILLISECONDS = 100L
  }

  @get:Rule
  val projectRule = AndroidProjectRule.inMemory()

  @get:Rule
  val edtRule = EdtRule()

  private val testDataPath: Path
    get() = TestUtils.resolveWorkspacePathUnchecked("tools/adt/idea/wear-whs/testData")

  private val deviceManager by lazy { FakeDeviceManager() }
  private val stateManager by lazy {
    WearHealthServicesToolWindowStateManagerImpl(deviceManager, pollingIntervalMillis = TEST_POLLING_INTERVAL_MILLISECONDS)
  }
  private val toolWindow by lazy {
    WearHealthServicesToolWindow(stateManager).apply {
      setSerialNumber("test")
    }
  }

  @Before
  fun setUp() {
    Disposer.register(projectRule.testRootDisposable, stateManager)
    Disposer.register(projectRule.testRootDisposable, toolWindow)
  }

  @Ignore("b/326061638")
  @Test
  fun `test panel screenshot matches expectation for current platform`() = runBlocking {
    val fakeUi = FakeUi(toolWindow)

    fakeUi.waitForCheckbox("Heart rate", true)
    fakeUi.waitForCheckbox("Location", true)
    fakeUi.waitForCheckbox("Steps", true)

    fakeUi.root.size = Dimension(400, 400)
    fakeUi.layoutAndDispatchEvents()

    ImageDiffUtil.assertImageSimilarPerPlatform(
      testDataPath = testDataPath,
      fileNameBase = "screens/whs-panel-default",
      actual = fakeUi.render(),
      maxPercentDifferent = 4.0)
  }

  @Test
  fun `test panel screenshot matches expectation with modified state manager values`() = runBlocking {
    deviceManager.activeExercise = true

    stateManager.forceUpdateState()

    stateManager.preset.value = Preset.CUSTOM
    stateManager.setCapabilityEnabled(deviceManager.capabilities[0], true)
    stateManager.setCapabilityEnabled(deviceManager.capabilities[1], false)
    stateManager.setCapabilityEnabled(deviceManager.capabilities[2], false)
    stateManager.setOverrideValue(deviceManager.capabilities[0], 2f)
    stateManager.setOverrideValue(deviceManager.capabilities[2], 5f)
    stateManager.applyChanges()

    val fakeUi = FakeUi(toolWindow)

    fakeUi.waitForCheckbox("Heart rate", true)
    fakeUi.waitForCheckbox("Location", false)
    fakeUi.waitForCheckbox("Steps", false)

    fakeUi.root.size = Dimension(400, 400)
    fakeUi.layoutAndDispatchEvents()

    ImageDiffUtil.assertImageSimilarPerPlatform(
      testDataPath = testDataPath,
      fileNameBase = "screens/whs-panel-state-manager-modified",
      actual = fakeUi.render(),
      maxPercentDifferent = 4.0)
  }

  @Test
  fun `test override value doesn't get reformatted from int to float`() = runBlocking {
    val fakeUi = FakeUi(toolWindow)

    deviceManager.activeExercise = true

    val textField = fakeUi.waitForDescendant<JTextField> { it.isVisible }
    textField.text = "50"

    val applyButton = fakeUi.waitForDescendant<JButton> { it.text == "Apply" }
    applyButton.doClick()

    delay(2 * TEST_POLLING_INTERVAL_MILLISECONDS)

    assertThat(textField.text).isNotEqualTo("50.0")
    assertThat(textField.text).isEqualTo("50")
  }

  @Test
  fun `test override value rejects invalid text`() = runBlocking {
    val fakeUi = FakeUi(toolWindow)

    deviceManager.activeExercise = true

    val textField = fakeUi.waitForDescendant<JTextField> { it.isVisible }

    textField.text = "50f"
    assertThat(textField.text).isEmpty()

    textField.text = "50"
    assertThat(textField.text).isEqualTo("50")

    textField.text = "50.0"
    assertThat(textField.text).isEqualTo("50.0")

    textField.text = "50.0a"
    assertThat(textField.text).isEqualTo("50.0")

    textField.text = "test"
    assertThat(textField.text).isEqualTo("50.0")
  }

  @Test
  fun `test panel displays the dropdown for event triggers`() = runBlocking {
    val fakeUi = FakeUi(toolWindow)
    val dropDownButton = fakeUi.waitForDescendant<CommonDropDownButton>()
    assertThat(dropDownButton).isNotNull()
    assertThat(dropDownButton.action.childrenActions).hasSize(EVENT_TRIGGER_GROUPS.size)
    assertThat(dropDownButton.action.childrenActions[0].childrenActions).hasSize(EVENT_TRIGGER_GROUPS[0].eventTriggers.size)
    assertThat(dropDownButton.action.childrenActions[1].childrenActions).hasSize(EVENT_TRIGGER_GROUPS[1].eventTriggers.size)
    assertThat(dropDownButton.action.childrenActions[2].childrenActions).hasSize(EVENT_TRIGGER_GROUPS[2].eventTriggers.size)
  }

  @Test
  fun `test panel disables checkboxes and dropdown during an exercise`() = runBlocking<Unit> {
    val fakeUi = FakeUi(toolWindow)

    fakeUi.waitForDescendant<ComboBox<Preset>> { it.isEnabled }
    fakeUi.waitForDescendant<JCheckBox> { it.text.contains("Heart rate") && it.isEnabled }
    fakeUi.waitForDescendant<JCheckBox> { it.text.contains("Steps") && it.isEnabled }

    deviceManager.activeExercise = true

    fakeUi.waitForDescendant<ComboBox<Preset>> { !it.isEnabled }
    fakeUi.waitForDescendant<JCheckBox> { it.text.contains("Heart rate") && !it.isEnabled }
    fakeUi.waitForDescendant<JCheckBox> { it.text.contains("Steps") && !it.isEnabled }
  }

  @Test
  fun `test star is only visible when changes are pending`(): Unit = runBlocking {
    val fakeUi = FakeUi(toolWindow)

    // TODO: Remove this apply when ag/26161198 is merged
    val applyButton = fakeUi.waitForDescendant<JButton> { it.text == "Apply" }
    applyButton.doClick()

    val hrCheckBox = fakeUi.waitForDescendant<JCheckBox> { it.text == "Heart rate" }
    hrCheckBox.doClick()

    fakeUi.waitForDescendant<JCheckBox> { it.text == "Heart rate*" }

    applyButton.doClick()

    fakeUi.waitForDescendant<JCheckBox> { it.text == "Heart rate" }

    deviceManager.activeExercise = true
    val textField = fakeUi.waitForDescendant<JTextField> { it.isVisible }
    textField.text = "50"

    fakeUi.waitForDescendant<JCheckBox> { it.text == "Heart rate*" }

    applyButton.doClick()

    fakeUi.waitForDescendant<JCheckBox> { it.text == "Heart rate" }
  }

  private fun FakeUi.waitForCheckbox(text: String, selected: Boolean) = waitForDescendant<JCheckBox> {
    it.text.contains(text) && it.isSelected == selected
  }

  // The UI loads on asynchronous coroutine, we need to wait
  private inline fun <reified T> FakeUi.waitForDescendant(crossinline predicate: (T) -> Boolean = { true }): T {
    waitForCondition(TEST_MAX_WAIT_TIME_SECONDS, TimeUnit.SECONDS) {
      root.findDescendant(predicate) != null
    }
    return root.findDescendant(predicate)!!
  }

  private suspend fun <T> StateFlow<T>.waitForValue(value: T, timeoutSeconds: Long = TEST_MAX_WAIT_TIME_SECONDS) {
    val received = mutableListOf<T>()
    try {
      withTimeout(timeoutSeconds.seconds) { takeWhile { it != value }.collect { received.add(it) } }
    }
    catch (ex: TimeoutCancellationException) {
      Assert.fail("Timed out waiting for value $value. Received values so far $received")
    }
  }
}
