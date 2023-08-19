/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.scene

import com.android.SdkConstants.FD_RES_XML
import com.android.SdkConstants.PreferenceTags.PREFERENCE_SCREEN
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.common.fixtures.ModelBuilder
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.common.type.DesignerTypeRegistrar
import com.android.tools.idea.modes.essentials.EssentialsMode
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.idea.uibuilder.surface.NlScreenViewProvider
import com.android.tools.idea.uibuilder.type.PreferenceScreenFileType
import com.google.common.truth.Truth.assertThat

class LayoutlibSceneManagerTest : SceneTest() {

  private lateinit var myLayoutlibSceneManager: LayoutlibSceneManager

  override fun setUp() {
    // we register it manually here in the tests context, but in production it should be handled by
    // NlEditorProvider
    DesignerTypeRegistrar.register(PreferenceScreenFileType)
    super.setUp()
    myLayoutlibSceneManager = (myScene.designSurface as NlDesignSurface).sceneManagers.first()
  }

  override fun tearDown() {
    super.tearDown()
    DesignerTypeRegistrar.clearRegisteredTypes()
  }

  fun testSceneModeWithPreferenceFile() {
    // Regression test for b/122673792
    val nlSurface = myScene.designSurface as NlDesignSurface

    whenever(nlSurface.screenViewProvider).thenReturn(NlScreenViewProvider.RENDER)
    myLayoutlibSceneManager.updateSceneView()
    assertNotNull(myLayoutlibSceneManager.sceneView)
    assertNull(myLayoutlibSceneManager.secondarySceneView)

    whenever(nlSurface.screenViewProvider).thenReturn(NlScreenViewProvider.BLUEPRINT)
    myLayoutlibSceneManager.updateSceneView()
    assertNotNull(myLayoutlibSceneManager.sceneView)
    assertNull(myLayoutlibSceneManager.secondarySceneView)

    whenever(nlSurface.screenViewProvider).thenReturn(NlScreenViewProvider.RENDER_AND_BLUEPRINT)
    myLayoutlibSceneManager.updateSceneView()
    assertNotNull(myLayoutlibSceneManager.sceneView)
    assertNotNull(myLayoutlibSceneManager.secondarySceneView)
  }

  fun testPowerSaveModeDoesNotRefreshOnResourcesChange() {
    EssentialsMode.setEnabled(true, project)
    try {
      myLayoutlibSceneManager.model.notifyModified(NlModel.ChangeType.DND_COMMIT)
      assertFalse(myLayoutlibSceneManager.isOutOfDate)
      myLayoutlibSceneManager.model.notifyModified(NlModel.ChangeType.RESOURCE_CHANGED)
      assertTrue(myLayoutlibSceneManager.isOutOfDate)
      // Requesting a render which will clear the flag.
      myLayoutlibSceneManager.requestRenderAsync()
      assertFalse(myLayoutlibSceneManager.isOutOfDate)
    } finally {
      EssentialsMode.setEnabled(false, project)
    }
  }

  fun testChangingShowDecorationsForcesReinflate() {
    val defaultShowDecorations = myLayoutlibSceneManager.isShowingDecorations
    assertThat(myLayoutlibSceneManager.isForceReinflate).isFalse()

    myLayoutlibSceneManager.setShowDecorations(!defaultShowDecorations)
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()

    myLayoutlibSceneManager.setShowDecorations(defaultShowDecorations)
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()
  }

  fun testTransitioningFromInteractiveToStaticForcesReinflate() {
    // static to interactive
    myLayoutlibSceneManager.interactive = true
    assertThat(myLayoutlibSceneManager.isForceReinflate).isFalse()

    // interactive to static
    myLayoutlibSceneManager.interactive = false
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()
  }

  fun testChangingUsePrivateClassLoaderForcesReinflate() {
    val defaultIsUsePrivateClassLoader = myLayoutlibSceneManager.isUsePrivateClassLoader
    assertThat(myLayoutlibSceneManager.isForceReinflate).isFalse()

    myLayoutlibSceneManager.isUsePrivateClassLoader = !defaultIsUsePrivateClassLoader
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()

    myLayoutlibSceneManager.isUsePrivateClassLoader = defaultIsUsePrivateClassLoader
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()
  }

  fun testSettingShrinkRenderingForcesReinflate() {
    val defaultShrinkRendering = myLayoutlibSceneManager.isUseShrinkRendering
    assertThat(myLayoutlibSceneManager.isForceReinflate).isFalse()

    myLayoutlibSceneManager.setShrinkRendering(!defaultShrinkRendering)
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()

    myLayoutlibSceneManager.setShrinkRendering(defaultShrinkRendering)
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()
  }

  fun testSettingTransparentRenderingForcesReinflate() {
    val defaultTransparentRendering = myLayoutlibSceneManager.isUseTransparentRendering
    assertThat(myLayoutlibSceneManager.isForceReinflate).isFalse()

    myLayoutlibSceneManager.setTransparentRendering(!defaultTransparentRendering)
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()

    myLayoutlibSceneManager.setTransparentRendering(defaultTransparentRendering)
    assertThat(myLayoutlibSceneManager.isForceReinflate).isTrue()
  }

  override fun createModel(): ModelBuilder {
    return model(
      FD_RES_XML,
      "preference.xml",
      component(PREFERENCE_SCREEN)
        .withBounds(0, 0, 1000, 1000)
        .matchParentWidth()
        .matchParentHeight()
    )
  }
}
