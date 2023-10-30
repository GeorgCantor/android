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
package com.android.tools.idea.compose.gradle

import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.common.surface.SceneViewPeerPanel
import com.android.tools.idea.compose.gradle.preview.TestComposePreviewView
import com.android.tools.idea.compose.gradle.preview.displayName
import com.android.tools.idea.compose.preview.ComposePreviewRefreshManager
import com.android.tools.idea.compose.preview.ComposePreviewRepresentation
import com.android.tools.idea.compose.preview.TEST_DATA_PATH
import com.android.tools.idea.compose.preview.waitForSmartMode
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.concurrency.awaitStatus
import com.android.tools.idea.editors.build.ProjectStatus
import com.android.tools.idea.editors.fast.FastPreviewConfiguration
import com.android.tools.idea.editors.fast.FastPreviewManager
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.NamedExternalResource
import com.android.tools.idea.uibuilder.editor.multirepresentation.PreferredVisibility
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import javax.swing.JPanel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.RuleChain
import org.junit.runner.Description

private val DEFAULT_REFRESH_TIMEOUT = 10.seconds
private val DEFAULT_BUILD_AND_REFRESH_TIMEOUT = 40.seconds

/**
 * A [ComposeGradleProjectRule] that uses the whole Compose Preview machinery, except from its UI,
 * that is replaced by a [FakeUi] instance using a [TestComposePreviewView], which is a fair
 * approximation of the production UI.
 */
