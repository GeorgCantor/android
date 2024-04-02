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
package com.android.tools.idea.adddevicedialog.localavd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import com.android.resources.ScreenOrientation
import com.android.sdklib.internal.avd.AvdCamera
import com.android.sdklib.internal.avd.AvdNetworkLatency
import com.android.sdklib.internal.avd.AvdNetworkSpeed
import com.android.sdklib.internal.avd.EmulatedProperties
import com.android.sdklib.internal.avd.GpuMode
import com.android.tools.idea.adddevicedialog.LocalFileSystem
import com.android.tools.idea.adddevicedialog.LocalProject
import com.android.tools.idea.avdmanager.skincombobox.Skin
import com.intellij.icons.AllIcons
import com.intellij.icons.ExpUiIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlinx.collections.immutable.ImmutableCollection
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.jewel.bridge.LocalComponent
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
internal fun AdditionalSettingsPanel(
  device: VirtualDevice,
  skins: ImmutableCollection<Skin>,
  state: AdditionalSettingsPanelState,
  onDeviceChange: (VirtualDevice) -> Unit,
  onImportButtonClick: () -> Unit,
) {
  Row(Modifier.padding(bottom = Padding.LARGE)) {
    Text("Device skin", Modifier.padding(end = Padding.SMALL))

    Dropdown(
      device.skin,
      skins,
      onSelectedItemChange = { onDeviceChange(device.copy(skin = it)) },
      Modifier.padding(end = Padding.MEDIUM),
    )

    OutlinedButton(onImportButtonClick) { Text("Import") }
  }

  CameraGroup(device, onDeviceChange)
  NetworkGroup(device, onDeviceChange)
  StartupGroup(device, onDeviceChange)
  StorageGroup(device, state.storageGroupState, onDeviceChange)
  EmulatedPerformanceGroup(device, onDeviceChange)
}

@Composable
private fun CameraGroup(device: VirtualDevice, onDeviceChange: (VirtualDevice) -> Unit) {
  GroupLayout(Modifier.padding(bottom = Padding.LARGE)) {
    GroupHeader("Camera")
    Text("Front")

    Dropdown(
      device.frontCamera,
      FRONT_CAMERAS,
      onSelectedItemChange = { onDeviceChange(device.copy(frontCamera = it)) },
    )

    InfoOutlineIcon(Modifier.layoutId(Icon))
    Text("Rear")

    Dropdown(
      device.rearCamera,
      REAR_CAMERAS,
      onSelectedItemChange = { onDeviceChange(device.copy(rearCamera = it)) },
    )

    InfoOutlineIcon(Modifier.layoutId(Icon))
  }
}

private val FRONT_CAMERAS =
  listOf(AvdCamera.NONE, AvdCamera.EMULATED, AvdCamera.WEBCAM).toImmutableList()

private val REAR_CAMERAS = AvdCamera.values().asIterable().toImmutableList()

@Composable
private fun NetworkGroup(device: VirtualDevice, onDeviceChange: (VirtualDevice) -> Unit) {
  GroupLayout(Modifier.padding(bottom = Padding.LARGE)) {
    GroupHeader("Network")
    Text("Speed")

    Dropdown(
      device.speed,
      SPEEDS,
      onSelectedItemChange = { onDeviceChange(device.copy(speed = it)) },
    )

    InfoOutlineIcon(Modifier.layoutId(Icon))
    Text("Latency")

    Dropdown(
      device.latency,
      LATENCIES,
      onSelectedItemChange = { onDeviceChange(device.copy(latency = it)) },
    )

    InfoOutlineIcon(Modifier.layoutId(Icon))
  }
}

private val SPEEDS = AvdNetworkSpeed.values().asIterable().toImmutableList()
private val LATENCIES = AvdNetworkLatency.values().asIterable().toImmutableList()

@Composable
private fun StartupGroup(device: VirtualDevice, onDeviceChange: (VirtualDevice) -> Unit) {
  GroupLayout(Modifier.padding(bottom = Padding.LARGE)) {
    GroupHeader("Startup")
    Text("Orientation")

    Dropdown(
      menuContent = {
        ORIENTATIONS.forEach {
          selectableItem(
            device.orientation == it,
            onClick = { onDeviceChange(device.copy(orientation = it)) },
          ) {
            Text(it.shortDisplayValue)
          }
        }
      }
    ) {
      Text(device.orientation.shortDisplayValue)
    }

    Text("Default boot")

    Dropdown(
      device.defaultBoot,
      BOOTS,
      onSelectedItemChange = { onDeviceChange(device.copy(defaultBoot = it)) },
    )

    InfoOutlineIcon(Modifier.layoutId(Icon))
  }
}

