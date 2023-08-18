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
package com.android.tools.idea.res.completion

import com.android.flags.junit.FlagRule
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.addManifest
import com.android.tools.idea.testing.caret
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.ui.JBColor
import com.intellij.util.ui.ImageUtil
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.awt.image.BufferedImage
import javax.swing.Icon

private val COLORS = mapOf("red" to JBColor.RED, "green" to JBColor.GREEN, "blue" to JBColor.BLUE)

/** Tests for [ResourceCompletionContributor]. */
@RunWith(JUnit4::class)
@RunsInEdt
class ResourceCompletionContributorTest {
  @get:Rule
  val projectRule = AndroidProjectRule.withSdk().onEdt()

  @get:Rule
  val restoreFlagRule = FlagRule(StudioFlags.RENDER_DRAWABLES_IN_AUTOCOMPLETE_ENABLED)

  private val fixture: CodeInsightTestFixture by lazy { projectRule.fixture }

  @Before
  fun setUp() {
    StudioFlags.RENDER_DRAWABLES_IN_AUTOCOMPLETE_ENABLED.override(true)
    addManifest(fixture)
    val fileName = "res/drawable/my_great_%s_icon.xml"
    // language=XML
    val circle = """
      <vector android:height="24dp" android:width="24dp" android:viewportHeight="24" android:viewportWidth="24" android:tint="#%X"
          xmlns:android="http://schemas.android.com/apk/res/android">
        <path android:fillColor="@android:color/white" android:pathData="M12,2C6.47,2 2,6.47 2,12s4.47,10 10,10 10,-4.47 10,-10S17.53,2 12,2z"/>
      </vector>
    """.trimIndent()

    COLORS.forEach { fixture.addFileToProject(fileName.format(it.key), circle.format(it.value.rgb)) }
    projectRule.projectRule.waitForResourceRepositoryUpdates()
  }

  @Test
  fun drawable_completion_java() {
    val file = fixture.addFileToProject(
      "/src/com/example/Foo.java",
      // language=java
      """
       package com.example;
       public class Foo {
         public void example() {
           int foo = R.drawable.my_gre${caret}
         }
       }
       """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    val results = fixture.completeBasic()
    assertThat(results).hasLength(COLORS.size)
    val icons = results.mapNotNull { it.renderedIcon() }.distinct()
    assertThat(icons).hasSize(COLORS.size)
    val expectedColors = results.map { lookupElement ->
      COLORS.entries.first { lookupElement.lookupString.contains(it.key) }.value.rgb
    }
    assertThat(icons.map { it.sampleMiddlePoint() }).containsExactlyElementsIn(expectedColors).inOrder()
  }

  @Test
  fun drawable_completion_kotlin() {
    val file = fixture.addFileToProject(
      "/src/com/example/Foo.kt",
      // language=kotlin
      """
       package com.example
       class Foo {
         fun example() {
           val foo = R.drawable.my_gre${caret}
         }
       }
       """.trimIndent())
    fixture.configureFromExistingVirtualFile(file.virtualFile)

    val results = fixture.completeBasic()
    assertThat(results).hasLength(COLORS.size)
    val icons = results.mapNotNull { it.renderedIcon() }.distinct()
    assertThat(icons).hasSize(COLORS.size)
    val expectedColors = results.map { lookupElement ->
      COLORS.entries.first { lookupElement.lookupString.contains(it.key) }.value.rgb
    }
    assertThat(icons.map { it.sampleMiddlePoint() }).containsExactlyElementsIn(expectedColors).inOrder()
  }

  private fun LookupElement.renderedIcon() : Icon? {
    val pres = LookupElementPresentation()
    renderElement(pres)
    return pres.icon
  }

  private fun Icon.sampleMiddlePoint(): Int {
    val bufferedImage = ImageUtil.createImage(iconWidth, iconHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = bufferedImage.createGraphics()
    paintIcon(null, graphics, 0, 0)
    graphics.dispose()
    return bufferedImage.getRGB(iconWidth / 2, iconHeight / 2)
  }
}
