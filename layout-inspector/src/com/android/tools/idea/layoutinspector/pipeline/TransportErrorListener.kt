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
package com.android.tools.idea.layoutinspector.pipeline

import com.android.tools.idea.appinspection.internal.process.toDeviceDescriptor
import com.android.tools.idea.layoutinspector.LayoutInspectorBundle
import com.android.tools.idea.layoutinspector.metrics.LayoutInspectorMetrics
import com.android.tools.idea.layoutinspector.model.NotificationModel
import com.android.tools.idea.run.AndroidRunConfigurationBase
import com.android.tools.idea.transport.FailedToStartServerException
import com.android.tools.idea.transport.TransportDeviceManager
import com.android.tools.idea.transport.TransportProxy
import com.android.tools.profiler.proto.Agent
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Transport
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorTransportError
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotificationPanel.Status

/**
 * Class responsible for listening to events published by the transport.
 */
class TransportErrorListener(
  private val project: Project,
  private val notificationModel: NotificationModel,
  private val layoutInspectorMetrics: LayoutInspectorMetrics,
  private val disposable: Disposable
  ) : TransportDeviceManager.TransportDeviceManagerListener {
  val errorMessage = LayoutInspectorBundle.message("two.versions.of.studio.running")

  private var hasStartServerFailed = false
    set(value) {
      field = value
      if (hasStartServerFailed) {
        // the banner can't be dismissed. It will automatically be dismissed when the Transport tries to start again.
        notificationModel.addNotification(errorMessage, Status.Error, emptyList())
        // TODO(b/258453315) log to metrics
      }
      else {
        notificationModel.removeNotification(errorMessage)
      }
    }

  init {
    project.messageBus.connect(disposable).subscribe(TransportDeviceManager.TOPIC, this)
  }

  override fun onPreTransportDaemonStart(device: Common.Device) {
    hasStartServerFailed = false
  }

  override fun onTransportDaemonException(device: Common.Device, exception: java.lang.Exception) { }

  override fun onTransportProxyCreationFail(device: Common.Device, exception: Exception) { }

  override fun onStartTransportDaemonServerFail(device: Common.Device, exception: FailedToStartServerException) {
    // this happens if the transport can't start the server on the designated port.
    // for example if multiple versions of Studio are running.
    hasStartServerFailed = true
    layoutInspectorMetrics.logTransportError(
      DynamicLayoutInspectorTransportError.Type.TRANSPORT_FAILED_TO_START_DAEMON,
      device.toDeviceDescriptor()
    )
  }

  override fun customizeProxyService(proxy: TransportProxy) { }
  override fun customizeDaemonConfig(configBuilder: Transport.DaemonConfig.Builder) { }
  override fun customizeAgentConfig(configBuilder: Agent.AgentConfig.Builder, runConfig: AndroidRunConfigurationBase?) { }
}