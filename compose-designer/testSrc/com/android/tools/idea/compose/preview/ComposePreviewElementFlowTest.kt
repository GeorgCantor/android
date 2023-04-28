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
package com.android.tools.idea.compose.preview

import com.android.tools.idea.compose.ComposeProjectRule
import com.android.tools.idea.concurrency.createChildScope
import com.android.tools.idea.testing.executeAndSave
import com.android.tools.idea.testing.insertText
import com.android.tools.idea.testing.moveCaretToEnd
import com.android.tools.idea.testing.replaceText
import com.android.tools.idea.ui.ApplicationUtils
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.SmartPointerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposePreviewElementFlowTest {
  @get:Rule val projectRule = ComposeProjectRule()

  @Test
  fun `test flow updates`(): Unit = runBlocking {
    val psiFile =
      projectRule.fixture.addFileToProject(
        "src/OtherFile.kt",
        // language=kotlin
        """
        import androidx.compose.ui.tooling.preview.Devices
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.runtime.Composable

        @Composable
        @Preview
        fun Preview1() {
        }
      """
          .trimIndent()
      )
    val psiFilePointer = runReadAction { SmartPointerManager.createPointer(psiFile) }

    val completed = CompletableDeferred<Unit>()
    val listenersReady = CompletableDeferred<Unit>()
    val testJob = launch {
      val flowScope = createChildScope()
      val flow = previewElementFlowForFile(psiFilePointer).stateIn(flowScope)
      flow.value.single().let { assertEquals("OtherFileKt.Preview1", it.composableMethodFqn) }

      listenersReady.complete(Unit)

      // We take 2 elements to ensure a change (the first one is the original, the second one the
      // change
      withTimeout(5000) {
        flow.take(2).collect()

        assertEquals(
          """
            OtherFileKt.Preview1
            OtherFileKt.Preview2
          """
            .trimIndent(),
          flow.value.map { it.composableMethodFqn }.sorted().joinToString("\n")
        )

        completed.complete(Unit)
      }

      flowScope.cancel()
    }

    listenersReady.await()

    // Make change
    ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
      projectRule.fixture.openFileInEditor(psiFile.virtualFile)

      // Make 3 changes that should trigger *at least* 3 flow elements
      projectRule.fixture.editor.moveCaretToEnd()
      projectRule.fixture.editor.executeAndSave {
        insertText(
          """

            @Composable
            @Preview
            fun Preview2() {
            }

          """
            .trimIndent()
        )
      }
    }

    completed.await()

    // Ensure the flow listener is terminated
    testJob.cancel()
  }

  @Test
  fun `test multi preview flow updates`(): Unit {
    val multiPreviewPsiFile =
      projectRule.fixture.addFileToProject(
        "src/Multipreview.kt",
        // language=kotlin
        """
          import androidx.compose.ui.tooling.preview.Preview

          @Preview(name = "A")
          @Preview(name = "B")
          annotation class MultiPreview
        """
          .trimIndent()
      )
    val psiFile =
      projectRule.fixture.addFileToProject(
        "src/OtherFile.kt",
        // language=kotlin
        """
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.runtime.Composable

        @Composable
        @MultiPreview
        fun Preview1() {
        }
      """
          .trimIndent()
      )
    val psiFilePointer = runReadAction { SmartPointerManager.createPointer(psiFile) }

    runBlocking {
      val flowScope = createChildScope()
      val flow = previewElementFlowForFile(psiFilePointer).stateIn(flowScope)
      assertEquals(
        "Preview1 - A,Preview1 - B",
        flow.filter { it.size == 2 }.first().joinToString(",") { it.displaySettings.name }
      )

      // Make change
      ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
        projectRule.fixture.openFileInEditor(multiPreviewPsiFile.virtualFile)

        // Make 3 changes that should trigger *at least* 3 flow elements
        projectRule.fixture.editor.moveCaretToEnd()
        projectRule.fixture.editor.executeAndSave {
          replaceText("@Preview(name = \"B\")", "@Preview(name = \"B\")\n@Preview(name = \"C\")")
        }
      }

      assertEquals(
        "Preview1 - A,Preview1 - B,Preview1 - C",
        flow.filter { it.size == 3 }.first().joinToString(",") { it.displaySettings.name }
      )

      // Terminate the flow
      flowScope.cancel()
    }
  }
}
