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
package com.android.tools.idea.devicemanagerv2

import com.android.annotations.concurrency.UiThread
import com.android.sdklib.deviceprovisioner.CreateDeviceAction
import com.android.sdklib.deviceprovisioner.CreateDeviceTemplateAction
import com.android.sdklib.deviceprovisioner.DeviceAction
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.SetChange
import com.android.sdklib.deviceprovisioner.trackSetChanges
import com.android.tools.adtui.actions.DropDownAction
import com.android.tools.adtui.categorytable.CategoryTable
import com.android.tools.adtui.categorytable.IconButton
import com.android.tools.adtui.util.ActionToolbarUtil
import com.android.tools.idea.adb.wireless.PairDevicesUsingWiFiAction
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.android.tools.idea.devicemanagerv2.DeviceTableColumns.columns
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.google.common.collect.ConcurrentHashMultiset
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import icons.StudioIcons
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.android.AndroidPluginDisposable

/** The main Device Manager panel, containing a table of devices and a toolbar of buttons above. */
internal class DeviceManagerPanel(val project: Project) : JPanel() {

  private val panelScope =
    AndroidCoroutineScope(AndroidPluginDisposable.getProjectInstance(project))
  private val deviceProvisioner = project.service<DeviceProvisionerService>().deviceProvisioner

  private val scrollPane = JBScrollPane()
  private var deviceTable =
    CategoryTable(
      columns(project, panelScope),
      DeviceRowData::key,
      uiThread,
      rowDataProvider = ::provideRowData
    )

  private val templateInstantiationCount = ConcurrentHashMultiset.create<DeviceTemplate>()

  init {
    layout = BorderLayout()

    // TODO: Add group selector dropdown

    val groupingActions =
      DefaultActionGroup("Group", null, AllIcons.Actions.GroupBy).apply {
        isPopup = true
        add(Separator.create("Group By"))
        add(GroupByNoneAction(deviceTable))
        add(GroupingAction(deviceTable, DeviceTableColumns.Type))
        add(GroupingAction(deviceTable, DeviceTableColumns.Status))
        // TODO: Group by Device groups, OEM, Source
      }

    val createDeviceActions = deviceProvisioner.createDeviceActions().map { it.toAnAction() }
    val createTemplateActions = deviceProvisioner.createTemplateActions().map { it.toAnAction() }
    val createActions = createDeviceActions + createTemplateActions

    val addDevice =
      when (createActions.size) {
        0 -> null
        1 -> createActions[0]
        else ->
          DropDownAction("Add Device", "Add a new device", StudioIcons.Common.ADD).also {
            it.addAll(createActions)
          }
      }

    val typeSpecificActions = ActionManager.getInstance().getAction("Android.DeviceManager.TypeSpecificActions")
    val toolbar =
      createToolbar(
        listOfNotNull(groupingActions, Separator.create(), addDevice, typeSpecificActions)
      )

    add(toolbar.component, BorderLayout.NORTH)

    deviceTable.toggleSortOrder(DeviceTableColumns.nameAttribute)
    deviceTable.addToScrollPane(scrollPane)
    add(scrollPane, BorderLayout.CENTER)

    panelScope.launch(uiThread) { trackDevices() }
    panelScope.launch(uiThread) { trackDeviceTemplates() }
  }

  private suspend fun trackDevices() {
    deviceProvisioner.devices
      .map { it.toSet() }
      .trackSetChanges()
      .collect { change ->
        when (change) {
          is SetChange.Add -> trackDevice(change.value)
          is SetChange.Remove -> {} // we use device scope ending to detect removal
        }
      }
  }
  private suspend fun trackDeviceTemplates() {
    deviceProvisioner.templates
      .map { it.toSet() }
      .trackSetChanges()
      .collect { change ->
        when (change) {
          is SetChange.Add -> deviceTable.addRow(DeviceRowData.create(change.value))
          is SetChange.Remove -> deviceTable.removeRowByKey(change.value)
        }
      }
  }

  private suspend fun trackDevice(handle: DeviceHandle) {
    deviceTable.addRow(DeviceRowData.create(handle))
    handle.sourceTemplate?.let {
      if (templateInstantiationCount.add(it, 1) == 0) {
        withContext(uiThread) { deviceTable.setRowVisibleByKey(it, false) }
      }
    }

    panelScope.launch {
      // As long as the device scope is active, update its state in the table.
      // When it completes, remove it from the table.
      handle.scope
        .launch {
          handle.stateFlow.collect {
            withContext(uiThread) { deviceTable.updateRow(DeviceRowData.create(handle)) }
          }
        }
        .join()

      withContext(uiThread) {
        deviceTable.removeRow(DeviceRowData.create(handle))
        handle.sourceTemplate?.let {
          if (templateInstantiationCount.remove(it, 1) == 1) {
            deviceTable.setRowVisibleByKey(it, true)
          }
        }
      }
    }
  }

  private fun <A : DeviceAction> A.toAnAction(action: suspend A.() -> Unit): AnAction {
    panelScope.launch {
      // Any time the DeviceAction presentation changes, update the ActivityTracker so that we can
      // update the AnAction presentation
      presentation.collect { ActivityTracker.getInstance().inc() }
    }
    return object :
      AnAction(presentation.value.label, presentation.value.label, presentation.value.icon) {
      override fun getActionUpdateThread() = ActionUpdateThread.BGT

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = presentation.value.enabled
        e.presentation.icon = presentation.value.icon
        e.presentation.text = presentation.value.label
      }

      override fun actionPerformed(e: AnActionEvent) {
        panelScope.launch { action() }
      }
    }
  }

  private fun CreateDeviceAction.toAnAction() = toAnAction(CreateDeviceAction::create)
  private fun CreateDeviceTemplateAction.toAnAction() =
    toAnAction(CreateDeviceTemplateAction::create)

  private fun createToolbar(actions: List<AnAction>): ActionToolbar {
    val toolbar =
      ActionManager.getInstance().createActionToolbar(TOOLBAR_ID, DefaultActionGroup(actions), true)
    toolbar.layoutPolicy = ActionToolbar.AUTO_LAYOUT_POLICY
    toolbar.targetComponent = this
    ActionToolbarUtil.makeToolbarNavigable(toolbar)
    return toolbar
  }
}

@UiThread
internal suspend fun IconButton.trackActionPresentation(action: DeviceAction?) =
  when (action) {
    null -> isEnabled = false
    else -> action.presentation.collect {
      isEnabled = it.enabled
      baseIcon = it.icon
    }
  }

private const val TOOLBAR_ID = "DeviceManager2"
