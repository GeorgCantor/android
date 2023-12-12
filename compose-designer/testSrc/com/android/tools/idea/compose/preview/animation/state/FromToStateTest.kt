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
package com.android.tools.idea.compose.preview.animation.state

import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.compose.preview.animation.AnimationCard
import com.android.tools.idea.compose.preview.animation.NoopAnimationTracker
import com.android.tools.idea.compose.preview.animation.TestUtils
import com.android.tools.idea.compose.preview.animation.TestUtils.assertBigger
import com.android.tools.idea.compose.preview.animation.TestUtils.findComboBox
import com.android.tools.idea.compose.preview.animation.TestUtils.findToolbar
import com.android.tools.idea.compose.preview.animation.timeline.ElementState
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.application.ApplicationManager
import java.awt.Dimension
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

class FromToStateTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val minimumSize = Dimension(10, 10)

  @Test
  fun createCard() {
    var callbacks = 0
    val state =
      FromToState(NoopAnimationTracker) { callbacks++ }
        .apply {
          updateStates(setOf("One", "Two", "Three"))
          setStartState("One")
          callbackEnabled = true
        }
    val card =
      AnimationCard(
          TestUtils.testPreviewState(),
          Mockito.mock(DesignSurface::class.java),
          ElementState("Title"),
          state.extraActions,
          NoopAnimationTracker
        )
        .apply { size = Dimension(300, 300) }

    ApplicationManager.getApplication().invokeAndWait {
      val ui =
        FakeUi(card).apply {
          updateToolbars()
          layoutAndDispatchEvents()
        }

      val toolbar = card.findToolbar("AnimationCard")
      assertEquals(5, toolbar.components.size)
      // All components are visible
      toolbar.components.forEach { assertBigger(minimumSize, it.size) }
      // Default state.
      assertEquals("One", (toolbar.components[2] as JPanel).findComboBox().text)
      assertEquals("Two", (toolbar.components[4] as JPanel).findComboBox().text)
      assertEquals("One", state.getState(0))
      assertEquals("Two", state.getState(1))
      val hash = state.stateHashCode()
      // Swap state.
      ui.clickOn(toolbar.components[1])
      // State hashCode has changed.
      assertNotEquals(hash, state.stateHashCode())
      // The states swapped.
      assertEquals(1, callbacks)
      assertEquals("Two", (toolbar.components[2] as JPanel).findComboBox().text)
      assertEquals("One", (toolbar.components[4] as JPanel).findComboBox().text)
      assertEquals("Two", state.getState(0))
      assertEquals("One", state.getState(1))
      // Update states.
      state.updateStates(setOf("Four", "Five", "Six"))
      state.setStartState("Four")
      ui.updateToolbars()
      assertEquals("Four", (toolbar.components[2] as JPanel).findComboBox().text)
      assertEquals("Five", (toolbar.components[4] as JPanel).findComboBox().text)
      assertEquals("Four", state.getState(0))
      assertEquals("Five", state.getState(1))
      // Swap state.
      ui.clickOn(toolbar.components[1])
      assertEquals("Five", (toolbar.components[2] as JPanel).findComboBox().text)
      assertEquals("Four", (toolbar.components[4] as JPanel).findComboBox().text)
      assertEquals("Five", state.getState(0))
      assertEquals("Four", state.getState(1))
      // State doesn't exist
      assertNull(state.getState(2))
    }
  }
}
