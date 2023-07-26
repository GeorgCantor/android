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
import com.android.tools.idea.compose.gradle.ComposePreviewFakeUiGradleRule
import com.android.tools.idea.compose.gradle.getPsiFile
import com.android.tools.idea.compose.preview.ComposePreviewRenderQualityPolicy
import com.android.tools.idea.compose.preview.ComposePreviewRepresentation
import com.android.tools.idea.compose.preview.RefreshType
import com.android.tools.idea.compose.preview.SIMPLE_COMPOSE_PROJECT_PATH
import com.android.tools.idea.compose.preview.SimpleComposeAppPaths
import com.android.tools.idea.compose.preview.getDefaultPreviewQuality
import com.android.tools.idea.compose.preview.waitForSmartMode
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.android.tools.idea.editors.build.PsiCodeFileChangeDetectorService
import com.android.tools.idea.editors.fast.CompilationResult
import com.android.tools.idea.editors.fast.DisableReason
import com.android.tools.idea.editors.fast.FastPreviewManager
import com.android.tools.idea.editors.fast.FastPreviewTrackerManager
import com.android.tools.idea.editors.fast.TestFastPreviewTrackerManager
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.tools.idea.testing.deleteLine
import com.android.tools.idea.testing.executeAndSave
import com.android.tools.idea.testing.insertText
import com.android.tools.idea.testing.moveCaret
import com.android.tools.idea.testing.moveCaretLines
import com.android.tools.idea.testing.moveCaretToEnd
import com.android.tools.idea.testing.replaceText
import com.android.tools.idea.ui.ApplicationUtils
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.problems.ProblemListener
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import java.awt.Point
import java.awt.Rectangle
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.idea.base.plugin.isK2Plugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

private val DISABLED_FOR_A_TEST = DisableReason("Disabled for a test")

class ComposePreviewRepresentationGradleTest {
  @get:Rule
  val projectRule =
    ComposePreviewFakeUiGradleRule(
      SIMPLE_COMPOSE_PROJECT_PATH,
      SimpleComposeAppPaths.APP_MAIN_ACTIVITY.path
    )
  private val project: Project
    get() = projectRule.project
  private val fixture: CodeInsightTestFixture
    get() = projectRule.fixture
  private val logger: Logger
    get() = projectRule.logger
  private val composePreviewRepresentation: ComposePreviewRepresentation
    get() = projectRule.composePreviewRepresentation
  private val psiMainFile: PsiFile
    get() = projectRule.psiMainFile
  private val previewView: TestComposePreviewView
    get() = projectRule.previewView
  private val fakeUi: FakeUi
    get() = projectRule.fakeUi

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
    projectRule.runAndWaitForRefresh {
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
    val firstRender = projectRule.findSceneViewRenderWithName("TwoElementsPreview")

    // Make a change to the preview
    runWriteActionAndWait {
      fixture.openFileInEditor(psiMainFile.virtualFile)
      fixture.moveCaret("Text(\"Hello 2\")|")
      fixture.editor.executeAndSave { insertText("\nText(\"Hello 3\")\n") }
      PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
      FileDocumentManager.getInstance().saveAllDocuments()
    }

    projectRule.buildAndRefresh()

    val secondRender = projectRule.findSceneViewRenderWithName("TwoElementsPreview")
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

    projectRule.buildAndRefresh()

    val thirdRender = projectRule.findSceneViewRenderWithName("TwoElementsPreview")
    ImageDiffUtil.assertImageSimilar("testImage", firstRender, thirdRender, 10.0, 20)
  }