class ComposePreviewFakeUiGradleRule(
  projectPath: String,
  private val previewFilePath: String,
  testDataPath: String = TEST_DATA_PATH,
  kotlinVersion: String = DEFAULT_KOTLIN_VERSION,
  projectRule: AndroidGradleProjectRule = AndroidGradleProjectRule(),
  enableRenderQuality: Boolean = true
) : ComposeGradleProjectRule(projectPath, testDataPath, kotlinVersion, projectRule) {

  // The logger must be initialized later since at this point the logger framework is not ready yet
  lateinit var logger: Logger
    private set

  lateinit var psiMainFile: PsiFile
    private set

  lateinit var composePreviewRepresentation: ComposePreviewRepresentation
    private set

  lateinit var previewView: TestComposePreviewView
    private set

  lateinit var fakeUi: FakeUi
    private set

  private lateinit var refreshManager: ComposePreviewRefreshManager

  override val delegate: RuleChain =
    super.delegate.around(
      object : NamedExternalResource() {
        override fun before(description: Description) {
          StudioFlags.COMPOSE_PREVIEW_RENDER_QUALITY.override(enableRenderQuality)
          setUpPreview()
        }

        override fun after(description: Description) {
          StudioFlags.COMPOSE_PREVIEW_RENDER_QUALITY.clearOverride()
          FastPreviewConfiguration.getInstance().resetDefault()
        }
      }
    )

  private fun setUpPreview() = runBlocking {
    logger = Logger.getInstance(ComposePreviewFakeUiGradleRule::class.java)
    logger.setLevel(LogLevel.ALL)
    Logger.getInstance(ComposePreviewRepresentation::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(FastPreviewManager::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(ProjectStatus::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(FastPreviewManager::class.java).setLevel(LogLevel.ALL)
    logger.info("ComposePreviewFakeUiGradleRuleImpl setUp")
    psiMainFile = getPsiFile(project, previewFilePath)
    previewView = TestComposePreviewView(fixture.testRootDisposable, project)
    composePreviewRepresentation = createComposePreviewRepresentation(psiMainFile, previewView)
    refreshManager = ComposePreviewRefreshManager.getInstance(project)

    withContext(AndroidDispatchers.uiThread) {
      fakeUi =
        FakeUi(
          JPanel().apply {
            layout = BorderLayout()
            size = Dimension(1000, 800)
            add(previewView, BorderLayout.CENTER)
          },
          1.0,
          true
        )
      fakeUi.root.validate()
    }

    waitForSmartMode(project)

    composePreviewRepresentation.activateAndWaitForRender(fakeUi)
    composePreviewRepresentation.waitForAnyPreviewToBeAvailable()

    runAndWaitForRefresh { composePreviewRepresentation.requestRefreshForTest() }
    logger.debug("requestRefresh completed")

    withContext(AndroidDispatchers.uiThread) {
      previewView.updateVisibilityAndNotifications()
      UIUtil.dispatchAllInvocationEvents()
    }

    Assert.assertTrue(previewView.hasRendered)
    Assert.assertTrue(previewView.hasContent)
    Assert.assertTrue(!composePreviewRepresentation.status().hasErrors)
    Assert.assertTrue(!composePreviewRepresentation.status().hasSyntaxErrors)
    Assert.assertTrue(!composePreviewRepresentation.status().isOutOfDate)

    withContext(AndroidDispatchers.uiThread) { validate() }
    logger.info("ComposePreviewFakeUiGradleRuleImpl setUp completed")
  }

  fun resetInitialConfiguration() {
    setUpPreview()
  }

  /** Executes [runnable], expecting it to cause a refresh to start running. */
  suspend fun waitForAnyRefreshToStart(
    timeout: Duration = DEFAULT_REFRESH_TIMEOUT,
    runnable: suspend () -> Unit
  ) = coroutineScope {
    val countDownLatch = CountDownLatch(1)
    // Make sure to start waiting for the change before triggering it
    val awaitingJob = launch {
      refreshManager.refreshingTypeFlow.awaitStatus(
        "Timeout waiting for refresh to start",
        timeout
      ) {
        it != null
      }
    }
    runnable()
    awaitingJob.join()
    assertTrue(awaitingJob.isCompleted)
    // Make sure that the job hasn't failed, i.e. that a refresh started
    assertFalse(awaitingJob.isCancelled)
  }

  /** Wait for all running refreshes to complete. */
  suspend fun waitForAllRefreshesToFinish(timeout: Duration = DEFAULT_REFRESH_TIMEOUT) {
    refreshManager.refreshingTypeFlow.awaitStatus(
      "Timeout waiting for refresh to finish",
      timeout
    ) {
      it == null
    }
  }

  /**
   * Runs the [runnable]. The [runnable] is expected to trigger a refresh and this method will
   * return once the refresh has happened. Throws an exception if the timeout is exceeded while
   * waiting.
   */
  suspend fun runAndWaitForRefresh(
    timeout: Duration = DEFAULT_BUILD_AND_REFRESH_TIMEOUT,
    aRefreshMustStart: Boolean = true,
    runnable: suspend () -> Unit
  ) {
    waitForAllRefreshesToFinish()
    try {
      waitForAnyRefreshToStart(runnable = runnable)
    } catch (t: Throwable) {
      if (aRefreshMustStart) throw t
    }
    waitForAllRefreshesToFinish(timeout)
  }

  /** Builds the project and waits for the preview panel to refresh. It also does zoom to fit. */
  suspend fun buildAndRefresh(timeout: Duration = DEFAULT_BUILD_AND_REFRESH_TIMEOUT) {
    logger.info("buildAndRefresh")
    runAndWaitForRefresh(timeout) { buildAndAssertIsSuccessful() }
    validate()
  }

  /** Validates the UI to ensure is up to date. */
  suspend fun validate(zoomToFit: Boolean = true) {
    withContext(AndroidDispatchers.uiThread) {
      fakeUi.root.validate()
      if (zoomToFit) {
        // zoom to fit might (but not always) trigger a render quality change
        runAndWaitForRefresh(aRefreshMustStart = false) {
          previewView.mainSurface.zoomToFit()
          fakeUi.root.validate()
        }
      }
      fakeUi.layoutAndDispatchEvents()
    }
  }

  fun createComposePreviewRepresentation(
    psiFile: PsiFile,
    view: TestComposePreviewView
  ): ComposePreviewRepresentation {
    val previewRepresentation =
      ComposePreviewRepresentation(psiFile, PreferredVisibility.SPLIT) { _, _, _, _, _, _ -> view }
    Disposer.register(fixture.testRootDisposable, previewRepresentation)
    return previewRepresentation
  }

  /** Finds the render result of the [SceneViewPeerPanel] with the given [name]. */
  fun findSceneViewRenderWithName(@Suppress("SameParameterValue") name: String): BufferedImage {
    val sceneViewPanel = fakeUi.findComponent<SceneViewPeerPanel> { it.displayName == name }!!
    return fakeUi
      .render()
      .getSubimage(sceneViewPanel.x, sceneViewPanel.y, sceneViewPanel.width, sceneViewPanel.height)
  }
}
