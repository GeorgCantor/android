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
package com.android.tools.profilers.memory.adapters

import com.android.sdklib.AndroidVersion
import com.android.tools.adtui.model.DataSeries
import com.android.tools.adtui.model.DurationData
import com.android.tools.adtui.model.DurationDataModel
import com.android.tools.adtui.model.RangedSeries
import com.android.tools.adtui.model.SeriesData
import com.android.tools.adtui.model.StreamingTimeline
import com.android.tools.adtui.model.axis.ClampedAxisComponentModel
import com.android.tools.adtui.model.formatter.BaseAxisFormatter
import com.android.tools.adtui.model.formatter.MemoryAxisFormatter
import com.android.tools.adtui.model.formatter.SingleUnitAxisFormatter
import com.android.tools.profiler.proto.Common
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.UnifiedEventDataSeries
import com.android.tools.profilers.memory.AllocationSamplingRateDataSeries
import com.android.tools.profilers.memory.DetailedMemoryUsage
import com.android.tools.profilers.memory.GcDurationData
import com.android.tools.profilers.memory.MemoryProfiler
import com.android.tools.profilers.memory.MemoryStageLegends
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

private fun Long.nanosToMicros() = TimeUnit.NANOSECONDS.toMicros(this)

open class MemoryDataProvider(val profilers: StudioProfilers,
                              val timeline: StreamingTimeline) {
  protected val logger get() = Logger.getInstance(this.javaClass)
  private val sessionData = profilers.session
  val gcStatsModel = makeModel(makeGcSeries())
  val isLiveAllocationTrackingReady get() = MemoryProfiler.isUsingLiveAllocation(profilers, sessionData)
  val allocationSamplingRateDataSeries = AllocationSamplingRateDataSeries(profilers.client, sessionData)
  val allocationSamplingRateDurations = makeModel(allocationSamplingRateDataSeries)
  val detailedMemoryUsage = DetailedMemoryUsage(profilers, this.gcStatsModel, this.allocationSamplingRateDurations)
  val memoryAxis = ClampedAxisComponentModel.Builder(detailedMemoryUsage.memoryRange, MEMORY_AXIS_FORMATTER).build()
  val objectsAxis = ClampedAxisComponentModel.Builder(detailedMemoryUsage.objectsRange, OBJECT_COUNT_AXIS_FORMATTER).build()
  val legends = MemoryStageLegends(detailedMemoryUsage, timeline.dataRange, false, ::isLiveAllocationTrackingReady)
  val tooltipLegends = MemoryStageLegends(detailedMemoryUsage, timeline.tooltipRange, true, ::isLiveAllocationTrackingReady)

  val isLiveAllocationTrackingSupported: Boolean
    get() = with(getDeviceForSelectedSession()) { this != null && featureLevel >= AndroidVersion.VersionCodes.O }

  fun getDeviceForSelectedSession() = profilers.getStream(profilers.session.streamId).let { stream ->
    if (stream.type === Common.Stream.Type.DEVICE) stream.device
    else null
  }

  private fun <T : DurationData> makeModel(series: DataSeries<T>) = DurationDataModel(RangedSeries(timeline.viewRange, series))

  private fun makeGcSeries() =
    UnifiedEventDataSeries(
      profilers.client.transportClient,
      sessionData.streamId,
      sessionData.pid,
      Common.Event.Kind.MEMORY_GC,
      UnifiedEventDataSeries.DEFAULT_GROUP_ID
    ) { events ->
      events.map { SeriesData(it.timestamp.nanosToMicros(), GcDurationData(it.memoryGc.duration.nanosToMicros())) }
    }

  companion object {
    val MEMORY_AXIS_FORMATTER: BaseAxisFormatter = MemoryAxisFormatter(1, 5, 5)
    val OBJECT_COUNT_AXIS_FORMATTER: BaseAxisFormatter = SingleUnitAxisFormatter(1, 5, 5, "")
  }
}