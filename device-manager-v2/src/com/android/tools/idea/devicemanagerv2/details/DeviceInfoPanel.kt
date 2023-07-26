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
package com.android.tools.idea.devicemanagerv2.details

import com.android.adblib.ConnectedDevice
import com.android.adblib.ShellCommandOutputElement
import com.android.adblib.isOnline
import com.android.adblib.selector
import com.android.adblib.shellAsLines
import com.android.adblib.shellAsText
import com.android.annotations.concurrency.UiThread
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.LocalEmulatorProperties
import com.android.sdklib.internal.avd.AvdManager
import com.android.tools.adtui.device.ScreenDiagram
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.ibm.icu.number.NumberFormatter
import com.ibm.icu.util.MeasureUnit
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.Collator
import java.time.Duration
import java.util.Formatter
import java.util.Locale
import java.util.TreeMap
import javax.swing.GroupLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.LayoutStyle
import javax.swing.plaf.basic.BasicGraphicsUtils
import kotlin.reflect.KProperty
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A panel within the [DeviceDetailsPanel] that shows a table of information about the device. */
internal class DeviceInfoPanel : JBPanel<DeviceInfoPanel>() {
  val apiLevelLabel = LabeledValue("API level")
  var apiLevel by apiLevelLabel

  val powerLabel = LabeledValue("Power")
  var power by powerLabel

  val resolutionLabel = LabeledValue("Resolution (px)")
  var resolution by resolutionLabel

  val resolutionDpLabel = LabeledValue("Resolution (dp)")
  var resolutionDp by resolutionDpLabel

  val abiListLabel = LabeledValue("ABI list")
  var abiList by abiListLabel

  val availableStorageLabel = LabeledValue("Available storage")
  var availableStorage by availableStorageLabel

  val summarySection =
    InfoSection(
      "Summary",
      listOf(
        apiLevelLabel,
        powerLabel,
        resolutionLabel,
        resolutionDpLabel,
        abiListLabel,
        availableStorageLabel
      )
    )

  var copyPropertiesButton: JComponent = JPanel()
    @UiThread
    set(value) {
      layout.replace(field, value)
      field = value
    }
  var propertiesSection: JComponent = JPanel()
    @UiThread
    set(value) {
      layout.replace(field, value)
      field = value
    }

  val screenDiagram = ScreenDiagram()

  val layout = GroupLayout(this)

  init {
    val horizontalGroup: GroupLayout.Group = layout.createParallelGroup()
    val verticalGroup = layout.createSequentialGroup()

    horizontalGroup
      .addGroup(
        layout
          .createSequentialGroup()
          .addComponent(summarySection)
          .addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
          .addComponent(screenDiagram)
      )
      .addComponent(copyPropertiesButton)
      .addComponent(propertiesSection)
    verticalGroup
      .addGroup(
        layout.createParallelGroup().addComponent(summarySection).addComponent(screenDiagram)
      )
      .addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
      .addComponent(copyPropertiesButton)
      .addPreferredGap(LayoutStyle.ComponentPlacement.UNRELATED)
      .addComponent(propertiesSection)

    layout.autoCreateContainerGaps = true
    layout.setHorizontalGroup(horizontalGroup)
    layout.setVerticalGroup(verticalGroup)

    setLayout(layout)
  }
}

internal class InfoSection(heading: String, private val labeledValues: List<LabeledValue>) :
  JBPanel<InfoSection>() {
  private val headingLabel = headingLabel(heading)

  init {
    isOpaque = false

    val labels = labeledValues.map { it.label }.toTypedArray()
    val layout = GroupLayout(this)
    layout.linkSize(*labels)

    val horizontalGroup = layout.createParallelGroup().addComponent(headingLabel)
    val verticalGroup = layout.createSequentialGroup().addComponent(headingLabel)

    for (labeledValue in labeledValues) {
      val label = labeledValue.label
      val value = labeledValue.value
      horizontalGroup.addGroup(
        layout.createSequentialGroup().addComponent(label).addComponent(value)
      )
      verticalGroup.addGroup(layout.createParallelGroup().addComponent(label).addComponent(value))
    }

    layout.autoCreateGaps = true
    layout.setHorizontalGroup(horizontalGroup)
    layout.setVerticalGroup(verticalGroup)

    this.layout = layout
  }

  fun writeTo(buffer: StringBuilder) {
    val formatter = Formatter(buffer)
    formatter.format("%s%n", headingLabel.text)

    val maxLength = labeledValues.maxOfOrNull { it.label.text.length } ?: 1
    val format = "%-" + maxLength + "s %s%n"

    for (labeledValue in labeledValues) {
      formatter.format(format, labeledValue.label.text, labeledValue.value.text)
    }
  }
}

/**
 * A pair of JBLabels: a label and a value, e.g. "API level: ", "33".
 *
 * It can be used as a property delegate, such that the value is tied to the property.
 */
@UiThread
class LabeledValue(label: String) {
  constructor(label: String, value: String) : this(label) {
    this.value.text = value
  }

