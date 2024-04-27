/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.animation

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.addFileToProjectAndInvalidate
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val UPDATE_TRANSITION_LABEL_NOT_SET_MESSAGE =
  "The label parameter should be set so this transition can be better inspected in the Animation Preview"

private const val ANIMATED_CONTENT_LABEL_NOT_SET_MESSAGE =
  "The label parameter should be set so this AnimatedContent can be better inspected in the Animation Preview"

private const val REMEMBER_INFINITE_TRANSITION_LABEL_NOT_SET_MESSAGE =
  "The label parameter should be set so this rememberInfiniteTransition can be better inspected in the Animation Preview"

private const val INFINITE_TRANSITION__PROPERTY_LABEL_NOT_SET_MESSAGE =
  "The label parameter should be set so this infinite transition property can be better inspected in the Animation Preview"

private const val ANIMATE_AS_STATE_LABEL_NOT_SET_MESSAGE =
  "The label parameter should be set so this animate*AsState can be better inspected in the Animation Preview"

private const val CROSSFADE_LABEL_NOT_SET_MESSAGE =
  "The label parameter should be set so this Crossfade can be better inspected in the Animation Preview"

private const val TRANSITION_PROPERTY_LABEL_NOT_SET_MESSAGE =
  "The label parameter should be set so this transition property can be better inspected in the Animation Preview"

class AnimationInspectionsTest {

  @get:Rule val projectRule: AndroidProjectRule = AndroidProjectRule.inMemory()
  private val fixture
    get() = projectRule.fixture

  @Before
  fun setUp() {
    fixture.addFileToProjectAndInvalidate(
      "src/androidx/compose/animation/core/Transition.kt",
      // language=kotlin
      """
      package androidx.compose.animation.core

      class Transition {}

      fun Transition.animateFloat(label: String = "FloatAnimation", transitionSpec: () -> Unit) {}

      fun <T> updateTransition(targetState: T, label: String? = null) {}
      """
        .trimIndent()
    )
    fixture.addFileToProjectAndInvalidate(
      "src/androidx/compose/animation/AnimatedContent.kt",
      // language=kotlin
      """
      package androidx.compose.animation

      fun <S> AnimatedContent(targetState: S, label: String = "AnimatedContent") {}
      // Extension is without label.
      fun <S> Transition.AnimatedContent(targetState: S) {}
      """
        .trimIndent()
    )

    fixture.addFileToProjectAndInvalidate(
      "src/androidx/compose/animation/core/InfiniteTransition.kt",
      // language=kotlin
      """
      package androidx.compose.animation.core

      class InfiniteTransition

      fun rememberInfiniteTransition(label: String = "rememberInfiniteTransition") {}
      fun InfiniteTransition.animateFloat(initialValue: Float, targetValue: Float, label: String = "FloatAnimation")
      fun <T> InfiniteTransition.animateValue(initialValue: T,targetValue: T,label: String = "ValueAnimation")
      """
        .trimIndent()
    )

    fixture.addFileToProjectAndInvalidate(
      "src/androidx/compose/animation/Transition.kt",
      // language=kotlin
      """
      package androidx.compose.animation

      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.core.Transition

      fun Transition.animateColor(transitionSpec: () -> Unit, label: String = "ColorAnimation") {}
      fun InfiniteTransition.animateColor(initialValue: Any, targetValue: Any, label: String = "ColorAnimation") {}
      """
        .trimIndent()
    )
    fixture.addFileToProjectAndInvalidate(
      "src/androidx/compose/animation/core/AnimateAsState.kt",
      // language=kotlin
      """
      package androidx.compose.animation.core

      fun animateFloatAsState(targetValue: Float, label: String = "FloatAnimation") {}
      // In previous versions of compose label didn't existed.
      fun animateIntAsState(targetValue: Int) {}

      """
        .trimIndent()
    )
    fixture.addFileToProjectAndInvalidate(
      "src/androidx/compose/animation/SingleValueAnimation.kt",
      // language=kotlin
      """
      package androidx.compose.animation

      fun animateColorAsState(targetValue: Any, label: String = "ColorAnimation") {}
      """
        .trimIndent()
    )
    fixture.addFileToProjectAndInvalidate(
      "src/androidx/compose/animation/Crossfade.kt",
      // language=kotlin
      """
      package androidx.compose.animation

      fun <T> Crossfade(targetState: T, label: String = "Crossfade") {}
      """
        .trimIndent()
    )
    fixture.enableInspections(UpdateTransitionLabelInspection() as InspectionProfileEntry)
    fixture.enableInspections(TransitionPropertiesLabelInspection() as InspectionProfileEntry)
    fixture.enableInspections(AnimatedContentLabelInspection() as InspectionProfileEntry)
    fixture.enableInspections(InfiniteTransitionLabelInspection() as InspectionProfileEntry)
    fixture.enableInspections(InfinitePropertiesLabelInspection() as InspectionProfileEntry)
    fixture.enableInspections(AnimateAsStateLabelInspection() as InspectionProfileEntry)
    fixture.enableInspections(CrossfadeLabelInspection() as InspectionProfileEntry)
  }

