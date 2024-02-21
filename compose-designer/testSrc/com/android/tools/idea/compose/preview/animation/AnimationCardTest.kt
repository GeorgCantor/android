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
package com.android.tools.idea.compose.preview.animation

import com.android.testutils.delayUntilCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.compose.preview.animation.TestUtils.findExpandButton
import com.android.tools.idea.compose.preview.animation.TestUtils.findToolbar
import com.android.tools.idea.compose.preview.animation.timeline.ElementState
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.android.tools.idea.testing.AndroidProjectRule
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

class AnimationCardTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private val minimumSize = Dimension(10, 10)

  @Test
  fun `create animation card`(): Unit = runBlocking {
    val card =
      AnimationCard(
          TestUtils.testPreviewState(),
          Mockito.mock(DesignSurface::class.java),
          MutableStateFlow(ElementState("Title")),
          emptyList(),
          NoopComposeAnimationTracker,
        )
        .apply { setDuration(111) }
    card.setSize(300, 300)

    val ui =
      withContext(uiThread) {
        FakeUi(card).apply {
          updateToolbars()
          layout()
          layoutAndDispatchEvents()
        }
      }
    var stateChanges = -1
    val job = launch { card.state.collect { stateChanges++ } }

    // collector above will collect once even without any user action
    delayUntilCondition(200) { stateChanges == 0 }

    withContext(uiThread) {
      // Expand/collapse button.
      card.findExpandButton().also {
        // Button is here and visible.
        assertTrue(it.isVisible)
        TestUtils.assertBigger(Dimension(10, 10), it.size)
        ui.clickOn(it)
        ui.updateToolbars()
        ui.layoutAndDispatchEvents()
        // Expand/collapse button clicked
        assertEquals(1, stateChanges)
      }
      // Transition name label.
      (card.components[0] as JComponent).components[1].also {
        assertTrue(it.isVisible)
        TestUtils.assertBigger(minimumSize, it.size)
      }
      // Transition duration label.
      (card.components[0] as JComponent).components[2].also {
        assertTrue(it.isVisible)
        TestUtils.assertBigger(minimumSize, it.size)
      }

      // Freeze button.
      run {
        var freezeButton = findFreezeButton(card)
        // Button is here and visible.
        assertTrue(freezeButton.isVisible)
        assertTrue(freezeButton.isEnabled)
        TestUtils.assertBigger(minimumSize, freezeButton.size)

        // Freeze and unfreeze
        ui.clickOn(freezeButton)
        ui.updateToolbars()
        // Freeze button clicked
        assertEquals(2, stateChanges)
        freezeButton = findFreezeButton(card)
        ui.clickOn(freezeButton)
        // Freeze button clicked
        assertEquals(3, stateChanges)
      }
      // Double click to open in new tab. Use label position just to make sure we are not clicking
      // on any button.
      val label = (card.components[0] as JComponent).components[1]
      var openInTabActions = 0
      card.addOpenInTabListener { openInTabActions++ }
      ui.mouse.doubleClick(label.x + 5, label.y + 5)
      assertEquals(1, openInTabActions)
      assertNotNull(ui)
      job.cancel()
    }
  }

  @Test
  fun `create animation card if coordination is not available`(): Unit =
    runBlocking(uiThread) {
      val card =
        AnimationCard(
            TestUtils.testPreviewState(false),
            Mockito.mock(DesignSurface::class.java),
            MutableStateFlow(ElementState("Title")),
            emptyList(),
            NoopComposeAnimationTracker,
          )
          .apply {
            setDuration(111)
            setSize(300, 300)
          }
      val ui =
        FakeUi(card).apply {
          updateToolbars()
          layout()
        }

      // Lock button is not available.
      findFreezeButton(card).also {
        // Button is here and visible.
        assertTrue(it.isVisible)
        assertFalse(it.isEnabled)
        TestUtils.assertBigger(minimumSize, it.size)
      }
      // Uncomment to preview ui.
      // ui.render()
    }

  private fun findFreezeButton(parent: Component): Component {
    return parent.findToolbar("AnimationCard").components[0]
  }
}
