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
package com.android.tools.idea.common.error

import com.android.annotations.concurrency.UiThread
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.common.surface.getDesignSurface
import com.android.tools.idea.common.type.DesignerEditorFileType
import com.android.tools.idea.common.type.typeOf
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.idea.uibuilder.type.DrawableFileType
import com.android.tools.idea.uibuilder.type.LayoutFileType
import com.android.tools.idea.uibuilder.type.MenuFileType
import com.android.tools.idea.uibuilder.type.PreferenceScreenFileType
import com.google.wireless.android.sdk.stats.UniversalProblemsPanelEvent
import com.intellij.analysis.problemsView.toolWindow.HighlightingPanel
import com.intellij.analysis.problemsView.toolWindow.ProblemsView
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewProjectErrorsPanelProvider
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.psi.PsiFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeModelAdapter
import javax.swing.event.TreeModelEvent
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.util.toPsiFile

private const val DEFAULT_SHARED_ISSUE_PANEL_TAB_TITLE = "Designer"
private const val SHARED_ISSUE_PANEL_TAB_NAME = "_Designer_Tab"

/** A service to help to show the issues of Design Tools in IJ's Problems panel. */
@Service(Service.Level.PROJECT)
class IssuePanelService(private val project: Project) {

  private val nameToTabMap: MutableMap<String, Pair<DesignerCommonIssuePanel, Content>> =
    mutableMapOf()

  private var inited = false

  private val fileToTabName = mutableMapOf<VirtualFile, String>()

  init {
    val manager = ToolWindowManager.getInstance(project)
    val problemsView = manager.getToolWindow(ProblemsView.ID)
    if (problemsView != null) {
      // ProblemsView has registered, init the tab.
      UIUtil.invokeLaterIfNeeded { initIssueTabs(problemsView) }
    } else {
      val connection = project.messageBus.connect()
      val listener: ToolWindowManagerListener
      listener =
        object : ToolWindowManagerListener {
          override fun toolWindowsRegistered(
            ids: MutableList<String>,
            toolWindowManager: ToolWindowManager
          ) {
            if (ProblemsView.ID in ids) {
              val problemsViewToolWindow = ProblemsView.getToolWindow(project)
              if (problemsViewToolWindow != null) {
                UIUtil.invokeLaterIfNeeded { initIssueTabs(problemsViewToolWindow) }
                connection.disconnect()
              }
            }
          }
        }
      connection.subscribe(ToolWindowManagerListener.TOPIC, listener)
    }
  }

  @UiThread
  fun initIssueTabs(problemsViewWindow: ToolWindow) {
    if (problemsViewWindow.isDisposed) {
      return
    }
    if (inited) {
      return
    }
    inited = true
    // This is the only common issue panel.
    val contentManager = problemsViewWindow.contentManager
    val contentFactory = contentManager.factory

    // The shared issue panel for all design tools.
    val issueProvider =
      DesignToolsIssueProvider(
        problemsViewWindow.disposable,
        project,
        NotSuppressedFilter + SelectedEditorFilter(project),
        null
      )
    val treeModel = DesignerCommonIssuePanelModelProvider.getInstance(project).createModel()
    val issuePanel =
      DesignerCommonIssuePanel(
        problemsViewWindow.disposable,
        project,
        treeModel,
        ::nodeFactoryProvider,
        issueProvider,
        ::getEmptyMessage
      )
    treeModel.addTreeModelListener(
      object : TreeModelAdapter() {
        override fun process(event: TreeModelEvent, type: EventType) {
          updateSharedIssuePanelTabName()
        }
      }
    )

    contentFactory.createContent(issuePanel.getComponent(), "Design Issue", true).apply {
      tabName = DESIGN_TOOL_TAB_NAME
      isPinnable = false
      isCloseable = false
      nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME] = Pair(issuePanel, this)
      contentManager.addContent(this@apply)
    }

