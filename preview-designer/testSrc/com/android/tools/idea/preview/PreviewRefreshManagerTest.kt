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
package com.android.tools.idea.preview

import com.android.annotations.concurrency.GuardedBy
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ProjectRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class TestPreviewRefreshRequest(
  private val scope: CoroutineScope,
  override val clientId: String,
  override val priority: Int,
  val name: String
) : PreviewRefreshRequest {
  companion object {
    // A lock is needed because these properties are shared between all requests
    val testLock = ReentrantLock()
    @GuardedBy("testLock") lateinit var log: StringBuilder
    @GuardedBy("testLock") lateinit var expectedLogPrintCount: CountDownLatch
  }

  override fun doRefresh(): Job {
    val refreshJob =
      scope.launch {
        testLock.withLock {
          log.appendLine("start $name")
          expectedLogPrintCount.countDown()
        }
        delay(1000)
      }
    return refreshJob
  }

  override fun onRefreshCompleted(result: RefreshResult, throwable: Throwable?) {
    testLock.withLock {
      when (result) {
        RefreshResult.SUCCESS -> log.appendLine("finish $name")
        RefreshResult.CANCELLED -> log.appendLine("cancel $name")
        // This should never happen, and if it does the test will fail when doing assertions about
        // the content of 'log'
        else -> log.appendLine("unexpected result")
      }
      expectedLogPrintCount.countDown()
    }
  }

  override fun onSkip(replacedBy: PreviewRefreshRequest) {
    testLock.withLock {
      log.appendLine("skip $name")
      expectedLogPrintCount.countDown()
    }
  }
}

class PreviewRefreshManagerTest {
  @JvmField @Rule val projectRule = ProjectRule()

  private lateinit var myDisposable: Disposable
  private lateinit var myScope: CoroutineScope
  private lateinit var refreshManager: PreviewRefreshManager

  @Before
  fun setUp() {
    myDisposable = Disposer.newDisposable()
    myScope = AndroidCoroutineScope(myDisposable)
    refreshManager = PreviewRefreshManager(myScope)
    TestPreviewRefreshRequest.log = StringBuilder()
  }

  @After
  fun tearDown() {
    Disposer.dispose(myDisposable)
    myScope.cancel()
  }

  @Test
  fun testRequestPriority() = runBlocking {
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(10)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 5, "req5"))
    val priorities = listOf(1, 2, 3, 4).shuffled()
    refreshManager.requestRefresh(
      TestPreviewRefreshRequest(myScope, "client2", priorities[0], "req${priorities[0]}")
    )
    refreshManager.requestRefresh(
      TestPreviewRefreshRequest(myScope, "client3", priorities[1], "req${priorities[1]}")
    )
    refreshManager.requestRefresh(
      TestPreviewRefreshRequest(myScope, "client4", priorities[2], "req${priorities[2]}")
    )
    refreshManager.requestRefresh(
      TestPreviewRefreshRequest(myScope, "client5", priorities[3], "req${priorities[3]}")
    )
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    assertEquals(
      """
      start req5
      finish req5
      start req4
      finish req4
      start req3
      finish req3
      start req2
      finish req2
      start req1
      finish req1
    """
        .trimIndent(),
      TestPreviewRefreshRequest.log.toString().trimIndent()
    )
  }

  @Test
  fun testSkipRequest() = runBlocking {
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(10)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 100, "req1"))
    val priorities2 = listOf(11, 22, 33)
    val priorities3 = listOf(1, 3, 2)
    for (i in 0 until 3) {
      refreshManager.requestRefresh(
        TestPreviewRefreshRequest(myScope, "client2", priorities2[i], "req2-${priorities2[i]}")
      )
      refreshManager.requestRefresh(
        TestPreviewRefreshRequest(myScope, "client3", priorities3[i], "req3-${priorities3[i]}")
      )
    }
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    val lines = TestPreviewRefreshRequest.log.toString().trimIndent().lines()
    assertEquals(10, lines.size) // 4 skip, 3 start and 3 finish
    // First 5 actions should be start of req1 and all skips,
    // but we cannot know in which of those positions the start will be
    assertTrue(lines.subList(0, 5).contains("start req1"))
    assertTrue(lines.subList(0, 5).contains("skip req2-11"))
    assertTrue(lines.subList(0, 5).contains("skip req2-22"))
    assertTrue(lines.subList(0, 5).contains("skip req3-1"))
    assertTrue(lines.subList(0, 5).contains("skip req3-2"))
    // We know the order of the last 5 actions
    assertEquals("finish req1", lines[5])
    assertEquals("start req2-33", lines[6])
    assertEquals("finish req2-33", lines[7])
    assertEquals("start req3-3", lines[8])
    assertEquals("finish req3-3", lines[9])
  }

  @Test
  fun testCancelRequest_newHigherPriority() = runBlocking {
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(1)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 1, "req1"))
    // wait for start of previous request and create a new one with higher priority
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(3)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 2, "req2"))
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    assertEquals(
      """
      start req1
      cancel req1
      start req2
      finish req2
    """
        .trimIndent(),
      TestPreviewRefreshRequest.log.toString().trimIndent()
    )
  }

  @Test
  fun testCancelRequest_newSamePriority() = runBlocking {
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(1)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 1, "req1"))
    // wait for start of previous request and create a new one with same priority
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(3)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 1, "req2"))
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    assertEquals(
      """
      start req1
      cancel req1
      start req2
      finish req2
    """
        .trimIndent(),
      TestPreviewRefreshRequest.log.toString().trimIndent()
    )
  }

  @Test
  fun testNotCancelRequest_newLowerPriority() = runBlocking {
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(1)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 1, "req1"))
    // wait for start of previous request and create a new one with lower priority
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(3)
    refreshManager.requestRefresh(TestPreviewRefreshRequest(myScope, "client1", 0, "req2"))
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    assertEquals(
      """
      start req1
      finish req1
      start req2
      finish req2
    """
        .trimIndent(),
      TestPreviewRefreshRequest.log.toString().trimIndent()
    )
  }
}
