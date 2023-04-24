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
package com.android.tools.idea.compose.preview

import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.common.surface.DesignSurfaceListener
import com.android.tools.idea.compose.ComposeProjectRule
import com.android.tools.idea.compose.pickers.preview.property.referenceDeviceIds
import com.android.tools.idea.compose.preview.navigation.ComposePreviewNavigationHandler
import com.android.tools.idea.concurrency.AndroidDispatchers.workerThread
import com.android.tools.idea.editors.build.ProjectStatus
import com.android.tools.idea.editors.fast.FastPreviewManager
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.ProjectSystemService
import com.android.tools.idea.projectsystem.TestProjectSystem
import com.android.tools.idea.testing.addFileToProjectAndInvalidate
import com.android.tools.idea.uibuilder.editor.multirepresentation.PreferredVisibility
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.assertInstanceOf
import com.intellij.testFramework.runInEdtAndWait
import java.util.UUID
import java.util.concurrent.CountDownLatch
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

internal class TestComposePreviewView(override val mainSurface: NlDesignSurface) :
  ComposePreviewView {
  override val component: JComponent
    get() = JPanel()
  override var bottomPanel: JComponent? = null
  override val isMessageBeingDisplayed: Boolean = false
  override var hasContent: Boolean = true
  override var hasRendered: Boolean = true

  override fun updateNotifications(parentEditor: FileEditor) {}

  override fun updateVisibilityAndNotifications() {}

  override fun updateProgress(message: String) {}

  override fun onRefreshCancelledByTheUser() {}

  override fun onRefreshCompleted() {}

  override fun onLayoutlibNativeCrash(onLayoutlibReEnable: () -> Unit) {}
}

class ComposePreviewRepresentationTest {
  private val logger = Logger.getInstance(ComposePreviewRepresentationTest::class.java)

  @get:Rule
  val projectRule =
    ComposeProjectRule(
      previewAnnotationPackage = "androidx.compose.ui.tooling.preview",
      composableAnnotationPackage = "androidx.compose.runtime"
    )
  private val project
    get() = projectRule.project
  private val fixture
    get() = projectRule.fixture

