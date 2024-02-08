/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test


class ModelVersionsTest {

  @Test
  fun checkModelConsumerVersionOrdering() {
    // Descriptions are not part of the ordering
    val ordered = listOf(
      ModelConsumerVersion(Int.MIN_VALUE, Int.MIN_VALUE, "z"),
      ModelConsumerVersion(0,0, "y"),
      ModelConsumerVersion(0,1, "z"),
      ModelConsumerVersion(0,2, "w"),
      ModelConsumerVersion(1,0, "v"),
      ModelConsumerVersion(1,1, "u"),
      ModelConsumerVersion(1,2, "t"),
      ModelConsumerVersion(2,0, "s"),
      ModelConsumerVersion(Int.MAX_VALUE, Int.MAX_VALUE, "a"),
    )
    assertThat(ordered.reversed().sorted())
      .containsExactlyElementsIn(ordered)
      .inOrder()
  }

  @Test
  fun checkModelVersionOrdering() {
    // Descriptions are not part of the ordering
    val ordered = listOf(
      ModelVersion(Int.MIN_VALUE, Int.MIN_VALUE, "z"),
      ModelVersion(0,0, "y"),
      ModelVersion(0,1, "z"),
      ModelVersion(0,2, "w"),
      ModelVersion(1,0, "v"),
      ModelVersion(1,1, "u"),
      ModelVersion(1,2, "t"),
      ModelVersion(2,0, "s"),
      ModelVersion(Int.MAX_VALUE, Int.MAX_VALUE, "a"),
    )
    assertThat(ordered.reversed().sorted())
      .containsExactlyElementsIn(ordered)
      .inOrder()
  }
}