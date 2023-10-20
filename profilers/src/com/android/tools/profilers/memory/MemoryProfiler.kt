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
package com.android.tools.profilers.memory

import com.android.tools.adtui.model.AspectObserver
import com.android.tools.adtui.model.Range
import com.android.tools.idea.io.grpc.StatusRuntimeException
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.idea.transport.poller.TransportEventListener
import com.android.tools.profiler.proto.Commands
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Memory
import com.android.tools.profiler.proto.Memory.AllocationsInfo
import com.android.tools.profiler.proto.Memory.HeapDumpInfo
import com.android.tools.profiler.proto.Memory.TrackStatus
import com.android.tools.profiler.proto.Trace
import com.android.tools.profiler.proto.Trace.TraceData.TraceEnded
import com.android.tools.profiler.proto.Trace.TraceInfo
import com.android.tools.profiler.proto.Transport
import com.android.tools.profiler.proto.Transport.GetEventGroupsRequest
import com.android.tools.profiler.proto.Transport.TimeRequest
import com.android.tools.profilers.ProfilerAspect
import com.android.tools.profilers.ProfilerClient
import com.android.tools.profilers.StudioProfiler
import com.android.tools.profilers.StudioProfilers
import com.android.tools.profilers.analytics.FeatureTracker
import com.android.tools.profilers.memory.BaseStreamingMemoryProfilerStage.LiveAllocationSamplingMode.FULL
import com.android.tools.profilers.ImportedSessionUtils.importFileWithArtifactEvent
import com.android.tools.profilers.ImportedSessionUtils.makeEndedEvent
import com.android.tools.profilers.sessions.SessionsManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class MemoryProfiler(private val profilers: StudioProfilers) : StudioProfiler {
  private val myAspectObserver = AspectObserver()
  private val sessionsManager get() = profilers.sessionsManager
  private val featureTracker get() = profilers.ideServices.featureTracker

  init {
    this.profilers.addDependency(myAspectObserver).onChange(ProfilerAspect.AGENT, ::agentStatusChanged)
    sessionsManager.registerImportHandler("hprof", Consumer(::importHprof))
    sessionsManager.registerImportHandler("alloc", Consumer(::importLegacyAllocations))
    sessionsManager.registerImportHandler("heapprofd", Consumer(::importHeapprofd))
    this.profilers.registerSessionChangeListener(Common.SessionMetaData.SessionType.MEMORY_CAPTURE) {
      val stage = MainMemoryProfilerStage(this.profilers)
      this.profilers.stage = stage
      stage.setPendingCaptureStartTimeGuarded(this.profilers.session.startTimestamp)
      this.profilers.timeline.apply {
        reset(this@MemoryProfiler.profilers.session.startTimestamp, this@MemoryProfiler.profilers.session.endTimestamp)
        viewRange.set(dataRange)
        setIsPaused(true)
      }
    }
  }

  override fun newMonitor() = MemoryMonitor(profilers)
  override fun startProfiling(session: Common.Session) {}
  override fun stopProfiling(session: Common.Session) =
    try {
      // Stop any ongoing allocation tracking sessions (either legacy or jvmti-based).
      trackAllocations(profilers, session, false, false, null)
    }
    catch (e: StatusRuntimeException) {
      logger.info(e)
    }

  /**
   * Attempts to start live allocation tracking.
   */
  private fun agentStatusChanged() {
    val session = profilers.session
    when {
      // Early return if the session is not valid/alive.
      Common.Session.getDefaultInstance() == session || session.endTimestamp != Long.MAX_VALUE -> {}
      // Early return if JVMTI agent is not attached.
      !profilers.isAgentAttached -> {}
      else -> try {
        // Attempts to stop an existing tracking session.
        // This should only happen if we are restarting Studio and reconnecting to an app that already has an agent attached.
        trackAllocations(profilers, session, false, false, null)
      }
      catch (e: StatusRuntimeException) {
        logger.info(e)
      }
    }
  }

  private fun importHprof(file: File) {
    fun makeInfo(start: Long, end: Long) = HeapDumpInfo.newBuilder().setStartTime(start).setEndTime(end)
    importFileWithArtifactEvent(sessionsManager, file, Common.SessionData.SessionStarted.SessionType.MEMORY_CAPTURE) { start, end ->
      makeEndedEvent(start, start, Common.Event.Kind.MEMORY_HEAP_DUMP) {
        setMemoryHeapdump(Memory.MemoryHeapDumpData.newBuilder().setInfo(makeInfo(start, end)))
      }
    }
    featureTracker.trackCreateSession(Common.SessionMetaData.SessionType.MEMORY_CAPTURE, SessionsManager.SessionCreationSource.MANUAL)
  }

  private fun importHeapprofd(file: File) {
    importFileWithArtifactEvent(sessionsManager, file, Common.SessionData.SessionStarted.SessionType.MEMORY_CAPTURE) { start, end ->
      makeEndedEvent(start, start, Common.Event.Kind.MEMORY_TRACE) {
        setTraceData(
          Trace.TraceData.newBuilder().setTraceEnded(
            TraceEnded.newBuilder().setTraceInfo(
              TraceInfo.newBuilder()
                .setFromTimestamp(start)
                .setToTimestamp(end)
            )
          )
        )
      }
    }
  }

  private fun importLegacyAllocations(file: File) {
    fun makeInfo(start: Long, end: Long) =
      AllocationsInfo.newBuilder().setStartTime(start).setEndTime(end).setLegacy(true).setSuccess(true)
    importFileWithArtifactEvent(sessionsManager, file, Common.SessionData.SessionStarted.SessionType.MEMORY_CAPTURE) { start, end ->
      makeEndedEvent(start, start, Common.Event.Kind.MEMORY_ALLOC_TRACKING) {
        setMemoryAllocTracking(Memory.MemoryAllocTrackingData.newBuilder().setInfo(makeInfo(start, end)))
      }
    }
    featureTracker.trackCreateSession(Common.SessionMetaData.SessionType.MEMORY_CAPTURE, SessionsManager.SessionCreationSource.MANUAL)
  }

  companion object {
    private val logger: Logger
      get() = Logger.getInstance(MemoryProfiler::class.java)

    /**
     * @return whether live allocation is active for the specified session.
     */
    @JvmStatic
    fun isUsingLiveAllocation(profilers: StudioProfilers, session: Common.Session) =
      getAllocationInfosForSession(profilers.client, session, profilers.timeline.dataRange).let { it.isNotEmpty() && !it[0].legacy }

    /**
     * @return True if live allocation tracking is in FULL mode throughout the entire input time range, false otherwise.
     */
    @JvmStatic
    fun hasOnlyFullAllocationTrackingWithinRegion(profilers: StudioProfilers,
                                                  session: Common.Session, startTimeUs: Long, endTimeUs: Long): Boolean {
      val series = AllocationSamplingRateDataSeries(profilers.client, session)
      val samplingModes = series.getDataForRange(Range(startTimeUs.toDouble(), endTimeUs.toDouble()))
      return samplingModes.size == 1 && samplingModes[0].value.currentRate.samplingNumInterval == FULL.value
    }

    @JvmStatic
    fun saveHeapDumpToFile(client: ProfilerClient,
                           session: Common.Session,
                           info: HeapDumpInfo,
                           outputStream: OutputStream,
                           featureTracker: FeatureTracker) =
      saveToFile(client, session, info.startTime, outputStream, featureTracker::trackExportHeap, "Failed to export heap dump file")

    @JvmStatic
    fun saveLegacyAllocationToFile(client: ProfilerClient,
                                   session: Common.Session,
                                   info: AllocationsInfo,
                                   outputStream: OutputStream,
                                   featureTracker: FeatureTracker) =
      saveToFile(client, session, info.startTime, outputStream, featureTracker::trackExportAllocation, "Failed to export allocation records")

    @JvmStatic
    fun saveHeapProfdSampleToFile(client: ProfilerClient,
                                  session: Common.Session,
                                  info: TraceInfo,
                                  outputStream: OutputStream) =
      saveToFile(client, session, info.fromTimestamp, outputStream, {}, "Failed to export native allocation records")

    private fun saveToFile(client: ProfilerClient, session: Common.Session, startTime: Long, outputStream: OutputStream,
                           onFinished: () -> Unit, errorMsg: String) {
      val response = client.transportClient
        .getBytes(Transport.BytesRequest.newBuilder().setStreamId(session.streamId).setId(startTime.toString()).build())
      if (response.contents !== ByteString.EMPTY) {
        try {
          response.contents.writeTo(outputStream)
          onFinished()
        }
        catch (exception: IOException) {
          logger.warn("$errorMsg:\n$exception")
        }
      }
    }

    /**
     * Generate a default name for a memory capture to be exported. The name suggested is based on the current timestamp and the capture type.
     */
    @JvmStatic
    fun generateCaptureFileName() =
      "memory-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))}"

    @JvmStatic
    fun getNativeHeapSamplesForSession(client: ProfilerClient, session: Common.Session, rangeUs: Range) =
      getForSession(client, session, rangeUs,
                    Common.Event.Kind.MEMORY_TRACE) {
        if (it.last().traceData.hasTraceStarted()) it.last().traceData.traceStarted.traceInfo
        else it.last().traceData.traceEnded.traceInfo
      }

    @JvmStatic
    fun getNativeHeapEventsForSessionSortedByTimestamp(client: ProfilerClient, session: Common.Session, rangeUs: Range) =
      getForSession(client, session, rangeUs, Common.Event.Kind.TRACE_STATUS) { it.last() }.sortedWith(compareBy { it.timestamp })

    @JvmStatic
    fun getHeapDumpsForSession(client: ProfilerClient, session: Common.Session, rangeUs: Range): List<HeapDumpInfo> =
      getForSession(client, session, rangeUs, Common.Event.Kind.MEMORY_HEAP_DUMP) { it.last().memoryHeapdump.info }

    @JvmStatic
    fun getAllocationInfosForSession(client: ProfilerClient, session: Common.Session, rangeUs: Range): List<AllocationsInfo> =
      getForSession(client, session, rangeUs, Common.Event.Kind.MEMORY_ALLOC_TRACKING) {
        // We only need the last event to get the most recent HeapDumpInfo
        var info = it.last().memoryAllocTracking.info
        if (info == AllocationsInfo.getDefaultInstance()) {
          // A default instance means that we have a generically ended group due to device disconnect.
          // In those case, we look for the start event and use its AllocationsInfo instead.
          assert(it.eventsCount > 1)
          info = it.getEvents(0).memoryAllocTracking.info
          if (info.legacy && info.endTime == Long.MAX_VALUE) {
            info = info.toBuilder().setEndTime(session.endTimestamp).setSuccess(false).build()
          }
        }
        info
      }

    private fun <T> getForSession(client: ProfilerClient, session: Common.Session, rangeUs: Range,
                                  eventKind: Common.Event.Kind, mapper: (Transport.EventGroup) -> T): List<T> =
      GetEventGroupsRequest.newBuilder()
        .setStreamId(session.streamId)
        .setPid(session.pid)
        .setKind(eventKind)
        .setFromTimestamp(rangeUs.min.microsToNanos())
        .setToTimestamp(rangeUs.max.microsToNanos())
        .build()
        .let { client.transportClient.getEventGroups(it).groupsList.map(mapper) }


    @JvmStatic
    fun trackAllocations(profilers: StudioProfilers,
                         session: Common.Session,
                         enable: Boolean,
                         endSession: Boolean,
                         responseHandler: Consumer<TrackStatus?>?) {
      val timeNs = profilers.client.transportClient
        .getCurrentTime(TimeRequest.newBuilder().setStreamId(session.streamId).build())
        .timestampNs
      val trackCommand = Commands.Command.newBuilder().apply {
        streamId = session.streamId
        pid = session.pid
        if (enable) {
          type = Commands.Command.CommandType.START_ALLOC_TRACKING
          setStartAllocTracking(Memory.StartAllocTracking.newBuilder().setRequestTime(timeNs))
        }
        else {
          type = Commands.Command.CommandType.STOP_ALLOC_TRACKING
          // To indicate to the STOP_ALLOC_TRACKING command handler to end the current session, we set the session id.
          if (endSession) {
            sessionId = session.sessionId
          }
          setStopAllocTracking(Memory.StopAllocTracking.newBuilder().setRequestTime(timeNs))
        }
      }
      val response = profilers.client.transportClient.execute(
        Transport.ExecuteRequest.newBuilder().setCommand(trackCommand).build())
      if (responseHandler != null) {
        val statusListener = TransportEventListener(Common.Event.Kind.MEMORY_ALLOC_TRACKING_STATUS,
                                                    profilers.ideServices.mainExecutor,
                                                    { event -> event.commandId == response.commandId },
                                                    { session.streamId },
                                                    { session.pid },
                                                    callback = { event -> true.also {
                                                      responseHandler.accept(event.memoryAllocTrackingStatus.status)
                                                    }})
        profilers.transportPoller.registerListener(statusListener)
      }
    }
  }
}

private fun Transport.EventGroup.last() = getEvents(eventsCount - 1)
private fun Double.microsToNanos() = when (val us = this.toLong()) {
  Long.MIN_VALUE, Long.MAX_VALUE -> us
  else -> TimeUnit.MICROSECONDS.toNanos(us)
}