/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.idea.editors.literals

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.tools.idea.editors.literals.LiveEditService.Companion.usesCompose
import com.android.tools.idea.editors.liveedit.LiveEditApplicationConfiguration
import com.android.tools.idea.editors.liveedit.ui.EmulatorLiveEditAdapter
import com.android.tools.idea.editors.liveedit.ui.LiveEditIssueNotificationAction
import com.android.tools.idea.execution.common.AndroidExecutionTarget
import com.android.tools.idea.run.AndroidRunConfigurationBase
import com.android.tools.idea.run.deployment.liveedit.EditEvent
import com.android.tools.idea.run.deployment.liveedit.LiveEditAdbEventsListener
import com.android.tools.idea.run.deployment.liveedit.LiveEditApp
import com.android.tools.idea.run.deployment.liveedit.LiveEditNotifications
import com.android.tools.idea.run.deployment.liveedit.LiveEditProjectMonitor
import com.android.tools.idea.run.deployment.liveedit.LiveEditStatus
import com.android.tools.idea.run.deployment.liveedit.PsiListener
import com.android.tools.idea.run.deployment.liveedit.SourceInlineCandidateCache
import com.android.tools.idea.run.profiler.AbstractProfilerExecutorGroup.Companion.getExecutorSetting
import com.android.tools.idea.run.profiler.ProfilingMode
import com.android.tools.idea.run.util.LaunchUtils
import com.android.tools.idea.streaming.RUNNING_DEVICES_TOOL_WINDOW_ID
import com.android.tools.idea.streaming.SERIAL_NUMBER_KEY
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.BrowseNotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.Executor

/**
 * Allows any component to listen to all method body edits of a project.
 */
