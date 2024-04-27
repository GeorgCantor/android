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
package com.android.tools.compose.analysis

import org.junit.Test

class TryCatchComposableCheckerTests : AbstractComposeDiagnosticsTest() {
  @Test
  fun testTryCatchReporting001() {
    doTest(
      """
          import androidx.compose.runtime.*;

          @Composable fun foo() { }

          @Composable fun bar() {
              <error descr="[ILLEGAL_TRY_CATCH_AROUND_COMPOSABLE] Try catch is not supported around composable function invocations.">try</error> {
                  foo()
              } catch(e: Exception) {
              }
          }
          """
    )
  }

  @Test
  fun testTryCatchReporting002() {
    doTest(
      """
          import androidx.compose.runtime.*;

          fun foo() { }

          @Composable fun bar() {
              try {
                  foo()
              } catch(e: Exception) {
              }
          }
          """
    )
  }

  @Test
  fun testTryCatchReporting003() {
    doTest(
      """
          import androidx.compose.runtime.*;

          @Composable fun foo() { }

          @Composable fun bar() {
              try {
              } catch(e: Exception) {
                  foo()
              } finally {
                  foo()
              }
          }
          """
    )
  }

  @Test
  fun testTryCatchReporting004() {
    doTest(
      """
          import androidx.compose.runtime.*;

          @Composable fun foo() { }

          @Composable fun bar() {
              <error descr="[ILLEGAL_TRY_CATCH_AROUND_COMPOSABLE] Try catch is not supported around composable function invocations.">try</error> {
                  (1..10).forEach { foo() }
              } catch(e: Exception) {
              }
          }
          """
    )
  }

  @Test
  fun testTryCatchReporting005() {
    doTest(
      """
          import androidx.compose.runtime.*

          var globalContent = @Composable {}
          fun setContent(content: @Composable () -> Unit) {
              globalContent = content
          }
          @Composable fun A() {}

          fun test() {
              try {
                  setContent {
                      A()
                  }
              } finally {
                  print("done")
              }
          }
          """
    )
  }

  @Test
  fun testTryCatchReporting006() {
    doTest(
      """
            import androidx.compose.runtime.*
            @Composable fun A() {}

            @Composable
            fun test() {
                <error descr="[ILLEGAL_TRY_CATCH_AROUND_COMPOSABLE] Try catch is not supported around composable function invocations.">try</error> {
                    object {
                        init { A() }
                    }
                } finally {}
            }
        """
    )
  }

  @Test
  fun testTryCatchReporting007() {
    doTest(
      """
            import androidx.compose.runtime.*
            @Composable fun A() {}

            @Composable
            fun test() {
                <error descr="[ILLEGAL_TRY_CATCH_AROUND_COMPOSABLE] Try catch is not supported around composable function invocations.">try</error> {
                    object {
                        val x = A()
                    }
                } finally {}
            }
        """
    )
  }

  @Test
  fun testTryCatchReporting008() {
    doTest(
      """
            import androidx.compose.runtime.*

            @Composable
            fun test() {
                <error descr="[ILLEGAL_TRY_CATCH_AROUND_COMPOSABLE] Try catch is not supported around composable function invocations.">try</error> {
                    val x by remember { lazy { 0 } }
                    print(x)
                } finally {}
            }
        """
    )
  }

  @Test
  fun testTryCatchReporting009() {
    doTest(
      """
            import androidx.compose.runtime.*
            @Composable fun A() {}

            @Composable
            fun test() {
                try {
                    object {
                        val x: Int
                            @Composable get() = remember { 0 }
                    }
                } finally {}
            }
        """
    )
  }

  @Test
  fun testTryCatchReporting010() {
    doTest(
      """
            import androidx.compose.runtime.*
            @Composable fun A() {}

            @Composable
            fun test() {
                try {
                    class C {
                        init { <error descr="[COMPOSABLE_INVOCATION] @Composable invocations can only happen from the context of a @Composable function">A</error>() }
                    }
                } finally {}
            }
        """
    )
  }

  @Test
  fun testTryCatchReporting011() {
    doTest(
      """
            import androidx.compose.runtime.*
            @Composable fun A() {}

            @Composable
            fun test() {
                try {
                    @Composable fun B() {
                        A()
                    }
                } finally {}
            }
        """
    )
  }
}
