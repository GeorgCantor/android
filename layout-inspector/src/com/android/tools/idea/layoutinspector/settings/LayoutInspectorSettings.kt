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
package com.android.tools.idea.layoutinspector.settings

import com.android.tools.idea.flags.StudioFlags
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "LayoutInspectorSettings", storages = [Storage("layoutInspectorSettings.xml")])
class LayoutInspectorSettings : PersistentStateComponent<LayoutInspectorSettings> {

  companion object {
    @JvmStatic
    fun getInstance(): LayoutInspectorSettings {
      return ApplicationManager.getApplication().getService(LayoutInspectorSettings::class.java)
    }
  }

  // TODO Replace with a regular variable once the flags are removed.
  private val autoConnectSetting =
    FlagControlledSetting(true) {
      StudioFlags.DYNAMIC_LAYOUT_INSPECTOR_AUTO_CONNECT_TO_FOREGROUND_PROCESS_ENABLED.get()
    }

  private val embeddedLayoutInspectorSetting =
    FlagControlledSetting(ApplicationManager.getApplication().isEAP) {
      StudioFlags.DYNAMIC_LAYOUT_INSPECTOR_IN_RUNNING_DEVICES_ENABLED.get()
    }

  // Property needs to have public setters and getters in order to be persisted.
  var autoConnectEnabled: Boolean
    get() = autoConnectSetting.get()
    set(value) = autoConnectSetting.set(value)

  // Property needs to have public setters and getters in order to be persisted.
  var embeddedLayoutInspectorEnabled: Boolean
    get() = embeddedLayoutInspectorSetting.get()
    set(value) = embeddedLayoutInspectorSetting.set(value)

  override fun getState() = this

  override fun loadState(state: LayoutInspectorSettings) = XmlSerializerUtil.copyBean(state, this)
}

/**
 * A setting that is also controlled by the state of a flag. The setting is enabled only if it is
 * both enabled in settings and in the flag.
 */
class FlagControlledSetting(val defaultValue: Boolean, val getFlagValue: () -> Boolean) {
  private var isEnabled = defaultValue

  fun set(value: Boolean) {
    isEnabled = value
  }

  fun get() = getFlagValue() && isEnabled
}
