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
@file:JvmName("AsyncTestUtils")
package com.android.testutils

import com.intellij.util.ui.EDT
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

/**
 * Waits until the given condition is satisfied while processing events.
 */
@JvmSynthetic
@Throws(TimeoutException::class)
fun waitForCondition(timeout: Duration, condition: () -> Boolean) {
  val timeoutMillis = timeout.inWholeMilliseconds
  val deadline = System.currentTimeMillis() + timeoutMillis
  var waitUnit = ((timeoutMillis + 9) / 10).coerceAtMost(10)
  val isEdt = EDT.isCurrentThreadEdt()
  while (waitUnit > 0) {
    if (isEdt) {
      UIUtil.dispatchAllInvocationEvents()
    }
    if (condition()) {
      return
    }
    Thread.sleep(waitUnit)
    waitUnit = waitUnit.coerceAtMost(deadline - System.currentTimeMillis())
  }
  throw TimeoutException()
}

@Throws(TimeoutException::class)
fun waitForCondition(timeout: Long, timeUnit: TimeUnit, condition: () -> Boolean) {
  waitForCondition(timeout.toDuration(timeUnit.toDurationUnit()), condition)
}

/**
 * Helper function that will loop until a [condition] is met or a [timeout] is exceeded. In each
 * iteration, the function will delay for a given time and then a given callback will be executed.
 */
suspend inline fun delayUntilCondition(
  delayPerIterationMs: Long,
  timeout: Duration = 30.seconds,
  crossinline condition: suspend () -> Boolean
) {
  withTimeout(timeout) {
    while (!condition()) {
      delay(delayPerIterationMs)
    }
  }
}

/**
 * Retries the given block until it no longer throws an AssertionError, or the timeout occurs. If timeout
 * occurs, throws an AssertionError, using the last AssertionError as the cause. If this is run from the EDT,
 * pumps the EDT in between checks.
 */
fun <R> retryUntilPassing(timeout: Duration, block: () -> R): R {
  var lastError: AssertionError? = null
  val isEdt = EDT.isCurrentThreadEdt()
  // TODO: Use kotlin.time.TimeSource.markNow() when it is no longer experimental
  val startNanos = System.nanoTime()
  val timeoutNanos = timeout.inWholeNanoseconds
  do {
    try {
      return block()
    } catch (e: AssertionError) {
      lastError = e
    }
    if (isEdt) {
      UIUtil.dispatchAllInvocationEvents()
    }
    Thread.sleep(20)
  } while (System.nanoTime() - startNanos < timeoutNanos)
  when (lastError) {
    null -> throw TimeoutException()
    else -> throw AssertionError("Expected state not reached before timeout", lastError)
  }
}
