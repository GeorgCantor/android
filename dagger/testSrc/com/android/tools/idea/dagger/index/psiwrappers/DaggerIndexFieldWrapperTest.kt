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
package com.android.tools.idea.dagger.index.psiwrappers

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.findParentElement
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class DaggerIndexFieldWrapperTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  private lateinit var myFixture: CodeInsightTestFixture

  @Before
  fun setup() {
    myFixture = projectRule.fixture
  }

  @Test
  fun kotlinProperty() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
      package com.example

      class Foo {
        lateinit var bar: Baz
      }
      """
          .trimIndent(),
      ) as KtFile

    val element: KtProperty = myFixture.findParentElement("b|ar")
    val wrapper = DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element)

    assertThat(wrapper.getSimpleName()).isEqualTo("bar")
    assertThat(wrapper.getContainingClass()?.getClassId()?.asString()).isEqualTo("com/example/Foo")
    assertThat(wrapper.getType()?.getSimpleName()).isEqualTo("Baz")
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.MODULE)).isFalse()
  }

  @Test
  fun kotlinPropertyWithoutType() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
      package com.example

      class Foo {
        val bar = resultOfSomeFunction()
      }
      """
          .trimIndent(),
      ) as KtFile

    val element: KtProperty = myFixture.findParentElement("b|ar")
    val wrapper = DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element)

    assertThat(wrapper.getType()).isNull()
  }

  @Test
  fun kotlinPropertyWithAnnotations() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
      package com.example

      import dagger.*

      class Foo {

        @Binds
        @Module()
        @Component(true)
        val bar: Baz
      }
      """
          .trimIndent(),
      ) as KtFile

    val element: KtProperty = myFixture.findParentElement("b|ar")
    val wrapper = DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element)

    assertThat(wrapper.getSimpleName()).isEqualTo("bar")
    assertThat(wrapper.getContainingClass()?.getClassId()?.asString()).isEqualTo("com/example/Foo")
    assertThat(wrapper.getType()?.getSimpleName()).isEqualTo("Baz")
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.BINDS)).isTrue()
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.MODULE)).isTrue()
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.COMPONENT)).isTrue()
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.PROVIDES)).isFalse()
  }

  @Test
  fun javaField() {
    val psiFile =
      myFixture.configureByText(
        JavaFileType.INSTANCE,
        // language=java
        """
      package com.example;
      public class Foo {
        private Baz bar;
      }
      """
          .trimIndent(),
      ) as PsiJavaFile

    val element: PsiField = myFixture.findParentElement("b|ar")
    val wrapper = DaggerIndexPsiWrapper.JavaFactory(psiFile).of(element)

    assertThat(wrapper.getSimpleName()).isEqualTo("bar")
    assertThat(wrapper.getContainingClass()?.getClassId()?.asString()).isEqualTo("com/example/Foo")
    assertThat(wrapper.getType()?.getSimpleName()).isEqualTo("Baz")
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.MODULE)).isFalse()
  }

  @Test
  fun javaFieldWithAnnotations() {
    val psiFile =
      myFixture.configureByText(
        JavaFileType.INSTANCE,
        // language=java
        """
      package com.example;

      import dagger.*;

      public class Foo {

        @Binds
        @Module()
        @Component(true)
        private Baz bar;
      }
      """
          .trimIndent(),
      ) as PsiJavaFile

    val element: PsiField = myFixture.findParentElement("b|ar")
    val wrapper = DaggerIndexPsiWrapper.JavaFactory(psiFile).of(element)

    assertThat(wrapper.getSimpleName()).isEqualTo("bar")
    assertThat(wrapper.getContainingClass()?.getClassId()?.asString()).isEqualTo("com/example/Foo")
    assertThat(wrapper.getType()?.getSimpleName()).isEqualTo("Baz")
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.BINDS)).isTrue()
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.MODULE)).isTrue()
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.COMPONENT)).isTrue()
    assertThat(wrapper.getIsAnnotatedWith(DaggerAnnotation.PROVIDES)).isFalse()
  }
}
