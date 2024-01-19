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
package com.android.tools.idea.uibuilder.surface.layout

import com.android.tools.adtui.common.SwingCoordinate
import com.android.tools.idea.common.model.scaleBy
import com.android.tools.idea.common.surface.SurfaceScale
import java.awt.Dimension
import java.awt.Point
import kotlin.math.max

/**
 * This layout puts the previews in the same group together and list them vertically. It shows every
 * preview in the top left of the window.
 *
 * If there is only one visible preview, put it at the center of window. If there are more than one
 * visible preview, they would be shown as a vertical list.
 *
 * @param padding layout paddings
 */
class GroupedListSurfaceLayoutManager(
  private val padding: GroupPadding,
  override val transform: (Collection<PositionableContent>) -> List<PositionableGroup>,
) : GroupedSurfaceLayoutManager(padding.previewPaddingProvider) {

  @SurfaceScale
  override fun getFitIntoScale(
    content: Collection<PositionableContent>,
    @SwingCoordinate availableWidth: Int,
    @SwingCoordinate availableHeight: Int,
  ): Double {
    if (content.isEmpty()) {
      // No content. Use 100% as zoom level
      return 1.0
    }
    // Use binary search to find the proper zoom-to-fit value.
    // Find the scale to put all the previews into the screen without any margin and padding.
    val totalRawHeight = content.sumOf { it.contentSize.height }
    val maxRawWidth = content.maxOf { it.contentSize.width }
    // The zoom-to-fit scale can not be larger than this scale, since it may need some margins
    // and/or paddings.
    val upperBound =
      minOf(availableHeight.toDouble() / totalRawHeight, availableWidth.toDouble() / maxRawWidth)

    if (upperBound <= MINIMUM_SCALE) {
      return MINIMUM_SCALE
    }
    // binary search between MINIMUM_SCALE to upper bound.
    return getMaxZoomToFitScale(
      content,
      MINIMUM_SCALE,
      upperBound,
      availableWidth,
      availableHeight,
      Dimension(),
    )
  }

  /** Binary search to find the largest scale for [width] x [height] space. */
  @SurfaceScale
  private fun getMaxZoomToFitScale(
    content: Collection<PositionableContent>,
    @SurfaceScale min: Double,
    @SurfaceScale max: Double,
    @SwingCoordinate width: Int,
    @SwingCoordinate height: Int,
    cache: Dimension,
    depth: Int = 0,
  ): Double {
    if (depth >= MAX_ITERATION_TIMES) {
      return min
    }
    if (max - min <= SCALE_UNIT) {
      // Last attempt.
      val dim = getSize(content, { contentSize.scaleBy(max) }, { max }, 0, cache)
      return if (dim.width <= width && dim.height <= height) max else min
    }
    val scale = (min + max) / 2
    val dim = getSize(content, { contentSize.scaleBy(scale) }, { scale }, 0, cache)
    return if (dim.width <= width && dim.height <= height) {
      getMaxZoomToFitScale(content, scale, max, width, height, cache, depth + 1)
    } else {
      getMaxZoomToFitScale(content, min, scale, width, height, cache, depth + 1)
    }
  }

  override fun getSize(
    content: Collection<PositionableContent>,
    sizeFunc: PositionableContent.() -> Dimension,
    scaleFunc: PositionableContent.() -> Double,
    availableWidth: Int,
    dimension: Dimension?,
  ): Dimension {
    val dim = dimension ?: Dimension()

    val verticalList = transform(content).flatMap { listOf(it.header) + it.content }.filterNotNull()

    if (verticalList.isEmpty()) {
      dim.setSize(0, 0)
      return dim
    }

    var requiredWidth = 0
    var totalRequiredHeight = padding.canvasTopPadding

    for (view in verticalList) {
      val scale = view.scaleFunc()
      val margin = view.getMargin(scale)
      val viewSize = view.sizeFunc()
      val framePadding = padding.previewPaddingProvider(scale)
      val viewWidth = framePadding + viewSize.width + margin.horizontal + framePadding
      val requiredHeight = framePadding + viewSize.height + margin.vertical + framePadding

      requiredWidth = maxOf(requiredWidth, viewWidth)
      totalRequiredHeight += requiredHeight
    }

    dim.setSize(requiredWidth, max(0, totalRequiredHeight))
    return dim
  }

  override fun measure(
    content: Collection<PositionableContent>,
    availableWidth: Int,
    availableHeight: Int,
    keepPreviousPadding: Boolean,
  ): Map<PositionableContent, Point> {
    val verticalList = transform(content).flatMap { listOf(it.header) + it.content }.filterNotNull()
    if (verticalList.isEmpty()) {
      return emptyMap()
    }

    if (content.size == 1) {
      val singleContent = content.single()
      // When there is only one visible preview, centralize it as a special case.
      val point = getSingleContentPosition(singleContent, availableWidth, availableHeight)

      return mapOf(singleContent to point)
    }

    val heightMap =
      verticalList.associateWith {
        val framePadding = padding.previewPaddingProvider(it.scale)
        framePadding + it.scaledContentSize.height + it.margin.vertical + framePadding
      }

    val positionMap = mutableMapOf<PositionableContent, Point>()
    var nextY = padding.canvasTopPadding
    for (view in verticalList) {
      val framePadding = padding.previewPaddingProvider(view.scale)
      positionMap.setContentPosition(
        view,
        framePadding + padding.canvasLeftPadding,
        nextY + framePadding,
      )
      nextY += heightMap[view]!!
    }

    return positionMap
  }

  private fun MutableMap<PositionableContent, Point>.setContentPosition(
    content: PositionableContent,
    x: Int,
    y: Int,
  ) {
    // The new compose layout consider the toolbar size as the anchor of location.
    val margin = content.margin
    val shiftedX = x + margin.left
    val shiftedY = y + margin.top
    put(content, Point(shiftedX, shiftedY))
  }
}
