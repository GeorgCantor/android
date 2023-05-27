/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.editors

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AndroidRegExpHostTest {
  @Test
  fun rightBracketNeedsEscaping() {
    // The right bracket must be escaped when in a character class such as "[abc\\}]+", but not otherwise.
    assertThat(AndroidRegExpHost().characterNeedsEscaping('}', true)).isFalse()
    assertThat(AndroidRegExpHost().characterNeedsEscaping('}', false)).isTrue()
  }
}