  @Ignore("b/269427611")
  @Test
  fun `MultiPreview annotation changes are reflected in the previews without rebuilding`() =
    runBlocking {
      // This test only makes sense when fast preview is disabled,
      // as some build related logic is being tested.
      FastPreviewManager.getInstance(project).disable(DISABLED_FOR_A_TEST)
      val otherPreviewsFile = getPsiFile(project, SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)

      // Add an annotation class annotated with Preview in OtherPreviews.kt
      runWriteActionAndWait {
        fixture.openFileInEditor(otherPreviewsFile.virtualFile)
        fixture.moveCaret("|@Preview")
        fixture.editor.executeAndSave { insertText("@Preview\nannotation class MyAnnotation\n\n") }
        PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
      }

      projectRule.runAndWaitForRefresh {
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

      projectRule.waitForAllRefreshesToFinish()
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

      projectRule.runAndWaitForRefresh(35.seconds) {
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
    projectRule.waitForAllRefreshesToFinish()
    assertTrue(composePreviewRepresentation.status().isOutOfDate)
    projectRule.buildAndRefresh()
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

      projectRule.waitForAllRefreshesToFinish()
      assertTrue(composePreviewRepresentation.status().isOutOfDate)
      // First build after modification should trigger refresh
      projectRule.buildAndRefresh()
      assertFalse(composePreviewRepresentation.status().isOutOfDate)
      // Second build shouldn't trigger refresh
      assertFails { projectRule.buildAndRefresh(15.seconds) }

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

  @Ignore("b/289888238")
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

    val otherPreviewsFile = getPsiFile(project, SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)

    // Modifying otherPreviewsFile should trigger a refresh in the main file representation.
    // (and in any active one)
    projectRule.runAndWaitForRefresh {
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
      val otherPreviewsFile = getPsiFile(project, SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)

      composePreviewRepresentation.onDeactivate()

      // Modifying otherPreviewsFile should not trigger a refresh in the main file representation
      // (nor in any inactive one).
      ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
        fixture.openFileInEditor(otherPreviewsFile.virtualFile)
      }

      assertFalse(composePreviewRepresentation.isInvalid())
      assertFails {
        projectRule.runAndWaitForRefresh(15.seconds) {
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

    val otherPreviewsFile = getPsiFile(project, SimpleComposeAppPaths.APP_OTHER_PREVIEWS.path)

    composePreviewRepresentation.onDeactivate()

    // Modifying otherPreviewsFile should not trigger a refresh in the main file representation
    // (nor in any inactive one).
    ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
      fixture.openFileInEditor(otherPreviewsFile.virtualFile)
    }

    assertFalse(composePreviewRepresentation.isInvalid())
    assertFails {
      projectRule.runAndWaitForRefresh(15.seconds) {
        runWriteActionAndWait {
          fixture.editor.moveCaretToEnd()
          fixture.editor.executeAndSave { insertText("\n\nfun testMethod() {}\n\n") }
          PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
          FileDocumentManager.getInstance().saveAllDocuments()
        }
      }
    }

    ApplicationUtils.invokeWriteActionAndWait(ModalityState.defaultModalityState()) {
      fixture.openFileInEditor(psiMainFile.virtualFile)
    }
    // When reactivating, a refresh should happen due to the modification of otherPreviewsFile
    // during the inactive time of this representation.
    projectRule.runAndWaitForRefresh { composePreviewRepresentation.onActivate() }
    assertFalse(composePreviewRepresentation.isInvalid())
  }

  @Test
  fun testPreviewRenderQuality() = runBlocking {
    try {
      StudioFlags.COMPOSE_PREVIEW_RENDER_QUALITY.override(true)
      // We need to set up things again to make sure that the flag change takes effect
      projectRule.resetInitialConfiguration()
      withContext(uiThread) { fakeUi.root.validate() }

      var firstPreview: SceneViewPeerPanel? = null
      // zoom and center to one preview (quality change refresh should happen)
      projectRule.runAndWaitForRefresh {
        firstPreview = fakeUi.findAllComponents<SceneViewPeerPanel>().first { it.isShowing }
        firstPreview!!.sceneView.let {
          previewView.mainSurface.zoomAndCenter(
            it,
            Rectangle(Point(it.x, it.y), it.scaledContentSize)
          )
        }
      }
      withContext(uiThread) { fakeUi.root.validate() }
      // Default quality should have been used
      assertEquals(
        getDefaultPreviewQuality(),
        (fakeUi
            .findAllComponents<SceneViewPeerPanel>()
            .first { it.displayName == firstPreview!!.displayName }
            .sceneView
            .sceneManager as LayoutlibSceneManager)
          .lastRenderQuality
      )

      // Now zoom out a lot to go below the threshold (quality change refresh should happen)
      projectRule.runAndWaitForRefresh {
        previewView.mainSurface.setScale(
          ComposePreviewRenderQualityPolicy.scaleVisibilityThreshold / 2.0
        )
      }
      withContext(uiThread) { fakeUi.root.validate() }
      assertEquals(
        ComposePreviewRenderQualityPolicy.lowestQuality,
        (fakeUi
            .findAllComponents<SceneViewPeerPanel>()
            .first { it.displayName == firstPreview!!.displayName }
            .sceneView
            .sceneManager as LayoutlibSceneManager)
          .lastRenderQuality
      )

      // Now zoom in a little bit to go above the threshold (quality change refresh should happen)
      projectRule.runAndWaitForRefresh {
        previewView.mainSurface.setScale(
          ComposePreviewRenderQualityPolicy.scaleVisibilityThreshold * 2.0
        )
      }
      withContext(uiThread) { fakeUi.root.validate() }
      assertEquals(
        ComposePreviewRenderQualityPolicy.scaleVisibilityThreshold * 2,
        (fakeUi
            .findAllComponents<SceneViewPeerPanel>()
            .first { it.displayName == firstPreview!!.displayName }
            .sceneView
            .sceneManager as LayoutlibSceneManager)
          .lastRenderQuality
      )
    } finally {
      StudioFlags.COMPOSE_PREVIEW_RENDER_QUALITY.clearOverride()
    }
  }
}
