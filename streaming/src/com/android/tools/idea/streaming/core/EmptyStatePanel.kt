/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.SdkConstants
import com.android.annotations.concurrency.AnyThread
import com.android.repository.Revision
import com.android.repository.api.RepoManager.RepoLoadedListener
import com.android.repository.impl.meta.RepositoryPackages
import com.android.tools.adtui.stdui.StandardColors
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.progress.StudioLoggerProgressIndicator
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.streaming.DeviceMirroringSettings
import com.android.tools.idea.streaming.DeviceMirroringSettingsListener
import com.android.tools.idea.streaming.EmulatorSettings
import com.android.tools.idea.streaming.EmulatorSettingsListener
import com.android.tools.idea.streaming.device.settings.DeviceMirroringSettingsPage
import com.android.tools.idea.streaming.emulator.settings.EmulatorSettingsPage
import com.intellij.collaboration.async.disposingScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.htmlComponent
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.AndroidIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

private const val MIN_REQUIRED_EMULATOR_VERSION = "31.3.10"

// As recommended at https://jetbrains.github.io/ui/principles/empty_state/#21.
private const val TOP_MARGIN = 0.45
private const val SIDE_MARGIN = 0.15

/**
 * The panel that is shown in the Running Devices tool window when there are no running
 * embedded emulators and no mirrored devices.
 */
internal class EmptyStatePanel(project: Project, disposableParent: Disposable): JBPanel<EmptyStatePanel>(GridBagLayout()), Disposable {

  private var emulatorLaunchesInToolWindow: Boolean
  private var deviceMirroringEnabled: Boolean
  private var emulatorVersionIsSufficient: Boolean
  private var hyperlinkListener: HyperlinkListener