  // region AnimatedContent Extension

  @Test
  fun animatedContentExtensionLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.AnimatedContent
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        val transition = updateTransition(targetState = false)
        transition.AnimatedContent(targetState = 10)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      UPDATE_TRANSITION_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun animatedContentExtensionLabelIsNotExpected() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.AnimatedContent
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        val transition = updateTransition(targetState = false, "Label")
        transition.AnimatedContent(targetState = 10)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }
  // endregion

  // region AnimatedContent
  @Test
  fun animatedContentLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.AnimatedContent

      fun MyComposable() {
        AnimatedContent(targetState = 10)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      ANIMATED_CONTENT_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun animatedContentLabelSetExplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.AnimatedContent

      fun MyComposable() {
        AnimatedContent(targetState = 10, label = "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun animatedContentLabelSetImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.AnimatedContent

      fun MyComposable() {
        AnimatedContent(targetState = 10, "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun animatedContentSetOtherParameterImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.AnimatedContent

      fun MyComposable() {
        AnimatedContent(targetState = 10, "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testQuickFixAnimationContent() {
    // language=kotlin
    val originalFileContent =
      """
     import androidx.compose.animation.AnimatedContent

     fun MyComposable() {
       AnimatedContent(targetState = 10)
     }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
      import androidx.compose.animation.AnimatedContent

      fun MyComposable() {
        AnimatedContent(targetState = 10, label = "")
      }
    """
        .trimIndent()

    val quickFix =
      (fixture.getAllQuickFixes().single() as QuickFixWrapper).fix as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }
  // endregion

  // region rememberInfiniteTransition
  @Test
  fun rememberInfiniteTransitionLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.rememberInfiniteTransition

      fun MyComposable() {
        rememberInfiniteTransition()
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      REMEMBER_INFINITE_TRANSITION_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun rememberInfiniteTransitionLabelSetExplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.rememberInfiniteTransition

      fun MyComposable() {
        rememberInfiniteTransition(label = "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun rememberInfiniteTransitionLabelSetImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.rememberInfiniteTransition

      fun MyComposable() {
        rememberInfiniteTransition("Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testQuickFixRememberInfiniteTransition() {
    // language=kotlin
    val originalFileContent =
      """
      import androidx.compose.animation.core.rememberInfiniteTransition

      fun MyComposable() {
        rememberInfiniteTransition()
      }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
      import androidx.compose.animation.core.rememberInfiniteTransition

      fun MyComposable() {
        rememberInfiniteTransition(label = "")
      }
    """
        .trimIndent()

    val quickFix =
      (fixture.getAllQuickFixes().single() as QuickFixWrapper).fix as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }
  // endregion

  // region infinite transition property
  @Test
  fun infiniteTransitionLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.core.animateValue

      fun MyComposable() {
        val transition = InfiniteTransition()
        transition.animateValue(initialValue = 1f, targetValue = 2f)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    fixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    assertEquals(
      INFINITE_TRANSITION__PROPERTY_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun floatInfiniteTransitionLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.core.animateFloat

      fun MyComposable() {
        val transition = InfiniteTransition()
        transition.animateFloat(initialValue = 1f, targetValue = 2f)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    fixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    assertEquals(
      INFINITE_TRANSITION__PROPERTY_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun colorInfiniteTransitionLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.animateColor

      fun MyComposable() {
        val transition = InfiniteTransition()
        transition.animateColor(10, 20)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    fixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
    assertEquals(
      INFINITE_TRANSITION__PROPERTY_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun infiniteTransitionLabelSetExplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.core.animateFloat

      fun MyComposable() {
        val transition = InfiniteTransition()
        transition.animateFloat(initialValue = 1f, targetValue = 2f, label = "label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun infiniteTransitionLabelSetImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.core.animateFloat

      fun MyComposable() {
        val transition = InfiniteTransition()
        transition.animateFloat(initialValue = 1f, targetValue = 2f, "label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testQuickFixInfiniteTransition() {
    // language=kotlin
    val originalFileContent =
      """
      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.core.animateFloat

      fun MyComposable() {
        val transition = InfiniteTransition()
        transition.animateFloat(initialValue = 1f, targetValue = 2f)
      }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
      import androidx.compose.animation.core.InfiniteTransition
      import androidx.compose.animation.core.animateFloat

      fun MyComposable() {
        val transition = InfiniteTransition()
        transition.animateFloat(initialValue = 1f, targetValue = 2f, label = "")
      }
    """
        .trimIndent()

    val quickFix =
      (fixture.getAllQuickFixes().single() as QuickFixWrapper).fix as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }
  // endregion

  // region animate*AsState
  @Test
  fun animateAsStateLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.animateFloatAsState

      fun MyComposable() {
        animateFloatAsState(10f)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      ANIMATE_AS_STATE_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun animateIntAsStateLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.animateIntAsState

      fun MyComposable() {
        animateIntAsState(10)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    // It's expected there is no warning as animateIntAsState doesn't have a label parameter.
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun animateColorAsStateLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.animateColorAsState

      fun MyComposable() {
        animateColorAsState(10f)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      ANIMATE_AS_STATE_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun animateAsStateLabelSetExplicitly() {
    // language=kotlin
    val fileContent =
      """
       import androidx.compose.animation.core.animateFloatAsState

      fun MyComposable() {
        animateFloatAsState(targetValue = 10f, label = "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun animateColorAsStateLabelSetExplicitly() {
    // language=kotlin
    val fileContent =
      """
       import androidx.compose.animation.animateColorAsState

      fun MyComposable() {
        animateColorAsState(targetValue = 10f, label = "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun animateAsStateLabelSetImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.animateFloatAsState

      fun MyComposable() {
        animateFloatAsState(targetValue = 10f, "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun animateAsStateSetOtherParameterImplicitly() {
    // language=kotlin
    val fileContent =
      """
    import androidx.compose.animation.core.animateFloatAsState

      fun MyComposable() {
        animateFloatAsState(targetValue = 10f, "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testQuickFixAnimateAsState() {
    // language=kotlin
    val originalFileContent =
      """
    import androidx.compose.animation.core.animateFloatAsState

      fun MyComposable() {
        animateFloatAsState(targetValue = 10f)
      }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
    import androidx.compose.animation.core.animateFloatAsState

      fun MyComposable() {
        animateFloatAsState(targetValue = 10f, label = "")
      }
    """
        .trimIndent()

    val quickFix =
      (fixture.getAllQuickFixes().single() as QuickFixWrapper).fix as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }
  // endregion

  // region Crossfade
  @Test
  fun crossfadeLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.Crossfade

      fun MyComposable() {
        Crossfade(targetState = 10)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      CROSSFADE_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun crossfadeLabelSetExplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.Crossfade

      fun MyComposable() {
        Crossfade(targetState = 10, label = "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun crossfadeLabelSetImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.Crossfade

      fun MyComposable() {
        Crossfade(targetState = 10, "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun crossfadeSetOtherParameterImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.Crossfade

      fun MyComposable() {
        Crossfade(targetState = 10, "Label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testQuickFixCrossfade() {
    // language=kotlin
    val originalFileContent =
      """
     import androidx.compose.animation.Crossfade

     fun MyComposable() {
       Crossfade(targetState = 10)
     }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
      import androidx.compose.animation.Crossfade

      fun MyComposable() {
        Crossfade(targetState = 10, label = "")
      }
    """
        .trimIndent()

    val quickFix =
      (fixture.getAllQuickFixes().single() as QuickFixWrapper).fix as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }
  // endregion

  @Test
  fun testLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        updateTransition(targetState = false)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      UPDATE_TRANSITION_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun testLabelSetExplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        updateTransition(targetState = false, label = "explicit label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testLabelSetImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        updateTransition(targetState = false, "implicit label")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testSetOtherParameterImplicitly() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        updateTransition("this is the targetState")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      UPDATE_TRANSITION_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun testAnimateFloatAnimationCorePackageLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.animateFloat
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateFloat(transitionSpec = {})
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      TRANSITION_PROPERTY_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun testAnimateFloatAnimationCorePackageLabelSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.animateFloat
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateFloat(transitionSpec = {}, label = "float property")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testAnimateColorAnimationPackageLabelNotSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.animateColor
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateColor(transitionSpec = {})
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertEquals(
      TRANSITION_PROPERTY_LABEL_NOT_SET_MESSAGE,
      fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).single().description
    )
  }

  @Test
  fun testAnimateColorAnimationPackageLabelSet() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.animateColor
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateColor(transitionSpec = {}, label = "color property")
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testAnimateColorCustomPackage() {
    // language=kotlin
    val fileContent =
      """
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateColor(transitionSpec = {})
      }

      fun Transition.animateColor(transitionSpec: (String) -> Unit, label: String = "ColorAnimation") {
        transitionSpec(label)
      }
    """
        .trimIndent()

    fixture.configureByText("Test.kt", fileContent)
    // The animateColor method is not defined in one of the Compose animation packages, so we don't
    // show a warning.
    assertTrue(fixture.doHighlighting(HighlightSeverity.WEAK_WARNING).isEmpty())
  }

  @Test
  fun testQuickFixUpdateTransition() {
    // language=kotlin
    val originalFileContent =
      """
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        updateTransition(targetState = false)
      }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
      import androidx.compose.animation.core.updateTransition

      fun MyComposable() {
        updateTransition(targetState = false, label = "")
      }
    """
        .trimIndent()

    val quickFix =
      QuickFixWrapper.unwrap(fixture.getAllQuickFixes().single()) as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }

  @Test
  fun testQuickFixTransitionProperty() {
    // language=kotlin
    val originalFileContent =
      """
      import androidx.compose.animation.core.animateFloat
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateFloat(transitionSpec = {})
      }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
      import androidx.compose.animation.core.animateFloat
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateFloat(transitionSpec = {}, label = "")
      }
    """
        .trimIndent()

    val quickFix =
      QuickFixWrapper.unwrap(fixture.getAllQuickFixes().single()) as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }

  @Test
  fun testQuickFixTransitionPropertyWithOnlyLambdaParamDefined() {
    // language=kotlin
    val originalFileContent =
      """
      import androidx.compose.animation.core.animateFloat
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateFloat {
          // fake spec
        }
      }
    """
        .trimIndent()
    fixture.configureByText("Test.kt", originalFileContent)

    // language=kotlin
    val fileContentAfterFix =
      """
      import androidx.compose.animation.core.animateFloat
      import androidx.compose.animation.core.Transition

      fun MyComposable() {
        val transition = Transition()
        transition.animateFloat(label = "") {
          // fake spec
        }
      }
    """
        .trimIndent()

    val quickFix =
      QuickFixWrapper.unwrap(fixture.getAllQuickFixes().single()) as LocalQuickFixOnPsiElement
    assertEquals("Add label parameter", quickFix.text)
    assertEquals("Compose preview", quickFix.familyName)

    ApplicationManager.getApplication().invokeAndWait {
      CommandProcessor.getInstance()
        .executeCommand(
          fixture.project,
          { runWriteAction { quickFix.applyFix() } },
          "Add Label Argument",
          null
        )
    }

    fixture.checkResult(fileContentAfterFix)
  }
}
