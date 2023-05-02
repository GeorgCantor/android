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
package com.android.tools.property.ptable

import com.intellij.ui.scale.JBUIScale
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.roundToInt
import kotlin.properties.Delegates

// The width where resize detection is active
private const val RESIZE_THUMB_WIDTH = 5

// The smallest width to consider (to avoid division with zero)
private const val MIN_WIDTH = 1

/**
 * A utility for handling a column fraction is a 2 column panel.
 *
 * The [nameColumnFraction] is updated with the position of the column divider in a fraction of the [currentWidth] of the
 * 2 column panel. [xOffset] is the offset of the mouse positions received, and [minFirstColumnSize] is the minimum size of
 * the first column. The onResizeModeChange will be called whenever the mouse shape should be changed.
 */
class ColumnFractionChangeHandler(
  private val nameColumnFraction: ColumnFraction,
  private val xOffset: () -> Int,
  private val currentWidth: () -> Int,
  private val minFirstColumnSize: () -> Int,
  onResizeModeChange: (Boolean) -> Unit
) : MouseAdapter() {
  var resizeMode by Delegates.observable(false) { _, old, new -> if (old != new) onResizeModeChange(new) }
    private set

  private var xMinResize = 1
  private var xMaxResize = 0

  override fun mouseExited(event: MouseEvent) {
    resizeMode = false
  }

  override fun mouseEntered(event: MouseEvent) {
    computeResizeBounds()
    updateResizeMode(event)
  }

  override fun mouseMoved(event: MouseEvent) {
    updateResizeMode(event)
  }

  override fun mouseDragged(event: MouseEvent) {
    val width = currentWidth()
    val x = xOffset() + maxOf(event.x, minFirstColumnSize())
    if (resizeMode && width > MIN_WIDTH && x < width) {
      nameColumnFraction.value = x.toFloat() / width.toFloat()
      computeResizeBounds()
    }
  }

  private fun computeResizeBounds() {
    val thumbWidth = JBUIScale.scale(RESIZE_THUMB_WIDTH)
    val width = currentWidth()
    if (width > thumbWidth * 5) {
      val mid = (width * nameColumnFraction.value).roundToInt() - xOffset()
      xMinResize = mid - thumbWidth / 2
      xMaxResize = xMinResize + thumbWidth
    }
    else {
      xMinResize = 1
      xMaxResize = 0
    }
  }

  private fun updateResizeMode(event: MouseEvent) {
    resizeMode = nameColumnFraction.resizeSupported && event.x in xMinResize..xMaxResize
  }
}
