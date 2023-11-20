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
package com.android.tools.profilers

import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Memory
import com.android.tools.profiler.proto.Trace.TraceInfo
import com.android.tools.profiler.proto.Trace.TraceConfiguration
import com.android.tools.profilers.cpu.CpuCaptureSessionArtifact
import com.android.tools.profilers.memory.AllocationSessionArtifact
import com.android.tools.profilers.memory.HeapProfdSessionArtifact
import com.android.tools.profilers.memory.HprofSessionArtifact
import com.android.tools.profilers.memory.LegacyAllocationsSessionArtifact
import com.android.tools.profilers.sessions.SessionArtifact
import com.android.tools.profilers.sessions.SessionItem

object SessionArtifactUtils {

  fun createCpuCaptureSessionArtifactWithConfig(profilers: StudioProfilers,
                                                session: Common.Session,
                                                sessionId: Long,
                                                traceId: Long,
                                                config: TraceConfiguration) = createCpuCaptureSessionArtifactWithConfig(profilers,
                                                                                                                        session,
                                                                                                                        sessionId,
                                                                                                                        traceId, 0, 0,
                                                                                                                        config)

  /**
   * Overload of createCpuCaptureSessionArtifactWithConfig that takes in from and end timestamps.
   */
  fun createCpuCaptureSessionArtifactWithConfig(profilers: StudioProfilers,
                                                session: Common.Session,
                                                sessionId: Long,
                                                traceId: Long,
                                                fromTimestamp: Long,
                                                toTimestamp: Long,
                                                config: TraceConfiguration): CpuCaptureSessionArtifact {
    val sessionMetadata = Common.SessionMetaData.newBuilder().setSessionId(sessionId).build()
    val info = TraceInfo.newBuilder().setFromTimestamp(fromTimestamp).setToTimestamp(toTimestamp).setTraceId(
      traceId).setConfiguration(config.toBuilder()).build()
    return CpuCaptureSessionArtifact(profilers, session, sessionMetadata, info)
  }

  fun createCpuCaptureSessionArtifact(profilers: StudioProfilers,
                                      session: Common.Session,
                                      sessionId: Long,
                                      traceId: Long) = createCpuCaptureSessionArtifactWithConfig(profilers, session, sessionId, traceId,
                                                                                                 TraceConfiguration.getDefaultInstance())

  fun createHprofSessionArtifact(profilers: StudioProfilers, session: Common.Session,
                                 startTimestamp: Long,
                                 endTimestamp: Long): HprofSessionArtifact {
    val sessionMetadata = Common.SessionMetaData.getDefaultInstance()
    val info = Memory.HeapDumpInfo.newBuilder().setStartTime(startTimestamp).setEndTime(endTimestamp).build()
    return HprofSessionArtifact(profilers, session, sessionMetadata, info)
  }

  fun createHeapProfdSessionArtifact(profilers: StudioProfilers, session: Common.Session,
                                     fromTimestamp: Long,
                                     toTimestamp: Long): HeapProfdSessionArtifact {
    val sessionMetadata = Common.SessionMetaData.getDefaultInstance()
    val info = TraceInfo.newBuilder().setFromTimestamp(fromTimestamp).setToTimestamp(toTimestamp).build()
    return HeapProfdSessionArtifact(profilers, session, sessionMetadata, info)
  }

  fun createAllocationSessionArtifact(profilers: StudioProfilers, session: Common.Session,
                                      startTimestamp: Long,
                                      endTimestamp: Long): AllocationSessionArtifact {
    val sessionMetadata = Common.SessionMetaData.getDefaultInstance()
    val info = Memory.AllocationsInfo.newBuilder().setStartTime(startTimestamp).setEndTime(endTimestamp).build()
    return AllocationSessionArtifact(profilers, session, sessionMetadata, info, startTimestamp.toDouble(), endTimestamp.toDouble())
  }

  fun createLegacyAllocationsSessionArtifact(profilers: StudioProfilers, session: Common.Session,
                                             startTimestamp: Long,
                                             endTimestamp: Long): LegacyAllocationsSessionArtifact {
    val sessionMetadata = Common.SessionMetaData.getDefaultInstance()
    val info = Memory.AllocationsInfo.newBuilder().setStartTime(startTimestamp).setEndTime(endTimestamp).build()
    return LegacyAllocationsSessionArtifact(profilers, session, sessionMetadata, info)
  }

  fun createSessionItem(profilers: StudioProfilers,
                        initialSession: Common.Session,
                        sessionId: Long,
                        childArtifacts: List<SessionArtifact<*>>): SessionItem {
    return createSessionItem(profilers, initialSession, sessionId, "", childArtifacts)
  }

  fun createSessionItem(profilers: StudioProfilers,
                        initialSession: Common.Session,
                        sessionId: Long,
                        sessionName: String,
                        childArtifacts: List<SessionArtifact<*>>): SessionItem {
    val sessionMetadata = Common.SessionMetaData.newBuilder().setSessionId(sessionId).setSessionName(sessionName).build()
    return SessionItem(profilers, initialSession, sessionMetadata).apply {
      setChildArtifacts(childArtifacts)
    }
  }
}