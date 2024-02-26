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
package com.android.tools.idea.preview.views

import com.android.annotations.concurrency.UiThread
import com.android.tools.adtui.stdui.ActionData
import com.android.tools.adtui.stdui.UrlData
import com.android.tools.adtui.workbench.WorkBench
import com.android.tools.idea.actions.DESIGN_SURFACE
import com.android.tools.idea.common.editor.ActionsToolbar
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.preview.gallery.GalleryModeProperty
import com.android.tools.idea.preview.mvvm.PreviewRepresentationView
import com.android.tools.idea.preview.mvvm.PreviewView
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.OverlayLayout

/** A simple implementation of [PreviewView] based on [NlDesignSurface]. */
class CommonNlDesignSurfacePreviewView(
  private val project: Project,
  surfaceBuilder: NlDesignSurface.Builder,
  parentDisposable: Disposable,
) : PreviewView, PreviewRepresentationView {

  override val mainSurface = surfaceBuilder.build()

  private val actionsToolbar = ActionsToolbar(parentDisposable, mainSurface)

  private val galleryModeProperty: GalleryModeProperty

  private val editorPanel =
    JPanel(BorderLayout()).apply {
      add(actionsToolbar.toolbarComponent, BorderLayout.NORTH)

      val overlayPanel =
        object : JPanel() {
          // Since the overlay panel is transparent, we can not use optimized drawing or it will
          // produce rendering artifacts.
          override fun isOptimizedDrawingEnabled(): Boolean = false
        }

      overlayPanel.apply {
        layout = OverlayLayout(this)

        add(mainSurface)
      }

      add(overlayPanel, BorderLayout.CENTER)

      galleryModeProperty = GalleryModeProperty(overlayPanel, mainSurface)
    }

  private val workbench: WorkBench<DesignSurface<*>> =
    object :
        WorkBench<DesignSurface<*>>(project, "Main Preview", null, parentDisposable), DataProvider {
        override fun getData(dataId: String): Any? =
          if (DESIGN_SURFACE.`is`(dataId)) mainSurface else null
      }
      .apply { init(editorPanel, mainSurface, listOf(), false) }

  @UiThread
  override fun showErrorMessage(
    message: String,
    recoveryUrl: UrlData?,
    actionToRecover: ActionData?,
  ) {
    workbench.hideContent()
    workbench.loadingStopped(message, null, recoveryUrl, actionToRecover)
  }

  @UiThread
  override fun showLoadingMessage(message: String) {
    workbench.showLoading(message)
  }

  @UiThread
  override fun showContent() {
    workbench.hideLoading()
    workbench.showContent()
  }

  @UiThread
  override fun updateToolbar() {
    actionsToolbar.updateActions()
  }

  override val component: JComponent
    get() = workbench

  override var galleryMode by galleryModeProperty
}
