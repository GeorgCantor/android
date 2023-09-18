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
package com.android.tools.idea.appinspection.inspectors.network.model.httpdata

import com.android.tools.adtui.model.Range
import com.android.tools.idea.appinspection.inspectors.network.model.FakeNetworkInspectorDataSource
import com.android.tools.idea.appinspection.inspectors.network.model.analytics.StubNetworkInspectorTracker
import com.android.tools.idea.appinspection.inspectors.network.model.httpClosed
import com.android.tools.idea.appinspection.inspectors.network.model.httpThread
import com.android.tools.idea.appinspection.inspectors.network.model.requestCompleted
import com.android.tools.idea.appinspection.inspectors.network.model.requestPayload
import com.android.tools.idea.appinspection.inspectors.network.model.requestStarted
import com.android.tools.idea.appinspection.inspectors.network.model.responseCompleted
import com.android.tools.idea.appinspection.inspectors.network.model.responsePayload
import com.android.tools.idea.appinspection.inspectors.network.model.responseStarted
import com.android.tools.idea.protobuf.ByteString
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Test

private const val CONNECTION_ID = 1L
private val fakeUrl = fakeUrl(CONNECTION_ID)
private val faceTrace = fakeStackTrace(CONNECTION_ID)

private val HTTP_DATA =
  listOf(
    requestStarted(
      CONNECTION_ID,
      timestampNanos = SECONDS.toNanos(0),
      url = fakeUrl,
      method = "",
      trace = faceTrace
    ),
    requestPayload(CONNECTION_ID, timestampNanos = SECONDS.toNanos(1), payload = "REQUEST_CONTENT"),
    requestCompleted(CONNECTION_ID, timestampNanos = SECONDS.toNanos(1)),
    responseStarted(
      CONNECTION_ID,
      timestampNanos = SECONDS.toNanos(2),
      fields = fakeResponseFields(CONNECTION_ID)
    ),
    responsePayload(
      CONNECTION_ID,
      timestampNanos = SECONDS.toNanos(3),
      payload = "RESPONSE_CONTENT"
    ),
    responseCompleted(CONNECTION_ID, timestampNanos = SECONDS.toNanos(3)),
    httpClosed(CONNECTION_ID, timestamp = SECONDS.toNanos(3), completed = true),
  )

private val HTTP_DATA_WITH_THREAD =
  listOf(
    requestStarted(
      CONNECTION_ID,
      timestampNanos = SECONDS.toNanos(0),
      url = fakeUrl,
      method = "",
      trace = faceTrace
    ),
    requestPayload(CONNECTION_ID, timestampNanos = SECONDS.toNanos(1), payload = "REQUEST_CONTENT"),
    requestCompleted(CONNECTION_ID, timestampNanos = SECONDS.toNanos(1)),
    responseStarted(
      CONNECTION_ID,
      timestampNanos = SECONDS.toNanos(2),
      fields = fakeResponseFields(CONNECTION_ID)
    ),
    responsePayload(
      CONNECTION_ID,
      timestampNanos = SECONDS.toNanos(3),
      payload = "RESPONSE_CONTENT"
    ),
    responseCompleted(CONNECTION_ID, timestampNanos = SECONDS.toNanos(3)),
    httpClosed(CONNECTION_ID, timestamp = SECONDS.toNanos(3), completed = true),
    httpThread(CONNECTION_ID, timestampNanos = SECONDS.toNanos(4), 1, "thread"),
  )

class HttpDataModelTest {

  @Test
  fun eventsToHttpData() {
    val source = FakeNetworkInspectorDataSource(httpEventList = HTTP_DATA_WITH_THREAD)
    val scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    val model = HttpDataModelImpl(source, StubNetworkInspectorTracker(), scope)
    val httpDataList = model.getData(Range(0.0, SECONDS.toMicros(5).toDouble()))
    assertThat(httpDataList).hasSize(1)
    val httpData = httpDataList[0]

    assertThat(httpData.requestStartTimeUs).isEqualTo(0)
    assertThat(httpData.requestCompleteTimeUs).isEqualTo(1000000)
    assertThat(httpData.responseStartTimeUs).isEqualTo(2000000)
    assertThat(httpData.responseCompleteTimeUs).isEqualTo(3000000)
    assertThat(httpData.connectionEndTimeUs).isEqualTo(3000000)
    assertThat(httpData.method).isEmpty()
    assertThat(httpData.url).isEqualTo(fakeUrl)
    assertThat(httpData.trace).isEqualTo(faceTrace)
    assertThat(httpData.requestPayload).isEqualTo(ByteString.copyFromUtf8("REQUEST_CONTENT"))
    assertThat(httpData.responsePayload).isEqualTo(ByteString.copyFromUtf8("RESPONSE_CONTENT"))
    assertThat(httpData.responseHeader.getField("connId")).isEqualTo("1")
  }

  @Test
  fun eventsWithoutThreadDataIgnored() {
    val source = FakeNetworkInspectorDataSource(httpEventList = HTTP_DATA)
    val scope = CoroutineScope(MoreExecutors.directExecutor().asCoroutineDispatcher())
    val model = HttpDataModelImpl(source, StubNetworkInspectorTracker(), scope)
    val httpDataList = model.getData(Range(0.0, SECONDS.toMicros(5).toDouble()))
    assertThat(httpDataList).isEmpty()
  }
}