  operator fun getValue(container: Any, property: KProperty<*>): String {
    return value.text
  }

  operator fun setValue(container: Any, property: KProperty<*>, value: String) {
    this.value.text = value
  }

  val label = JBLabel(label)
  val value = JBLabel()

  var isVisible: Boolean
    get() = label.isVisible
    set(visible) {
      label.isVisible = visible
      value.isVisible = visible
    }
}

internal fun DeviceInfoPanel.populateDeviceInfo(properties: DeviceProperties) {
  apiLevel = properties.androidVersion?.apiStringWithExtension ?: "Unknown"
  abiList = properties.abi?.toString() ?: "Unknown"
  resolution = properties.resolution?.toString() ?: "Unknown"
  val resolutionDp = properties.resolutionDp
  this.resolutionDp = resolutionDp?.toString() ?: "Unknown"
  screenDiagram.setDimensions(resolutionDp?.width ?: 0, resolutionDp?.height ?: 0)

  if (properties is LocalEmulatorProperties) {
    val avdConfigProperties =
      properties.avdConfigProperties.filterNotTo(TreeMap(Collator.getInstance())) {
        it.key in EXCLUDED_LOCAL_AVD_PROPERTIES
      }
    if (avdConfigProperties.isNotEmpty()) {
      val values = avdConfigProperties.map { LabeledValue(it.key, it.value) }
      val section = InfoSection("Properties", values)
      copyPropertiesButton = createCopyPropertiesButton(section)
      propertiesSection = section
    }
  }
}

private fun createCopyPropertiesButton(infoSection: InfoSection) =
  JButton("Copy properties to clipboard", AllIcons.Actions.Copy).apply {
    border = null
    isContentAreaFilled = false
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED

    val gap = iconTextGap
    val size = BasicGraphicsUtils.getPreferredButtonSize(this, gap)

    maximumSize = size
    minimumSize = size
    preferredSize = size

    addActionListener {
      val text = StringBuilder().also { infoSection.writeTo(it) }.toString()
      Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
  }

private val EXCLUDED_LOCAL_AVD_PROPERTIES =
  setOf(
    AvdManager.AVD_INI_ABI_TYPE,
    AvdManager.AVD_INI_CPU_ARCH,
    AvdManager.AVD_INI_SKIN_NAME,
    AvdManager.AVD_INI_SKIN_PATH,
    AvdManager.AVD_INI_SDCARD_SIZE,
    AvdManager.AVD_INI_SDCARD_PATH,
    AvdManager.AVD_INI_IMAGES_2,
  )

internal suspend fun populateDeviceInfo(deviceInfoPanel: DeviceInfoPanel, handle: DeviceHandle) =
  withContext(uiThread) {
    val state = handle.state
    val properties = state.properties
    val device = state.connectedDevice?.takeIf { it.isOnline }

    deviceInfoPanel.populateDeviceInfo(properties)

    launch {
      if (device != null && properties.isVirtual == false) {
        deviceInfoPanel.powerLabel.isVisible = true
        deviceInfoPanel.power = readDevicePower(device)
      } else {
        deviceInfoPanel.powerLabel.isVisible = false
      }
    }

    launch {
      if (device != null) {
        deviceInfoPanel.availableStorage = readDeviceStorage(device)
      }
    }
  }

private suspend fun readDeviceStorage(device: ConnectedDevice): String {
  val output = device.shellStdoutLines("df /data")
  val kilobytes =
    DF_OUTPUT_REGEX.matchEntire(output[1])?.groupValues?.get(1)?.toIntOrNull() ?: return "Unknown"
  return MB_FORMATTER.format(kilobytes / 1024).toString()
}

private suspend fun readDevicePower(device: ConnectedDevice): String {
  val output =
    device.session.deviceServices
      .shellAsText(device.selector, "dumpsys battery", commandTimeout = Duration.ofSeconds(5))
      .stdout
      .trim()

  return when {
    output.contains("Wireless powered: true") -> "Wireless"
    output.contains("AC powered: true") -> "AC"
    output.contains("USB powered: true") -> "USB"
    else -> Regex("level: (\\d+)").find(output)?.groupValues?.get(1)?.let { "Battery: $it" }
        ?: "Unknown"
  }
}

private suspend fun ConnectedDevice.shellStdoutLines(command: String): List<String> =
  session.deviceServices
    .shellAsLines(selector, command, commandTimeout = Duration.ofSeconds(5))
    .transform {
      when (it) {
        is ShellCommandOutputElement.StdoutLine -> emit(it.contents)
        else -> {}
      }
    }
    .toList()

internal fun headingLabel(heading: String) =
  JBLabel(heading).apply { font = font.deriveFont(Font.BOLD) }

private val DF_OUTPUT_REGEX = Regex(""".+\s+\d+\s+\d+\s+(\d+)\s+.+\s+.+""")
private val MB_FORMATTER = NumberFormatter.withLocale(Locale.US).unit(MeasureUnit.MEGABYTE)
