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
package com.android.tools.idea.wearwhs.view

import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.wearwhs.EventTrigger
import com.android.tools.idea.wearwhs.WHS_CAPABILITIES
import com.android.tools.idea.wearwhs.WhsCapability
import com.android.tools.idea.wearwhs.WhsDataType
import com.android.tools.idea.wearwhs.communication.CapabilityState
import com.android.tools.idea.wearwhs.communication.ConnectionLostException
import com.android.tools.idea.wearwhs.communication.WearHealthServicesDeviceManager
import com.android.tools.idea.wearwhs.logger.WearHealthServicesEventLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import kotlin.time.Duration.Companion.milliseconds

/**
 * Default polling interval for updating the state manager with values from [WearHealthServicesDeviceManager].
 */
private const val POLLING_INTERVAL_MILLISECONDS: Long = 5000

/**
 * Maximum wait time for a command to get executed.
 */
private const val MAX_WAIT_TIME_FOR_COMMANDS_MILLISECONDS: Long = 5000

internal class WearHealthServicesToolWindowStateManagerImpl(
  private val deviceManager: WearHealthServicesDeviceManager,
  private val eventLogger: WearHealthServicesEventLogger = WearHealthServicesEventLogger(),
  @VisibleForTesting private val pollingIntervalMillis: Long = POLLING_INTERVAL_MILLISECONDS)
  : WearHealthServicesToolWindowStateManager, Disposable {

  override var runPeriodicUpdates = false

  private val logger: Logger = Logger.getInstance(WearHealthServicesToolWindowStateManagerImpl::class.java)
  override val preset: MutableStateFlow<Preset> = MutableStateFlow(Preset.ALL)

  override val capabilitiesList = deviceManager.getCapabilities()

  private val capabilityToState = capabilitiesList.associateWith {
    MutableStateFlow(CapabilityUIState())
  }

  private val _status = MutableStateFlow<WhsStateManagerStatus>(WhsStateManagerStatus.Initializing)
  override val status = _status

  private val _ongoingExercise = MutableStateFlow(false)
  override val ongoingExercise = _ongoingExercise

  private val workerScope = AndroidCoroutineScope(this)

  override var serialNumber: String? = null
    set(value) {
      // Only accept non-null values to avoid tool window unbinding completely
      value?.let {
        eventLogger.logBindEmulator()
        deviceManager.setSerialNumber(it)
        field = value
      }
    }

  init {
    workerScope.launch {
      while (true) {
        updateState()
        delay(pollingIntervalMillis.milliseconds)
      }
    }
    workerScope.launch {
      preset.collect {
        when (it) {
          Preset.STANDARD -> for (capability in capabilityToState.keys) {
            setCapabilityEnabled(capability, capability.isStandardCapability)
          }

          Preset.ALL -> for (capability in capabilityToState.keys) {
            setCapabilityEnabled(capability, true)
          }

          Preset.CUSTOM -> {}
        }
        // First time this runs, make status idle
        if (_status.value == WhsStateManagerStatus.Initializing) {
          _status.value = WhsStateManagerStatus.Idle
        }
      }
    }
  }

  private suspend fun updateState() {
    if (!runPeriodicUpdates) {
      // Don't update the state if the tool window is hidden
      return
    }
    runWithStatus(WhsStateManagerStatus.Busy) {
      val activeExerciseResult =
        deviceManager.loadActiveExercise().map { activeExercise ->
          _ongoingExercise.value = activeExercise
        }
      if (activeExerciseResult.isFailure) {
        // Return early on failure
        activeExerciseResult
      }
      else {
        deviceManager.loadCurrentCapabilityStates().map { states ->
          val currentStates = states.toMutableMap()
          val allCapabilities = deviceManager.getCapabilities().map { it.dataType }.toSet()
          val missingCapabilities = allCapabilities.minus(currentStates.keys.toSet())
          missingCapabilities.forEach {
            currentStates[it] = CapabilityState(true, null)
          }
          currentStates.forEach { (dataType, state) ->
            // Update values only if they're synced through and got changed in the background
            capabilityToState[dataType.toCapability()]?.let { stateFlow ->
              if (stateFlow.value.synced) {
                stateFlow.value =
                  stateFlow.value.copy(
                    capabilityState = state,
                  )
              }
            }
          }
        }
      }
    }
  }

  /**
   * Waits until state manager is idle, then executes the given block after setting the state manager
   * status to [status]. It restores the status to [WhsStateManagerStatus.Idle] after the block is
   * executed, or [WhsStateManagerStatus.ConnectionLost] if the block throws a [ConnectionLostException].
   */
  private suspend fun runWithStatus(status: WhsStateManagerStatus, block: suspend () -> Result<Unit>) {
    try {
      withTimeout(MAX_WAIT_TIME_FOR_COMMANDS_MILLISECONDS) {
        _status.takeWhile {
          !it.idle
        }.collect {}
        _status.value = status
        block().onSuccess {
          _status.value = WhsStateManagerStatus.Idle
        }.onFailure {
          logger.warn(it)
          _status.value = WhsStateManagerStatus.ConnectionLost
        }
      }
    }
    catch (exception: TimeoutCancellationException) {
      _status.value = WhsStateManagerStatus.Timeout
    }
  }

  override suspend fun isWhsVersionSupported(): Boolean =
    deviceManager.isWhsVersionSupported().onFailure {
      logger.warn(it)
    }.getOrDefault(false)

  override suspend fun triggerEvent(eventTrigger: EventTrigger) =
    runWithStatus(WhsStateManagerStatus.Syncing) {
      deviceManager.triggerEvent(eventTrigger)
    }

  override fun getState(capability: WhsCapability): StateFlow<CapabilityUIState> =
    capabilityToState[capability]?.asStateFlow() ?: throw IllegalArgumentException()

  override suspend fun setCapabilityEnabled(capability: WhsCapability, enabled: Boolean) {
    val stateFlow = capabilityToState[capability] ?: throw IllegalArgumentException()
    if (enabled == stateFlow.value.capabilityState.enabled) {
      return
    }
    val newState = stateFlow.value.copy(capabilityState = CapabilityState(enabled, stateFlow.value.capabilityState.overrideValue),
                                        synced = false)
    stateFlow.value = newState
  }

  override suspend fun setOverrideValue(capability: WhsCapability, value: Float?) {
    val stateFlow = capabilityToState[capability] ?: throw IllegalArgumentException()
    if (value == stateFlow.value.capabilityState.overrideValue) {
      return
    }
    val newState = stateFlow.value.copy(capabilityState = CapabilityState(stateFlow.value.capabilityState.enabled, value), synced = false)
    stateFlow.value = newState
  }

  override suspend fun applyChanges() =
    runWithStatus(WhsStateManagerStatus.Syncing) {
      val capabilityUpdates = capabilityToState.entries.associate { it.key.dataType to it.value.value.capabilityState.enabled }
      val overrideUpdates = capabilityToState.entries.associate { it.key.dataType to it.value.value.capabilityState.overrideValue }
      // Return early if any of the updates fail
      deviceManager.setCapabilities(capabilityUpdates).onFailure {
        eventLogger.logApplyChangesFailure()
        return@runWithStatus Result.failure(it)
      }
      deviceManager.overrideValues(overrideUpdates).onFailure {
        eventLogger.logApplyChangesFailure()
        return@runWithStatus Result.failure(it)
      }
      capabilityToState.entries.forEach {
        val stateFlow = it.value
        val state = stateFlow.value
        stateFlow.value = state.copy(synced = true)
      }
      eventLogger.logApplyChangesSuccess()
      Result.success(Unit)
    }

  override suspend fun reset() =
    runWithStatus(WhsStateManagerStatus.Syncing) {
      preset.value = Preset.ALL
      for (entry in capabilityToState.keys) {
        setOverrideValue(entry, null)
      }
      deviceManager.clearContentProvider()
    }

  override fun dispose() {}

  @TestOnly
  internal suspend fun forceUpdateState() {
    updateState()
  }
}

private fun WhsDataType.toCapability(): WhsCapability {
  return WHS_CAPABILITIES.single {
    it.dataType == this
  }
}
