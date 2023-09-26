/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.streaming.core

import com.android.adblib.serialNumber
import com.android.annotations.concurrency.AnyThread
import com.android.annotations.concurrency.UiThread
import com.android.sdklib.SdkVersionInfo
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceProvisioner
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.sdklib.deviceprovisioner.ReservationState
import com.android.sdklib.deviceprovisioner.mapStateNotNull
import com.android.sdklib.internal.avd.AvdInfo
import com.android.tools.idea.adb.wireless.PairDevicesUsingWiFiAction
import com.android.tools.idea.avdmanager.AvdLaunchListener
import com.android.tools.idea.avdmanager.AvdLaunchListener.RequestType
import com.android.tools.idea.avdmanager.AvdManagerConnection
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.addCallback
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerService
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.run.DeviceHeadsUpListener
import com.android.tools.idea.streaming.DeviceMirroringSettings
import com.android.tools.idea.streaming.EmulatorSettings
import com.android.tools.idea.streaming.MirroringHandle
import com.android.tools.idea.streaming.MirroringManager
import com.android.tools.idea.streaming.MirroringState
import com.android.tools.idea.streaming.RUNNING_DEVICES_TOOL_WINDOW_ID
import com.android.tools.idea.streaming.core.StreamingDevicePanel.UiState
import com.android.tools.idea.streaming.device.DeviceClient
import com.android.tools.idea.streaming.device.DeviceConfiguration
import com.android.tools.idea.streaming.device.DeviceToolWindowPanel
import com.android.tools.idea.streaming.device.composeDeviceName
import com.android.tools.idea.streaming.device.dialogs.MirroringConfirmationDialog
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionState
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionStateListener
import com.android.tools.idea.streaming.emulator.EmulatorId
import com.android.tools.idea.streaming.emulator.EmulatorToolWindowPanel
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.utils.FlightRecorder
import com.android.utils.TraceUtils
import com.google.common.cache.CacheBuilder
import com.intellij.collaboration.async.disposingScope
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.ToggleToolbarAction
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionButtonComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ex.MessagesEx.showErrorDialog
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerEvent.ContentOperation
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.impl.ContentImpl.PROP_CONTENT_MANAGER
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.util.Alarm
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor
import com.intellij.util.concurrency.EdtExecutorService
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.EventQueue
import java.awt.event.KeyEvent
import java.text.Collator
import java.time.Duration
import java.util.Arrays

private const val DEVICE_FRAME_VISIBLE_PROPERTY = "com.android.tools.idea.streaming.emulator.frame.visible"
private const val DEVICE_FRAME_VISIBLE_DEFAULT = true
private const val ZOOM_TOOLBAR_VISIBLE_PROPERTY = "com.android.tools.idea.streaming.zoom.toolbar.visible"
private const val ZOOM_TOOLBAR_VISIBLE_DEFAULT = true
private const val EMULATOR_DISCOVERY_INTERVAL_MILLIS = 1000

private val ID_KEY = Key.create<DeviceId>("device-id")

private val ATTENTION_REQUEST_EXPIRATION = Duration.ofSeconds(30)

private val COLLATOR = Collator.getInstance()

private val TAB_COMPARATOR = compareBy<Content, Any?>(COLLATOR) { it.tabName ?: "" }.thenBy { ID_KEY.get(it) }

/**
 * Manages contents of the Running Devices tool window. Listens to device connections and
 * disconnections and maintains [StreamingDevicePanel]s, one per running AVD or a mirrored physical
 * device.
 */
