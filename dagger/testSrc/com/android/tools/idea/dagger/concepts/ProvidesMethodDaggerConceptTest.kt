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
package com.android.tools.idea.dagger.concepts

import com.android.tools.idea.dagger.addDaggerAndHiltClasses
import com.android.tools.idea.dagger.index.IndexValue
import com.android.tools.idea.dagger.index.psiwrappers.DaggerIndexMethodWrapper
import com.android.tools.idea.dagger.index.psiwrappers.DaggerIndexPsiWrapper
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.findParentElement
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.util.projectScope
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@RunsInEdt
class ProvidesMethodDaggerConceptTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory().onEdt()

  private lateinit var myFixture: CodeInsightTestFixture
  private lateinit var myProject: Project

  @Before
  fun setup() {
    myFixture = projectRule.fixture
    myProject = myFixture.project
  }

  private fun runIndexer(wrapper: DaggerIndexMethodWrapper): Map<String, Set<IndexValue>> =
    mutableMapOf<String, MutableSet<IndexValue>>().also { indexEntries ->
      ProvidesMethodDaggerConcept.indexers.methodIndexers.forEach {
        it.addIndexEntries(wrapper, indexEntries)
      }
    }

  @Test
  fun indexer_moduleClassWithoutProvides() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import dagger.Module

        @Module
        interface HeaterModule {
          fun provideHeater(electricHeater: ElectricHeater) : Heater {}
        }
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("provide|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries).isEmpty()
  }

  @Test
  fun indexer_providesMethodWithoutModule() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import dagger.Provides

        interface NotAModule {
          @Provides
          fun provideHeater(electricHeater: ElectricHeater) : Heater {}
        }
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("provide|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries).isEmpty()
  }

  @Test
  fun indexer_providesMethodOutsideClass() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import dagger.Provides

        @Provides
        fun provideHeater(electricHeater: ElectricHeater) : Heater {}
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("provide|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries).isEmpty()
  }

  @Test
  fun indexer_providesMethod() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import dagger.Module
        import dagger.Provides

        @Module
        interface HeaterModule {
          @Provides
          fun provideHeater(electricHeater: ElectricHeater, electricHeater2: ElectricHeater) : Heater {}
        }
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("provide|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries)
      .containsExactly(
        "Heater",
        setOf(ProvidesMethodIndexValue(HEATER_MODULE_ID, "provideHeater")),
        "ElectricHeater",
        setOf(
          ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "provideHeater", "electricHeater"),
          ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "provideHeater", "electricHeater2")
        ),
      )
  }

  @Test
  fun indexer_bindsMethod() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import dagger.Binds
        import dagger.Module

        @Module
        abstract class HeaterModule {
          @Binds
          abstract fun bindHeater(electricHeater: ElectricHeater) : Heater
        }
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("bind|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries)
      .containsExactly(
        "Heater",
        setOf(ProvidesMethodIndexValue(HEATER_MODULE_ID, "bindHeater")),
        "ElectricHeater",
        setOf(
          ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "bindHeater", "electricHeater"),
        ),
      )
  }

  @Test
  fun indexer_providesMethodOnCompanion() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import dagger.Module
        import dagger.Provides

        @Module
        interface HeaterModule {
          companion object {
            @Provides
            fun provideHeater(electricHeater: ElectricHeater, electricHeater2: ElectricHeater) : Heater {}
          }
        }
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("provide|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries)
      .containsExactly(
        "Heater",
        setOf(ProvidesMethodIndexValue(HEATER_MODULE_COMPANION_ID, "provideHeater")),
        "ElectricHeater",
        setOf(
          ProvidesMethodParameterIndexValue(
            HEATER_MODULE_COMPANION_ID,
            "provideHeater",
            "electricHeater"
          ),
          ProvidesMethodParameterIndexValue(
            HEATER_MODULE_COMPANION_ID,
            "provideHeater",
            "electricHeater2"
          )
        ),
      )
  }

  @Test
  fun indexer_wrongProvidesAnnotation() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import dagger.Module
        import com.other.Provides

        @Module
        interface HeaterModule {
          @Provides
          fun provideHeater(electricHeater: ElectricHeater, electricHeater2: ElectricHeater) : Heater {}
        }
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("provide|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries).isEmpty()
  }

  @Test
  fun indexer_wrongModuleAnnotation() {
    val psiFile =
      myFixture.configureByText(
        KotlinFileType.INSTANCE,
        // language=kotlin
        """
        package com.example

        import com.other.Module
        import dagger.Provides

        @Module
        interface HeaterModule {
          @Provides
          fun provideHeater(electricHeater: ElectricHeater, electricHeater2: ElectricHeater) : Heater {}
        }
        """
          .trimIndent()
      ) as KtFile

    val element: KtFunction = myFixture.findParentElement("provide|Heater")
    val entries = runIndexer(DaggerIndexPsiWrapper.KotlinFactory(psiFile).of(element))

    assertThat(entries).isEmpty()
  }

  @Test
  fun providesMethodIndexValue_serialization() {
    val indexValue = ProvidesMethodIndexValue(HEATER_MODULE_ID, "b")
    assertThat(serializeAndDeserializeIndexValue(indexValue)).isEqualTo(indexValue)
  }

  @Test
  fun providesMethodIndexValue_resolveToPsiElements_kotlin() {
    addDaggerAndHiltClasses(myFixture)

    myFixture.configureByText(
      KotlinFileType.INSTANCE,
      // language=kotlin
      """
      package com.example

      import dagger.Binds
      import dagger.Module
      import dagger.Provides

      interface Heater {}
      class ElectricHeater : Heater {}

      @Module
      interface HeaterModule {
        @Provides
        fun provideHeater(electricHeater: ElectricHeater) : Heater {}

        fun dontProvideHeater(electricHeater: ElectricHeater) : Heater {}

        @Binds
        fun bindHeater(electricHeater: ElectricHeater) : Heater {}
      }
      """
        .trimIndent()
    )

    val indexValue1 = ProvidesMethodIndexValue(HEATER_MODULE_ID, "provideHeater")
    val indexValue2 = ProvidesMethodIndexValue(HEATER_MODULE_ID, "dontProvideHeater")
    val indexValue3 = ProvidesMethodIndexValue(HEATER_MODULE_ID, "bindHeater")

    val provideHeaterFunction: KtFunction = myFixture.findParentElement("fun provideHe|ater")
    val bindHeaterFunction: KtFunction = myFixture.findParentElement("fun bindHe|ater")

    assertThat(indexValue1.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ProviderDaggerElement(provideHeaterFunction))
    assertThat(indexValue2.resolveToDaggerElements(myProject, myProject.projectScope())).isEmpty()
    assertThat(indexValue3.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ProviderDaggerElement(bindHeaterFunction))
  }

  @Test
  fun providesMethodIndexValueOnCompanion_resolveToPsiElements_kotlin() {
    addDaggerAndHiltClasses(myFixture)

    myFixture.configureByText(
      KotlinFileType.INSTANCE,
      // language=kotlin
      """
      package com.example

      import dagger.Binds
      import dagger.Module
      import dagger.Provides

      interface Heater {}
      class ElectricHeater : Heater {}

      @Module
      interface HeaterModule {
        companion object {
          @Provides
          fun provideHeater(electricHeater: ElectricHeater) : Heater {}

          fun dontProvideHeater(electricHeater: ElectricHeater) : Heater {}

          @Binds
          fun bindHeater(electricHeater: ElectricHeater) : Heater {}
        }
      }
      """
        .trimIndent()
    )

    val indexValue1 = ProvidesMethodIndexValue(HEATER_MODULE_COMPANION_ID, "provideHeater")
    val indexValue2 = ProvidesMethodIndexValue(HEATER_MODULE_COMPANION_ID, "dontProvideHeater")
    val indexValue3 = ProvidesMethodIndexValue(HEATER_MODULE_COMPANION_ID, "bindHeater")

    val provideHeaterFunction: KtFunction = myFixture.findParentElement("fun provideHe|ater")
    val bindHeaterFunction: KtFunction = myFixture.findParentElement("fun bindHe|ater")

    assertThat(indexValue1.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ProviderDaggerElement(provideHeaterFunction))
    assertThat(indexValue2.resolveToDaggerElements(myProject, myProject.projectScope())).isEmpty()
    assertThat(indexValue3.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ProviderDaggerElement(bindHeaterFunction))
  }

  @Test
  fun providesMethodIndexValue_resolveToPsiElements_java() {
    addDaggerAndHiltClasses(myFixture)

    myFixture.configureByText(
      JavaFileType.INSTANCE,
      // language=java
      """
      package com.example;

      import dagger.Binds;
      import dagger.Module;
      import dagger.Provides;

      public interface Heater {}
      public class ElectricHeater implements Heater {}

      @Module
      public interface HeaterModule {
        @Provides
        Heater provideHeater(ElectricHeater electricHeater) {}

        Heater dontProvideHeater(ElectricHeater electricHeater) {}

        @Binds
        Heater bindHeater(ElectricHeater electricHeater) {}
      }
      """
        .trimIndent()
    )

    val indexValue1 = ProvidesMethodIndexValue(HEATER_MODULE_ID, "provideHeater")
    val indexValue2 = ProvidesMethodIndexValue(HEATER_MODULE_ID, "dontProvideHeater")
    val indexValue3 = ProvidesMethodIndexValue(HEATER_MODULE_ID, "bindHeater")

    val provideHeaterFunction: PsiMethod = myFixture.findParentElement("Heater provideHe|ater")
    val bindHeaterFunction: PsiMethod = myFixture.findParentElement("Heater bindHe|ater")

    assertThat(indexValue1.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ProviderDaggerElement(provideHeaterFunction))
    assertThat(indexValue2.resolveToDaggerElements(myProject, myProject.projectScope())).isEmpty()
    assertThat(indexValue3.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ProviderDaggerElement(bindHeaterFunction))
  }

  @Test
  fun providesMethodParameterIndexValue_serialization() {
    val indexValue = ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "b", "c")
    assertThat(serializeAndDeserializeIndexValue(indexValue)).isEqualTo(indexValue)
  }

  @Test
  fun providesMethodParameterIndexValue_resolveToPsiElements_kotlin() {
    addDaggerAndHiltClasses(myFixture)

    myFixture.configureByText(
      KotlinFileType.INSTANCE,
      // language=kotlin
      """
      package com.example

      import dagger.Binds
      import dagger.Module
      import dagger.Provides

      interface Heater {}
      class ElectricHeater : Heater {}

      @Module
      interface HeaterModule {
        @Provides
        fun provideHeater(electricHeater: ElectricHeater) : Heater {}

        fun dontProvideHeater(electricHeater: ElectricHeater) : Heater {}

        @Binds
        fun bindHeater(electricHeater: ElectricHeater) : Heater {}
      }
      """
        .trimIndent()
    )

    val indexValue1 =
      ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "provideHeater", "electricHeater")
    val indexValue2 =
      ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "dontProvideHeater", "electricHeater")
    val indexValue3 =
      ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "bindHeater", "electricHeater")

    val electricHeaterProvidesParameter: KtParameter =
      myFixture.findParentElement("provideHeater(elect|ricHeater: ElectricHeater")

    val electricHeaterBindsParameter: KtParameter =
      myFixture.findParentElement("bindHeater(elect|ricHeater: ElectricHeater")

    assertThat(indexValue1.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ConsumerDaggerElement(electricHeaterProvidesParameter))
    assertThat(indexValue2.resolveToDaggerElements(myProject, myProject.projectScope())).isEmpty()
    assertThat(indexValue3.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ConsumerDaggerElement(electricHeaterBindsParameter))
  }

  @Test
  fun providesMethodParameterIndexValueOnCompanion_resolveToPsiElements_kotlin() {
    addDaggerAndHiltClasses(myFixture)

    myFixture.configureByText(
      KotlinFileType.INSTANCE,
      // language=kotlin
      """
      package com.example

      import dagger.Binds
      import dagger.Module
      import dagger.Provides

      interface Heater {}
      class ElectricHeater : Heater {}

      @Module
      interface HeaterModule {
        companion object {
          @Provides
          fun provideHeater(electricHeater: ElectricHeater) : Heater {}

          fun dontProvideHeater(electricHeater: ElectricHeater) : Heater {}

          @Binds
          fun bindHeater(electricHeater: ElectricHeater) : Heater {}
        }
      }
      """
        .trimIndent()
    )

    val indexValue1 =
      ProvidesMethodParameterIndexValue(
        HEATER_MODULE_COMPANION_ID,
        "provideHeater",
        "electricHeater"
      )
    val indexValue2 =
      ProvidesMethodParameterIndexValue(
        HEATER_MODULE_COMPANION_ID,
        "dontProvideHeater",
        "electricHeater"
      )
    val indexValue3 =
      ProvidesMethodParameterIndexValue(HEATER_MODULE_COMPANION_ID, "bindHeater", "electricHeater")

    val electricHeaterProvidesParameter: KtParameter =
      myFixture.findParentElement("provideHeater(elect|ricHeater: ElectricHeater")

    val electricHeaterBindsParameter: KtParameter =
      myFixture.findParentElement("bindHeater(elect|ricHeater: ElectricHeater")

    assertThat(indexValue1.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ConsumerDaggerElement(electricHeaterProvidesParameter))
    assertThat(indexValue2.resolveToDaggerElements(myProject, myProject.projectScope())).isEmpty()
    assertThat(indexValue3.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ConsumerDaggerElement(electricHeaterBindsParameter))
  }

  @Test
  fun providesMethodParameterIndexValue_resolveToPsiElements_java() {
    addDaggerAndHiltClasses(myFixture)

    myFixture.configureByText(
      JavaFileType.INSTANCE,
      // language=java
      """
      package com.example;

      import dagger.Binds;
      import dagger.Module;
      import dagger.Provides;

      public interface Heater {}
      public class ElectricHeater implements Heater {}

      @Module
      public interface HeaterModule {
        @Provides
        Heater provideHeater(ElectricHeater electricHeater) {}

        Heater dontProvideHeater(ElectricHeater electricHeater) {}

        @Binds
        Heater bindHeater(ElectricHeater electricHeater) {}
      }
      """
        .trimIndent()
    )

    val indexValue1 =
      ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "provideHeater", "electricHeater")
    val indexValue2 =
      ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "dontProvideHeater", "electricHeater")
    val indexValue3 =
      ProvidesMethodParameterIndexValue(HEATER_MODULE_ID, "bindHeater", "electricHeater")

    val electricHeaterProvidesParameter: PsiParameter =
      myFixture.findParentElement("provideHeater(ElectricHeater elec|tricHeater")

    val electricHeaterBindsParameter: PsiParameter =
      myFixture.findParentElement("bindHeater(ElectricHeater elec|tricHeater")

    assertThat(indexValue1.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ConsumerDaggerElement(electricHeaterProvidesParameter))
    assertThat(indexValue2.resolveToDaggerElements(myProject, myProject.projectScope())).isEmpty()
    assertThat(indexValue3.resolveToDaggerElements(myProject, myProject.projectScope()).single())
      .isEqualTo(ConsumerDaggerElement(electricHeaterBindsParameter))
  }

  companion object {
    private val HEATER_MODULE_ID = ClassId.fromString("com/example/HeaterModule")
    private val HEATER_MODULE_COMPANION_ID =
      ClassId.fromString("com/example/HeaterModule.Companion")
  }
}
