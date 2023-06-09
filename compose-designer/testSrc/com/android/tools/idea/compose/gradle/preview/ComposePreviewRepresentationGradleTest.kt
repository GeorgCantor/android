/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.compose.gradle.preview

import com.android.testutils.ImageDiffUtil
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.common.surface.SceneViewPeerPanel
import com.android.tools.idea.compose.gradle.ComposeGradleProjectRule
import com.android.tools.idea.compose.gradle.activateAndWaitForRender
import com.android.tools.idea.compose.preview.ComposePreviewRepresentation
import com.android.tools.idea.compose.preview.RefreshType
import com.android.tools.idea.compose.preview.SIMPLE_COMPOSE_PROJECT_PATH
import com.android.tools.idea.compose.preview.SimpleComposeAppPaths
import com.android.tools.idea.compose.preview.waitForSmartMode
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.android.tools.idea.editors.build.ProjectStatus
import com.android.tools.idea.editors.build.PsiCodeFileChangeDetectorService
import com.android.tools.idea.editors.fast.CompilationResult
import com.android.tools.idea.editors.fast.DisableReason
import com.android.tools.idea.editors.fast.FastPreviewConfiguration
import com.android.tools.idea.editors.fast.FastPreviewManager
import com.android.tools.idea.editors.fast.FastPreviewTrackerManager
import com.android.tools.idea.editors.fast.TestFastPreviewTrackerManager
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.tools.idea.testing.deleteLine
import com.android.tools.idea.testing.executeAndSave
import com.android.tools.idea.testing.insertText
import com.android.tools.idea.testing.moveCaret
import com.android.tools.idea.testing.moveCaretLines
import com.android.tools.idea.testing.moveCaretToEnd
import com.android.tools.idea.testing.replaceText
import com.android.tools.idea.ui.ApplicationUtils
import com.android.tools.idea.uibuilder.editor.multirepresentation.PreferredVisibility
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.problems.ProblemListener
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.idea.base.plugin.isK2Plugin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

private val DISABLED_FOR_A_TEST = DisableReason("Disabled for a test")

class ComposePreviewRepresentationGradleTest {
  // The logger must be initialized later since at this point the logger framework is not ready yet
  private lateinit var logger: Logger

  @get:Rule val projectRule = ComposeGradleProjectRule(SIMPLE_COMPOSE_PROJECT_PATH)

  private val project: Project
    get() = projectRule.project
  private val fixture: CodeInsightTestFixture
    get() = projectRule.fixture

  private lateinit var psiMainFile: PsiFile
  private lateinit var composePreviewRepresentation: ComposePreviewRepresentation
  private lateinit var previewView: TestComposePreviewView
  private lateinit var fakeUi: FakeUi