  @Before
  fun setup() {
    Logger.getInstance(ComposePreviewRepresentation::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(FastPreviewManager::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(ProjectStatus::class.java).setLevel(LogLevel.ALL)
    logger.info("setup")
    val testProjectSystem = TestProjectSystem(project)
    runInEdtAndWait { testProjectSystem.useInTests() }
    logger.info("setup complete")
  }

  @After
  fun tearDown() {
    StudioFlags.NELE_ATF_FOR_COMPOSE.clearOverride()
  }

  @Test
  fun testPreviewInitialization() =
    runBlocking(workerThread) {
      val composeTest = runWriteActionAndWait {
        fixture.addFileToProjectAndInvalidate(
          "Test.kt",
          // language=kotlin
          """
        import androidx.compose.ui.tooling.preview.Devices
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.runtime.Composable

        @Composable
        @Preview
        fun Preview1() {
        }

        @Composable
        @Preview(name = "preview2", apiLevel = 12, group = "groupA", showBackground = true)
        fun Preview2() {
        }
      """
            .trimIndent()
        )
      }

      val navigationHandler = ComposePreviewNavigationHandler()
      val mainSurface =
        NlDesignSurface.builder(project, fixture.testRootDisposable)
          .setNavigationHandler(navigationHandler)
          .build()
      val modelRenderedLatch = CountDownLatch(2)

      mainSurface.addListener(
        object : DesignSurfaceListener {
          override fun modelChanged(surface: DesignSurface<*>, model: NlModel?) {
            val id = UUID.randomUUID().toString().substring(0, 5)
            logger.info("modelChanged ($id)")
            (surface.getSceneManager(model!!) as? LayoutlibSceneManager)?.addRenderListener {
              logger.info("renderListener ($id)")
              modelRenderedLatch.countDown()
            }
          }
        }
      )

      val composeView = TestComposePreviewView(mainSurface)
      val preview =
        ComposePreviewRepresentation(composeTest, PreferredVisibility.SPLIT) { _, _, _, _, _, _ ->
          composeView
        }
      Disposer.register(fixture.testRootDisposable, preview)
      withContext(Dispatchers.IO) {
        logger.info("compile")
        ProjectSystemService.getInstance(project).projectSystem.getBuildManager().compileProject()
        logger.info("activate")
        preview.onActivate()

        modelRenderedLatch.await()

        while (preview.status().isRefreshing || DumbService.getInstance(project).isDumb) kotlinx
          .coroutines
          .delay(250)
      }

      mainSurface.models.forEach { assertTrue(navigationHandler.defaultNavigationMap.contains(it)) }

      assertArrayEquals(
        arrayOf("groupA"),
        preview.availableGroups.map { it.displayName }.toTypedArray()
      )

      val status = preview.status()
      val debugStatus = preview.debugStatusForTesting()
      assertFalse(debugStatus.toString(), status.isOutOfDate)
      // Ensure the only warning message is the missing Android SDK message
      assertTrue(
        debugStatus.renderResult
          .flatMap { it.logger.messages }
          .none { !it.html.contains("No Android SDK found.") }
      )
      preview.onDeactivate()
    }

  @Ignore("b/252543742")
  @Test
  fun testUiCheckMode() {
    StudioFlags.NELE_ATF_FOR_COMPOSE.override(true)
    runBlocking(workerThread) {
      val composeTest = runWriteActionAndWait {
        fixture.addFileToProjectAndInvalidate(
          "Test.kt",
          // language=kotlin
          """
        import androidx.compose.ui.tooling.preview.Devices
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.runtime.Composable

        @Composable
        @Preview
        fun Preview1() {
        }

        @Composable
        @Preview(name = "preview2", apiLevel = 12, group = "groupA", showBackground = true)
        fun Preview2() {
        }
      """
            .trimIndent()
        )
      }

      val navigationHandler = ComposePreviewNavigationHandler()
      val mainSurface =
        NlDesignSurface.builder(project, fixture.testRootDisposable)
          .setNavigationHandler(navigationHandler)
          .build()
      val modelRenderedLatch = CountDownLatch(2)

      mainSurface.addListener(
        object : DesignSurfaceListener {
          override fun modelChanged(surface: DesignSurface<*>, model: NlModel?) {
            val id = UUID.randomUUID().toString().substring(0, 5)
            logger.info("modelChanged ($id)")
            (surface.getSceneManager(model!!) as? LayoutlibSceneManager)?.addRenderListener {
              logger.info("renderListener ($id)")
              modelRenderedLatch.countDown()
            }
          }
        }
      )

      val composeView = TestComposePreviewView(mainSurface)
      val preview =
        ComposePreviewRepresentation(composeTest, PreferredVisibility.SPLIT) { _, _, _, _, _, _ ->
          composeView
        }
      Disposer.register(fixture.testRootDisposable, preview)
      withContext(Dispatchers.IO) {
        logger.info("compile")
        ProjectSystemService.getInstance(project).projectSystem.getBuildManager().compileProject()
        logger.info("activate")
        preview.onActivate()

        modelRenderedLatch.await()

        while (preview.status().isRefreshing || DumbService.getInstance(project).isDumb) kotlinx
          .coroutines
          .delay(250)
      }
      assertInstanceOf<ComposePreviewRepresentation.DefaultPreviewElementProvider>(
        preview.previewElementProvider.previewProvider
      )

      val previewElements =
        mainSurface.models.mapNotNull { it.dataContext.getData(COMPOSE_PREVIEW_ELEMENT_INSTANCE) }
      val uiCheckElement = previewElements.first()

      preview.startUiCheckPreview(uiCheckElement)
      while (!preview.isUiCheckPreview) kotlinx.coroutines.delay(250)
      assertInstanceOf<ComposePreviewRepresentation.UiCheckPreviewElementProvider>(
        preview.previewElementProvider.previewProvider
      )

      assertTrue(preview.atfChecksEnabled)
      assert(preview.availableGroups.size == 1)
      assertEquals("Screen sizes", preview.availableGroups.first().displayName)
      val devices = mutableListOf<String>()
      mainSurface.models
        .mapNotNull { it.dataContext.getData(COMPOSE_PREVIEW_ELEMENT_INSTANCE) }
        .forEach {
          assertEquals(uiCheckElement.composableMethodFqn, it.composableMethodFqn)
          devices.add(it.configuration.deviceSpec)
        }
      assertThat(devices.sorted(), `is`(referenceDeviceIds.keys.sorted()))

      preview.stopUiCheckPreview()
      while (preview.isUiCheckPreview) kotlinx.coroutines.delay(250)
      assertInstanceOf<ComposePreviewRepresentation.DefaultPreviewElementProvider>(
        preview.previewElementProvider.previewProvider
      )

      mainSurface.models
        .mapNotNull { it.dataContext.getData(COMPOSE_PREVIEW_ELEMENT_INSTANCE) }
        .forEachIndexed { index, previewElement ->
          assertEquals(previewElements[index], previewElement)
        }

      preview.onDeactivate()
    }
  }
}