    val contentManagerListener =
      object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) {
          val content = event.content
          val selectedTab =
            when {
              content.isTab(Tab.CURRENT_FILE) ->
                UniversalProblemsPanelEvent.ActivatedTab.CURRENT_FILE
              content.isTab(Tab.PROJECT_ERRORS) ->
                UniversalProblemsPanelEvent.ActivatedTab.PROJECT_ERRORS
              content.isTab(Tab.DESIGN_TOOLS) ->
                UniversalProblemsPanelEvent.ActivatedTab.DESIGN_TOOLS
              else -> UniversalProblemsPanelEvent.ActivatedTab.UNKNOWN_TAB
            }
          DesignerCommonIssuePanelUsageTracker.getInstance().trackSelectingTab(selectedTab, project)
        }
      }
    contentManager.addContentManagerListener(contentManagerListener)

    project.messageBus
      .connect(problemsViewWindow.disposable)
      .subscribe(
        FileEditorManagerListener.FILE_EDITOR_MANAGER,
        object : FileEditorManagerListener {
          override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            val editor = source.getSelectedEditor(file)
            updateIssuePanelVisibility(file, editor, true)
          }

          override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
            if (!source.hasOpenFiles()) {
              // There is no opened file, remove the tab.
              removeSharedIssueTabFromProblemsPanel()
            }
          }

          override fun selectionChanged(event: FileEditorManagerEvent) {
            updateIssuePanelVisibility(event.newFile, event.newEditor, false)
          }

          private fun updateIssuePanelVisibility(
            newFile: VirtualFile?,
            newEditor: FileEditor?,
            selectIfVisible: Boolean
          ) {
            if (newFile == null) {
              setSharedIssuePanelVisibility(false)
              return
            }
            if (isSupportedDesignerFileType(newFile)) {
              addIssuePanel()
              return
            }
            val surface = newEditor?.getDesignSurface()
            if (surface != null) {
              updateIssuePanelVisibility(newFile)
            } else {
              removeSharedIssueTabFromProblemsPanel()
            }
          }
        }
      )

    // If the shared issue panel is initialized after opening editor, the message bus misses the
    // file editor event. This may happen when opening a project. Make sure the initial status is
    // correct here.
    val isDesignFile =
      FileEditorManager.getInstance(project).selectedEditors.any { isDesignEditor(it) }
    if (isDesignFile) {
      // If the selected file is not a design file, just keeps the default status of problems
      // pane. Otherwise we need to make it selected the issue panel tab, no matter if the problems
      // pane is open or not.
      if (problemsViewWindow.isVisible) {
        setSharedIssuePanelVisibility(true)
      } else {
        problemsViewWindow.hide {
          updateSharedIssuePanelTabName()
          selectSharedIssuePanelTab()
        }
      }
    }
  }

  private fun updateIssuePanelVisibility(file: VirtualFile) {
    val psiFileType = file.toPsiFile(project)?.typeOf()
    if (psiFileType is DrawableFileType) {
      // We don't support Shared issue panel for Drawable files.
      removeSharedIssueTabFromProblemsPanel()
    } else {
      addIssuePanel()
    }
  }

  /**
   * Add shared issue panel into IJ's problems panel. If [selected] is true, select the shared issue
   * panel after added.
   */
  private fun addIssuePanel() {
    addSharedIssueTabToProblemsPanel()
    updateSharedIssuePanelTabName()
  }

  private fun selectSharedIssuePanelTab() {
    val tab = nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second ?: return
    tab.manager?.setSelectedContent(tab)
  }

  /**
   * Remove the shared issue tab from Problems Tool Window. Return true if the shared issue tab is
   * removed successfully, or false if it doesn't exist or is not in the Problems Tool Window (e.g.
   * has been removed before).
   */
  @VisibleForTesting
  fun removeSharedIssueTabFromProblemsPanel(): Boolean {
    val tab = nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second ?: return false
    val toolWindow = ProblemsView.getToolWindow(project) ?: return false
    val contentManager = toolWindow.contentManager
    contentManager.removeContent(tab, false)
    return true
  }

  /**
   * Add the shared issue tab into Problems Tool Window. Return true if the shared issue tab is
   * added successfully, or false if it doesn't exist or is in the Problems Tool Window already
   * (e.g. has been added before).
   */
  private fun addSharedIssueTabToProblemsPanel(): Boolean {
    val tab = nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second ?: return false
    val toolWindow = ProblemsView.getToolWindow(project) ?: return false
    val contentManager = toolWindow.contentManager
    if (contentManager.contents.contains(tab)) {
      return false
    }
    contentManager.addContent(tab, contentManager.contentCount)
    return true
  }

  /** Return whether the shared issue panel is showing. */
  fun isShowingIssuePanel(): Boolean {
    return isSharedIssueTabShowing(nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second)
  }

  /**
   * Set the visibility of IJ's problems pane and change the selected tab to the [selectedTab]. If
   * [selectedTab] is not given or not found, only the visibility of problems pane is changed.
   *
   * @see setSharedIssuePanelVisibility
   */
  fun setIssuePanelVisibility(visible: Boolean, selectedTab: Tab?) {
    if (selectedTab == Tab.DESIGN_TOOLS) {
      setSharedIssuePanelVisibility(visible)
      return
    }
    val problemsViewPanel = ProblemsView.getToolWindow(project) ?: return
    val contentManager = problemsViewPanel.contentManager
    val contentOfTab: Content? =
      if (selectedTab != null) contentManager.contents.firstOrNull { it.isTab(selectedTab) }
      else null
    val runnable: Runnable? =
      if (contentOfTab != null) Runnable { contentManager.setSelectedContent(contentOfTab) }
      else null
    if (visible) {
      problemsViewPanel.show(runnable)
    } else {
      problemsViewPanel.hide(runnable)
    }
  }

  /**
   * Set the visibility of shared issue panel. When [visible] is true, this opens the problem panel
   * and switch the tab to shared issue panel tab. The optional given [onAfterSettingVisibility] is
   * executed after the visibility is changed.
   */
  fun setSharedIssuePanelVisibility(visible: Boolean, onAfterSettingVisibility: Runnable? = null) {
    val tab = nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second ?: return
    val problemsViewPanel = ProblemsView.getToolWindow(project) ?: return
    DesignerCommonIssuePanelUsageTracker.getInstance()
      .trackChangingCommonIssuePanelVisibility(visible, project)
    if (visible) {
      if (!isSharedIssueTabShowing(tab)) {
        problemsViewPanel.show {
          updateSharedIssuePanelTabName()
          selectSharedIssuePanelTab()
          onAfterSettingVisibility?.run()
        }
      }
    } else {
      problemsViewPanel.hide { onAfterSettingVisibility?.run() }
    }
  }

  @TestOnly
  fun isSharedIssuePanelAddedToProblemsPane(): Boolean {
    val tab = nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second ?: return false
    val problemsViewPanel = ProblemsView.getToolWindow(project) ?: return false
    return problemsViewPanel.contentManager.contents.any { it === tab }
  }

  fun getSharedPanelIssues() =
    nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.first?.issueProvider?.getFilteredIssues()

  /** Update the tab name (includes the issue count) of shared issue panel. */
  private fun updateSharedIssuePanelTabName() {
    val tab = nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second ?: return
    val count =
      nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]
        ?.first
        ?.issueProvider
        ?.getFilteredIssues()
        ?.distinct()
        ?.size
    // This change the ui text, run it in the UI thread.
    runInEdt { tab.displayName = createTabName(getSharedIssuePanelTabTitle(), count) }
  }

  /** Get the title of shared issue panel. The returned string doesn't include the issue count. */
  @VisibleForTesting
  fun getSharedIssuePanelTabTitle(): String {
    val editors =
      FileEditorManager.getInstance(project).selectedEditors
        ?: return DEFAULT_SHARED_ISSUE_PANEL_TAB_TITLE
    if (editors.size != 1) {
      // TODO: What tab name should be show when opening multiple file editor?
      return DEFAULT_SHARED_ISSUE_PANEL_TAB_TITLE
    }
    val file = editors[0].file ?: return DEFAULT_SHARED_ISSUE_PANEL_TAB_TITLE

    fileToTabName[file]?.let {
      return it
    }

    val name = getTabNameOfSupportedDesignerFile(file)
    if (name != null) {
      return name
    }
    val surface = editors[0].getDesignSurface() ?: return DEFAULT_SHARED_ISSUE_PANEL_TAB_TITLE
    if (surface.name != null) {
      return surface.name
    }
    return DEFAULT_SHARED_ISSUE_PANEL_TAB_TITLE
  }

  /**
   * Return true if the given editor is Design Editor (e.g. compose editor, layout editor,
   * navigation editor, ...)
   */
  private fun isDesignEditor(editor: FileEditor): Boolean {
    val virtualFile = editor.file ?: return false
    return isSupportedDesignerFileType(virtualFile) || editor.getDesignSurface() != null
  }

  private fun isComposeFile(file: VirtualFile): Boolean {
    val extension = file.extension
    val fileType = file.fileType
    val psiFile = file.toPsiFile(project)
    return extension == KotlinFileType.INSTANCE.defaultExtension &&
      fileType == KotlinFileType.INSTANCE &&
      psiFile?.getModuleSystem()?.usesCompose == true
  }

  private fun isSupportedDesignerFileType(file: VirtualFile): Boolean {
    return getTabNameOfSupportedDesignerFile(file) != null
  }

  /** Returns null if the given file is not the supported [DesignerEditorFileType]. */
  private fun getTabNameOfSupportedDesignerFile(file: VirtualFile): String? {
    val psiFile = file.toPsiFile(project) ?: return null
    return when {
      isComposeFile(file) -> "Compose"
      LayoutFileType.isResourceTypeOf(psiFile) -> "Layout and Qualifiers"
      PreferenceScreenFileType.isResourceTypeOf(psiFile) -> "Preference"
      MenuFileType.isResourceTypeOf(psiFile) -> "Menu"
      else -> null
    }
  }

  /** Return the message to display when there is no issue in the given files. */
  private fun getEmptyMessage(): String {
    val files = FileEditorManager.getInstance(project).selectedEditors.mapNotNull { it.file }
    if (files.isEmpty()) {
      return "No problems found"
    }

    val psiFiles =
      ApplicationManager.getApplication().runReadAction<List<PsiFile>> {
        files.mapNotNull { it.toPsiFile(project) }
      }
    val fileNameString = files.joinToString { it.name }
    return if (
      psiFiles.size == files.size && psiFiles.all { LayoutFileType.isResourceTypeOf(it) }
    ) {
      "No layout problems in $fileNameString and qualifiers"
    } else {
      "No problems in $fileNameString"
    }
  }

  private fun nodeFactoryProvider(): NodeFactory {
    // TODO Return UICheckNodeFactory for compose files.
    return LayoutValidationNodeFactory
  }

  /**
   * Select the highest severity issue related to the provided [NlComponent] and scroll the viewport
   * to issue.
   */
  fun showIssueForComponent(surface: DesignSurface<*>, component: NlComponent) {
    val issueModel = surface.issueModel
    val issue: Issue = issueModel.getHighestSeverityIssue(component) ?: return
    setSharedIssuePanelVisibility(true)
    setSelectedNode(IssueNodeVisitor(issue))
  }

  /** Return the visibility of the issue panel. */
  fun isIssuePanelVisible(): Boolean {
    return isSharedIssueTabShowing(nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second)
  }

  /**
   * Return true if IJ's problem panel is visible and selecting the given [tab], false otherwise.
   */
  private fun isSharedIssueTabShowing(tab: Content?): Boolean {
    if (tab == null) {
      return false
    }
    val problemsViewPanel = ProblemsView.getToolWindow(project) ?: return false
    if (!problemsViewPanel.isVisible || tab !in problemsViewPanel.contentManager.contents) {
      return false
    }
    return tab.isSelected
  }

  fun getSelectedSharedIssuePanel(): DesignerCommonIssuePanel? {
    return if (nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.second?.isSelected == true)
      nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.first
    else null
  }

  /** Focus IJ's problems pane if Problems Panel is visible. Or do nothing otherwise. */
  fun focusIssuePanelIfVisible() {
    val problemsViewPanel = ProblemsView.getToolWindow(project) ?: return
    if (problemsViewPanel.isVisible) {
      problemsViewPanel.activate(null, true)
    }
  }

  /** Get the selected issues from the selected issue panel. */
  fun getSelectedIssues(): List<Issue> {
    val issuePanel = ProblemsView.getToolWindow(project)?.contentManager?.selectedContent
    val issuePanelComponent = issuePanel?.component ?: return emptyList()
    return DataManager.getInstance().getDataContext(issuePanelComponent).getData(SELECTED_ISSUES)
      ?: emptyList()
  }

  /**
   * Register a file to the corresponding [DesignSurface] and make sure to unregister it when the
   * surface is disposed.
   */
  fun registerFileToSurface(file: VirtualFile, surface: DesignSurface<*>) {
    Disposer.register(surface) { unregisterFile(file) }
    registerFile(file, surface.name)
  }

  /**
   * Register a file which should have the shared issue panel. [tabTitle] indicated the preferred
   * tab name of this file.
   */
  fun registerFile(file: VirtualFile, tabTitle: String?) {
    if (tabTitle != null) {
      fileToTabName[file] = tabTitle
    } else {
      fileToTabName.remove(file)
    }
    if (FileEditorManager.getInstance(project).selectedEditors.any { it.file == file }) {
      updateIssuePanelVisibility(file)
    }
  }

  fun unregisterFile(file: VirtualFile) {
    fileToTabName.remove(file)
  }

  /** Select the node by using the given [TreeVisitor] */
  fun setSelectedNode(nodeVisitor: TreeVisitor) {
    nameToTabMap[SHARED_ISSUE_PANEL_TAB_NAME]?.first?.setSelectedNode(nodeVisitor)
  }

  @UiThread
  fun startUiCheck(
    parentDisposable: Disposable,
    name: String,
    displayName: String,
    surface: NlDesignSurface,
    additionalDataProvider: DataProvider
  ) {
    val contentManager =
      ToolWindowManager.getInstance(project).getToolWindow(ProblemsView.ID)?.contentManager
        ?: return

    var uiCheckIssuePanel = nameToTabMap[name]?.first
    if (uiCheckIssuePanel == null) {
      uiCheckIssuePanel =
        DesignerCommonIssuePanel(
          parentDisposable,
          project,
          DesignerCommonIssuePanelModelProvider.getInstance(project).createModel(),
          { UICheckNodeFactory },
          DesignToolsIssueProvider(parentDisposable, project, NotSuppressedFilter, name),
          { "UI Check did not find any issues to report" },
          additionalDataProvider
        )

      val tab =
        contentManager.factory
          .createContent(uiCheckIssuePanel.getComponent(), displayName, true)
          .apply {
            tabName = name
            isPinnable = true
            isCloseable = true
          }

      contentManager.addContent(tab)
      contentManager.setSelectedContent(tab)
      contentManager.addContentManagerListener(
        object : ContentManagerListener {
          override fun contentRemoved(event: ContentManagerEvent) {
            if (tab == event.content) {
              nameToTabMap.remove(name)
            }
          }
        }
      )
      Disposer.register(parentDisposable) { contentManager.removeContent(tab, true) }
      nameToTabMap[name] = Pair(uiCheckIssuePanel, tab)
    }
    uiCheckIssuePanel.addIssueSelectionListener(surface.issuePanelSelectionListener)
    contentManager.addContentManagerListener(surface.issuePanelTabListener)

    surface.visualLintIssueProvider.uiCheckInstanceId = name
  }

  fun stopUiCheck(name: String, surface: NlDesignSurface) {
    nameToTabMap[name]?.first?.removeIssueSelectionListener(surface.issuePanelSelectionListener)
    ToolWindowManager.getInstance(project)
      .getToolWindow(ProblemsView.ID)
      ?.contentManager
      ?.removeContentManagerListener(surface.issuePanelTabListener)
    surface.visualLintIssueProvider.uiCheckInstanceId = null
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project): IssuePanelService =
      project.getService(IssuePanelService::class.java)

    val SELECTED_ISSUES =
      DataKey.create<List<Issue>>(DesignerCommonIssuePanel::class.java.name + "_selectedIssues")

    const val DESIGN_TOOL_TAB_NAME = "Designer"
  }

  /**
   * List of the possible tabs of Problems pane. This is used by [setIssuePanelVisibility] to assign
   * the selected tab after changing the visibility of problems pane.
   */
  enum class Tab {
    CURRENT_FILE,
    PROJECT_ERRORS,
    DESIGN_TOOLS
  }
}

