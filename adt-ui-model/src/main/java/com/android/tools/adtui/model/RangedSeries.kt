/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.adtui.model

/**
 * This class is the default implementation of a ranged series. It provides access to the DataSeries
 * scoped by a given Range or the intersection of two given ranges.
 *
 * @param <E> This should be the type of data this RangedSeries represents.
 */
open class RangedSeries<E>
@JvmOverloads constructor(/**
                           * The range of the view
                           */
                          val xRange: Range,
                          /**
                           * Store for the data we will provide scoped access to
                           */
                          private var _series: DataSeries<E>,
                          /**
                           * The range of the data
                           */
                          private val intersectRange: Range = Range(-Double.MAX_VALUE, Double.MAX_VALUE)) {
  private val maxEndPoints = listOf(Long.MAX_VALUE.toDouble(), Double.MAX_VALUE)

  private var lastQueriedRange = Range()
  private var lastQueriedSeries = emptyList<SeriesData<E>>()

  /**
   * A new range object that represents the intersection between the default and intersect ranges.
   */
  val intersection: Range get() = xRange.getIntersection(intersectRange)

  /**
   * A new, immutable [List<SeriesData>] consisting of items in the DataStore scoped to the range(s) that the RangedSeries was
   * initialized with.
   *
   * Note - this call is frequently made by UI components on the main thread, so the last queried results are cached and returned if the
   * query range is determined to not have changed to avoid hitting the Datastore redundantly. If the query range's max value is
   * Long.MAX_VALUE or Double.MAX_VALUE, however, then the cache is bypassed since there might be new data that are still streaming in.
   */
  val series: List<SeriesData<E>>
    get() = getValuesInRange()

  // See comments on [series] for more details on how this function works.
  private fun getValuesInRange(): List<SeriesData<E>> {
    val queryRange = xRange.getIntersection(intersectRange)

    if (queryRange.max in maxEndPoints) {
      return _series.getDataForRange(queryRange)
    }

    if (!lastQueriedRange.isSameAs(queryRange)) {
      val queriedSeries = _series.getDataForRange(queryRange)

      lastQueriedRange = queryRange
      lastQueriedSeries = queriedSeries.toList() // Make a copy to allow the underlying series to change freely
    }

    return lastQueriedSeries
  }

  /**
   * @param range The range to which the data will be scoped.
   * @return A new, immutable [SeriesData] list that allows the caller to get items in the DataStore scoped to the given range.
   */
  fun getSeriesForRange(range: Range): List<SeriesData<E>> = _series.getDataForRange(range)

  /** Invalidate cached query */
  fun invalidate() {
    lastQueriedRange = Range()
    lastQueriedSeries = emptyList()
  }
}
