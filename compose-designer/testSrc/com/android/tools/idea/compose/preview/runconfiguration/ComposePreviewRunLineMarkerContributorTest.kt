/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.runconfiguration

import com.android.AndroidProjectTypes
import com.android.tools.compose.COMPOSABLE_ANNOTATION_FQ_NAME
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.addFileToProjectAndInvalidate
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.TestFixtureBuilder
import org.jetbrains.android.AndroidTestCase
import org.jetbrains.android.compose.stubComposableAnnotation
import org.jetbrains.android.compose.stubPreviewAnnotation
import org.jetbrains.android.uipreview.AndroidEditorSettings
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction

private fun PsiFile.findFunctionIdentifier(name: String): PsiElement {
  val function =
    PsiTreeUtil.findChildrenOfType(this, KtNamedFunction::class.java).first { it.name == name }
  return PsiTreeUtil.getChildrenOfType(function, LeafPsiElement::class.java)?.first {
    it.node.elementType == KtTokens.IDENTIFIER
  }!!
}

class ComposePreviewRunLineMarkerContributorTest : AndroidTestCase() {

  private val contributor = ComposePreviewRunLineMarkerContributor()

  override fun setUp() {
    super.setUp()
    myFixture.stubComposableAnnotation()
    myFixture.stubPreviewAnnotation()
    StudioFlags.COMPOSE_PREVIEW_LITE_MODE.override(true)
  }

  override fun tearDown() {
    super.tearDown()
    StudioFlags.COMPOSE_PREVIEW_LITE_MODE.clearOverride()
    AndroidEditorSettings.getInstance().globalState.isComposePreviewLiteModeEnabled = false
  }

  override fun configureAdditionalModules(
    projectBuilder: TestFixtureBuilder<IdeaProjectTestFixture>,
    modules: MutableList<MyAdditionalModuleData>
  ) {
    super.configureAdditionalModules(projectBuilder, modules)
    addModuleWithAndroidFacet(
      projectBuilder,
      modules,
      "myLibrary",
      AndroidProjectTypes.PROJECT_TYPE_LIBRARY
    )
  }

  fun testGetInfo() {
    val file =
      myFixture.addFileToProjectAndInvalidate(
        "src/Test.kt",
        // language=kotlin
        """
        import $COMPOSABLE_ANNOTATION_FQ_NAME
        import androidx.compose.ui.tooling.preview.Preview

        @Composable
        @Preview
        fun Preview1() {
        }
      """
          .trimIndent()
      )

    val functionIdentifier = file.findFunctionIdentifier("Preview1")
    // a run line marker should be created since the function is a valid preview.
    assertNotNull(contributor.getInfo(functionIdentifier))
  }

  fun testGetInfoWhenLiteModeIsEnabled() {
    AndroidEditorSettings.getInstance().globalState.isComposePreviewLiteModeEnabled = true
    val file =
      myFixture.addFileToProjectAndInvalidate(
        "src/Test.kt",
        // language=kotlin
        """
        import $COMPOSABLE_ANNOTATION_FQ_NAME
        import androidx.compose.ui.tooling.preview.Preview

        @Composable
        @Preview
        fun Preview1() {
        }
      """
          .trimIndent()
      )

    val functionIdentifier = file.findFunctionIdentifier("Preview1")
    // Although the function is a valid preview, a run line marker should not be created, because
    // Lite Mode is enabled
    assertNull(contributor.getInfo(functionIdentifier))
  }

  fun testGetInfoLibraryModule() {
    val modulePath = getAdditionalModulePath("myLibrary")

    val file =
      myFixture.addFileToProjectAndInvalidate(
        "$modulePath/src/main/java/com/example/mylibrary/TestLibraryFile.kt",
        // language=kotlin
        """
        import $COMPOSABLE_ANNOTATION_FQ_NAME
        import androidx.compose.ui.tooling.preview.Preview

        @Composable
        @Preview
        fun Preview1() {
        }
      """
          .trimIndent()
      )

    val functionIdentifier = file.findFunctionIdentifier("Preview1")
    // a run line marker should not be created since the function is located in a library module.
    assertNull(contributor.getInfo(functionIdentifier))
  }

  fun testGetInfoMultipreview() {
    val file =
      myFixture.addFileToProjectAndInvalidate(
        "src/Test.kt",
        // language=kotlin
        """
        import androidx.compose.ui.tooling.preview.Preview
        import $COMPOSABLE_ANNOTATION_FQ_NAME

        @Preview
        annotation class MyAnnotation() {}

        @Composable
        @MyAnnotation
        fun Preview1() {
        }
      """
          .trimIndent()
      )

    val functionIdentifier = file.findFunctionIdentifier("Preview1")
    // a run line marker should be created since the function is a valid preview.
    assertNotNull(contributor.getInfo(functionIdentifier))
  }

  fun testGetInfoEmptyMultipreview() {
    val file =
      myFixture.addFileToProjectAndInvalidate(
        "src/Test.kt",
        // language=kotlin
        """
        import androidx.compose.ui.tooling.preview.Preview
        import $COMPOSABLE_ANNOTATION_FQ_NAME

        annotation class MyNotPreviewAnnotation() {}

        @Composable
        @MyNotPreviewAnnotation
        fun Preview1() {
        }
      """
          .trimIndent()
      )

    val functionIdentifier = file.findFunctionIdentifier("Preview1")
    // a run line marker should not be created since the annotation class is not annotated with
    // Preview.
    assertNull(contributor.getInfo(functionIdentifier))
  }

  fun testGetInfoInvalidComposePreview() {
    val file =
      myFixture.addFileToProjectAndInvalidate(
        "src/TestNotPreview.kt",
        // language=kotlin
        """
        import $COMPOSABLE_ANNOTATION_FQ_NAME
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun Test() {
          fun NotAPreview() {
          }
        }

        @Preview
        @Composable
        fun Test() {
          @Preview
          @Composable
          fun NestedPreview() {
          }
        }
      """
          .trimIndent()
      )

    val notPreview = file.findFunctionIdentifier("NotAPreview")
    // a run line marker should not be created since the function is not a valid preview.
    assertNull(contributor.getInfo(notPreview))

    val nestedPreview = file.findFunctionIdentifier("NestedPreview")
    // a run line marker should not be created since the function is not a valid preview.
    assertNull(contributor.getInfo(nestedPreview))
  }
}
