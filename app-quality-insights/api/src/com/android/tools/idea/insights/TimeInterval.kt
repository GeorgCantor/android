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
package com.android.tools.idea.insights

import java.time.Instant
import java.time.temporal.ChronoUnit

enum class TimeIntervalFilter(val numDays: Long, private val displayString: String) {
  ONE_DAY(1, "Last 24 hours"),
  SEVEN_DAYS(7, "Last 7 days"),
  FOURTEEN_DAYS(14, "Last 14 days"),
  TWENTY_EIGHT_DAYS(28, "Last 28 days"),
  THIRTY_DAYS(30, "Last 30 days"),
  SIXTY_DAYS(60, "Last 60 days"),
  NINETY_DAYS(90, "Last 90 days");

  override fun toString(): String {
    return this.displayString
  }

  /**
   * Returns a [Pair] of [Long] values representing the (start, end) times as millis since epoch.
   *
   * A [TimeIntervalFilter] represents a timeframe, from "now" to "X" time in the past. This
   * extension function calculates this interval as Milliseconds from the time it was called.
   */
  fun asMillisFromNow(): Pair<Long, Long> {
    val endOfRange = Instant.now()
    val startOfRange = endOfRange.minus(numDays, ChronoUnit.DAYS)
    return startOfRange.toEpochMilli() to endOfRange.toEpochMilli()
  }
}
