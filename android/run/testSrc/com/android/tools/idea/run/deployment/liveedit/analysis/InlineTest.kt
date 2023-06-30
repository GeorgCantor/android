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
package com.android.tools.idea.run.deployment.liveedit.analysis

import com.android.tools.idea.run.deployment.liveedit.analysis.leir.IrClass
import com.android.tools.idea.run.deployment.liveedit.setUpComposeInProjectFixture
import com.android.tools.idea.testing.AndroidProjectRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineTest {
  @get:Rule
  var projectRule = AndroidProjectRule.inMemory()

  @Before
  fun setUp() {
    setUpComposeInProjectFixture(projectRule)
  }

  @Test
  fun testInline() {
    val original = projectRule.compile("""
      class A {
        fun inlineMethod(): Int {
          return 0
        }
        fun method() {
          inlineMethod()
        }
      }""", "A.kt")

    val new = projectRule.compile("""
      class A {
        inline fun inlineMethod(): Int {
          return 0
        }
        fun method() {
          inlineMethod()
        }
      }""", "A.kt")

    assertChanges(original, new)

    val oldClazz = IrClass(original)
    val oldMethod = oldClazz.methods.first { it.name == "inlineMethod" }
    assertFalse(oldMethod.isInline())

    val newClazz = IrClass(new)
    val newMethod = newClazz.methods.first { it.name == "inlineMethod" }
    assertTrue(newMethod.isInline())
  }

  @Test
  fun testNoInline() {
    val original = projectRule.compile("""
      class A {
        inline fun inlineMethod(first: () -> Int, second: () -> Int): Int {
          first()
          second()
          return 0
        }
        fun method() {
          inlineMethod({0}, {1})
        }
      }""", "A.kt")

    val new = projectRule.compile("""
      class A {
        inline fun inlineMethod(first: () -> Int, noinline second: () -> Int): Int {
          first()
          second()
          return 0
        }
        fun method() {
          inlineMethod({0}, {1})
        }
      }""", "A.kt")

    assertChanges(original, new)
  }
}