  init {
    Disposer.register(disposableParent, this)

    isOpaque = true
    background = StandardColors.BACKGROUND_COLOR
    border = JBUI.Borders.empty()
    // Allow the panel to receive focus so that the framework considers the tool window active (b/157181475).
    isFocusable = true

    emulatorLaunchesInToolWindow = EmulatorSettings.getInstance().launchInToolWindow
    deviceMirroringEnabled = DeviceMirroringSettings.getInstance().deviceMirroringEnabled || StudioFlags.DIRECT_ACCESS.get()
    emulatorVersionIsSufficient = true

    hyperlinkListener = HyperlinkListener { event ->
      if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
        when (event.description) {
          "DeviceManager" -> {
            // Action id is from com.android.tools.idea.devicemanager.DeviceManagerAction.
            val action = ActionManager.getInstance().getAction("Android.DeviceManager")
            ActionUtil.invokeAction(action, SimpleDataContext.getProjectContext(project), ActionPlaces.UNKNOWN, null, null)
          }
          "CheckForUpdate" -> {
            val action = ActionManager.getInstance().getAction("CheckForUpdate")
            ActionUtil.invokeAction(action, SimpleDataContext.getProjectContext(project), ActionPlaces.UNKNOWN, null, null)
          }
          "EmulatorSettings" -> {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, EmulatorSettingsPage::class.java)
          }
          "DeviceMirroringSettings" -> {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DeviceMirroringSettingsPage::class.java)
          }
        }
      }
    }

    val messageBusConnection = project.messageBus.connect(this)
    messageBusConnection.subscribe(EmulatorSettingsListener.TOPIC, EmulatorSettingsListener { settings ->
      emulatorLaunchesInToolWindow = settings.launchInToolWindow
      updateContent()
    })
    messageBusConnection.subscribe(DeviceMirroringSettingsListener.TOPIC, DeviceMirroringSettingsListener { settings ->
      deviceMirroringEnabled = settings.deviceMirroringEnabled
      updateContent()
    })

    val progress = StudioLoggerProgressIndicator(EmptyStatePanel::class.java)
    @Suppress("UnstableApiUsage")
    val job = disposingScope(Dispatchers.IO).launch {
      val sdkHandler = AndroidSdks.getInstance().tryToChooseSdkHandler()
      val sdkManager = sdkHandler.getSdkManager(progress)
      val listener = RepoLoadedListener { packages -> localPackagesUpdated(packages) }
      try {
        Disposer.register(this@EmptyStatePanel) { sdkManager.removeLocalChangeListener(listener) }
        sdkManager.addLocalChangeListener(listener)
        localPackagesUpdated(sdkManager.packages)
      }
      catch (_: IncorrectOperationException) {
        // Disposed already.
      }
    }
    Disposer.register(this) {
      progress.cancel()
      job.cancel()
      if (ApplicationManager.getApplication().isUnitTestMode) {
        runBlocking { job.join() } // Wait for asynchronous activity to finish in tests.
      }
    }

    updateContent()
  }

  @AnyThread
  private fun localPackagesUpdated(packages: RepositoryPackages) {
    val emulatorPackage = packages.localPackages[SdkConstants.FD_EMULATOR] ?: return
    UIUtil.invokeLaterIfNeeded { // This is safe because this code doesn't touch PSI or VFS.
      val sufficient = emulatorPackage.version >= Revision.parseRevision(MIN_REQUIRED_EMULATOR_VERSION)
      if (emulatorVersionIsSufficient != sufficient) {
        emulatorVersionIsSufficient = sufficient
        updateContent()
      }
    }
  }

  private fun createContent() {
    val linkColorString = (JBUI.CurrentTheme.Link.Foreground.ENABLED.rgb and 0xFFFFFF).toString(16)
    val html = when {
      emulatorLaunchesInToolWindow && emulatorVersionIsSufficient && deviceMirroringEnabled ->
        """
        <center>
        <p>To launch a&nbsp;virtual device, use
        the&nbsp;<font color = $linkColorString><a href='DeviceManager'>Device&nbsp;Manager</a></font>
        or run your app while targeting a&nbsp;virtual device.</p>
        <p/>
        <p>To mirror a&nbsp;physical device, connect it via USB cable or over WiFi.</p>
        </center>
        """.trimIndent()
      emulatorLaunchesInToolWindow && emulatorVersionIsSufficient && !deviceMirroringEnabled ->
        """
        <center>
        <p>To launch a&nbsp;virtual device, use
        the&nbsp;<font color = $linkColorString><a href='DeviceManager'>Device&nbsp;Manager</a></font>
        or run your app while targeting a&nbsp;virtual device.</p>
        <p/>
        <p>To mirror physical devices, select the&nbsp;<i>Enable mirroring of physical Android devices</i> option
        in&nbsp;the&nbsp;<font color = $linkColorString><a href='DeviceMirroringSettings'>Device&nbsp;Mirroring&nbsp;settings</a></font>.
        </p>
        </center>
        """.trimIndent()
      emulatorLaunchesInToolWindow && !emulatorVersionIsSufficient && deviceMirroringEnabled ->
        """
        <center>
        <p>To launch virtual devices in this window, install Android Emulator $MIN_REQUIRED_EMULATOR_VERSION or higher.
        Please <font color = $linkColorString><a href='CheckForUpdate'>check for&nbsp;updates</a></font> and install
        the&nbsp;latest version of the&nbsp;Android&nbsp;Emulator.</p>
        <p/>
        <p>To mirror a&nbsp;physical device, connect it via USB cable or over WiFi.</p>
        </center>
        """.trimIndent()
      emulatorLaunchesInToolWindow && !emulatorVersionIsSufficient && !deviceMirroringEnabled ->
        """
        <center>
        <p>To launch virtual devices in this window, install Android Emulator $MIN_REQUIRED_EMULATOR_VERSION or higher.
        Please <font color = $linkColorString><a href='CheckForUpdate'>check for&nbsp;updates</a></font> and install
        the&nbsp;latest version of the&nbsp;Android&nbsp;Emulator.</p>
        <p/>
        <p>To mirror physical devices, select the&nbsp;<i>Enable mirroring of physical Android devices</i> option
        in&nbsp;the&nbsp;<font color = $linkColorString><a href='DeviceMirroringSettings'>Device&nbsp;Mirroring&nbsp;settings</a></font>.
        </p>
        </center>
        """.trimIndent()
      deviceMirroringEnabled ->
        """
        <center>
        <p>To launch virtual devices in this window, select the&nbsp;<i>Launch in&nbsp;a&nbsp;tool window</i> option
        in&nbsp;the&nbsp;<font color = $linkColorString><a href='EmulatorSettings'>Emulator&nbsp;settings</a></font>.</p>
        <p/>
        <p>To mirror a&nbsp;physical device, connect it via USB cable or over WiFi.</p>
        </center>
        """.trimIndent()
      else ->
        """
        <center>
        <p>To launch virtual devices in this window, select the&nbsp;<i>Launch in&nbsp;a&nbsp;tool window</i> option
        in&nbsp;the&nbsp;<font color = $linkColorString><a href='EmulatorSettings'>Emulator&nbsp;settings</a></font>.</p>
        <p/>
        <p>To mirror physical devices, select the&nbsp;<i>Enable mirroring of physical Android devices</i> option
        in&nbsp;the&nbsp;<font color = $linkColorString><a href='DeviceMirroringSettings'>Device&nbsp;Mirroring&nbsp;settings</a></font>.
        </p>
        </center>
        """.trimIndent()
    }

    val text = htmlComponent(text = html,
                             lineWrap = true,
                             font = JBUI.Fonts.label(13f),
                             foreground = StandardColors.PLACEHOLDER_TEXT_COLOR,
                             hyperlinkListener = hyperlinkListener).apply {
      isOpaque = false
      isFocusable = false
      border = JBUI.Borders.empty()
    }

    val c = GridBagConstraints().apply {
      fill = GridBagConstraints.BOTH
      gridx = 1
      gridy = 0
      weightx = 1 - SIDE_MARGIN * 2
      weighty = TOP_MARGIN
    }
    val icon = JBLabel(AndroidIcons.Explorer.DevicesLineup)
    icon.horizontalAlignment = SwingConstants.CENTER
    icon.verticalAlignment = SwingConstants.BOTTOM
    add(icon, c)

    c.apply {
      gridx = 0
      gridy = 1
      weightx = SIDE_MARGIN
      weighty = 1 - TOP_MARGIN
    }
    add(createSpacer(), c)

    c.apply {
      gridx = 2
    }
    add(createSpacer(), c)

    c.apply {
      gridx = 1
    }
    add(text, c)
  }

  private fun createSpacer(): JBPanel<*> {
    return JBPanel<JBPanel<*>>()
      .withBorder(JBUI.Borders.empty())
      .withMinimumWidth(0)
      .withMinimumHeight(0)
      .withPreferredSize(0, 0)
      .andTransparent()
  }

  private fun updateContent() {
    removeAll()
    createContent()
    validate()
  }

  override fun updateUI() {
    super.updateUI()
    updateContent()
  }

  override fun dispose() {
  }
}