@Service(Service.Level.PROJECT)
class LiveEditServiceImpl(val project: Project,
                          var executor: Executor,
                          override val adbEventsListener: LiveEditAdbEventsListener) : Disposable, LiveEditService {

  private val notifications = LiveEditNotifications(project)

  private val deployMonitor: LiveEditProjectMonitor

  private var showMultiDeviceNotification = true

  private var showMultiDeployNotification = true

  // We quickly hand off the processing of PSI events to our own executor, since PSI events are likely
  // dispatched from the UI thread, and we do not want to block it.
  constructor(project: Project) : this(project,
                                       AppExecutorUtil.createBoundedApplicationPoolExecutor(
                                         "Document changed listeners executor", 1),
                                       LiveEditAdbEventsListener())

  init {
    val adapter = EmulatorLiveEditAdapter(project)
    LiveEditIssueNotificationAction.registerProject(project, adapter)
    Disposer.register(this) { LiveEditIssueNotificationAction.unregisterProject(project) }
    ApplicationManager.getApplication().invokeLater {
      val toolWindowManager = project.getServiceIfCreated(ToolWindowManager::class.java)
      toolWindowManager?.invokeLater {
        val runningDevicesWindow = toolWindowManager.getToolWindow(RUNNING_DEVICES_TOOL_WINDOW_ID)
        runningDevicesWindow?.addContentManagerListener(object : ContentManagerListener {
          override fun contentAdded(event: ContentManagerEvent) {
            val dataProvider = event.content.component as? DataProvider ?: return
            val serial = dataProvider.getData(SERIAL_NUMBER_KEY.name) as String?
            serial?.let { adapter.register(it) }
          }

          override fun contentRemoveQuery(event: ContentManagerEvent) {
            val dataProvider = event.content.component as? DataProvider ?: return
            val serial = dataProvider.getData(SERIAL_NUMBER_KEY.name) as String?
            serial?.let { adapter.unregister(it) }
          }
        })

        runningDevicesWindow?.contentManagerIfCreated?.contents?.forEach {
          val dataProvider = it.component as? DataProvider ?: return@forEach
          val serial = dataProvider.getData(SERIAL_NUMBER_KEY.name) as String?
          serial?.let { s -> adapter.register(s) }
        }
      }
    }

    // TODO: Deactivate this when not needed.
    val listener = PsiListener(this::onPsiChanged)
    PsiManager.getInstance(project).addPsiTreeChangeListener(listener, this)
    deployMonitor = LiveEditProjectMonitor(this, project)
    // TODO: Delete if it turns our we don't need Hard-refresh trigger.
    //bindKeyMapShortcut(LiveEditApplicationConfiguration.getInstance().leTriggerMode)

    // Listen for when the user starts a Run/Debug.
    project.messageBus.connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, object: ExecutionListener {
      override fun processStarting(executorId: String, env: ExecutionEnvironment) {
        val executionTarget = (env.executionTarget as? AndroidExecutionTarget) ?: return
        val devices = executionTarget.runningDevices

        val multiDeploy = deployMonitor.notifyExecution(devices)

        if (devices.size > 1 && showMultiDeviceNotification) {
          NotificationGroupManager.getInstance().getNotificationGroup("Deploy")
            .createNotification(
              "Live Edit works with multi-device deployments but this is not officially supported.",
              NotificationType.INFORMATION)
            .addAction(BrowseNotificationAction("Learn more", "https://developer.android.com/studio/run#limitations"))
            .notify(project)
          showMultiDeviceNotification = false
        }

        if (multiDeploy && showMultiDeployNotification) {
          NotificationGroupManager.getInstance().getNotificationGroup("Deploy")
            .createNotification(
              "Live Edit does not work with previous deployments on different devices.",
              NotificationType.INFORMATION)
            .addAction(BrowseNotificationAction("Learn more", "https://developer.android.com/studio/run#limitations"))
            .notify(project)
          showMultiDeployNotification = false
        }
      }
    })
  }

  override fun inlineCandidateCache() : SourceInlineCandidateCache {
    return deployMonitor.compiler.inlineCandidateCache
  }

  companion object {
    private fun hasLiveEditSupportedDeviceConnected() = AndroidDebugBridge.getBridge()!!.devices.any { device ->
      LiveEditProjectMonitor.supportLiveEdits(device)
    }
  }

  // TODO: Refactor this away when AndroidLiveEditDeployMonitor functionality is moved to LiveEditService/other classes.
  @VisibleForTesting
  override fun getDeployMonitor(): LiveEditProjectMonitor {
    return deployMonitor
  }

  override fun devices(): Set<IDevice> {
    return deployMonitor.devices()
  }

  override fun editStatus(device: IDevice): LiveEditStatus {
    return deployMonitor.status(device)
  }

  /**
   * Called from Android Studio when an app is "Refreshed" (namely Apply Changes or Apply Code Changes) to a device
   */
  override fun notifyAppRefresh(device: IDevice): Boolean {
    return deployMonitor.notifyAppRefresh(device)
  }

  /**
   * Called from Android Studio when an app is deployed (a.k.a Installed / IWIed / Delta-installed) to a device
   */
  override fun notifyAppDeploy(runProfile: RunProfile,
                               executor: com.intellij.execution.Executor,
                               packageName: String,
                               device: IDevice,
                               app: LiveEditApp): Boolean {
    return deployMonitor.notifyAppDeploy(packageName, device, app) { isLiveEditable(runProfile, executor) }
  }

  override fun toggleLiveEdit(oldMode: LiveEditApplicationConfiguration.LiveEditMode, newMode: LiveEditApplicationConfiguration.LiveEditMode) {
    if (oldMode == newMode) {
      return
    } else if (newMode == LiveEditApplicationConfiguration.LiveEditMode.LIVE_EDIT) {
      if (LiveEditService.usesCompose(project) && hasLiveEditSupportedDeviceConnected()) {
        deployMonitor.requestRerun()
      }
    } else {
      deployMonitor.clearDevices()
    }
  }

  override fun toggleLiveEditMode(oldMode: LiveEditService.Companion.LiveEditTriggerMode, newMode: LiveEditService.Companion.LiveEditTriggerMode) {
    if (oldMode == newMode) {
      return
    }
    else if (newMode == LiveEditService.Companion.LiveEditTriggerMode.AUTOMATIC) {
      deployMonitor.onManualLETrigger()
    }
  }

  @com.android.annotations.Trace
  private fun onPsiChanged(event: EditEvent) {
    executor.execute { deployMonitor.onPsiChanged(event) }
  }

  override fun dispose() {
    //TODO: "Not yet implemented"
  }

  override fun triggerLiveEdit() {
    deployMonitor.onManualLETrigger()
  }

  override fun notifyLiveEditAvailability(device: IDevice) {
    notifications.notifyLiveEditAvailability(device)
  }

  private fun isLiveEditable(runProfile: RunProfile, executor: com.intellij.execution.Executor): Boolean {
    // TODO(b/281742972): Move this to use ManifestInfo and remove direct work around profilers.
    // Profiler has a hack in AGP that sets debugability of the APK to false, and is not reflected in the AndroidModel.
    // To properly catch this, we need to parse the APK for the debugability flag, which is a much bigger change than we want for now.
    val profilerSetting = getExecutorSetting(executor.id)
    if (!usesCompose(project)) {
      return false
    }
    if (profilerSetting != null && profilerSetting.profilingMode !== ProfilingMode.DEBUGGABLE) {
      return false
    }
    if (runProfile !is AndroidRunConfigurationBase) {
      return false
    }
    val module: Module = runProfile.configurationModule.module ?: return false
    val facet = AndroidFacet.getInstance(module)
    return facet != null && LaunchUtils.canDebugApp(facet)
  }
}
