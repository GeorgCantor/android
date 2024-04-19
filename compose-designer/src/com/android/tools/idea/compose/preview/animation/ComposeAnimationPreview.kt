/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.compose.preview.animation

import androidx.compose.animation.tooling.ComposeAnimation
import androidx.compose.animation.tooling.ComposeAnimationType
import com.android.annotations.concurrency.UiThread
import com.android.tools.idea.compose.preview.animation.managers.AnimatedVisibilityAnimationManager
import com.android.tools.idea.compose.preview.animation.managers.ComposeAnimationManager
import com.android.tools.idea.compose.preview.animation.managers.ComposeSupportedAnimationManager
import com.android.tools.idea.compose.preview.animation.managers.ComposeUnsupportedAnimationManager
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.android.tools.idea.preview.animation.AnimationPreview
import com.android.tools.idea.preview.animation.InspectorLayout
import com.android.tools.idea.preview.animation.timeline.TimelineElement
import com.android.tools.idea.preview.animation.timeline.TransitionCurve
import com.android.tools.idea.preview.animation.timeline.getOffsetForValue
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.scene.executeInRenderSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import javax.swing.JComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays details about animations belonging to a Compose Preview.
 *
 * @param psiFilePointer a pointer to a [PsiFile] for current Preview in which Animation Preview is
 *   opened.
 */
