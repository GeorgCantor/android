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
package com.android.tools.idea.lang.typedef

import com.android.tools.idea.lang.typedef.TypeDef.Companion.ANNOTATION_FQ_NAMES
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.findParentElement
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests the [TypeDef] class. */
@RunWith(JUnit4::class)
class TypeDefTest {
  @get:Rule
  val projectRule = AndroidProjectRule.onDisk().onEdt()

  private val fixture by lazy { projectRule.fixture as JavaCodeInsightTestFixture }

  @Test
  fun typeStringsAreCorrect() {
    assertThat(TypeDef.Type.INT.annotationFqName).isEqualTo("androidx.annotation.IntDef")
    assertThat(TypeDef.Type.LONG.annotationFqName).isEqualTo("androidx.annotation.LongDef")
    assertThat(TypeDef.Type.STRING.annotationFqName).isEqualTo("androidx.annotation.StringDef")
    assertThat(TypeDef.Type.INT.javaTypeName).isEqualTo("int")
    assertThat(TypeDef.Type.LONG.javaTypeName).isEqualTo("long")
    assertThat(TypeDef.Type.STRING.javaTypeName).isEqualTo("String")
    assertThat(TypeDef.Type.INT.kotlinTypeName).isEqualTo("Int")
    assertThat(TypeDef.Type.LONG.kotlinTypeName).isEqualTo("Long")
    assertThat(TypeDef.Type.STRING.kotlinTypeName).isEqualTo("String")
  }

  @Test
  fun typeFromFqName() {
    assertThat(ANNOTATION_FQ_NAMES["androidx.annotation.IntDef"]).isEqualTo(TypeDef.Type.INT)
    assertThat(ANNOTATION_FQ_NAMES["androidx.annotation.LongDef"]).isEqualTo(TypeDef.Type.LONG)
    assertThat(ANNOTATION_FQ_NAMES["androidx.annotation.StringDef"]).isEqualTo(TypeDef.Type.STRING)
    assertThat(ANNOTATION_FQ_NAMES["com.google.IntDef"]).isNull()
    assertThat(ANNOTATION_FQ_NAMES["totally.complete.garbage"]).isNull()
  }

