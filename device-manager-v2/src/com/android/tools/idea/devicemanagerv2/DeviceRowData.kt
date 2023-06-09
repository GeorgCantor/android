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

import com.android.sdklib.AndroidVersion
import com.android.sdklib.deviceprovisioner.DeviceError
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.sdklib.devices.Abi
import com.android.tools.idea.deviceprovisioner.DEVICE_HANDLE_KEY
import com.intellij.openapi.actionSystem.DataKey

/**
 * Immutable snapshot of relevant parts of a [DeviceHandle] or [DeviceTemplate] for use in
 * CategoryTable.
 */
internal data class DeviceRowData(
  /**
   * If this row represents a template, this value is set and handle is null. Otherwise, handle must
   * be set, and this is also set to handle.sourceTemplate (which may be null).
   */
  val template: DeviceTemplate?,
  val handle: DeviceHandle?,
  val name: String,
  val type: DeviceType,
  val androidVersion: AndroidVersion?,
  val abi: Abi?,
  val status: Status,
  val error: DeviceError?,
  val isVirtual: Boolean,
  val wearPairingId: String?,
  val pairingStatus: List<PairingStatus>,
) {
  init {
    checkNotNull(handle ?: template) { "Either template or handle must be set" }
  }

  fun key() = handle ?: template!!

  companion object {
    fun create(handle: DeviceHandle, pairingStatus: List<PairingStatus>): DeviceRowData {
      val state = handle.state
      val properties = state.properties
      return DeviceRowData(
        template = handle.sourceTemplate,
        handle = handle,
        name = properties.title,
        type = properties.deviceType ?: DeviceType.HANDHELD,
        androidVersion = properties.androidVersion,
        abi = properties.abi,
        status =
          when {
            state.isOnline() -> Status.ONLINE
            else -> Status.OFFLINE
          },
        error = state.error,
        isVirtual = properties.isVirtual ?: false,
        wearPairingId = properties.wearPairingId,
        pairingStatus = pairingStatus,
      )
    }

    fun create(template: DeviceTemplate): DeviceRowData {
      val properties = template.properties
      return DeviceRowData(
        template = template,
        handle = null,
        name = properties.title,
        type = properties.deviceType ?: DeviceType.HANDHELD,
        androidVersion = properties.androidVersion,
        abi = properties.abi,
        status = Status.OFFLINE,
        error = null,
        isVirtual = properties.isVirtual ?: false,
        wearPairingId = properties.wearPairingId,
        pairingStatus = emptyList(),
      )
    }
  }

  enum class Status {
    OFFLINE,
    ONLINE;
    override fun toString() = name.titlecase()
  }
}

internal val DEVICE_ROW_DATA_KEY = DataKey.create<DeviceRowData>("DeviceRowData")

internal fun provideRowData(dataId: String, row: DeviceRowData): Any? =
  when {
    DEVICE_ROW_DATA_KEY.`is`(dataId) -> row
    DEVICE_HANDLE_KEY.`is`(dataId) -> row.handle
    else -> null
  }

internal fun String.titlecase() = lowercase().let { it.replaceFirstChar { it.uppercase() } }
