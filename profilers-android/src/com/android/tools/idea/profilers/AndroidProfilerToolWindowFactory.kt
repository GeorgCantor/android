/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.profilers

import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.profilers.tasks.OpenProfilerTaskListener
import com.android.tools.idea.IdeInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import icons.StudioIcons
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

class AndroidProfilerToolWindowFactory : DumbAware, ToolWindowFactory {

  override val icon = if (IdeInfo.getInstance().isAndroidStudio) {
    StudioIcons.Shell.ToolWindows.ANDROID_PROFILER
  } else {
    AllIcons.Toolwindows.ToolWindowProfilerAndroid
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    if (StudioFlags.PROFILER_TASK_BASED_UX.get()) {

      // Create the profiler window.
      val profilerToolWindow = createProfilerToolWindow(project, toolWindow)

      // Create the home tab.
      profilerToolWindow.openHomeTab()
      toolWindow.isAvailable = true

      // If the window is re-opened after all tabs were manually closed, re-create the home tab.
      project.messageBus.connect().subscribe(
        ToolWindowManagerListener.TOPIC,
        object : ToolWindowManagerListener {
          override fun toolWindowShown(shownToolWindow: ToolWindow) {
            if (toolWindow === shownToolWindow && toolWindow.isVisible && toolWindow.contentManager.isEmpty) {
              profilerToolWindow.openHomeTab()
            }
          }
        })

      // Listen for events requesting that a task tab be opened.
      project.messageBus.connect(toolWindow.disposable).subscribe(OpenProfilerTaskListener.TOPIC, OpenProfilerTaskListener { _, _ ->
        AndroidCoroutineScope(toolWindow.disposable).launch {
          withContext(AndroidDispatchers.uiThread) {
            profilerToolWindow.openTaskTab()
            toolWindow.activate(null)
          }
        }
      })

      return
    }

    project.messageBus.connect().subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
      override fun stateChanged(toolWindowManager: ToolWindowManager) {
        // We need to query the tool window again, because it might have been unregistered when closing the project.
        val window = toolWindowManager.getToolWindow(ID) ?: return
        val profilerToolWindow = getProfilerToolWindow(project)
        if (window.isVisible && profilerToolWindow == null) {
          createContent(project, window)
        }
      }
    })
  }

  override fun init(toolWindow: ToolWindow) {
    toolWindow.stripeTitle = PROFILER_TOOL_WINDOW_TITLE
    // Android Studio wants to always show the stripe button for profiler. It's a common entry point into profiler for Studio users.
    toolWindow.isShowStripeButton = true

    // When we initialize the ToolWindow we call to the profiler service to also make sure it is initialized.
    // The default behavior for intellij is to lazy load services so having this call here forces intellij to
    // load the AndroidProfilerService registering the data and callbacks required for initializing the profilers.
    // Note: The AndroidProfilerService is where all application level components should be managed. This means if
    // we have something that impacts the TransportPipeline or should be done only once for X instances of
    // profilers or projects it will need to be handled there.
    AndroidProfilerService.getInstance()
  }

  companion object {
    const val ID = "Android Profiler"
    @Nls
    private val PROFILER_TOOL_WINDOW_TITLE = if (IdeInfo.getInstance().isAndroidStudio) "Profiler" else AndroidProfilerBundle.message("android.profiler.tool.window.title")
    private val PROJECT_PROFILER_MAP: MutableMap<Project, AndroidProfilerToolWindow> = HashMap()
    private fun createContent(project: Project, toolWindow: ToolWindow) {
      val view = createProfilerToolWindow(project, toolWindow)
      val contentFactory = ContentFactory.getInstance()
      val content = contentFactory.createContent(view.profilersPanel, "", false)
      Disposer.register(project, view)
      toolWindow.contentManager.addContent(content)

      Disposer.register(content) { PROJECT_PROFILER_MAP.remove(project) }

      // Forcibly synchronize the Tool Window to a visible state. Otherwise, the Tool Window may not auto-hide correctly.
      toolWindow.show(null)
    }

    private fun createProfilerToolWindow(
      project: Project,
      toolWindow: ToolWindow
    ): AndroidProfilerToolWindow {
      val wrapper = ToolWindowWrapperImpl(project, toolWindow)
      val profilerToolWindow = AndroidProfilerToolWindow(wrapper, project)
      toolWindow.setIcon(StudioIcons.Shell.ToolWindows.ANDROID_PROFILER)
      PROJECT_PROFILER_MAP[project] = profilerToolWindow
      return profilerToolWindow
    }

    /**
     * Gets the [AndroidProfilerToolWindow] corresponding to a given [Project] if it was already created. Otherwise, returns null.
     */
    @JvmStatic
    fun getProfilerToolWindow(project: Project): AndroidProfilerToolWindow? {
      val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return null
      val contentManager = window.contentManager
      return if (contentManager.contentCount == 0) null else PROJECT_PROFILER_MAP[project]
    }

    fun removeContent(toolWindow: ToolWindow) {
      if (toolWindow.contentManager.contentCount > 0) {
        toolWindow.contentManager.removeAllContents(true)
      }
    }
  }
}