private val ORIENTATIONS =
  listOf(ScreenOrientation.PORTRAIT, ScreenOrientation.LANDSCAPE).toImmutableList()

private val BOOTS = enumValues<Boot>().asIterable().toImmutableList()

@Composable
private fun StorageGroup(
  device: VirtualDevice,
  storageGroupState: StorageGroupState,
  onDeviceChange: (VirtualDevice) -> Unit,
) {
  GroupHeader("Storage")

  Row {
    Text("Internal storage")

    StorageCapacityField(
      device.internalStorage,
      onValueChange = { onDeviceChange(device.copy(internalStorage = it)) },
    )
  }

  Row { Text("Expanded storage") }

  Row {
    val existingImageFieldState = storageGroupState.existingImageFieldState
    val fileSystem = LocalFileSystem.current

    Column {
      RadioButtonRow(
        RadioButton.CUSTOM,
        storageGroupState.selectedRadioButton,
        onClick = {
          storageGroupState.selectedRadioButton = RadioButton.CUSTOM

          val custom = storageGroupState.custom.withMaxUnit()
          onDeviceChange(device.copy(expandedStorage = Custom(custom)))
        },
        Modifier.testTag("CustomRadioButton"),
      )

      RadioButtonRow(
        RadioButton.EXISTING_IMAGE,
        storageGroupState.selectedRadioButton,
        onClick = {
          storageGroupState.selectedRadioButton = RadioButton.EXISTING_IMAGE

          if (existingImageFieldState.valid) {
            val image = fileSystem.getPath(existingImageFieldState.value)
            onDeviceChange(device.copy(expandedStorage = ExistingImage(image)))
          }
        },
        Modifier.testTag("ExistingImageRadioButton"),
      )

      RadioButtonRow(
        RadioButton.NONE,
        storageGroupState.selectedRadioButton,
        onClick = {
          storageGroupState.selectedRadioButton = RadioButton.NONE
          onDeviceChange(device.copy(expandedStorage = None))
        },
      )
    }

    Column {
      Row {
        StorageCapacityField(
          storageGroupState.custom,
          onValueChange = {
            storageGroupState.custom = it
            onDeviceChange(device.copy(expandedStorage = Custom(it.withMaxUnit())))
          },
          storageGroupState.selectedRadioButton == RadioButton.CUSTOM,
        )
      }

      ExistingImageField(
        existingImageFieldState,
        storageGroupState.selectedRadioButton == RadioButton.EXISTING_IMAGE,
        onStateChange = {
          storageGroupState.existingImageFieldState = it

          if (it.valid) {
            val image = fileSystem.getPath(it.value)
            onDeviceChange(device.copy(expandedStorage = ExistingImage(image)))
          }

          // TODO Else image is not valid. Disable the Add button.
        },
      )
    }
  }
}

@Composable
private fun <E : Enum<E>> RadioButtonRow(
  value: Enum<E>,
  selectedValue: Enum<E>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  RadioButtonRow(value.toString(), selectedValue == value, onClick, modifier)
}

@Composable
private fun ExistingImageField(
  state: ExistingImageFieldState,
  enabled: Boolean,
  onStateChange: (ExistingImageFieldState) -> Unit,
) {
  if (enabled && !state.valid) {
    Text("The specified image must be a valid file")
  }

  val fileSystem = LocalFileSystem.current
  @OptIn(ExperimentalJewelApi::class) val component = LocalComponent.current
  val project = LocalProject.current

  TextField(
    state.value,
    onValueChange = {
      onStateChange(ExistingImageFieldState(it, Files.isRegularFile(fileSystem.getPath(it))))
    },
    Modifier.testTag("ExistingImageField"),
    enabled,
    trailingIcon = {
      Icon(
        "general/openDisk.svg",
        null,
        AllIcons::class.java,
        Modifier.clickable(
            enabled,
            onClick = {
              val image = chooseFile(component, project)

              if (image != null) {
                onStateChange(ExistingImageFieldState(image.toString(), true))
              }
            },
          )
          .pointerHoverIcon(PointerIcon.Default),
      )
    },
  )
}