@VisibleForTesting
fun Content.isTab(tab: IssuePanelService.Tab): Boolean {
  return when (tab) {
    IssuePanelService.Tab.DESIGN_TOOLS -> this.tabName == IssuePanelService.DESIGN_TOOL_TAB_NAME
    IssuePanelService.Tab.CURRENT_FILE ->
      (this.component as? ProblemsViewTab)?.getTabId() == HighlightingPanel.ID
    IssuePanelService.Tab.PROJECT_ERRORS ->
      (this.component as? ProblemsViewTab)?.getTabId() == ProblemsViewProjectErrorsPanelProvider.ID
  }
}

/**
 * Helper function to set the issue panel in [DesignSurface] without tracking it into the layout
 * editor metrics.
 *
 * @param show whether to show or hide the issue panel.
 * @param onAfterSettingVisibility optional task to execute after the visibility of issue panel is
 *   changed.
 */
fun DesignSurface<*>.setIssuePanelVisibilityNoTracking(
  show: Boolean,
  onAfterSettingVisibility: Runnable? = null
) {
  IssuePanelService.getInstance(project)
    .setSharedIssuePanelVisibility(show, onAfterSettingVisibility)
}

/**
 * Helper function to set the issue panel in [DesignSurface].
 *
 * @param show whether to show or hide the issue panel.
 * @param runnable optional task to execute after the visibility of issue panel is changed.
 *
 * TODO(b/300646581): Revisit this function to see if we can remove the DesignSurface dependency.
 */
fun DesignSurface<*>.setIssuePanelVisibility(show: Boolean, runnable: Runnable? = null) {
  analyticsManager.trackShowIssuePanel()
  setIssuePanelVisibilityNoTracking(show, runnable)
}

/**
 * This should be same as [com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanel.getName]
 * for consistency.
 */
@VisibleForTesting
@NlsContexts.TabTitle
fun createTabName(title: String, issueCount: Int?): String {
  val count: Int = issueCount ?: 0
  val name: String = title
  val padding = (if (count <= 0) 0 else JBUI.scale(8)).toString()
  val fg = ColorUtil.toHtmlColor(NamedColorUtil.getInactiveTextColor())
  val number = if (count <= 0) "" else count.toString()

  @Language("HTML")
  val labelWithCounter =
    "<html><body>" +
      "<table cellpadding='0' cellspacing='0'><tr>" +
      "<td><nobr>%s</nobr></td>" +
      "<td width='%s'></td>" +
      "<td><font color='%s'>%s</font></td>" +
      "</tr></table></body></html>"
  return String.format(labelWithCounter, name, padding, fg, number)
}
