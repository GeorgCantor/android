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
package com.android.tools.idea.appinspection.inspectors.network.model

import com.android.tools.adtui.model.Range
import com.android.tools.idea.appinspection.inspector.api.AppInspectorMessenger
import com.android.tools.idea.appinspection.inspectors.network.model.httpdata.HttpData
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class NetworkInspectorDataSourceTest {
  private val executor = Executors.newSingleThreadExecutor()
  private val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())

  @Test
  fun basicSearch(): Unit = runBlocking {
    val speedEvent = speedEvent(timestampNanos = 1000, rxSpeed = 10, txSpeed = 20)
    val httpEvent = requestStarted(id = 1, timestampNanos = 1002, url = "www.google.com")
    val testMessenger =
      TestMessenger(scope, flowOf(speedEvent.toByteArray(), httpEvent.toByteArray()))
    val dataSource = NetworkInspectorDataSourceImpl(testMessenger, scope)
    testMessenger.await()

    assertThat(dataSource.queryForSpeedData(Range(1.0, 2.0))).containsExactly(speedEvent)

    assertThat(dataSource.queryForHttpData(Range(1.0, 2.0)))
      .containsExactly(
        HttpData.createHttpData(
          id = 1,
          updateTimeUs = 1,
          requestStartTimeUs = 1,
          url = "www.google.com"
        )
      )
  }

  @Test
  fun advancedSearch(): Unit = runBlocking {
    val speedEvent1 = speedEvent(timestampNanos = 1000, rxSpeed = 10, txSpeed = 10)
    val speedEvent2 = speedEvent(timestampNanos = 2000, rxSpeed = 10, txSpeed = 10)
    val speedEvent3 = speedEvent(timestampNanos = 2000, rxSpeed = 10, txSpeed = 10)
    val speedEvent4 = speedEvent(timestampNanos = 3000, rxSpeed = 10, txSpeed = 10)
    val speedEvent5 = speedEvent(timestampNanos = 3000, rxSpeed = 10, txSpeed = 10)
    val speedEvent6 = speedEvent(timestampNanos = 3000, rxSpeed = 10, txSpeed = 10)
    val speedEvent7 = speedEvent(timestampNanos = 3001, rxSpeed = 10, txSpeed = 10)
    val speedEvent8 = speedEvent(timestampNanos = 6000, rxSpeed = 10, txSpeed = 10)

    val testMessenger =
      TestMessenger(
        scope,
        flowOf(
          speedEvent1.toByteArray(),
          speedEvent2.toByteArray(),
          speedEvent3.toByteArray(),
          speedEvent4.toByteArray(),
          speedEvent5.toByteArray(),
          speedEvent6.toByteArray(),
          speedEvent7.toByteArray(),
          speedEvent8.toByteArray()
        )
      )
    val dataSource = NetworkInspectorDataSourceImpl(testMessenger, scope)
    testMessenger.await()

    // basic inclusive search
    run {
      val speedEvents = dataSource.queryForSpeedData(Range(0.0, 10.0))
      assertThat(speedEvents)
        .containsExactly(
          speedEvent1,
          speedEvent2,
          speedEvent3,
          speedEvent4,
          speedEvent5,
          speedEvent6,
          speedEvent7,
          speedEvent8
        )
    }

    // search beginning
    run {
      val speedEvents = dataSource.queryForSpeedData(Range(0.0, 1.0))
      assertThat(speedEvents).containsExactly(speedEvent1)
    }

    // search end
    run {
      val speedEvents = dataSource.queryForSpeedData(Range(6.0, 10.0))
      assertThat(speedEvents).containsExactly(speedEvent8)
    }

    // search outside range of data
    run {
      val speedEvents = dataSource.queryForSpeedData(Range(9.0, 10.0))
      assertThat(speedEvents).isEmpty()
    }

    // no data exist in this range
    run {
      val speedEvents = dataSource.queryForSpeedData(Range(4.0, 5.0))
      assertThat(speedEvents).isEmpty()
    }

    // multiple elements with same timestamp at search boundary
    run {
      val speedEvents = dataSource.queryForSpeedData(Range(2.0, 3.0))
      assertThat(speedEvents)
        .containsExactly(speedEvent2, speedEvent3, speedEvent4, speedEvent5, speedEvent6)
    }
  }

  @Test
  fun searchHttpData(): Unit = runBlocking {
    // Request that starts in the selection range but ends outside of it.
    val srart1 = requestStarted(id = 1, timestampNanos = 1002, url = "www.url1.com")
    val end1 = requestCompleted(id = 1, timestampNanos = 3000)

    // Request that starts outside the range, but ends inside of it.
    val start2 = requestStarted(id = 2, timestampNanos = 44, url = "www.url2.com")
    val end2 = requestCompleted(id = 2, timestampNanos = 1534)

    // Request that starts and ends outside the range, but spans over it.
    val start3 = requestStarted(id = 3, timestampNanos = 55, url = "www.url3.com")
    val end3 = requestCompleted(id = 3, timestampNanos = 4500)

    // Request that starts and ends outside and not overlap the range.
    val start4 = requestStarted(id = 4, timestampNanos = 58, url = "www.url4.com")
    val end4 = requestCompleted(id = 4, timestampNanos = 67)

    val testMessenger =
      TestMessenger(
        scope,
        flowOf(
          srart1.toByteArray(),
          end1.toByteArray(),
          start2.toByteArray(),
          end2.toByteArray(),
          start3.toByteArray(),
          end3.toByteArray(),
          start4.toByteArray(),
          end4.toByteArray()
        )
      )
    val dataSource = NetworkInspectorDataSourceImpl(testMessenger, scope)
    testMessenger.await()

    assertThat(dataSource.queryForHttpData(Range(1.0, 2.0)))
      .containsExactly(
        HttpData.createHttpData(
          id = 2,
          updateTimeUs = 1,
          requestStartTimeUs = 0,
          requestCompleteTimeUs = 1,
          url = "www.url2.com"
        ),
        HttpData.createHttpData(
          id = 3,
          updateTimeUs = 4,
          requestStartTimeUs = 0,
          requestCompleteTimeUs = 4,
          url = "www.url3.com"
        ),
        HttpData.createHttpData(
          id = 1,
          updateTimeUs = 3,
          requestStartTimeUs = 1,
          requestCompleteTimeUs = 3,
          url = "www.url1.com"
        ),
      )
      .inOrder()
  }

  @Test
  fun cleanUpChannelOnDispose() = runBlocking {
    val testMessenger =
      TestMessenger(scope, flow { throw ArithmeticException("Something went wrong!") })
    val dataSource = NetworkInspectorDataSourceImpl(testMessenger, scope)
    testMessenger.await()
    try {
      dataSource.queryForSpeedData(Range(0.0, 5.0))
      fail()
    } catch (e: Throwable) {
      assertThat(e).isInstanceOf(CancellationException::class.java)
      var cause: Throwable? = e.cause
      while (cause != null && cause !is ArithmeticException) {
        cause = cause.cause
      }
      assertThat(cause).isInstanceOf(ArithmeticException::class.java)
    }
  }
}

private class TestMessenger(override val scope: CoroutineScope, val flow: Flow<ByteArray>) :
  AppInspectorMessenger {
  private val isCollected = CompletableDeferred<Boolean>()

  override suspend fun sendRawCommand(rawData: ByteArray): ByteArray {
    throw NotImplementedError()
  }

  override val eventFlow = flow {
    try {
      flow.collect { value -> emit(value) }
    } finally {
      isCollected.complete(true)
    }
  }

  suspend fun await() {
    isCollected.await()
  }
}