@UiThread
internal class StreamingToolWindowManager @AnyThread constructor(
  private val toolWindow: ToolWindow,
) : RunningEmulatorCatalog.Listener, DumbAware, Disposable {

  private val project
    @AnyThread get() = toolWindow.project
  private val properties = PropertiesComponent.getInstance(project)
  private val emulatorSettings = EmulatorSettings.getInstance()
  private val deviceMirroringSettings = DeviceMirroringSettings.getInstance()
  private val deviceProvisioner
    @AnyThread get() = project.service<DeviceProvisionerService>().deviceProvisioner
  private var initialized = false
  private var contentShown = false
  private var mirroringConfirmationDialogShowing = false
  private var physicalDeviceWatcher: PhysicalDeviceWatcher

  /** When the tool window is hidden, the state of the UI for all emulators, otherwise empty. */
  private val savedUiState = hashMapOf<DeviceId, UiState>()
  private val emulators = hashSetOf<EmulatorController>()

  private var onlineDevices = mapOf<String, ConnectedDevice>()
  /** Clients for mirrorable devices keyed by serial numbers. */
  private var deviceClients = mutableMapOf<String, DeviceClient>()
  /** Handles of devices excluded from mirroring keyed by serial numbers. */
  private var devicesExcludedFromMirroring = mutableMapOf<String, DeviceDescription>()

  /** Requested activation levels of devices that recently requested attention keyed by their serial numbers. */
  private val recentAttentionRequests =
      CacheBuilder.newBuilder().expireAfterWrite(ATTENTION_REQUEST_EXPIRATION).build<String, ActivationLevel>()
  /** Requested activation levels of AVDs keyed by their IDs. */
  private val recentEmulatorLaunches =
      CacheBuilder.newBuilder().expireAfterWrite(ATTENTION_REQUEST_EXPIRATION).build<String, ActivationLevel>()

  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  @Suppress("UnstableApiUsage")
  private val toolWindowScope = disposingScope(Dispatchers.EDT)

  // Copy-on-write to allow changes while iterating.
  private val contentManagers = ContainerUtil.createLockFreeCopyOnWriteList<ContentManager>()

  private val contentManagerListener = object : ContentManagerListener {
    override fun selectionChanged(event: ContentManagerEvent) {
      if (event.operation != ContentOperation.remove || !Content.TEMPORARY_REMOVED_KEY.get(event.content, false)) {
        viewSelectionChanged()
      }
    }

    override fun contentAdded(event: ContentManagerEvent) {
      event.content.addPropertyChangeListener { evt ->
        if (evt.propertyName == PROP_CONTENT_MANAGER) {
          val contentManager = evt.newValue as? ContentManager
          contentManager?.let { adoptContentManager(it) }
        }
      }
    }

    override fun contentRemoveQuery(event: ContentManagerEvent) {
      val content = event.content
      if (Content.TEMPORARY_REMOVED_KEY.get(content, false)) {
        return
      }
      val panel = content.component as? StreamingDevicePanel ?: return
      when (panel) {
        is EmulatorToolWindowPanel -> panel.emulator.shutdown()
        is DeviceToolWindowPanel -> panelClosed(panel)
      }

      savedUiState.remove(panel.id)
      if (contentManagers.size == 1 && content.manager?.contentCount == 1) {
        if (contentShown) {
          createEmptyStatePanel()
        }
        hideLiveIndicator()
      }
    }
  }

  private val connectionStateListener = object : ConnectionStateListener {
    @AnyThread
    override fun connectionStateChanged(emulator: EmulatorController, connectionState: ConnectionState) {
      if (connectionState == ConnectionState.DISCONNECTED) {
        EventQueue.invokeLater { // This is safe because this code doesn't touch PSI or VFS.
          if (removeEmulatorPanel(emulator)) {
            emulators.remove(emulator)
          }
        }
      }
    }
  }

  private var deviceFrameVisible
    get() = properties.getBoolean(DEVICE_FRAME_VISIBLE_PROPERTY, DEVICE_FRAME_VISIBLE_DEFAULT)
    set(value) {
      properties.setValue(DEVICE_FRAME_VISIBLE_PROPERTY, value, DEVICE_FRAME_VISIBLE_DEFAULT)
      for (contentManager in contentManagers) {
        for (i in 0 until contentManager.contentCount) {
          (contentManager.getContent(i)?.component as? StreamingDevicePanel)?.setDeviceFrameVisible(value)
        }
      }
    }

  private var zoomToolbarIsVisible
    get() = properties.getBoolean(ZOOM_TOOLBAR_VISIBLE_PROPERTY, ZOOM_TOOLBAR_VISIBLE_DEFAULT)
    set(value) {
      properties.setValue(ZOOM_TOOLBAR_VISIBLE_PROPERTY, value, ZOOM_TOOLBAR_VISIBLE_DEFAULT)
      for (contentManager in contentManagers) {
        for (i in 0 until contentManager.contentCount) {
          (contentManager.getContent(i)?.component as? StreamingDevicePanel)?.zoomToolbarVisible = value
        }
      }
    }

  init {
    FlightRecorder.initialize(1000)
    Disposer.register(toolWindow.disposable, this)

    // Lazily initialize content since we can only have one frame.
    val messageBusConnection = project.messageBus.connect(this)
    messageBusConnection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {

      override fun stateChanged(toolWindowManager: ToolWindowManager) {
        val toolWindow = toolWindowManager.getToolWindow(RUNNING_DEVICES_TOOL_WINDOW_ID) ?: return

        toolWindowManager.invokeLater {
          if (!toolWindow.isDisposed) {
            if (toolWindow.isVisible) {
              onToolWindowShown()
            }
            else {
              onToolWindowHidden()
            }
          }
        }
      }
    })

    messageBusConnection.subscribe(AvdLaunchListener.TOPIC,
                                   AvdLaunchListener { avd, commandLine, requestType, project ->
                                     if (project == toolWindow.project && isEmbeddedEmulator(commandLine)) {
                                       RunningEmulatorCatalog.getInstance().updateNow()
                                       EventQueue.invokeLater { // This is safe because this code doesn't touch PSI or VFS.
                                         showLiveIndicator()
                                         if (requestType != RequestType.INDIRECT) {
                                           onEmulatorHeadsUp(avd.name, ActivationLevel.ACTIVATE_TAB)
                                         }
                                       }
                                     }
                                   })

    messageBusConnection.subscribe(DeviceHeadsUpListener.TOPIC, MyDeviceHeadsUpListener())

    physicalDeviceWatcher = PhysicalDeviceWatcher(this)
  }

  override fun dispose() {
    onToolWindowHidden()
  }

  @AnyThread
  private fun onDeviceHeadsUp(serialNumber: String, activationLevel: ActivationLevel, project: Project) {
    if (project == toolWindow.project) {
      UIUtil.invokeLaterIfNeeded {
        val excludedDevice = devicesExcludedFromMirroring.remove(serialNumber)
        when {
          excludedDevice != null -> activateMirroring(serialNumber, excludedDevice.handle, excludedDevice.config, activationLevel)
          serialNumber in deviceClients -> onPhysicalDeviceHeadsUp(serialNumber, activationLevel)
          else -> addAttentionRequestAndTriggerEmulatorCatalogUpdate(serialNumber, activationLevel)
        }
      }
    }
  }

  private fun addAttentionRequestAndTriggerEmulatorCatalogUpdate(serialNumber: String, activationLevel: ActivationLevel) {
    recentAttentionRequests.put(serialNumber, activationLevel)
    alarm.addRequest(recentAttentionRequests::cleanUp, ATTENTION_REQUEST_EXPIRATION.toMillis())
    if (isLocalEmulator(serialNumber)) {
      val future = RunningEmulatorCatalog.getInstance().updateNow()
      future.addCallback(EdtExecutorService.getInstance(),
                         success = { emulators ->
                           if (emulators != null) {
                             onEmulatorHeadsUp(serialNumber, emulators, activationLevel)
                           }
                         },
                         failure = {})
    }
  }

  private fun onPhysicalDeviceHeadsUp(serialNumber: String, activationLevel: ActivationLevel) {
    if (toolWindow.isVisible) {
      val content = findContentBySerialNumberOfPhysicalDevice(serialNumber)
      if (content != null) {
        content.select()
        toolWindow.activate(activationLevel)
      }
    }
    else {
      recentAttentionRequests.put(serialNumber, activationLevel)
      toolWindow.activate(activationLevel)
    }
  }

  private fun onEmulatorHeadsUp(serialNumber: String, runningEmulators: Set<EmulatorController>, activationLevel: ActivationLevel) {
    val emulator = runningEmulators.find { it.emulatorId.serialNumber == serialNumber } ?: return
    // Ignore standalone emulators.
    if (emulator.emulatorId.isEmbedded) {
      onEmulatorHeadsUp(emulator.emulatorId.avdId, activationLevel)
    }
  }

  private fun onEmulatorHeadsUp(avdId: String, activationLevel: ActivationLevel) {
    toolWindow.activate(activationLevel)

    val content = findContentByAvdId(avdId)
    if (content == null) {
      RunningEmulatorCatalog.getInstance().updateNow()
      recentEmulatorLaunches.put(avdId, activationLevel)
      alarm.addRequest(recentEmulatorLaunches::cleanUp, ATTENTION_REQUEST_EXPIRATION.toMillis())
    }
    else {
      content.select()
    }
  }

  private fun onToolWindowShown() {
    if (!initialized) {
      initialized = true

      val newTabAction = NewTabAction()
      newTabAction.registerCustomShortcutSet(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK, toolWindow.component)
      (toolWindow as ToolWindowEx).setTabActions(newTabAction)

      val actionGroup = DefaultActionGroup()
      actionGroup.addAction(ToggleZoomToolbarAction())
      actionGroup.addAction(ToggleDeviceFrameAction())
      toolWindow.setAdditionalGearActions(actionGroup)
      adoptContentManager(toolWindow.contentManager)
    }

    if (contentShown) {
      return
    }
    contentShown = true

    val emulatorCatalog = RunningEmulatorCatalog.getInstance()
    emulatorCatalog.updateNow()
    emulatorCatalog.addListener(this, EMULATOR_DISCOVERY_INTERVAL_MILLIS)
    assert(emulators.isEmpty())
    emulators.addAll(emulatorCatalog.emulators.filter { it.emulatorId.isEmbedded }) // Ignore standalone emulators.

    for (emulator in emulators) {
      if (findContentByEmulatorId(emulator.emulatorId) == null) {
        addEmulatorPanel(emulator)
      }
    }
    for (deviceClient in deviceClients.values) {
      if (findContentBySerialNumberOfPhysicalDevice(deviceClient.deviceSerialNumber) == null) {
        addPanel(DeviceToolWindowPanel(toolWindow.disposable, project, deviceClient))
      }
    }

    for (contentManager in contentManagers) {
      // Remove panels of inactive devices.
      for (content in contentManager.contents) {
        val deviceId = ID_KEY.get(content) ?: continue
        when (deviceId) {
          is DeviceId.EmulatorDeviceId -> {
            val emulator = emulators.find { it.emulatorId == deviceId.emulatorId }
            if (emulator == null || emulator.isShuttingDown) {
              savedUiState.remove(deviceId)
              content.removeAndDispose()
            }
          }

          is DeviceId.PhysicalDeviceId -> {
            val deviceClient = deviceClients[deviceId.serialNumber]
            if (deviceClient == null) {
              savedUiState.remove(deviceId)
              content.removeAndDispose()
            }
          }
        }
      }
      // Restore content of visible panels.
      for (content in contentManager.selectedContents) {
        val panel = content.component as? StreamingDevicePanel ?: continue
        if (!panel.hasContent) {
          panel.createContent(deviceFrameVisible, savedUiState[panel.id])
        }
      }
    }

    if (contentManagers.size == 1 && contentManagers[0].contentCount == 0) {
      createEmptyStatePanel()
    }

    viewSelectionChanged()
  }

  private fun onToolWindowHidden() {
    if (!contentShown) {
      return
    }
    contentShown = false

    RunningEmulatorCatalog.getInstance().removeListener(this)
    emulators.clear()
    recentAttentionRequests.invalidateAll()
    recentEmulatorLaunches.invalidateAll()

    for (contentManager in contentManagers) {
      val panel = contentManager.selectedContent?.component as? StreamingDevicePanel ?: continue
      savedUiState[panel.id] = panel.destroyContent()
    }
  }

  private fun adoptContentManager(contentManager: ContentManager) {
    if (contentManager !in contentManagers) {
      contentManagers.add(contentManager)
      contentManager.addContentManagerListener(contentManagerListener)
      contentManager.addSelectedPanelDataProvider()
      Disposer.register(contentManager) {
        contentManagers.remove(contentManager)
      }
    }
  }

  private fun addEmulatorPanel(emulator: EmulatorController) {
    emulator.addConnectionStateListener(connectionStateListener)
    addPanel(EmulatorToolWindowPanel(toolWindow.disposable, project, emulator))
  }

  private fun addPanel(panel: StreamingDevicePanel) {
    val contentManager = toolWindow.contentManager
    val placeholderContent = contentManager.placeholderContent

    val contentFactory = ContentFactory.getInstance()
    val content = contentFactory.createContent(panel, shortenTitleText(panel.title), false).apply {
      putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
      tabName = panel.title
      description = panel.description
      icon = panel.icon
      popupIcon = panel.icon
      setPreferredFocusedComponent(panel::preferredFocusableComponent)
      ID_KEY.set(this, panel.id)
    }

    panel.zoomToolbarVisible = zoomToolbarIsVisible

    if (StudioFlags.DEVICE_MIRRORING_TAB_DND.get()) {
      if (findContentByDeviceId(panel.id) != null) {
        thisLogger().error("An attempt to add a duplicate panel ${TraceUtils.getSimpleId(content)} ${content.displayName}\n" +
                           TraceUtils.getCurrentStack() +
                           "Panel creation history:\n${FlightRecorder.getAndClear().joinToString("\n")}")
        return
      }

      // Add panel to the end.
      contentManager.addContent(content)
    }
    else {
      val index = Arrays.binarySearch(contentManager.contents, content, TAB_COMPARATOR).inv()
      if (index < 0) {
        thisLogger().error("An attempt to add a duplicate panel ${TraceUtils.getSimpleId(panel)} ${panel.title}\n" +
                           FlightRecorder.getAndClear())
        return
      }

      // Insert panel in alphabetical order of the title.
      contentManager.addContent(content, index)
    }

    FlightRecorder.log { "${TraceUtils.getSimpleId(this)}: added panel ${TraceUtils.getSimpleId(content)} ${content.displayName}\n" +
                         TraceUtils.getCurrentStack() }

    if (!content.isSelected) {
      // Activate the newly added panel if it corresponds to a recently launched or used Emulator.
      val deviceId = panel.id
      if (deviceId is DeviceId.EmulatorDeviceId) {
        val avdId = deviceId.emulatorId.avdId
        if (recentEmulatorLaunches.getIfPresent(avdId) != null) {
          recentEmulatorLaunches.invalidate(avdId)
          content.select()
        }
      }
    }

    if (placeholderContent != null) {
      showLiveIndicator()
      placeholderContent.removeAndDispose() // Remove the placeholder panel if it was present.
    }
  }

  private fun removeEmulatorPanel(emulator: EmulatorController): Boolean {
    emulator.removeConnectionStateListener(connectionStateListener)
    val content = findContentByEmulatorId(emulator.emulatorId) ?: return false
    savedUiState.remove(ID_KEY.get(content))
    content.removeAndDispose()
    return true
  }

  private fun removePhysicalDevicePanel(serialNumber: String) {
    val content = findContentBySerialNumberOfPhysicalDevice(serialNumber) ?: return
    savedUiState.remove(ID_KEY.get(content))
    deviceClients.remove(serialNumber)?.let {
      Disposer.dispose(it)
      updateMirroringHandlesFlow()
    }
    content.removeAndDispose()
  }

  private fun removeAllPhysicalDevicePanels() {
    for (deviceClient in deviceClients.values) {
      Disposer.dispose(deviceClient)
    }
    deviceClients.clear()
    updateMirroringHandlesFlow()
    for (contentManager in contentManagers) {
      for (content in contentManager.contents) {
        if (content.component is DeviceToolWindowPanel) {
          content.removeAndDispose()
        }
      }
    }
  }

  private fun createEmptyStatePanel() {
    val panel = try {
      EmptyStatePanel(project, this)
    }
    catch (e: IncorrectOperationException) {
      // This object has been disposed already.
      return
    }
    val contentFactory = ContentFactory.getInstance()
    val content = contentFactory.createContent(panel, null, false).apply {
      isCloseable = false
    }
    val contentManager = toolWindow.contentManager
    try {
      contentManager.addContent(content)
      contentManager.setSelectedContent(content)
    }
    catch (e: IncorrectOperationException) {
      // Content manager has been disposed already.
      Disposer.dispose(content)
    }
  }

  private fun viewSelectionChanged() {
    for (contentManager in contentManagers) {
      for (i in 0 until contentManager.contentCount) {
        val content = contentManager.getContent(i) ?: break
        val panel = content.component
        if (panel is StreamingDevicePanel) {
          if (content.isSelected) {
            if (!panel.hasContent) {
              // The panel became visible - create its content.
              panel.createContent(deviceFrameVisible, savedUiState.remove(panel.id))
              // Synchronize toolbar visibility across panels.
              ToggleToolbarAction.setToolbarVisible(toolWindow, PropertiesComponent.getInstance(project), null)
            }
          }
          else {
            if (panel.hasContent) {
              // The panel is no longer visible - destroy its content.
              savedUiState[panel.id] = panel.destroyContent()
            }
          }
        }
      }
    }
  }

  private fun findContentByDeviceId(deviceId: DeviceId): Content? =
      findContent { ID_KEY.get(it) == deviceId }
  private fun findContentByEmulatorId(emulatorId: EmulatorId): Content? =
      findContent { (ID_KEY.get(it) as? DeviceId.EmulatorDeviceId)?.emulatorId == emulatorId }
  private fun findContentByAvdId(avdId: String): Content? =
      findContent { (ID_KEY.get(it) as? DeviceId.EmulatorDeviceId)?.emulatorId?.avdId == avdId }
  private fun findContentBySerialNumberOfPhysicalDevice(serialNumber: String): Content? =
      findContent { ID_KEY.get(it)?.serialNumber == serialNumber && it.component is DeviceToolWindowPanel}

  private fun findContent(predicate: (Content) -> Boolean): Content? {
    for (contentManager in contentManagers) {
      for (i in 0 until contentManager.contentCount) {
        val content = contentManager.getContent(i) ?: break
        if (predicate(content)) {
          return content
        }
      }
    }
    return null
  }

  private fun showLiveIndicator() {
    toolWindow.setIcon(ExecutionUtil.getLiveIndicator(StudioIcons.Shell.ToolWindows.EMULATOR))
  }

  private fun hideLiveIndicator() {
    toolWindow.setIcon(StudioIcons.Shell.ToolWindows.EMULATOR)
  }

  @AnyThread
  override fun emulatorAdded(emulator: EmulatorController) {
    if (emulator.emulatorId.isEmbedded) {
      EventQueue.invokeLater { // This is safe because this code doesn't touch PSI or VFS.
        if (contentShown && emulators.add(emulator)) {
          addEmulatorPanel(emulator)
        }
      }
    }
  }

  @AnyThread
  override fun emulatorRemoved(emulator: EmulatorController) {
    if (emulator.emulatorId.isEmbedded) {
      EventQueue.invokeLater { // This is safe because this code doesn't touch PSI or VFS.
        if (removeEmulatorPanel(emulator)) {
          emulators.remove(emulator)
        }
      }
    }
  }

  private fun panelClosed(panel: DeviceToolWindowPanel) {
    val deviceHandle = panel.deviceClient.deviceHandle
    if (deviceHandle.state.isOnline()) {
      val deactivationAction = if (isLocalEmulator(panel.deviceSerialNumber)) null else deviceHandle.deactivationAction
      deactivationAction?.let { CoroutineScope(Dispatchers.IO).launch { it.deactivate() } } ?: stopMirroring(panel.deviceSerialNumber)
    }
  }

  private fun deactivateMirroring(serialNumber: String) {
    if (contentShown) {
      val content = findContentBySerialNumberOfPhysicalDevice(serialNumber) ?: return
      content.removeAndDispose()
    }
    else {
      stopMirroring(serialNumber)
    }
  }

  private fun stopMirroring(serialNumber: String) {
    val deviceClient = deviceClients.remove(serialNumber)
    if (deviceClient != null) {
      devicesExcludedFromMirroring[serialNumber] =
          DeviceDescription(deviceClient.deviceName, serialNumber, deviceClient.deviceHandle, deviceClient.deviceConfig)
      Disposer.dispose(deviceClient)
      updateMirroringHandlesFlow()
    }
  }

  private fun ToolWindow.activate(activationLevel: ActivationLevel) {
    if (isVisible) {
      if (activationLevel >= ActivationLevel.ACTIVATE_TAB) {
        activate(null)
      }
    }
    else {
      show {
        if (activationLevel >= ActivationLevel.ACTIVATE_TAB) {
          activate(null)
        }
      }
    }
  }

  private fun activateMirroring(deviceDescription: DeviceDescription) {
    val serialNumber = deviceDescription.serialNumber
    if (serialNumber !in deviceClients) {
      startMirroringIfConfirmed(serialNumber, deviceDescription.handle, deviceDescription.config, ActivationLevel.ACTIVATE_TAB)
    }
  }

  private fun activateMirroring(serialNumber: String, handle: DeviceHandle, config: DeviceConfiguration, activationLevel: ActivationLevel) {
    if (contentShown) {
      recentAttentionRequests.invalidate(serialNumber)
      if (serialNumber !in deviceClients && serialNumber !in devicesExcludedFromMirroring) {
        startMirroringIfConfirmed(serialNumber, handle, config, activationLevel)
      }
      if (activationLevel >= ActivationLevel.SELECT_TAB) {
        onPhysicalDeviceHeadsUp(serialNumber, activationLevel)
      }
    }
    else if (activationLevel >= ActivationLevel.SHOW_TOOL_WINDOW) {
      startMirroringIfConfirmed(serialNumber, handle, config, activationLevel)
      toolWindow.activate(activationLevel)
    }
  }

  private fun startMirroringIfConfirmed(serialNumber: String, handle: DeviceHandle, config: DeviceConfiguration,
                                        activationLevel: ActivationLevel) {
    // Reservable devices are assumed to be privacy protected.
    if (deviceMirroringSettings.confirmationDialogShown || handle.reservationAction != null) {
      val deviceClient = getOrCreateDeviceClient(serialNumber, handle, config)
      startMirroring(serialNumber, deviceClient, activationLevel)
    }
    else if (!mirroringConfirmationDialogShowing) { // Ignore a recursive call inside the dialog's event loop.
      mirroringConfirmationDialogShowing = true
      val title = "About to Start Mirroring of ${config.deviceName}"
      val dialogWrapper = MirroringConfirmationDialog(title).createWrapper(project).apply { show() }
      mirroringConfirmationDialogShowing = false
      when (dialogWrapper.exitCode) {
        MirroringConfirmationDialog.ACCEPT_EXIT_CODE -> {
          deviceMirroringSettings.confirmationDialogShown = true
          val deviceClient = getOrCreateDeviceClient(serialNumber, handle, config)
          startMirroring(serialNumber, deviceClient, activationLevel)
        }
        MirroringConfirmationDialog.REJECT_EXIT_CODE -> stopMirroring(serialNumber)
        else -> return
      }
    }
  }

  private fun startMirroring(serialNumber: String, deviceClient: DeviceClient, activationLevel: ActivationLevel) {
    devicesExcludedFromMirroring.remove(serialNumber)
    if (serialNumber in onlineDevices) {
      showLiveIndicator()
      if (contentShown) {
        updateMirroringHandlesFlow()
        deviceClient.establishAgentConnectionWithoutVideoStreamAsync() // Start the agent and connect to it proactively.
        val panel = DeviceToolWindowPanel(toolWindow.disposable, project, deviceClient)
        addPanel(panel)
        if (activationLevel >= ActivationLevel.SELECT_TAB) {
          selectContent(panel, requestFocus = activationLevel >= ActivationLevel.ACTIVATE_TAB)
        }
      }
      else if (activationLevel >= ActivationLevel.SHOW_TOOL_WINDOW) {
        recentAttentionRequests.put(serialNumber, activationLevel)
        toolWindow.activate(activationLevel)
      }
    }
  }

  private fun selectContent(panel: DeviceToolWindowPanel, requestFocus: Boolean) {
    val contentManager = toolWindow.contentManager
    val content = contentManager.getContent(panel) ?: return
    contentManager.setSelectedContent(content, requestFocus)
  }

  private fun updateMirroringHandlesFlow() {
    if (project.isDisposed) {
      return
    }
    val mirroringHandles = mutableMapOf<DeviceHandle, MirroringHandle>()
    for (device in devicesExcludedFromMirroring.values) {
      if (device.handle.reservationAction == null) {
        mirroringHandles[device.handle] = MirroringActivator(device)
      }
    }
    for (client in deviceClients.values) {
      if (client.deviceHandle.reservationAction == null) {
        mirroringHandles[client.deviceHandle] = MirroringDeactivator(client.deviceSerialNumber)
      }
    }
    project.service<MirroringManager>().mirroringHandles.value = mirroringHandles
  }

  @AnyThread
  private fun deviceConnected(serialNumber: String, device: ConnectedDevice) {
    val config = DeviceConfiguration(device.state.properties, useTitleAsName = isLocalEmulator(serialNumber))
    UIUtil.invokeLaterIfNeeded { // This is safe because this code doesn't touch PSI or VFS.
      deviceConnected(serialNumber, device.handle, config)
    }
  }

  private fun deviceConnected(serialNumber: String, deviceHandle: DeviceHandle, config: DeviceConfiguration) {
    if (serialNumber in onlineDevices && serialNumber !in deviceClients.keys) {
      if (deviceMirroringSettings.activateOnConnection || recentAttentionRequests.getIfPresent(serialNumber) != null) {
        val activationLevel = if (recentAttentionRequests.getIfPresent(serialNumber) != null) ActivationLevel.SELECT_TAB
                              else ActivationLevel.SHOW_TOOL_WINDOW
        activateMirroring(serialNumber, deviceHandle, config, activationLevel)
      }
      else {
        // The device is excluded from mirroring.
        val deviceDescription = devicesExcludedFromMirroring[serialNumber]
        if (deviceDescription == null) {
          devicesExcludedFromMirroring[serialNumber] = DeviceDescription(config.deviceName, serialNumber, deviceHandle, config)
          updateMirroringHandlesFlow()
        }
      }
    }
  }

  private fun getOrCreateDeviceClient(serialNumber: String, deviceHandle: DeviceHandle, config: DeviceConfiguration): DeviceClient {
    val disposable = physicalDeviceWatcher
    var deviceClient = deviceClients[serialNumber]
    if (deviceClient == null) {
      deviceClient = DeviceClient(disposable, serialNumber, deviceHandle, config, config.deviceProperties.primaryAbi.toString(), project)
      deviceClients[serialNumber] = deviceClient
      updateMirroringHandlesFlow()
    }
    return deviceClient
  }

  private suspend fun showDeviceActionPopup(anchorComponent: Component?, dataContext: DataContext) {
    val actionGroup = createDeviceActions()

    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
        null, actionGroup, dataContext, ActionSelectionAid.SPEEDSEARCH, true, null, -1, null,
        ActionPlaces.getActionGroupPopupPlace(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR))

    if (anchorComponent == null) {
      popup.showInFocusCenter()
    }
    else {
      popup.showUnderneathOf(anchorComponent)
    }
    // Clear initial selection.
    (popup as? ListPopupImpl)?.list?.clearSelection()
  }

  private suspend fun createDeviceActions(): DefaultActionGroup {
    return DefaultActionGroup().apply {
      val deviceDescriptions = devicesExcludedFromMirroring.values.toTypedArray().sortedBy { it.deviceName }
      if (deviceDescriptions.isNotEmpty()) {
        add(Separator("Connected Devices"))
        for (deviceDescription in deviceDescriptions) {
          add(StartDeviceMirroringAction(deviceDescription))
        }
        add(Separator.getInstance())
      }

      val remoteDevices = deviceProvisioner.reservedAndStartableDevices()
      if (remoteDevices.isNotEmpty()) {
        add(Separator("Remote Devices"))
        for (device in remoteDevices) {
          add(StartRemoteDeviceAction(device))
        }
        add(Separator.getInstance())
      }

      val avds = getStartableAvds().sortedBy { it.displayName }
      if (avds.isNotEmpty()) {
        add(Separator("Virtual Devices"))
        for (avd in avds) {
          add(StartAvdAction(avd, project))
        }
        add(Separator.getInstance())
      }

      add(ActionManager.getInstance().getAction(PairDevicesUsingWiFiAction.ID))
    }
  }

  private suspend fun getStartableAvds(): List<AvdInfo> {
    return withContext(Dispatchers.IO) {
      val runningAvdFolders = RunningEmulatorCatalog.getInstance().emulators.map { it.emulatorId.avdFolder }.toSet()
      val avdManager = AvdManagerConnection.getDefaultAvdManagerConnection()
      avdManager.getAvds(false).filter { it.dataFolderPath !in runningAvdFolders }
    }
  }

  private inner class MyDeviceHeadsUpListener : DeviceHeadsUpListener {

    override fun userInvolvementRequired(deviceSerialNumber: String, project: Project) {
      onDeviceHeadsUp(deviceSerialNumber, ActivationLevel.ACTIVATE_TAB, project)
    }

    override fun launchingApp(deviceSerialNumber: String, project: Project) {
      val activate = if (isNonMirrorableLocalEmulator(deviceSerialNumber)) emulatorSettings.activateOnAppLaunch
                     else deviceMirroringSettings.activateOnAppLaunch
      if (activate) {
        onDeviceHeadsUp(deviceSerialNumber, ActivationLevel.SELECT_TAB, project)
      }
    }

    override fun launchingTest(deviceSerialNumber: String, project: Project) {
      val activate = if (isNonMirrorableLocalEmulator(deviceSerialNumber)) emulatorSettings.activateOnTestLaunch
                     else deviceMirroringSettings.activateOnTestLaunch
      if (activate) {
        onDeviceHeadsUp(deviceSerialNumber, ActivationLevel.SELECT_TAB, project)
      }
    }

    private fun isNonMirrorableLocalEmulator(deviceSerialNumber: String): Boolean {
      return isLocalEmulator(deviceSerialNumber) &&
             deviceSerialNumber !in deviceClients && deviceSerialNumber !in devicesExcludedFromMirroring
    }
  }

  private inner class ToggleDeviceFrameAction : ToggleAction("Show Device Frame"), DumbAware {

    override fun update(event: AnActionEvent) {
      super.update(event)
      event.presentation.isEnabledAndVisible =
          findContent { (it.component as? EmulatorToolWindowPanel)?.emulator?.emulatorConfig?.skinFolder != null } != null
    }

    override fun isSelected(event: AnActionEvent): Boolean {
      return deviceFrameVisible
    }

    override fun setSelected(event: AnActionEvent, state: Boolean) {
      deviceFrameVisible = state
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
  }

  private inner class ToggleZoomToolbarAction : ToggleAction("Show Zoom Controls"), DumbAware {

    override fun isSelected(event: AnActionEvent): Boolean {
      return zoomToolbarIsVisible
    }

    override fun setSelected(event: AnActionEvent, state: Boolean) {
      zoomToolbarIsVisible = state
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
  }

  private inner class PhysicalDeviceWatcher(disposableParent: Disposable) : Disposable {
    private val coroutineScope: CoroutineScope

    init {
      Disposer.register(disposableParent, this)
      val executor = createBoundedApplicationPoolExecutor("EmulatorToolWindowManager.PhysicalDeviceWatcher", 1)
      coroutineScope = AndroidCoroutineScope(this, executor.asCoroutineDispatcher())
      coroutineScope.launch {
        deviceProvisioner.mirrorableDevicesBySerialNumber().collect { newOnlineDevices ->
          UIUtil.invokeLaterIfNeeded {
            onlineDevices = newOnlineDevices
            onlineDevicesChanged()
          }
        }
      }
    }

    private fun onlineDevicesChanged() {
      val removedExcluded = devicesExcludedFromMirroring.keys.retainAll(onlineDevices.keys)
      val removed = deviceClients.keys.minus(onlineDevices.keys)
      if (contentShown) {
        for (device in removed) {
          removePhysicalDevicePanel(device)
        }
      }
      else {
        deviceClients.keys.removeAll(removed)
      }
      if (removedExcluded || removed.isNotEmpty()) {
        updateMirroringHandlesFlow()
      }

      for ((serialNumber, device) in onlineDevices) {
        if (serialNumber !in deviceClients && serialNumber !in devicesExcludedFromMirroring) {
          coroutineScope.launch {
            deviceConnected(serialNumber, device)
          }
        }
      }

      if (!contentShown) {
        toolWindowScope.launch(Dispatchers.IO) {
          val embeddedEmulators = RunningEmulatorCatalog.getInstance().updateNow().await().filter { it.emulatorId.isEmbedded }
          withContext(Dispatchers.EDT) {
            if (deviceClients.isEmpty() && embeddedEmulators.isEmpty()) {
              hideLiveIndicator()
            }
          }
        }
      }
    }

    override fun dispose() {
      deviceClients.clear() // The clients have been disposed already.
      updateMirroringHandlesFlow()
      removeAllPhysicalDevicePanels()
    }
  }

  private inner class NewTabAction : DumbAwareAction("Add Device", "Show a new device", AllIcons.General.Add), DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
      val component = event.inputEvent?.component
      val actionComponent = if (component is ActionButtonComponent) component else event.findComponentForAction(this)
      val dataContext = event.dataContext

      toolWindowScope.launch {
        showDeviceActionPopup(actionComponent, dataContext)
      }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  }

  private inner class StartDeviceMirroringAction(
    private val device: DeviceDescription,
  ) : DumbAwareAction(device.deviceName, null, device.config.deviceProperties.icon) {

    override fun actionPerformed(event: AnActionEvent) {
      activateMirroring(device)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  }

  private inner class StartRemoteDeviceAction(
    private val device: DeviceHandle,
  ) : DumbAwareAction(device.sourceTemplate?.properties?.composeDeviceName(), null, device.sourceTemplate?.properties?.icon) {

    override fun actionPerformed(event: AnActionEvent) {
      device.scope.launch { device.activationAction?.activate() }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  }

  private inner class StartAvdAction(
    private val avd: AvdInfo,
    private val project: Project,
  ) : DumbAwareAction(avd.displayName, null, avd.icon) {

    override fun actionPerformed(event: AnActionEvent) {
      toolWindowScope.launch(Dispatchers.IO) {
        val avdManager = AvdManagerConnection.getDefaultAvdManagerConnection()
        try {
          avdManager.startAvd(project, avd, RequestType.DIRECT_RUNNING_DEVICES).asDeferred().await()
        }
        catch (e: Exception) {
          val message = e.message?.let { if (it.contains(avd.displayName)) it else "Unable to launch ${avd.displayName} - $it"} ?:
              "Unable to launch ${avd.displayName}"
          withContext(Dispatchers.EDT) {
            showErrorDialog(toolWindow.component, message)
          }
        }
      }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  }

  private inner class MirroringActivator(private val device: DeviceDescription) : MirroringHandle {

    override val mirroringState: MirroringState
      get() = MirroringState.INACTIVE

    override fun toggleMirroring() {
      activateMirroring(device)
    }

    override fun toString(): String {
      return "MirroringActivator for ${device.serialNumber}"
    }
  }

  private inner class MirroringDeactivator(private val serialNumber: String) : MirroringHandle {

    override val mirroringState: MirroringState
      get() = MirroringState.ACTIVE

    override fun toggleMirroring() {
      deactivateMirroring(serialNumber)
    }

    override fun toString(): String {
      return "MirroringDeactivator for $serialNumber"
    }
  }

  private class DeviceDescription(val deviceName: String, val serialNumber: String, val handle: DeviceHandle,
                                  val config: DeviceConfiguration)

  private enum class ActivationLevel {
    /** Create tab, but don't select it and don't show the tool window if hidden. */
    CREATE_TAB,
    /** Create tab and show the tool window if hidden. */
    SHOW_TOOL_WINDOW,
    /** Create tab, show the tool window if hidden and select the new tab. */
    SELECT_TAB,
    /** Create tab, show the tool window if hidden, select the new tab and focus on it. */
    ACTIVATE_TAB,
  }
}

private class ConnectedDevice(val handle: DeviceHandle, val state: DeviceState.Connected)

private fun DeviceProvisioner.mirrorableDevicesBySerialNumber(): Flow<Map<String, ConnectedDevice>> {
  return connectedDevices().map { connectedDevices ->
    connectedDevices.filter { it.state.isMirrorable() }.associateBy { it.state.serialNumber }
  }
}

private fun DeviceProvisioner.connectedDevices(): Flow<List<ConnectedDevice>> {
  return mapStateNotNull { handle, state -> (state as? DeviceState.Connected)?.let { ConnectedDevice(handle, it) } }
}

private fun DeviceProvisioner.reservedAndStartableDevices(): List<DeviceHandle> {
  return devices.value.filter {
    it.state.reservation?.state == ReservationState.ACTIVE && it.activationAction?.presentation?.value?.enabled == true
  }
}

private suspend fun DeviceState.Connected.isMirrorable(): Boolean {
  if (!isOnline()) {
    return false
  }

  val deviceSerialNumber = serialNumber
  when {
    isLocalEmulator(deviceSerialNumber) -> { // Local virtual device.
      if (!StudioFlags.DEVICE_MIRRORING_STANDALONE_EMULATORS.get()) {
        return false
      }
      val emulators = RunningEmulatorCatalog.getInstance().updateNow().suspendingGet()
      val emulator = emulators.find { "emulator-${it.emulatorId.serialPort}" == deviceSerialNumber }
      if (emulator == null || emulator.emulatorId.isEmbedded) {
        return false
      }
    }
    properties.isVirtual == true -> { // Remote virtual device.
      if (!StudioFlags.DEVICE_MIRRORING_REMOTE_EMULATORS.get()) {
        return false
      }
    }
  }

  val apiLevel = properties.androidVersion?.apiLevel ?: SdkVersionInfo.HIGHEST_KNOWN_STABLE_API
  // Mirroring is supported for API >= 26. Wear OS devices with API < 30 don't support VP8/VP9 video encoders.
  return apiLevel >= 26 && (properties.deviceType != DeviceType.WEAR || apiLevel >= 30) && properties.primaryAbi != null
}

private val DeviceState.Connected.serialNumber: String
    get() = connectedDevice.serialNumber

private fun ContentManager.addSelectedPanelDataProvider() {
  addDataProvider { dataId -> (selectedContent?.component as? DataProvider)?.getData(dataId) }
}

private val ContentManager.placeholderContent: Content?
  get() {
    val content = if (contentCount == 1) getContent(0) else return null
    return if (ID_KEY.get(content) == null) content else null
  }

private fun Content.select() {
  manager?.setSelectedContent(this)
}

private fun Content.removeAndDispose() {
  FlightRecorder.log { "${TraceUtils.getSimpleId(this)}.removeAndDispose()\n${TraceUtils.getCurrentStack()}" }
  manager?.removeContent(this, true)
}

private fun isLocalEmulator(deviceSerialNumber: String) =
    deviceSerialNumber.startsWith("emulator-")

private fun isEmbeddedEmulator(commandLine: GeneralCommandLine) =
    commandLine.parametersList.parameters.contains("-qt-hide-window")

private fun shortenTitleText(title: String): String =
    StringUtil.shortenTextWithEllipsis(title, 25, 6)