class ComposeAnimationPreview(
  project: Project,
  val tracker: ComposeAnimationTracker,
  private val sceneManagerProvider: () -> LayoutlibSceneManager?,
  private val rootComponent: JComponent,
  val psiFilePointer: SmartPsiElementPointer<PsiFile>,
) :
  AnimationPreview<ComposeAnimationManager>(project, sceneManagerProvider, rootComponent, tracker),
  ComposeAnimationHandler {

  /** Generates unique tab names for each tab e.g "tabTitle(1)", "tabTitle(2)". */
  private val tabNames = TabNamesGenerator()

  /**
   * Wrapper of the `PreviewAnimationClock` that animations inspected in this panel are subscribed
   * to. Null when there are no animations.
   */
  override var animationClock: AnimationClock? = null

  /** Update list of [TimelineElement] for selected [ComposeSupportedAnimationManager]s. */
  @UiThread
  override fun getTimelineElements(): List<TimelineElement> {
    val selected = selectedAnimation as? ComposeSupportedAnimationManager
    if (selected != null) {
      // Paint single selected animation.
      val state = selected.elementState.value
      val curve =
        TransitionCurve.create(
          getOffsetForValue(state.valueOffset, timeline.sliderUI.positionProxy),
          if (state.frozen) state.frozenValue else null,
          selected.currentTransition,
          rowMinY = InspectorLayout.timelineHeaderHeightScaled(),
          timeline.sliderUI.positionProxy,
        )
      selected.selectedPropertiesCallback = { curve.timelineUnits = it }
      curve.timelineUnits = selected.selectedProperties
      if (Disposer.tryRegister(this@ComposeAnimationPreview, curve)) {
        curve.setNewOffsetCallback {
          selected.elementState.value =
            selected.elementState.value.copy(valueOffset = curve.getValueForOffset(it))
        }
      }
      return listOf(curve)
    } else {
      var minY = InspectorLayout.timelineHeaderHeightScaled()
      return animations.map { tab ->
        tab.createTimelineElement(timeline, minY, timeline.sliderUI.positionProxy).also { element ->
          minY += element.heightScaled()
          if (Disposer.tryRegister(this@ComposeAnimationPreview, element)) {
            if (tab is ComposeSupportedAnimationManager) {
              tab.card.expandedSize = TransitionCurve.expectedHeight(tab.currentTransition)
              tab.card.setDuration(tab.currentTransition.duration)
              element.setNewOffsetCallback {
                tab.elementState.value =
                  tab.elementState.value.copy(valueOffset = element.getValueForOffset(it))
              }
            }
          }
        }
      }
    }
  }

  /**
   * Set clock time
   *
   * @param newValue new clock time in milliseconds.
   * @param longTimeout set true to use a long timeout.
   */
  override suspend fun setClockTime(newValue: Int, longTimeout: Boolean) {
    animationClock?.apply {
      val clockTimeMs = newValue.toLong()
      val animationsToUpdate = animations.filterIsInstance<ComposeSupportedAnimationManager>()

      sceneManagerProvider()?.executeInRenderSession(longTimeout) {
        setClockTimes(
          animationsToUpdate.associate {
            val newTime =
              (if (it.elementState.value.frozen) it.elementState.value.frozenValue.toLong()
              else clockTimeMs) - it.elementState.value.valueOffset
            it.animation to newTime
          }
        )
      }

      animationsToUpdate.forEach { it.loadProperties() }
    }
  }

  /**
   * Creates and adds [ComposeAnimationManager] for given [ComposeAnimation] to the panel.
   *
   * If this animation was already added, removes old [ComposeAnimationManager] first. Repaints
   * panel.
   */
  override fun addAnimation(animation: ComposeAnimation) =
    scope.launch {
      removeAnimation(animation).join()
      val tab = withContext(uiThread) { createAnimationManager(animation) }
      tab.setup()
      withContext(uiThread) { addAnimationManager(tab) }
      updateTimelineElements()
    }

  override suspend fun updateMaxDuration(longTimeout: Boolean) {
    val clock = animationClock ?: return

    sceneManagerProvider()?.executeInRenderSession(longTimeout) {
      maxDurationPerIteration.value = clock.getMaxDurationMsPerIteration()
    }
  }

  /**
   * Creates an [ComposeAnimationManager] corresponding to the given [animation]. Note: this method
   * does not add the tab to [tabbedPane]. For that, [addAnimationManager] should be used.
   */
  @UiThread
  private fun createAnimationManager(animation: ComposeAnimation): ComposeAnimationManager =
    when (animation.type) {
      ComposeAnimationType.ANIMATED_VISIBILITY ->
        AnimatedVisibilityAnimationManager(
          animation,
          tabNames.createName(animation),
          tracker,
          animationClock!!,
          maxDurationPerIteration,
          timeline,
          sceneManagerProvider(),
          tabbedPane,
          rootComponent,
          playbackControls,
          { longTimeout -> resetTimelineAndUpdateWindowSize(longTimeout) },
          { updateTimelineElements() },
          scope,
        )
      ComposeAnimationType.TRANSITION_ANIMATION,
      ComposeAnimationType.ANIMATE_X_AS_STATE,
      ComposeAnimationType.ANIMATED_CONTENT,
      ComposeAnimationType.INFINITE_TRANSITION ->
        ComposeSupportedAnimationManager(
          animation,
          tabNames.createName(animation),
          tracker,
          animationClock!!,
          maxDurationPerIteration,
          timeline,
          sceneManagerProvider(),
          tabbedPane,
          rootComponent,
          playbackControls,
          { longTimeout -> resetTimelineAndUpdateWindowSize(longTimeout) },
          { updateTimelineElements() },
          scope,
        )
      ComposeAnimationType.ANIMATED_VALUE,
      ComposeAnimationType.ANIMATABLE,
      ComposeAnimationType.ANIMATE_CONTENT_SIZE,
      ComposeAnimationType.DECAY_ANIMATION,
      ComposeAnimationType.TARGET_BASED_ANIMATION,
      ComposeAnimationType.UNSUPPORTED ->
        ComposeUnsupportedAnimationManager(animation, tabNames.createName(animation))
    }

  /**
   * Removes the [ComposeAnimationManager] card and tab corresponding to the given [animation] from
   * [tabbedPane].
   */
  override fun removeAnimation(animation: ComposeAnimation) =
    scope.launch {
      withContext(uiThread) {
        animations.find { it.animation == animation }?.let { removeAnimationManager(it) }
      }
      updateTimelineElements()
    }

  override fun invalidatePanel() =
    scope.launch {
      super.invalidatePanel().join()
      tabNames.clear()
    }

  override fun dispose() {
    super.dispose()
    tabNames.clear()
  }
}