private fun chooseFile(parent: Component, project: Project?): Path? {
  // TODO chooseFile logs an error because it does slow things on the EDT
  val virtualFile =
    FileChooser.chooseFile(
      FileChooserDescriptorFactory.createSingleFileDescriptor(),
      parent,
      project,
      null,
    )

  if (virtualFile == null) {
    return null
  }

  val path = virtualFile.toNioPath()
  assert(Files.isRegularFile(path))

  return path
}

@Composable
private fun EmulatedPerformanceGroup(
  device: VirtualDevice,
  onDeviceChange: (VirtualDevice) -> Unit,
) {
  GroupHeader("Emulated Performance")

  CheckboxRow(
    "Enable multithreading",
    device.cpuCoreCount != null,
    onCheckedChange = {
      val count = if (it) EmulatedProperties.RECOMMENDED_NUMBER_OF_CORES else null
      onDeviceChange(device.copy(cpuCoreCount = count))
    },
  )

  Row {
    Text("CPU cores")
    val cpuCoreCount = device.cpuCoreCount ?: 1

    Dropdown(
      enabled = device.cpuCoreCount != null,
      menuContent = {
        for (count in 1..max(1, Runtime.getRuntime().availableProcessors() / 2)) {
          selectableItem(
            cpuCoreCount == count,
            onClick = { onDeviceChange(device.copy(cpuCoreCount = count)) },
          ) {
            Text(count.toString())
          }
        }
      },
    ) {
      Text(cpuCoreCount.toString())
    }
  }

  Row {
    Text("Graphic acceleration")

    Dropdown(
      device.graphicAcceleration,
      GRAPHIC_ACCELERATION_ITEMS,
      onSelectedItemChange = { onDeviceChange(device.copy(graphicAcceleration = it)) },
    )
  }

  Row {
    Text("Simulated RAM")

    StorageCapacityField(
      device.simulatedRam,
      onValueChange = { onDeviceChange(device.copy(simulatedRam = it)) },
    )
  }

  Row {
    Text("VM heap size")

    StorageCapacityField(
      device.vmHeapSize,
      onValueChange = { onDeviceChange(device.copy(vmHeapSize = it)) },
    )
  }
}

// TODO The third item depends on the system image
private val GRAPHIC_ACCELERATION_ITEMS =
  GpuMode.values().filterNot { it == GpuMode.OFF }.toImmutableList()

internal class AdditionalSettingsPanelState internal constructor(device: VirtualDevice) {
  internal val storageGroupState = StorageGroupState(device)
}

internal class StorageGroupState internal constructor(device: VirtualDevice) {
  internal var selectedRadioButton by mutableStateOf(RadioButton.valueOf(device.expandedStorage))
  internal var custom by mutableStateOf(customValue(device))

  internal var existingImageFieldState by
    mutableStateOf(ExistingImageFieldState.from(device.expandedStorage))

  private companion object {
    private fun customValue(device: VirtualDevice) =
      if (device.expandedStorage is Custom) {
        device.expandedStorage.value
      } else {
        StorageCapacity(512, StorageCapacity.Unit.MB)
      }
  }
}

internal enum class RadioButton {
  CUSTOM {
    override fun toString() = "Custom"
  },
  EXISTING_IMAGE {
    override fun toString() = "Existing image"
  },
  NONE {
    override fun toString() = "None"
  };

  internal companion object {
    internal fun valueOf(storage: ExpandedStorage) =
      when (storage) {
        is Custom -> CUSTOM
        is ExistingImage -> EXISTING_IMAGE
        is None -> NONE
      }
  }
}

/**
 * @property value the value of the Existing image text field
 * @property valid if Files.isRegularFile(Path.of(value)) is true
 */
internal data class ExistingImageFieldState
internal constructor(internal val value: String, internal val valid: Boolean) {
  internal companion object {
    internal fun from(storage: ExpandedStorage) =
      if (storage is ExistingImage) {
        // If storage is an ExistingImage the Existing image radio button is selected.
        // storage.toString() returns storage.value.toString() which must be a valid path. Set valid
        // to true.
        ExistingImageFieldState(storage.toString(), true)
      } else {
        // The Existing image radio button is not selected. The Existing image text field is still
        // displayed, and it still needs a string value. Use the empty string and set valid to false
        // because the empty string is not a path to a real file.
        ExistingImageFieldState("", false)
      }
  }
}

@Composable
private fun InfoOutlineIcon(modifier: Modifier = Modifier) {
  Icon("expui/status/infoOutline.svg", null, ExpUiIcons::class.java, modifier)
}
