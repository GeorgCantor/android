/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.layoutinspector.metrics

import com.android.tools.analytics.UsageTracker
import com.android.tools.idea.appinspection.ide.analytics.toDeviceInfo
import com.android.tools.idea.appinspection.inspector.api.process.DeviceDescriptor
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorAutoConnectInfo
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorEvent
import layout_inspector.LayoutInspector

/** Utility class used to log metrics for [ForegroundProcessDetection] to Studio metrics. */
object ForegroundProcessDetectionMetrics {

  /**
   * Used to log the result of a handshake. SUPPORTED, NOT_SUPPORTED or UNKNOWN. In case of
   * NOT_SUPPORTED we also log the reason why.
   */
  fun logHandshakeResult(
    handshakeInfo: LayoutInspector.TrackingForegroundProcessSupported,
    device: DeviceDescriptor,
    isRecoveryHandshake: Boolean
  ) {
    val autoConnectInfo =
      getAutoConnectInfoBuilder(isRecoveryHandshake).buildAutoConnectInfo(handshakeInfo)
    val event = buildLayoutInspectorEvent(autoConnectInfo, device)
    UsageTracker.log(event)
  }

  /** Used to log the conversion of a device with UNKNOWN support to SUPPORTED or NOT_SUPPORTED. */
  fun logHandshakeConversion(
    conversion: DynamicLayoutInspectorAutoConnectInfo.HandshakeConversion,
    device: DeviceDescriptor,
    isRecoveryHandshake: Boolean
  ) {
    val autoConnectInfo =
      getAutoConnectInfoBuilder(isRecoveryHandshake).buildAutoConnectInfo(conversion)
    val event = buildLayoutInspectorEvent(autoConnectInfo, device)
    UsageTracker.log(event)
  }

  private fun buildLayoutInspectorEvent(
    autoConnect: DynamicLayoutInspectorAutoConnectInfo,
    device: DeviceDescriptor
  ): AndroidStudioEvent.Builder {
    return AndroidStudioEvent.newBuilder().apply {
      kind = AndroidStudioEvent.EventKind.DYNAMIC_LAYOUT_INSPECTOR_EVENT
      dynamicLayoutInspectorEvent =
        DynamicLayoutInspectorEvent.newBuilder()
          .apply {
            type = DynamicLayoutInspectorEvent.DynamicLayoutInspectorEventType.AUTO_CONNECT_INFO
            autoConnectInfo = autoConnect
          }
          .build()
      deviceInfo = device.toDeviceInfo()
    }
  }

  private fun DynamicLayoutInspectorAutoConnectInfo.Builder.buildAutoConnectInfo(
    supportInfo: LayoutInspector.TrackingForegroundProcessSupported,
  ): DynamicLayoutInspectorAutoConnectInfo {
    val supportType = supportInfo.supportType
    if (supportType != null) {
      handshakeResult = supportType.toHandshakeResult()
    }

    if (
      supportType == LayoutInspector.TrackingForegroundProcessSupported.SupportType.NOT_SUPPORTED
    ) {
      reasonNotSupported = supportInfo.reasonNotSupported.toAutoConnectReasonNotSupported()
    }

    return build()
  }

  private fun DynamicLayoutInspectorAutoConnectInfo.Builder.buildAutoConnectInfo(
    handshakeConversion: DynamicLayoutInspectorAutoConnectInfo.HandshakeConversion,
  ): DynamicLayoutInspectorAutoConnectInfo {
    return apply { handshakeConversionInfo = handshakeConversion }.build()
  }

  private fun getAutoConnectInfoBuilder(
    isRecoveryHandshake: Boolean
  ): DynamicLayoutInspectorAutoConnectInfo.Builder {
    return DynamicLayoutInspectorAutoConnectInfo.newBuilder()
      .setIsRecoveryHandshake(isRecoveryHandshake)
  }

  private fun LayoutInspector.TrackingForegroundProcessSupported.SupportType.toHandshakeResult():
    DynamicLayoutInspectorAutoConnectInfo.HandshakeResult {
    return when (this) {
      LayoutInspector.TrackingForegroundProcessSupported.SupportType.UNKNOWN ->
        DynamicLayoutInspectorAutoConnectInfo.HandshakeResult.SUPPORT_UNKNOWN
      LayoutInspector.TrackingForegroundProcessSupported.SupportType.SUPPORTED ->
        DynamicLayoutInspectorAutoConnectInfo.HandshakeResult.SUPPORTED
      LayoutInspector.TrackingForegroundProcessSupported.SupportType.NOT_SUPPORTED ->
        DynamicLayoutInspectorAutoConnectInfo.HandshakeResult.NOT_SUPPORTED
      LayoutInspector.TrackingForegroundProcessSupported.SupportType.UNRECOGNIZED ->
        DynamicLayoutInspectorAutoConnectInfo.HandshakeResult.UNSPECIFIED_RESULT
    }
  }

  private fun LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported
    .toAutoConnectReasonNotSupported():
    DynamicLayoutInspectorAutoConnectInfo.AutoConnectReasonNotSupported {
    return when (this) {
      LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported.DUMPSYS_NOT_FOUND ->
        DynamicLayoutInspectorAutoConnectInfo.AutoConnectReasonNotSupported.DUMPSYS_NOT_FOUND
      LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported.GREP_NOT_FOUND ->
        DynamicLayoutInspectorAutoConnectInfo.AutoConnectReasonNotSupported.GREP_NOT_FOUND
      LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported
        .DUMPSYS_NO_TOP_ACTIVITY_NO_SLEEPING_ACTIVITIES ->
        DynamicLayoutInspectorAutoConnectInfo.AutoConnectReasonNotSupported
          .DUMPSYS_NO_TOP_ACTIVITY_NO_SLEEPING_ACTIVITIES
      LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported
        .DUMPSYS_NO_TOP_ACTIVITY_BUT_HAS_AWAKE_ACTIVITIES ->
        DynamicLayoutInspectorAutoConnectInfo.AutoConnectReasonNotSupported
          .DUMPSYS_NO_TOP_ACTIVITY_BUT_HAS_AWAKE_ACTIVITIES
      LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported.UNRECOGNIZED,
      LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported.UNKNOWN_REASON ->
        DynamicLayoutInspectorAutoConnectInfo.AutoConnectReasonNotSupported.UNSPECIFIED_REASON
    }
  }
}
