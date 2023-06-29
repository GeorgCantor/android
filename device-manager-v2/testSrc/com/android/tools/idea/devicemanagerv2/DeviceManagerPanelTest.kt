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
package com.android.tools.idea.devicemanagerv2

import com.android.adblib.utils.createChildScope
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.tools.adtui.categorytable.CategoryTable
import com.android.tools.adtui.categorytable.RowKey.ValueRowKey
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ProjectRule
import icons.StudioIcons
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceManagerPanelTest {

  @get:Rule val projectRule = ProjectRule()

  /**
   * When a template is activated, the resulting handle should be just above the (hidden) template,
   * and selected if the template was.
   */
  @Test
  fun activateTemplate() = runTestWithFixture {
    val pixel5Template = createTemplate("Pixel 5")
    val pixel5Handle = createHandle("Pixel 5", pixel5Template)
    val pixel5Emulator = createHandle("Pixel 5")
    val pixel6 = createHandle("Pixel 6")

    deviceHandles.send(listOf(pixel5Emulator, pixel6))
    deviceTemplates.send(listOf(pixel5Template))

    deviceTable.selection.selectRow(ValueRowKey(pixel5Template))

    assertThat(deviceTable.values).hasSize(3)
    val originalValues = deviceTable.values.map { it.key() }

    deviceHandles.send(listOf(pixel5Emulator, pixel6, pixel5Handle))

    val valuesAfterActivation =
      originalValues.toMutableList().apply { add(indexOf(pixel5Template), pixel5Handle) }
    assertThat(deviceTable.values.map { it.key() })
      .containsExactlyElementsIn(valuesAfterActivation)
      .inOrder()

    assertThat(deviceTable.selection.selectedKeys())
      .containsExactly(ValueRowKey<DeviceRowData>(pixel5Handle))
  }

  @Test
  fun templateVisibility() = runTestWithFixture {
    val pixel4 = createHandle("Pixel 4")
    val pixel5Template = createTemplate("Pixel 5")
    val pixel5Handle = createHandle("Pixel 5", pixel5Template)
    val pixel6 = createHandle("Pixel 6")

    deviceHandles.send(listOf(pixel4, pixel6))
    deviceTemplates.send(listOf(pixel5Template))

    assertThat(deviceTable.visibleKeys()).containsExactly(pixel4, pixel5Template, pixel6)

    deviceHandles.send(listOf(pixel4, pixel6, pixel5Handle))
    // Send an update to the state to be more realistic
    pixel5Handle.stateFlow.update {
      DeviceState.Disconnected(
        DeviceProperties.build {
          manufacturer = "Google"
          model = "Pixel 5"
          icon = StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_PHONE
        }
      )
    }
    assertThat(deviceTable.visibleKeys()).containsExactly(pixel4, pixel5Handle, pixel6)

    deviceHandles.send(listOf(pixel4, pixel6))
    pixel5Handle.scope.cancel()

    assertThat(deviceTable.visibleKeys()).containsExactly(pixel4, pixel5Template, pixel6)
  }

  @Test
  fun detailsTracksSelection() = runTestWithFixture {
    val pixel4 = createHandle("Pixel 4")
    val pixel5 = createHandle("Pixel 5")

    deviceHandles.send(listOf(pixel4, pixel5))

    val pixel4Row = DeviceRowData.create(pixel4, emptyList())
    val pixel5Row = DeviceRowData.create(pixel5, emptyList())

    ViewDetailsAction().actionPerformed(actionEvent(dataContext(panel, deviceRowData = pixel4Row)))

    assertThat(panel.deviceDetailsPanelRow).isEqualTo(pixel4Row)

    deviceTable.selection.selectRow(ValueRowKey(pixel5))

    assertThat(panel.deviceDetailsPanelRow).isEqualTo(pixel5Row)

    deviceTable.selection.clear()

    // Shouldn't change anything
    assertThat(panel.deviceDetailsPanelRow).isEqualTo(pixel5Row)
  }

  fun <T : Any> CategoryTable<T>.visibleKeys() =
    values.mapNotNull { primaryKey(it).takeIf { isRowVisibleByKey(it) } }

  private fun runTestWithFixture(block: suspend Fixture.() -> Unit) = runTest {
    val fixture = Fixture(projectRule.project, this)
    fixture.block()
    fixture.scope.cancel()
  }

  private class Fixture(project: Project, testScope: TestScope) {
    val deviceHandles = Channel<List<DeviceHandle>>(Channel.UNLIMITED)
    val deviceTemplates = Channel<List<DeviceTemplate>>(Channel.UNLIMITED)
    val pairedDevices = Channel<Map<String, List<PairingStatus>>>(Channel.UNLIMITED)

    // UnconfinedTestDispatcher is extremely useful here to cause actions to run to completion.
    val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)

    val scope = testScope.createChildScope(context = dispatcher)

    val panel =
      DeviceManagerPanel(
        project,
        scope,
        dispatcher,
        deviceHandles.consumeAsFlow().stateIn(scope, SharingStarted.Lazily, emptyList()),
        deviceTemplates.consumeAsFlow().stateIn(scope, SharingStarted.Lazily, emptyList()),
        emptyList(),
        emptyList(),
        pairedDevices.consumeAsFlow().stateIn(scope, SharingStarted.Lazily, emptyMap()),
      )
    val deviceTable = panel.deviceTable

    fun createHandle(name: String, sourceTemplate: DeviceTemplate? = null) =
      FakeDeviceHandle(
        scope.createChildScope(isSupervisor = true),
        sourceTemplate,
        DeviceProperties.build {
          model = name
          icon = StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_PHONE
        }
      )
    fun createTemplate(name: String) = FakeDeviceTemplate(name)
  }
}
