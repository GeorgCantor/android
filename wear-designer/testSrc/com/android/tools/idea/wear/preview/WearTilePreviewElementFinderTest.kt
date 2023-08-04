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
package com.android.tools.idea.wear.preview

import com.android.ide.common.resources.Locale
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.addFileToProjectAndInvalidate
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.changesHandler.range
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WearTilePreviewElementFinderTest {
  @get:Rule val projectRule: AndroidProjectRule = AndroidProjectRule.inMemory()

  private val project
    get() = projectRule.project
  private val fixture
    get() = projectRule.fixture

  @Before
  fun setUp() {
    fixture.addFileToProjectAndInvalidate(
      "androidx/wear/tiles/tooling/preview/TilePreview.kt",
      // language=kotlin
      """
        package androidx.wear.tiles.tooling.preview

        import androidx.annotation.FloatRange

        object WearDevices {
            const val LARGE_ROUND = "id:wearos_large_round"
            const val SMALL_ROUND = "id:wearos_small_round"
            const val SQUARE = "id:wearos_square"
            const val RECT = "id:wearos_rect"
        }

        annotation class TilePreview(
            val name: String = "",
            val group: String = "",
            val device: String = WearDevices.SMALL_ROUND,
            val locale: String = "",
            @FloatRange(from = 0.01) val fontScale: Float = 1f,
        )
        """
        .trimIndent()
    )
  }

  @Test
  fun testWearTileElementsFinder() = runBlocking {
    val previewsTest =
      fixture.addFileToProjectAndInvalidate(
        "com/android/test/Src.kt",
        // language=kotlin
        """
        package com.android.test

        import androidx.wear.tiles.TileService
        import androidx.wear.tiles.tooling.preview.TilePreview
        import androidx.wear.tiles.tooling.preview.WearDevices

        @TilePreview
        class ThisShouldNotBePreviewed : TileService

        @TilePreview
        private fun tilePreview() {
        }

        @TilePreview(
          device = WearDevices.LARGE_ROUND
        )
        private fun largeRoundTilePreview() {
        }

        @TilePreview(
          name = "some name"
        )
        private fun namedTilePreview() {
        }

        @TilePreview(
          group = "some group",
          device = WearDevices.SQUARE
        )
        private fun tilePreviewWithGroup() {
        }

        fun someRandomMethod() {
        }

        @TilePreview(
          locale = "fr"
        )
        private fun tilePreviewWithLocale() {
        }

        @TilePreview(
          fontScale = 1.2f
        )
        private fun tilePreviewWithFontScale() {
        }

        """
          .trimIndent()
      )

    val otherFileTest =
      fixture.addFileToProjectAndInvalidate(
        "com/android/test/OtherFile.kt",
        // language=kotlin
        """
        package com.android.test

        import androidx.wear.tiles.tooling.preview.TilePreview

        @TilePreview
        private fun tilePreviewInAnotherFile() {
        }
        """
          .trimIndent()
      )

    val fileWithNoPreviews =
      fixture.addFileToProjectAndInvalidate(
        "com/android/test/SourceFileNone.kt",
        // language=kotlin
        """
        package com.android.test

        import androidx.wear.tiles.TileService

        class WearTileService : TileService

        fun someRandomMethod() {
        }
        """
          .trimIndent()
      )

    assertThat(WearTilePreviewElementFinder.hasPreviewElements(project, previewsTest.virtualFile))
      .isTrue()
    assertThat(WearTilePreviewElementFinder.hasPreviewElements(project, otherFileTest.virtualFile))
      .isTrue()
    assertThat(
        WearTilePreviewElementFinder.hasPreviewElements(project, fileWithNoPreviews.virtualFile)
      )
      .isFalse()

    runBlocking {
      val previewElements =
        WearTilePreviewElementFinder.findPreviewElements(project, previewsTest.virtualFile)
      assertThat(previewElements).hasSize(6)

      previewElements.elementAt(0).let {
        assertThat(it.displaySettings.name).isEqualTo("tilePreview")
        assertThat(it.displaySettings.group).isNull()
        assertThat(it.displaySettings.showBackground).isTrue()
        assertThat(it.displaySettings.showDecoration).isFalse()
        assertThat(it.displaySettings.backgroundColor).isEqualTo("#ff000000")
        assertThat(it.configuration.device).isEqualTo("id:wearos_small_round")
        assertThat(it.configuration.locale).isNull()
        assertThat(it.configuration.fontScale).isEqualTo(1f)

        ReadAction.run<Throwable> {
          assertThat(it.previewBodyPsi?.psiRange?.range)
            .isEqualTo(previewsTest.textRange("tilePreview"))
          assertThat(it.previewElementDefinitionPsi?.element?.text).isEqualTo("@TilePreview")
        }
      }

      previewElements.elementAt(1).let {
        assertThat(it.displaySettings.name).isEqualTo("largeRoundTilePreview")
        assertThat(it.displaySettings.group).isNull()
        assertThat(it.displaySettings.showBackground).isTrue()
        assertThat(it.displaySettings.showDecoration).isFalse()
        assertThat(it.displaySettings.backgroundColor).isEqualTo("#ff000000")
        assertThat(it.configuration.device).isEqualTo("id:wearos_large_round")
        assertThat(it.configuration.locale).isNull()
        assertThat(it.configuration.fontScale).isEqualTo(1f)

        ReadAction.run<Throwable> {
          assertThat(it.previewBodyPsi?.psiRange?.range)
            .isEqualTo(previewsTest.textRange("largeRoundTilePreview"))
          assertThat(it.previewElementDefinitionPsi?.element?.text)
            .isEqualTo(
              """
            @TilePreview(
              device = WearDevices.LARGE_ROUND
            )
          """
                .trimIndent()
            )
        }
      }

      previewElements.elementAt(2).let {
        assertThat(it.displaySettings.name).isEqualTo("namedTilePreview - some name")
        assertThat(it.displaySettings.group).isNull()
        assertThat(it.displaySettings.showBackground).isTrue()
        assertThat(it.displaySettings.showDecoration).isFalse()
        assertThat(it.displaySettings.backgroundColor).isEqualTo("#ff000000")
        assertThat(it.configuration.device).isEqualTo("id:wearos_small_round")
        assertThat(it.configuration.locale).isNull()
        assertThat(it.configuration.fontScale).isEqualTo(1f)

        ReadAction.run<Throwable> {
          assertThat(it.previewBodyPsi?.psiRange?.range)
            .isEqualTo(previewsTest.textRange("namedTilePreview"))
          assertThat(it.previewElementDefinitionPsi?.element?.text)
            .isEqualTo(
              """
            @TilePreview(
              name = "some name"
            )
          """
                .trimIndent()
            )
        }
      }

      previewElements.elementAt(3).let {
        assertThat(it.displaySettings.name).isEqualTo("tilePreviewWithGroup")
        assertThat(it.displaySettings.group).isEqualTo("some group")
        assertThat(it.displaySettings.showBackground).isTrue()
        assertThat(it.displaySettings.showDecoration).isFalse()
        assertThat(it.displaySettings.backgroundColor).isEqualTo("#ff000000")
        assertThat(it.configuration.device).isEqualTo("id:wearos_square")
        assertThat(it.configuration.locale).isNull()
        assertThat(it.configuration.fontScale).isEqualTo(1f)

        ReadAction.run<Throwable> {
          assertThat(it.previewBodyPsi?.psiRange?.range)
            .isEqualTo(previewsTest.textRange("tilePreviewWithGroup"))
          assertThat(it.previewElementDefinitionPsi?.element?.text)
            .isEqualTo(
              """
            @TilePreview(
              group = "some group",
              device = WearDevices.SQUARE
            )
          """
                .trimIndent()
            )
        }
      }
      previewElements.elementAt(4).let {
        assertThat(it.displaySettings.name).isEqualTo("tilePreviewWithLocale")
        assertThat(it.displaySettings.group).isNull()
        assertThat(it.displaySettings.showBackground).isTrue()
        assertThat(it.displaySettings.showDecoration).isFalse()
        assertThat(it.displaySettings.backgroundColor).isEqualTo("#ff000000")
        assertThat(it.configuration.device).isEqualTo("id:wearos_small_round")
        assertThat(it.configuration.locale).isEqualTo(Locale.create("fr"))
        assertThat(it.configuration.fontScale).isEqualTo(1f)

        ReadAction.run<Throwable> {
          assertThat(it.previewBodyPsi?.psiRange?.range)
            .isEqualTo(previewsTest.textRange("tilePreviewWithLocale"))
          assertThat(it.previewElementDefinitionPsi?.element?.text)
            .isEqualTo(
              """
           @TilePreview(
             locale = "fr"
           )
         """
                .trimIndent()
            )
        }
      }
      previewElements.elementAt(5).let {
        assertThat(it.displaySettings.name).isEqualTo("tilePreviewWithFontScale")
        assertThat(it.displaySettings.group).isNull()
        assertThat(it.displaySettings.showBackground).isTrue()
        assertThat(it.displaySettings.showDecoration).isFalse()
        assertThat(it.displaySettings.backgroundColor).isEqualTo("#ff000000")
        assertThat(it.configuration.device).isEqualTo("id:wearos_small_round")
        assertThat(it.configuration.locale).isNull()
        assertThat(it.configuration.fontScale).isEqualTo(1.2f)

        ReadAction.run<Throwable> {
          assertThat(it.previewBodyPsi?.psiRange?.range)
            .isEqualTo(previewsTest.textRange("tilePreviewWithFontScale"))
          assertThat(it.previewElementDefinitionPsi?.element?.text)
            .isEqualTo(
              """
           @TilePreview(
             fontScale = 1.2f
           )
         """
                .trimIndent()
            )
        }
      }
    }
  }
}

private fun PsiFile.textRange(methodName: String): TextRange {
  return ReadAction.compute<TextRange, Throwable> {
    toUElementOfType<UFile>()?.method(methodName)?.uastBody?.sourcePsi?.textRange!!
  }
}

private fun UFile.declaredMethods(): Sequence<UMethod> =
  classes.asSequence().flatMap { it.methods.asSequence() }

private fun UFile.method(name: String): UMethod? =
  declaredMethods().filter { it.name == name }.singleOrNull()
