/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.common.surface.organization

import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.JPanel
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.times

class OrganizationLineTest {

  @Test
  fun drawNoLine1() {
    val panels = listOf(emptyList<JComponent>(), emptyList())
    verifyCalls(panels, 0)
  }

  @Test
  fun drawNoLine2() {
    val panels = emptyList<List<JComponent>>()
    verifyCalls(panels, 0)
  }

  @Test
  fun drawOneLine1() {
    val panels = listOf(emptyList(), listOf(JPanel(), JPanel(), JPanel()))
    verifyCalls(panels, 1)
  }

  @Test
  fun drawOneLine2() {
    val panels = listOf(listOf(JPanel(), JPanel(), JPanel()))
    verifyCalls(panels, 1)
  }

  @Test
  fun drawTwoLines1() {
    val panels = listOf(listOf(JPanel(), JPanel(), JPanel()), listOf(JPanel(), JPanel(), JPanel()))
    verifyCalls(panels, 2)
  }

  @Test
  fun drawTwoLines2() {
    val panels = listOf(listOf(JPanel()), listOf(JPanel(), JPanel(), JPanel()))
    verifyCalls(panels, 2)
  }

  private fun verifyCalls(panels: Collection<Collection<JComponent>>, calls: Int) {
    val g2d = createG2d()
    panels.paintLines(g2d)
    Mockito.verify(g2d, times(calls)).drawLine(anyInt(), anyInt(), anyInt(), anyInt())
  }

  private fun createG2d() =
    Mockito.mock(Graphics2D::class.java).apply {
      Mockito.`when`(this.color).then {}
      Mockito.`when`(this.stroke).then {}
      Mockito.`when`(this.drawLine(anyInt(), anyInt(), anyInt(), anyInt())).then {}
    }
}
