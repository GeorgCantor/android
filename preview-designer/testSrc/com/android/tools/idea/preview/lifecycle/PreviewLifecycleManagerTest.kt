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
package com.android.tools.idea.preview.lifecycle

import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private enum class ActiveState {
  INITIALIZED,
  RESUMED,
  DEACTIVATED,
  FULLY_DEACTIVATED
}

class PreviewLifecycleManagerTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @Test
  fun testExecutesOnlyIfActive() = runBlocking {
    val manager = PreviewLifecycleManager(projectRule.project, this@runBlocking, {}, {}, {}, {})

    assertNull(manager.executeIfActive { 1 })

    manager.activate()

    assertEquals(1, manager.executeIfActive { 1 })

    manager.deactivate()

    assertNull(manager.executeIfActive { 1 })
  }

  @Test
  fun testActivationLifecycle() = runBlocking {
    var state = ActiveState.DEACTIVATED

    var delayedCallback: () -> Unit = {}

    val delayedExecutor = { ignore: Disposable, callback: () -> Unit -> delayedCallback = callback }

    val manager =
      PreviewLifecycleManager.createForTest(
        this@runBlocking,
        { state = ActiveState.INITIALIZED },
        { state = ActiveState.RESUMED },
        { state = ActiveState.DEACTIVATED },
        { state = ActiveState.FULLY_DEACTIVATED },
        delayedExecutor,
      )

    manager.activate()
    assertEquals(ActiveState.INITIALIZED, state)

    manager.deactivate()
    assertEquals(ActiveState.DEACTIVATED, state)

    manager.activate()
    assertEquals(ActiveState.RESUMED, state)

    // Resumed before the time the delayed callback was scheduled
    delayedCallback()
    assertEquals(ActiveState.RESUMED, state)

    manager.deactivate()
    assertEquals(ActiveState.DEACTIVATED, state)

    delayedCallback()
    assertEquals(ActiveState.FULLY_DEACTIVATED, state)

    manager.activate()
    assertEquals(ActiveState.RESUMED, state)

    manager.deactivate()
  }

  @Test
  fun testExecutionCancelledIfScopeIsCancelled() = runBlocking {
    val number = AtomicInteger(0)

    val job = launch {
      val manager = PreviewLifecycleManager(projectRule.project, this@launch, {}, {}, {}, {})

      manager.activate()

      manager.executeIfActive {
        runBlocking {
          delay(1000)
          number.set(1)
        }
      }
    }

    job.cancel()
    job.join()

    assertEquals(0, number.get())
  }

  @Test
  fun testLifecycleAutoDispose() {
    var manager: PreviewLifecycleManager?
    var disposed = false
    val delayedExecutor = { disposable: Disposable, _: () -> Unit ->
      Disposer.register(disposable) { disposed = true }
    }

    // Run within a new scope and simulate an activation/deactivation cycle. This will schedule a
    // delayed deactivation.
    runBlocking {
      manager =
        PreviewLifecycleManager.createForTest(this@runBlocking, scheduleDelayed = delayedExecutor)
      manager!!.activate()
      manager!!.deactivate()
      assertFalse("The delayed deactivation disposed before the scope finished", disposed)
    }

    assertTrue("Delayed deactivation should have been disposed when the scope finished", disposed)
  }

  @Test
  fun testDelayedDeactivationTimerIsRestarted() = runBlocking {
    val lruActionQueue = DelayedLruActionQueue(3, Duration.ofSeconds(3))
    var delayedDeactivationDone = false
    val lifecycleManager =
      PreviewLifecycleManager.createForTest(
        this,
        {},
        {},
        {},
        { delayedDeactivationDone = true },
        lruActionQueue::addDelayedAction,
      )
    repeat(4) {
      lifecycleManager.activate()
      lifecycleManager.deactivate()
      delay(2.seconds)
    }
    assertFalse(delayedDeactivationDone)
  }
}
