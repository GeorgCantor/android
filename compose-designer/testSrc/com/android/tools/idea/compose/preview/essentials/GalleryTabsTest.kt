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
package com.android.tools.idea.compose.preview.essentials

import com.android.tools.adtui.TreeWalker
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import java.util.stream.Collectors
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class GalleryTabsTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  private var rootComponent = JPanel(BorderLayout())

  private data class TestKey(override val title: String) : TitledKey

  @Test
  fun `first tab is selected`() {
    invokeAndWaitIfNeeded {
      val keys = setOf(TestKey("First Tab"), TestKey("Second Tab"), TestKey("Third Tab"))
      val tabs = GalleryTabs(rootComponent, { keys }, { _, _ -> })
      val ui = FakeUi(tabs).apply { updateToolbars() }
      assertEquals(keys.first(), tabs.selectedKey)
    }
  }

  @Test
  fun `second tab is selected if first removed`() {
    invokeAndWaitIfNeeded {
      val keys = setOf(TestKey("Second Tab"), TestKey("Third Tab"))
      var providedKeys = setOf(TestKey("First Tab")) + keys
      val tabs = GalleryTabs(rootComponent, { providedKeys }, { _, _ -> })
      providedKeys = keys
      val ui = FakeUi(tabs).apply { updateToolbars() }
      assertEquals(keys.first(), tabs.selectedKey)
    }
  }

  @Test
  fun `new tab is added `() {
    invokeAndWaitIfNeeded {
      val newTab = TestKey("newTab")
      val providedKeys = mutableSetOf(TestKey("Tab"), TestKey("Tab2"), TestKey("Tab3"))
      val tabs = GalleryTabs(rootComponent, { providedKeys }) { _, _ -> }
      val ui = FakeUi(tabs)
      ui.updateNestedActions()
      assertEquals(3, findAllActionButtons(tabs).size)
      providedKeys += newTab
      ui.updateNestedActions()
      assertEquals(4, findAllActionButtons(tabs).size)
    }
  }

  @Test
  fun `order correct after update`() {
    invokeAndWaitIfNeeded {
      val keyOne = TestKey("First")
      val keyTwo = TestKey("Second")
      val keyThree = TestKey("Third")
      var providedKeys = setOf(keyTwo)
      val tabs = GalleryTabs(rootComponent, { providedKeys }) { _, _ -> }
      providedKeys = setOf(keyOne, keyTwo, keyThree)
      val ui = FakeUi(tabs)
      ui.updateNestedActions()
      val allActions = findAllActionButtons(tabs)
      assertEquals(3, allActions.size)
      assertEquals("First", allActions[0].presentation.text)
      assertEquals("Second", allActions[1].presentation.text)
      assertEquals("Third", allActions[2].presentation.text)
    }
  }

  @Test
  fun `toolbar is not updated`() {
    invokeAndWaitIfNeeded {
      val providedKeys = setOf(TestKey("First Tab"), TestKey("Second Tab"), TestKey("Third Tab"))
      val tabs = GalleryTabs(rootComponent, { providedKeys }) { _, _ -> }
      val ui = FakeUi(tabs).apply { updateToolbars() }
      val toolbar = findTabs(tabs)
      // Update toolbars
      ui.updateNestedActions()
      val updatedToolbar = findTabs(tabs)
      // Toolbar was not updated, it's same as before.
      assertEquals(toolbar, updatedToolbar)
    }
  }

  @Test
  fun `toolbar is updated with new key`() {
    invokeAndWaitIfNeeded {
      val providedKeys =
        mutableSetOf(TestKey("First Tab"), TestKey("Second Tab"), TestKey("Third Tab"))
      val tabs = GalleryTabs(rootComponent, { providedKeys }) { _, _ -> }
      val ui = FakeUi(tabs).apply { updateToolbars() }
      val toolbar = findTabs(tabs)
      // Set new set of keys.
      providedKeys += TestKey("New Tab")
      ui.updateNestedActions()
      val updatedToolbar = findTabs(tabs)
      // New toolbar was created.
      assertNotEquals(toolbar, updatedToolbar)
    }
  }

  @Test
  fun `toolbar is updated with removed key`() {
    invokeAndWaitIfNeeded {
      val keyToRemove = TestKey("Key to remove")
      var providedKeys =
        mutableSetOf(TestKey("First Tab"), TestKey("Second Tab"), TestKey("Third Tab"), keyToRemove)
      val tabs = GalleryTabs(rootComponent, { providedKeys }) { _, _ -> }
      val ui = FakeUi(tabs)
      ui.updateNestedActions()
      val toolbar = findTabs(tabs)
      // Set updated set of keys
      providedKeys.remove(keyToRemove)
      ui.updateNestedActions()
      val updatedToolbar = findTabs(tabs)
      // New toolbar was created.
      assertNotEquals(toolbar, updatedToolbar)
    }
  }

  @Ignore
  @Test
  /**
   * This test is used to verify and preview the tabs. It's ignored, so it's only run on demand. See
   * ui.render() to visually verify preview if required - it shows three tabs with first tab
   * selected.
   */
  fun `preview tabs`() {
    invokeAndWaitIfNeeded {
      val tabs =
        GalleryTabs(
          rootComponent,
          { setOf(TestKey("First Tab"), TestKey("Second Tab"), TestKey("Third Tab")) },
        ) { _, _ ->
        }
      val root = JPanel(BorderLayout()).apply { size = Dimension(400, 400) }
      root.add(tabs, BorderLayout.NORTH)
      val ui = FakeUi(root)
      ui.updateToolbars()
      ui.layout()
      ui.render()
    }
  }

  @Test
  fun `click on tabs`() {
    invokeAndWaitIfNeeded {
      var selectedTab: TestKey? = null
      val tabs =
        GalleryTabs(
          rootComponent,
          { setOf(TestKey("First Tab"), TestKey("Second Tab"), TestKey("Third Tab")) },
        ) { _, key ->
          selectedTab = key
        }
      val root = JPanel(BorderLayout()).apply { size = Dimension(400, 400) }
      root.add(tabs, BorderLayout.NORTH)
      val ui = FakeUi(root).apply { updateNestedActions() }
      val buttons = findAllActionButtons(root)
      assertEquals("First Tab", selectedTab?.title)
      assertEquals("First Tab", tabs.selectedKey?.title)
      ui.clickOn(buttons[1])
      assertEquals("Second Tab", selectedTab?.title)
      assertEquals("Second Tab", tabs.selectedKey?.title)
      ui.clickOn(buttons[2])
      assertEquals("Third Tab", selectedTab?.title)
      assertEquals("Third Tab", tabs.selectedKey?.title)
      ui.clickOn(buttons[0])
      assertEquals("First Tab", selectedTab?.title)
      assertEquals("First Tab", tabs.selectedKey?.title)
    }
  }

  @Test
  fun `selected tab is always visible`() {
    invokeAndWaitIfNeeded {
      val tabs =
        GalleryTabs(
          rootComponent,
          { setOf(TestKey("First Tab"), TestKey("Second Tab"), TestKey("Third Tab")) },
        ) { _, _ ->
        }
      // Width is 100, so only first tab is actually visible.
      val root = JPanel(BorderLayout()).apply { size = Dimension(150, 400) }
      root.add(tabs, BorderLayout.NORTH)

      FakeUi(root).apply { updateNestedActions() }
      val buttons = findAllActionButtons(root)
      val scrollPane = findScrollPane(root)
      // Only first button is visible
      assertTrue(scrollPane.bounds.contains(buttons[0].relativeBounds()))
      assertFalse(scrollPane.bounds.contains(buttons[1].relativeBounds()))
      assertFalse(scrollPane.bounds.contains(buttons[2].relativeBounds()))
      // Only second button is visible
      buttons[1].click()
      assertFalse(scrollPane.bounds.contains(buttons[0].relativeBounds()))
      assertTrue(scrollPane.bounds.contains(buttons[1].relativeBounds()))
      assertFalse(scrollPane.bounds.contains(buttons[2].relativeBounds()))
      // Only third button is visible
      buttons[2].click()
      assertFalse(scrollPane.bounds.contains(buttons[0].relativeBounds()))
      assertFalse(scrollPane.bounds.contains(buttons[1].relativeBounds()))
      assertTrue(scrollPane.bounds.contains(buttons[2].relativeBounds()))
      // Only first button is visible
      buttons[0].click()
      assertTrue(scrollPane.bounds.contains(buttons[0].relativeBounds()))
      assertFalse(scrollPane.bounds.contains(buttons[1].relativeBounds()))
      assertFalse(scrollPane.bounds.contains(buttons[2].relativeBounds()))
    }
  }

  private fun FakeUi.updateNestedActions() {
    this.updateToolbars()
    this.updateToolbars()
    this.layoutAndDispatchEvents()
  }

  private fun ActionButtonWithText.relativeBounds(): Rectangle {
    val bounds = Rectangle(this.bounds)
    bounds.translate(this.parent.parent.location.x, this.parent.parent.location.y)
    return bounds
  }

  private fun findScrollPane(parent: Component): JBScrollPane =
    TreeWalker(parent)
      .descendantStream()
      .filter { it is JBScrollPane }
      .collect(Collectors.toList())
      .first() as JBScrollPane

  private fun findAllActionButtons(parent: Component): List<ActionButtonWithText> =
    TreeWalker(parent)
      .descendantStream()
      .filter { it is ActionButtonWithText }
      .collect(Collectors.toList())
      .map { it as ActionButtonWithText }

  private fun findTabs(parent: Component) = findToolbar(parent, "Gallery Tabs")

  private fun findToolbar(parent: Component, place: String): ActionToolbarImpl =
    TreeWalker(parent)
      .descendantStream()
      .filter { it is ActionToolbarImpl }
      .collect(Collectors.toList())
      .map { it as ActionToolbarImpl }
      .first { it.place == place }
}