  @Test
  @RunsInEdt
  fun typeDefConstruction() {
    createJavaTypeDef()
    createKotlinTypeDef()
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_java_prioritizes() {
    val javaTypeDef = createJavaTypeDef()
    val lookupElement = LookupElementBuilder.create(javaTypeDef.values.last())

    javaTypeDef.decorateAndPrioritize(lookupElement).let {
      assertThat(it).isNotNull()
      assertThat(it).isInstanceOf(PrioritizedLookupElement::class.java)
      assertThat((it as PrioritizedLookupElement<*>).priority).isEqualTo(TypeDef.HIGH_PRIORITY)
    }
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_java_setsLookupStrings() {
    val javaTypeDef = createJavaTypeDef()
    val lookupElement = LookupElementBuilder.create(javaTypeDef.values.last())

    assertThat(javaTypeDef.decorateAndPrioritize(lookupElement)?.allLookupStrings).containsExactly(
      "KnocksSongs", "CLASSIC"
    )
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_java_decorates() {
    val javaTypeDef = createJavaTypeDef()
    val lookupElement = LookupElementBuilder.create(javaTypeDef.values.last())

    val presentation = LookupElementPresentation()
    javaTypeDef.decorateAndPrioritize(lookupElement)?.renderElement(presentation)

    with(presentation) {
      assertThat(icon).isEqualTo(javaTypeDef.values.last().getIcon(0))
      assertThat(itemText).isEqualTo("CLASSIC")
      assertThat(isItemTextBold).isTrue()
      assertThat(typeText).isEqualTo("@KnocksSong")
    }
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_java_returnsNullIfNotAValue() {
    val javaTypeDef = createJavaTypeDef()
    val lookupElement = LookupElementBuilder.create(javaTypeDef.annotation)

    assertThat(javaTypeDef.decorateAndPrioritize(lookupElement)).isNull()
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_kotlin_prioritizes() {
    val kotlinTypeDef = createKotlinTypeDef()
    val lookupElement = LookupElementBuilder.create(kotlinTypeDef.values.last())

    kotlinTypeDef.decorateAndPrioritize(lookupElement).let {
      assertThat(it).isNotNull()
      assertThat(it).isInstanceOf(PrioritizedLookupElement::class.java)
      assertThat((it as PrioritizedLookupElement<*>).priority).isEqualTo(TypeDef.HIGH_PRIORITY)
    }
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_kotlin_setsLookupStrings() {
    val kotlinTypeDef = createKotlinTypeDef()
    val lookupElement = LookupElementBuilder.create(kotlinTypeDef.values.first())

    assertThat(kotlinTypeDef.decorateAndPrioritize(lookupElement)?.allLookupStrings).containsExactly(
      "RegrettesSong", "Companion", "BARELY_ON_MY_MIND"
    )
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_kotlin_decorates() {
    val kotlinTypeDef = createKotlinTypeDef()
    val lookupElement = LookupElementBuilder.create(kotlinTypeDef.values.first())

    val presentation = LookupElementPresentation()
    kotlinTypeDef.decorateAndPrioritize(lookupElement)?.renderElement(presentation)

    with(presentation) {
      assertThat(icon).isEqualTo(kotlinTypeDef.values.last().getIcon(0))
      assertThat(itemText).isEqualTo("BARELY_ON_MY_MIND")
      assertThat(isItemTextBold).isTrue()
      assertThat(typeText).isEqualTo("@RegrettesSong")
    }
  }

  @Test
  @RunsInEdt
  fun decorateAndPrioritize_kotlin_returnsNullIfNotAValue() {
    val kotlinTypeDef = createKotlinTypeDef()
    val lookupElement = LookupElementBuilder.create(kotlinTypeDef.annotation)

    assertThat(kotlinTypeDef.decorateAndPrioritize(lookupElement)).isNull()
  }

  private fun createKotlinTypeDef(): TypeDef {
    val file = fixture.addFileToProject(
      "/src/com/typedef/RegrettesSong.kt",
      // language=kotlin
      """
        package src.com.typedef
        annotation class RegrettesSong {
          companion object {
            const val BARELY_ON_MY_MIND = 0
            const val MONDAY = 1
            const val I_DARE_YOU = 2
          }
        }
      """.trimIndent()
    )
    fixture.openFileInEditor(file.virtualFile)
    val annotation = fixture.findParentElement<KtClass>("Regrettes|Song")
    val firstValue = fixture.findParentElement<KtProperty>("BAR|ELY")
    val secondValue = fixture.findParentElement<KtProperty>("MON|DAY")
    val thirdValue = fixture.findParentElement<KtProperty>("DA|RE")
    return TypeDef(annotation, listOf(firstValue, secondValue, thirdValue), TypeDef.Type.INT)
  }

  private fun createJavaTypeDef(): TypeDef {
    val file = fixture.addFileToProject(
      "/src/com/typedef/KnocksSongs.java",
      // language=java
      """
      package src.com.typedef;
      public class KnocksSongs {
        public static final int SLOW_SONG = 0;
        public static final int RIDE_OR_DIE = 1;
        public static final int CLASSIC = 2;
        public @interface KnocksSong {}
      }
      """.trimIndent()
    )
    fixture.openFileInEditor(file.virtualFile)
    val annotation = fixture.findParentElement<PsiClass>("Knocks|Song ")
    val firstValue = fixture.findParentElement<PsiField>("SL|OW")
    val secondValue = fixture.findParentElement<PsiField>("RI|DE")
    val thirdValue = fixture.findParentElement<PsiField>("CLAS|SIC")
    return TypeDef(annotation, listOf(firstValue, secondValue, thirdValue), TypeDef.Type.INT)
  }
}