  @Before
  fun setUp() = runBlocking {
    logger = Logger.getInstance(ComposePreviewRepresentationGradleTest::class.java)
    logger.setLevel(LogLevel.ALL)
    Logger.getInstance(ComposePreviewRepresentation::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(FastPreviewManager::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(ProjectStatus::class.java).setLevel(LogLevel.ALL)
    Logger.getInstance(FastPreviewManager::class.java).setLevel(LogLevel.ALL)
    logger.info("setUp")
    psiMainFile = getPsiFile(SimpleComposeAppPaths.APP_MAIN_ACTIVITY.path)
    previewView = TestComposePreviewView(fixture.testRootDisposable, project)
    composePreviewRepresentation = createComposePreviewRepresentation(psiMainFile, previewView)

    withContext(uiThread) {
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

    waitForSmartMode(project, logger)

    composePreviewRepresentation.activateAndWaitForRender(fakeUi)
    composePreviewRepresentation.waitForAnyPreviewToBeAvailable()

    runAndWaitForRefresh { composePreviewRepresentation.requestRefresh() }
    logger.debug("requestRefresh completed")

    withContext(uiThread) {
      previewView.updateVisibilityAndNotifications()
      UIUtil.dispatchAllInvocationEvents()
    }

    assertTrue(previewView.hasRendered)
    assertTrue(previewView.hasContent)
    assertTrue(!composePreviewRepresentation.status().hasErrors)
    assertTrue(!composePreviewRepresentation.status().hasSyntaxErrors)
    assertTrue(!composePreviewRepresentation.status().isOutOfDate)

    withContext(uiThread) { validate() }
    logger.info("setUp completed")
  }

  @After
  fun tearDown() {
    FastPreviewConfiguration.getInstance().resetDefault()
  }

  /** Wait for any running refreshes to complete. */
  private fun waitForRefreshToFinish() = runBlocking {
    logger.info("waitForRefreshToFinish")
    // Wait for refresh to finish
    while (composePreviewRepresentation.status().isRefreshing) delay(500)
    logger.info("Refresh completed")
  }

  /** Finds the render result of the [SceneViewPeerPanel] with the given [name]. */
  private fun findSceneViewRenderWithName(
    @Suppress("SameParameterValue") name: String
  ): BufferedImage {
    val sceneViewPanel = fakeUi.findComponent<SceneViewPeerPanel> { it.displayName == name }!!
    return fakeUi
      .render()
      .getSubimage(sceneViewPanel.x, sceneViewPanel.y, sceneViewPanel.width, sceneViewPanel.height)
  }

  /** Validates the UI to ensure is up to date. */
  private suspend fun validate() =
    withContext(uiThread) {
      fakeUi.root.validate()
      previewView.mainSurface.zoomToFit()
      fakeUi.root.validate()
      fakeUi.layoutAndDispatchEvents()
    }

  /**
   * Runs the [runnable]. The [runnable] is expected to trigger a refresh and this method will
   * return once the refresh has happened.
   */
  private suspend fun runAndWaitForRefresh(
    timeout: Duration = Duration.ofSeconds(40),
    runnable: suspend () -> Unit
  ) {
    logger.info("runAndWaitForRefresh")
    // Wait for any on going refreshes to finish
    waitForRefreshToFinish()
    val onRefreshCompletable = previewView.getOnRefreshCompletable()
    logger.info("runAndWaitForRefresh: Starting runnable")
    runnable()
    logger.info("runAndWaitForRefresh: Runnable executed")
    // Wait for the refresh to complete outside of the timeout to reduce the changes of indexing
    // interfering with the runnable execution.
    waitForSmartMode(project, logger)
    withTimeout(timeout.toMillis()) {
      onRefreshCompletable.await()
      logger.info("runAndWaitForRefresh: Refresh completed")
    }
    waitForRefreshToFinish()
  }

  /** Runs the [runnable]. The [runnable] is expected to trigger a fast preview refresh */
  private fun runAndWaitForFastRefresh(
    timeout: Duration = Duration.ofSeconds(40),
    runnable: () -> Unit
  ) = runBlocking {
    logger.info("runAndWaitForFastRefresh")
    val fastPreviewManager = FastPreviewManager.getInstance(project)

    assertTrue("FastPreviewManager must be enabled", fastPreviewManager.isEnabled)

    val compileDeferred = CompletableDeferred<CompilationResult>()
    val fastPreviewManagerListener =
      object : FastPreviewManager.Companion.FastPreviewManagerListener {
        override fun onCompilationStarted(files: Collection<PsiFile>) {
          logger.info("runAndWaitForFastRefresh: onCompilationStarted")
        }

        override fun onCompilationComplete(result: CompilationResult, files: Collection<PsiFile>) {
          logger.info("runAndWaitForFastRefresh: onCompilationComplete $result")
          compileDeferred.complete(result)
        }
      }
    fastPreviewManager.addListener(fixture.testRootDisposable, fastPreviewManagerListener)
    val startMillis = System.currentTimeMillis()
    // Wait for the refresh to complete outside of the timeout to reduce the changes of indexing
    // interfering with the compilation or
    // runnable execution.
    waitForSmartMode(project, logger)
    withTimeout(timeout.toMillis()) {
      logger.info("runAndWaitForFastRefresh: Waiting for any previous compilations to complete")
      while (FastPreviewManager.getInstance(project).isCompiling) delay(50)
    }
    val remainingMillis = timeout.toMillis() - (System.currentTimeMillis() - startMillis)
    waitForSmartMode(project, logger)
    logger.info("runAndWaitForFastRefresh: Executing runnable")
    runnable()
    logger.info("runAndWaitForFastRefresh: Runnable executed")
    withTimeout(remainingMillis) {
      val result = compileDeferred.await()
      logger.info("runAndWaitForFastRefresh: Compilation finished $result")
      (result as? CompilationResult.WithThrowable)?.let { logger.error(it.e) }
    }
  }

  /** Builds the project and waits for the preview panel to refresh. It also does zoom to fit. */
  private suspend fun buildAndRefresh(timeout: Duration = Duration.ofSeconds(40)) {
    logger.info("buildAndRefresh")
    runAndWaitForRefresh(timeout) { projectRule.buildAndAssertIsSuccessful() }
    validate()
  }

  @Test
  fun `panel renders correctly first time`() {
    assertEquals(
      """
        DefaultPreview
        TwoElementsPreview
        NavigatablePreview
        OnlyATextNavigation
        MyPreviewWithInline
      """
        .trimIndent(),
      fakeUi
        .findAllComponents<SceneViewPeerPanel>()
        .filter { it.isShowing }
        .joinToString("\n") { it.displayName }
    )

    val output = fakeUi.render()

    val defaultPreviewSceneViewPeerPanel =
      fakeUi.findComponent<SceneViewPeerPanel> { it.displayName == "DefaultPreview" }!!
    val defaultPreviewRender =
      output.getSubimage(
        defaultPreviewSceneViewPeerPanel.x,
        defaultPreviewSceneViewPeerPanel.y,
        defaultPreviewSceneViewPeerPanel.width,
        defaultPreviewSceneViewPeerPanel.height
      )
    ImageDiffUtil.assertImageSimilar(
      Paths.get(
        "${fixture.testDataPath}/${SIMPLE_COMPOSE_PROJECT_PATH}/defaultRender-withPanel.png"
      ),
      defaultPreviewRender,
      10.0,
      20
    )
  }

  @Test
  fun `removing preview makes it disappear without refresh`() = runBlocking {
    runAndWaitForRefresh {
      // Remove the @Preview from the NavigatablePreview
      runWriteActionAndWait {
        fixture.openFileInEditor(psiMainFile.virtualFile)
        fixture.moveCaret("NavigatablePreview|")
        // Move to the line with the annotation
        fixture.editor.moveCaretLines(-2)
        fixture.editor.executeAndSave { fixture.editor.deleteLine() }
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
      }
    }
    withContext(uiThread) { fakeUi.root.validate() }

    assertEquals(
      listOf("DefaultPreview", "MyPreviewWithInline", "OnlyATextNavigation", "TwoElementsPreview"),
      fakeUi
        .findAllComponents<SceneViewPeerPanel>()
        .filter { it.isShowing }
        .map { it.displayName }
        .sorted()
    )
  }

  @Test
  fun `changes to code are reflected in the preview`() = runBlocking {
    // This test only makes sense when fast preview is disabled,
    // as some build related logic is being tested.
    FastPreviewManager.getInstance(project).disable(DISABLED_FOR_A_TEST)
    val firstRender = findSceneViewRenderWithName("TwoElementsPreview")

    // Make a change to the preview
    runWriteActionAndWait {
      fixture.openFileInEditor(psiMainFile.virtualFile)
      fixture.moveCaret("Text(\"Hello 2\")|")
      fixture.editor.executeAndSave { insertText("\nText(\"Hello 3\")\n") }
      PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
      FileDocumentManager.getInstance().saveAllDocuments()
    }

    buildAndRefresh()

    val secondRender = findSceneViewRenderWithName("TwoElementsPreview")
    assertTrue(
      "Second image expected at least 10% higher but were second=${secondRender.height} first=${firstRender.height}",
      secondRender.height > (firstRender.height * 1.10)
    )
    try {
      ImageDiffUtil.assertImageSimilar("testImage", firstRender, secondRender, 10.0, 20)
      fail("First render and second render are expected to be different")
    } catch (_: AssertionError) {}

    // Restore to the initial state and verify
    runWriteActionAndWait {
      fixture.editor.executeAndSave { replaceText("Text(\"Hello 3\")\n", "") }
    }

    buildAndRefresh()

    val thirdRender = findSceneViewRenderWithName("TwoElementsPreview")
    ImageDiffUtil.assertImageSimilar("testImage", firstRender, thirdRender, 10.0, 20)
  }

  @Ignore("b/269427611")
  @Test
  fun `MultiPreview annotation changes are reflected in the previews without rebuilding`() =
    runBlocking {
      // This test only makes sense when fast preview is disabled,
      // as some build related logic is being tested.
      FastPreviewManager.getInstance(project).disable(DISABLED_FOR_A_TEST)
      val otherPreviewsFile = getPsiFile(SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)

      // Add an annotation class annotated with Preview in OtherPreviews.kt
      runWriteActionAndWait {
        fixture.openFileInEditor(otherPreviewsFile.virtualFile)
        fixture.moveCaret("|@Preview")
        fixture.editor.executeAndSave { insertText("@Preview\nannotation class MyAnnotation\n\n") }
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
      }

      runAndWaitForRefresh {
        // Annotate DefaultPreview with the new MultiPreview annotation class
        runWriteActionAndWait {
          fixture.openFileInEditor(psiMainFile.virtualFile)
          fixture.moveCaret("|@Preview")
          fixture.editor.executeAndSave { insertText("@MyAnnotation\n") }
          PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
          FileDocumentManager.getInstance().saveAllDocuments()
        }
      }
      withContext(uiThread) {
        fakeUi.root.validate()
        fakeUi.layoutAndDispatchEvents()
      }

      waitForRefreshToFinish()
      assertEquals(
        """
        DefaultPreview
        DefaultPreview - MyAnnotation 1
        NavigatablePreview
        OnlyATextNavigation
        TwoElementsPreview
      """
          .trimIndent(),
        fakeUi
          .findAllComponents<SceneViewPeerPanel>()
          .filter { it.isShowing }
          .map { it.displayName }
          .joinToString("\n")
      )

      // Simulate what happens when leaving the MainActivity.kt tab in the editor
      // TODO(b/232092986) This is actually not a tab change, but currently we don't have a better
      // way of simulating it, and this is the only relevant consequence of changing tabs for this
      // test.
      composePreviewRepresentation.onDeactivate()

      // Modify the Preview annotating MyAnnotation
      runWriteActionAndWait {
        fixture.openFileInEditor(otherPreviewsFile.virtualFile)
        fixture.moveCaret("@Preview|")
        fixture.editor.executeAndSave { insertText("(name = \"newName\")") }
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
      }

      runAndWaitForRefresh(Duration.ofSeconds(35)) {
        // Simulate what happens when changing back to the MainActivity.kt tab in the editor
        // TODO(b/232092986) This is actually not a tab change, but currently we don't have a better
        // way of simulating it, and this is the only relevant consequence of changing tabs for this
        // test.
        runWriteActionAndWait { fixture.openFileInEditor(psiMainFile.virtualFile) }
        composePreviewRepresentation.onActivate()
      }

      withContext(uiThread) {
        fakeUi.root.validate()
        fakeUi.layoutAndDispatchEvents()
      }

      assertEquals(
        """
        DefaultPreview
        DefaultPreview - newName
        NavigatablePreview
        OnlyATextNavigation
        TwoElementsPreview
      """
          .trimIndent(),
        fakeUi
          .findAllComponents<SceneViewPeerPanel>()
          .filter { it.isShowing }
          .map { it.displayName }
          .joinToString("\n")
      )
    }

  @Test
  fun `build clean triggers needs refresh`() {
    GradleBuildInvoker.getInstance(projectRule.project).cleanProject().get(2, TimeUnit.SECONDS)
    assertTrue(composePreviewRepresentation.status().isOutOfDate)
  }

  @Ignore("b/283057643")
  @Test
  fun `updating different file triggers needs refresh`() = runBlocking {
    // This test only makes sense when fast preview is disabled,
    // as some build related logic is being tested.
    FastPreviewManager.getInstance(project).disable(DISABLED_FOR_A_TEST)
    val otherFile =
      VfsUtil.findRelativeFile(
        SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path,
        ProjectRootManager.getInstance(projectRule.project).contentRoots[0]
      )!!

    runWriteActionAndWait {
      projectRule.fixture.openFileInEditor(otherFile)
      projectRule.fixture.moveCaret("Text(\"Line3\")|")
      projectRule.fixture.type("\nText(\"added during test execution\")")
      PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
      FileDocumentManager.getInstance().saveAllDocuments()
    }
    withContext(uiThread) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue() // Consume editor events
    }
    waitForRefreshToFinish()
    assertTrue(composePreviewRepresentation.status().isOutOfDate)
    buildAndRefresh()
    assertFalse(composePreviewRepresentation.status().isOutOfDate)
  }

  // Regression test for b/246963901
  @Ignore("b/270198240")
  @Test
  fun `second build doesn't trigger refresh on first nor second activation`() = runBlocking {
    // This test only makes sense when fast preview is disabled,
    // as some build related logic is being tested.
    FastPreviewManager.getInstance(project).disable(DISABLED_FOR_A_TEST)
    repeat(2) {
      runWriteActionAndWait {
        projectRule.fixture.openFileInEditor(psiMainFile.virtualFile)
        projectRule.fixture.moveCaret("Text(text = \"Hello \$name!\")|")
        projectRule.fixture.type("\nText(\"added during test execution\")")
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
      }
      withContext(uiThread) {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue() // Consume editor events
      }

      waitForRefreshToFinish()
      assertTrue(composePreviewRepresentation.status().isOutOfDate)
      // First build after modification should trigger refresh
      buildAndRefresh()
      assertFalse(composePreviewRepresentation.status().isOutOfDate)
      // Second build shouldn't trigger refresh
      assertFailsWith<TimeoutCancellationException> { buildAndRefresh(Duration.ofSeconds(15)) }

      // Deactivating and activating the representation shouldn't affect its
      // behaviour for the next repetition of the code above
      composePreviewRepresentation.onDeactivate()
      composePreviewRepresentation.onActivate()
    }
  }

  @Test
  fun `refresh returns completed exceptionally if ComposePreviewRepresentation is disposed`() {
    var refreshDeferred = runBlocking {
      val completableDeferred = CompletableDeferred<Unit>()
      composePreviewRepresentation.requestRefresh(
        RefreshType.QUICK,
        completableDeferred = completableDeferred
      )
      completableDeferred
    }
    assertNotNull(refreshDeferred)

    runInEdtAndWait { Disposer.dispose(composePreviewRepresentation) }
    refreshDeferred = runBlocking {
      val completableDeferred = CompletableDeferred<Unit>()
      composePreviewRepresentation.requestRefresh(
        RefreshType.QUICK,
        completableDeferred = completableDeferred
      )
      completableDeferred
    }
    // Verify that is completed exceptionally
    assertTrue(refreshDeferred.isCompleted)
    assertNotNull(refreshDeferred.getCompletionExceptionOrNull())
  }

  @Test
  fun `fast preview request`() {
    val requestCompleted = CompletableDeferred<Unit>()
    val testTracker = TestFastPreviewTrackerManager { requestCompleted.complete(Unit) }

    project.replaceService(
      FastPreviewTrackerManager::class.java,
      testTracker,
      fixture.testRootDisposable
    )

    runAndWaitForFastRefresh {
      WriteCommandAction.runWriteCommandAction(project) {
        projectRule.fixture.openFileInEditor(psiMainFile.virtualFile)
        projectRule.fixture.moveCaret("Text(\"Hello 2\")|")
        projectRule.fixture.editor.insertText("\nText(\"added during test execution\")")
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
      }
    }

    runBlocking {
      withTimeout(TimeUnit.SECONDS.toMillis(10)) {
        // Wait for the tracking request to be submitted
        requestCompleted.await()
      }
    }

    assertEquals(
      "compilationSucceeded (compilationDurationMs=>0, compiledFiles=1, refreshTime=>0)",
      testTracker.logOutput()
    )
  }

  @Test
  fun `fast preview cancellation`() {
    val requestCompleted = CompletableDeferred<Unit>()
    val completedRequestsCount = AtomicInteger(0)
    val testTracker = TestFastPreviewTrackerManager {
      if (completedRequestsCount.incrementAndGet() == 2) requestCompleted.complete(Unit)
    }

    project.replaceService(
      FastPreviewTrackerManager::class.java,
      testTracker,
      fixture.testRootDisposable
    )

    runAndWaitForFastRefresh {
      WriteCommandAction.runWriteCommandAction(project) {
        projectRule.fixture.openFileInEditor(psiMainFile.virtualFile)
        projectRule.fixture.moveCaret("Text(\"Hello 2\")|")
        projectRule.fixture.editor.insertText(
          "\nkotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(5000L) }"
        )
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
      }
    }

    runAndWaitForFastRefresh {
      WriteCommandAction.runWriteCommandAction(project) {
        projectRule.fixture.openFileInEditor(psiMainFile.virtualFile)
        projectRule.fixture.moveCaret(
          "kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(5000L) }|"
        )
        fixture.editor.executeAndSave { fixture.editor.deleteLine() }
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
      }
    }

    runBlocking {
      withTimeout(TimeUnit.SECONDS.toMillis(30)) {
        // Wait for the 2 tracking request to be submitted
        requestCompleted.await()
      }
    }

    assertEquals(
      """
        refreshCancelled (compilationCompleted=true)
        compilationSucceeded (compilationDurationMs=>0, compiledFiles=1, refreshTime=>0)
      """
        .trimIndent(),
      testTracker.logOutput()
    )
  }

  @Test
  fun `fast preview fixing syntax error triggers compilation`() {
    runAndWaitForFastRefresh {
      // Mark the file as invalid so the fast preview triggers a compilation when the problems
      // dissapear
      PsiCodeFileChangeDetectorService.getInstance(project).markFileAsOutOfDate(psiMainFile)
      project.messageBus
        .syncPublisher(ProblemListener.TOPIC)
        .problemsDisappeared(psiMainFile.virtualFile)
    }
  }

  @Test
  fun `file modification triggers refresh on other active preview representations`() = runBlocking {
    // This test only makes sense when fast preview is disabled
    FastPreviewManager.getInstance(project).disable(DISABLED_FOR_A_TEST)

    val otherPreviewsFile = getPsiFile(SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)
    val otherPreviewView = TestComposePreviewView(fixture.testRootDisposable, project)
    val otherPreviewRepresentation =
      createComposePreviewRepresentation(otherPreviewsFile, otherPreviewView)

    otherPreviewRepresentation.onActivate()

    // Now both ComposePreviewRepresentation are active, so modifying otherPreviewsFile
    // should trigger a refresh in the main file representation.
    runAndWaitForRefresh {
      runWriteActionAndWait {
        fixture.openFileInEditor(otherPreviewsFile.virtualFile)
        // Add a MultiPreview annotation that won't be used
        fixture.moveCaret("|@Preview")
        fixture.editor.executeAndSave { insertText("@Preview\nannotation class MyAnnotation\n\n") }
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
      }
    }
  }

  @Test
  fun `file modification don't trigger refresh on inactive preview representations`(): Unit =
    runBlocking {
      // This test only makes sense when fast preview is disabled
      FastPreviewManager.getInstance(project).disable(DISABLED_FOR_A_TEST)

      val otherPreviewsFile = getPsiFile(SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)
      val otherPreviewView = TestComposePreviewView(fixture.testRootDisposable, project)
      val otherPreviewRepresentation =
        createComposePreviewRepresentation(otherPreviewsFile, otherPreviewView)

      composePreviewRepresentation.onDeactivate()
      otherPreviewRepresentation.onActivate()

      // Now otherPreviewRepresentation is active, but the main file representation is not,
      // so modifying otherPreviewsFile shouldn't trigger a refresh in the later one.
      ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
        fixture.openFileInEditor(otherPreviewsFile.virtualFile)
      }
      assertFailsWith<TimeoutCancellationException> {
        runAndWaitForRefresh(Duration.ofSeconds(15)) {
          runWriteActionAndWait {
            // Add a MultiPreview annotation that won't be used
            fixture.moveCaret("|@Preview")
            fixture.editor.executeAndSave {
              insertText("@Preview\nannotation class MyAnnotation\n\n")
            }
            PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
            FileDocumentManager.getInstance().saveAllDocuments()
          }
        }
      }
    }

  /**
   * When a kotlin file is updated while a preview is inactive, this will not trigger a refresh. See
   * [file modification don't trigger refresh on inactive preview representations]
   *
   * This test verifies that the refresh does happen when we come back to the preview.
   */
  @Test
  fun `file modification refresh triggers refresh on reactivation`(): Unit = runBlocking {
    // Fail early to not time out (and not make noises) if running in K2
    // Otherwise, the lack of [ResolutionFacade] causes [IllegalStateException],
    // which is not properly propagated / handled, resulting in non-recovered hanging coroutines,
    // which make all other tests cancelled as well.
    assertFalse(isK2Plugin())

    val psiCodeFileChangeDetectorService = PsiCodeFileChangeDetectorService.getInstance(project)
    val otherPreviewsFile = getPsiFile(SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)
    val otherPreviewView = TestComposePreviewView(fixture.testRootDisposable, project)
    val otherPreviewRepresentation =
      createComposePreviewRepresentation(otherPreviewsFile, otherPreviewView)

    composePreviewRepresentation.onDeactivate()
    otherPreviewRepresentation.onActivate()

    // Now otherPreviewRepresentation is active, but the main file representation is not,
    // so modifying otherPreviewsFile shouldn't trigger a refresh in the later one, only invalidate
    // it
    ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
      fixture.openFileInEditor(otherPreviewsFile.virtualFile)
    }

    assertFalse(composePreviewRepresentation.isInvalid())
    assertFailsWith<TimeoutCancellationException> {
      runAndWaitForRefresh(Duration.ofSeconds(15)) {
        runWriteActionAndWait {
          fixture.editor.executeAndSave {
            moveCaretToEnd()
            insertText("\n\nfun testMethod() {}\n\n")
          }
          PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
          FileDocumentManager.getInstance().saveAllDocuments()
        }
      }
    }

    // Make sure there are no pending files to update
    psiCodeFileChangeDetectorService.fileUpdatesFlow.filter { it.isEmpty() }.first()

    // The preview must now be invalid until it is activated and it automatically refreshes
    assertTrue(composePreviewRepresentation.isInvalid())

    ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
      fixture.openFileInEditor(psiMainFile.virtualFile)
    }
    runAndWaitForRefresh {
      otherPreviewRepresentation.onDeactivate()
      composePreviewRepresentation.onActivate()
    }
    assertFalse(composePreviewRepresentation.isInvalid())
  }

  private fun getPsiFile(path: String): PsiFile {
    val vFile = project.guessProjectDir()!!.findFileByRelativePath(path)!!
    return runReadAction { PsiManager.getInstance(project).findFile(vFile)!! }
  }

  private fun createComposePreviewRepresentation(
    psiFile: PsiFile,
    view: TestComposePreviewView
  ): ComposePreviewRepresentation {
    val previewRepresentation =
      ComposePreviewRepresentation(psiFile, PreferredVisibility.SPLIT) { _, _, _, _, _, _, _ ->
        view
      }
    Disposer.register(fixture.testRootDisposable, previewRepresentation)
    return previewRepresentation
  }
}
