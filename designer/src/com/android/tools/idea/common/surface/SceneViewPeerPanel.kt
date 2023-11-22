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
package com.android.tools.idea.common.surface

import com.android.tools.adtui.common.SwingCoordinate
import com.android.tools.idea.common.model.scaleBy
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.uibuilder.scene.hasRenderErrors
import com.android.tools.idea.uibuilder.surface.layout.PositionableContent
import com.android.tools.idea.uibuilder.surface.layout.getScaledContentSize
import com.android.tools.idea.uibuilder.surface.layout.horizontal
import com.android.tools.idea.uibuilder.surface.layout.margin
import com.android.tools.idea.uibuilder.surface.layout.scaledContentSize
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Distance between the bottom bound of model name and top bound of SceneView. */
@SwingCoordinate private const val TOP_BAR_BOTTOM_MARGIN = 3

/** Distance between the top bound of bottom bar and bottom bound of SceneView. */
@SwingCoordinate private const val BOTTOM_BAR_TOP_MARGIN = 3

/** Minimum allowed width for the SceneViewPeerPanel. */
@SwingCoordinate private const val SCENE_VIEW_PEER_PANEL_MIN_WIDTH = 100

/** Minimum allowed width for the model name label. */
@SwingCoordinate private const val MODEL_NAME_LABEL_MIN_WIDTH = 20

private data class LayoutData
private constructor(
  val scale: Double,
  val modelName: String?,
  val modelTooltip: String?,
  val x: Int,
  val y: Int,
  val scaledSize: Dimension
) {

  // Used to avoid extra allocations in isValidFor calls
  private val cachedDimension = Dimension()

  /**
   * Returns whether this [LayoutData] is still valid (has not changed) for the given [SceneView]
   */
  fun isValidFor(sceneView: SceneView): Boolean =
    scale == sceneView.scale &&
      x == sceneView.x &&
      y == sceneView.y &&
      modelName == sceneView.scene.sceneManager.model.modelDisplayName &&
      scaledSize == sceneView.getContentSize(cachedDimension).scaleBy(sceneView.scale)

  companion object {
    fun fromSceneView(sceneView: SceneView): LayoutData =
      LayoutData(
        sceneView.scale,
        sceneView.scene.sceneManager.model.modelDisplayName,
        sceneView.scene.sceneManager.model.modelTooltip,
        sceneView.x,
        sceneView.y,
        sceneView.getContentSize(null).scaleBy(sceneView.scale)
      )
  }
}

private val nameLabelDefaultColor = JBColor(0x6c707e, 0xdfe1e5)
private val nameLabelHoverColor = JBColor(0x5a5d6b, 0xf0f1f2)

/**
 * A Swing component associated to the given [SceneView]. There will be one of this components in
 * the [DesignSurface] per every [SceneView] available. This panel will be positioned on the
 * coordinates of the [SceneView] and can be used to paint Swing elements on top of the [SceneView].
 */
class SceneViewPeerPanel(
  val sceneView: SceneView,
  disposable: Disposable,
  private val sceneViewStatusIcon: JComponent?,
  private val sceneViewToolbar: JComponent?,
  private val sceneViewBottomBar: JComponent?,
  private val sceneViewLeftBar: JComponent?,
  private val sceneViewRightBar: JComponent?,
  private val sceneViewErrorsPanel: JComponent?,
  private val onLabelClicked: (suspend (SceneView, Boolean) -> Boolean)
) : JPanel() {

  private val scope = AndroidCoroutineScope(disposable)

  /**
   * Contains cached layout data that can be used by this panel to verify when it's been invalidated
   * without having to explicitly call [revalidate]
   */
  private var layoutData = LayoutData.fromSceneView(sceneView)

  private val cachedContentSize = Dimension()
  private val cachedScaledContentSize = Dimension()
  private val cachedPreferredSize = Dimension()

  /** This label displays the [SceneView] model if there is any */
  private val modelNameLabel =
    JBLabel().apply {
      maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
      foreground = nameLabelDefaultColor
      addMouseListener(
        object : MouseAdapter() {
          override fun mouseEntered(e: MouseEvent?) {
            foreground = nameLabelHoverColor
          }

          override fun mouseExited(e: MouseEvent?) {
            foreground = nameLabelDefaultColor
          }

          override fun mouseClicked(e: MouseEvent?) {
            scope.launch { onLabelClicked(sceneView, false) }
          }
        }
      )
    }

  val positionableAdapter =
    object : PositionableContent {
      override val groupId: String?
        get() = this@SceneViewPeerPanel.sceneView.sceneManager.model.groupId

      override val scale: Double
        get() = sceneView.scale

      override val x: Int
        get() = sceneView.x
      override val y: Int
        get() = sceneView.y
      override val isVisible: Boolean
        get() = sceneView.isVisible

      override fun getMargin(scale: Double): Insets {
        val contentSize = getContentSize(null).scaleBy(scale)
        val sceneViewMargin =
          sceneView.margin.also {
            // Extend top to account for the top toolbar
            it.top += sceneViewTopPanel.preferredSize.height
            if (sceneViewErrorsPanel?.isVisible == true) {
              // Calculating panel margins to always keep shown the error panel when zooming
              it.bottom += sceneViewCenterPanel.preferredSize.height
              it.left += sceneViewLeftPanel.preferredSize.width
              it.right += sceneViewRightPanel.preferredSize.width
            }
          }

        return if (contentSize.width < minimumSize.width) {
          // If there is no content, or the content is smaller than the minimum size,
          // pad the margins horizontally to occupy the empty space.
          val horizontalPadding = (minimumSize.width - contentSize.width).coerceAtLeast(0)

          JBUI.insets(
            sceneViewMargin.top,
            sceneViewMargin.left,
            sceneViewMargin.bottom,
            // The content is aligned on the left
            sceneViewMargin.right + horizontalPadding
          )
        } else {
          sceneViewMargin
        }
      }

      override fun getContentSize(dimension: Dimension?): Dimension =
        if (sceneView.hasContentSize())
          sceneView.getContentSize(dimension).also { cachedContentSize.size = it }
        else if (!sceneView.isVisible || sceneView.hasRenderErrors()) {
          dimension?.apply { setSize(0, 0) } ?: Dimension(0, 0)
        } else {
          dimension?.apply { size = cachedContentSize } ?: Dimension(cachedContentSize)
        }

      /** Applies the calculated coordinates from this adapter to the backing SceneView. */
      private fun applyLayout() {
        getScaledContentSize(cachedScaledContentSize)
        val margin = this.margin // To avoid recalculating the size
        setBounds(
          x - margin.left,
          y - margin.top,
          cachedScaledContentSize.width + margin.left + margin.right,
          cachedScaledContentSize.height + margin.top + margin.bottom
        )
        sceneView.scene.needsRebuildList()
      }

      override fun setLocation(x: Int, y: Int) {
        // The SceneView is painted right below the top toolbar panel.
        // This set the top-left corner of preview.
        sceneView.setLocation(x, y)

        // After positioning the view, we re-apply the bounds to the SceneViewPanel.
        // We do this even if x & y did not change since the size might have.
        applyLayout()
      }
    }

  fun PositionableContent.isEmptyContent() =
    scaledContentSize.let { it.height == 0 && it.width == 0 }

  /**
   * This panel wraps both the label and the toolbar and puts them left aligned (label) and right
   * aligned (the toolbar).
   */
  @VisibleForTesting
  val sceneViewTopPanel =
    JPanel(BorderLayout()).apply {
      border = JBUI.Borders.emptyBottom(TOP_BAR_BOTTOM_MARGIN)
      isOpaque = false
      // Make the status icon be part of the top panel
      val sceneViewStatusIconSize = sceneViewStatusIcon?.minimumSize?.width ?: 0
      if (sceneViewStatusIcon != null && sceneViewStatusIconSize > 0) {
        add(sceneViewStatusIcon, BorderLayout.LINE_START)
        sceneViewStatusIcon.isVisible = true
      }
      add(modelNameLabel, BorderLayout.CENTER)
      if (sceneViewToolbar != null) {
        add(sceneViewToolbar, BorderLayout.LINE_END)
        // Initialize the toolbar as invisible. Its visibility will be controlled by hovering the
        // sceneViewTopPanel.
        sceneViewToolbar.isVisible = false
      }
      // The space of name label is sacrificed when there is no enough width to display the toolbar.
      // When it happens, the label will be trimmed and show the ellipsis at its tail.
      // User can still hover it to see the full label in the tooltips.
      val minWidth =
        sceneViewStatusIconSize +
          MODEL_NAME_LABEL_MIN_WIDTH +
          (sceneViewToolbar?.minimumSize?.width ?: 0)
      // Since sceneViewToolbar visibility can change, sceneViewTopPanel (its container) might want
      // to reduce its size when sceneViewToolbar
      // gets invisible, resulting in a visual misbehavior where the toolbar moves a little when the
      // actions appear/disappear. To fix this,
      // we should set sceneViewTopPanel preferred size to always occupy the height taken by
      // sceneViewToolbar when it exists.
      val minHeight =
        maxOf(
          minimumSize.height,
          sceneViewToolbar?.preferredSize?.height ?: 0,
          sceneViewToolbar?.minimumSize?.height ?: 0
        )
      minimumSize = Dimension(minWidth, minHeight)
      preferredSize = sceneViewToolbar?.let { Dimension(minWidth, minHeight) }

      setUpTopPanelMouseListeners()
    }

  /**
   * Creates and adds the [MouseAdapter]s required to show the [sceneViewToolbar] when the mouse is
   * hovering the [sceneViewTopPanel], and hide it otherwise.
   */
  private fun JPanel.setUpTopPanelMouseListeners() {
    // MouseListener to show the sceneViewToolbar when the mouse enters the target component, and to
    // hide it when the mouse exits the bounds
    // of sceneViewTopPanel.
    val hoverTopPanelMouseListener =
      object : MouseAdapter() {

        override fun mouseEntered(e: MouseEvent?) {
          // Show the toolbar actions when mouse is hovering the top panel.
          sceneViewToolbar?.let { it.isVisible = true }
        }

        override fun mouseExited(e: MouseEvent?) {
          SwingUtilities.getWindowAncestor(this@setUpTopPanelMouseListeners)?.let {
            if (!it.isFocused) {
              // Dismiss the toolbar if the current window loses focus, e.g. when alt tabbing.
              hideToolbar()
              return@mouseExited
            }
          }

          e?.locationOnScreen?.let {
            SwingUtilities.convertPointFromScreen(it, this@setUpTopPanelMouseListeners)
            // Hide the toolbar when the mouse exits the bounds of sceneViewTopPanel or the
            // containing design surface.
            if (!containsExcludingBorder(it) || !designSurfaceContains(e.locationOnScreen)) {
              hideToolbar()
            } else {
              // We've exited to one of the toolbar actions, so we need to make sure this listener
              // is algo registered on them.
              sceneViewToolbar?.let { toolbar ->
                for (i in 0 until toolbar.componentCount) {
                  toolbar
                    .getComponent(i)
                    .removeMouseListener(this) // Prevent duplicate listeners being added.
                  toolbar.getComponent(i).addMouseListener(this)
                }
              }
            }
          }
            ?: hideToolbar()
        }

        private fun JPanel.designSurfaceContains(p: Point): Boolean {
          var component = parent
          var designSurface: DesignSurfaceScrollPane? = null
          while (component != null) {
            if (component is DesignSurfaceScrollPane) {
              designSurface = component
              break
            }
            component = component.parent
          }
          if (designSurface == null) return false
          SwingUtilities.convertPointFromScreen(p, designSurface)
          // Consider the scrollbar width exiting from the right
          return p.x in 0 until (designSurface.width - UIUtil.getScrollBarWidth()) &&
            p.y in 0 until designSurface.height
        }

        private fun JPanel.containsExcludingBorder(p: Point): Boolean {
          val borderInsets = border.getBorderInsets(this@setUpTopPanelMouseListeners)
          return p.x in borderInsets.left until (width - borderInsets.right) &&
            p.y in borderInsets.top until (height - borderInsets.bottom)
        }

        private fun hideToolbar() {
          sceneViewToolbar?.let { toolbar -> toolbar.isVisible = false }
        }
      }

    addMouseListener(hoverTopPanelMouseListener)
    modelNameLabel.addMouseListener(hoverTopPanelMouseListener)
  }

  val sceneViewBottomPanel =
    JPanel(BorderLayout()).apply {
      border = JBUI.Borders.emptyTop(BOTTOM_BAR_TOP_MARGIN)
      isOpaque = false
      isVisible = true
      if (sceneViewBottomBar != null) {
        add(sceneViewBottomBar, BorderLayout.CENTER)
      }
    }

  val sceneViewLeftPanel =
    JPanel(BorderLayout()).apply {
      isOpaque = false
      isVisible = true
      if (sceneViewLeftBar != null) {
        add(sceneViewLeftBar, BorderLayout.CENTER)
      }
    }

  val sceneViewRightPanel =
    JPanel(BorderLayout()).apply {
      isOpaque = false
      isVisible = true
      if (sceneViewRightBar != null) {
        add(sceneViewRightBar, BorderLayout.CENTER)
      }
    }

  val sceneViewCenterPanel =
    JPanel(BorderLayout()).apply {
      isOpaque = false
      isVisible = true
      if (sceneViewErrorsPanel != null) {
        add(sceneViewErrorsPanel, BorderLayout.CENTER)
      }
    }

  init {
    isOpaque = false
    layout = null

    add(sceneViewTopPanel)
    add(sceneViewCenterPanel)
    add(sceneViewBottomPanel)
    add(sceneViewLeftPanel)
    add(sceneViewRightPanel)
    // This setup the initial positions of sceneViewTopPanel, sceneViewCenterPanel,
    // sceneViewBottomPanel, and sceneViewLeftPanel.
    // Otherwise they are all placed at top-left corner before first time layout.
    doLayout()
  }

  override fun isValid(): Boolean {
    return super.isValid() && layoutData.isValidFor(sceneView)
  }

  override fun doLayout() {
    layoutData = LayoutData.fromSceneView(sceneView)

    // If there is a model name, we manually assign the content of the modelNameLabel and position
    // it here.
    // Once this panel gets more functionality, we will need the use of a layout manager. For now,
    // we just lay out the component manually.
    if (layoutData.modelName == null) {
      modelNameLabel.text = ""
      modelNameLabel.toolTipText = ""
      sceneViewTopPanel.isVisible = false
    } else {
      modelNameLabel.text = layoutData.modelName
      // Use modelName for tooltip if none has been specified.
      modelNameLabel.toolTipText = layoutData.modelTooltip ?: layoutData.modelName
      // We layout the top panel. We make the width to match the SceneViewPanel width and we let it
      // choose its own
      // height.
      sceneViewTopPanel.setBounds(
        0,
        0,
        width + insets.horizontal,
        sceneViewTopPanel.preferredSize.height
      )
      sceneViewTopPanel.isVisible = true
    }
    val leftSectionWidth = sceneViewLeftPanel.preferredSize.width
    val centerPanelHeight =
      if (positionableAdapter.isEmptyContent()) {
        sceneViewCenterPanel.preferredSize.height
      } else {
        positionableAdapter.scaledContentSize.height
      }
    sceneViewCenterPanel.setBounds(
      leftSectionWidth,
      sceneViewTopPanel.preferredSize.height,
      width + insets.horizontal - leftSectionWidth,
      centerPanelHeight
    )
    sceneViewBottomPanel.setBounds(
      0,
      sceneViewTopPanel.preferredSize.height + centerPanelHeight,
      width + insets.horizontal,
      sceneViewBottomPanel.preferredSize.height
    )
    sceneViewLeftPanel.setBounds(
      0,
      sceneViewTopPanel.preferredSize.height,
      sceneViewLeftPanel.preferredSize.width,
      centerPanelHeight
    )
    sceneViewRightPanel.setBounds(
      sceneViewLeftPanel.preferredSize.width + sceneViewCenterPanel.width,
      sceneViewTopPanel.preferredSize.height,
      sceneViewRightPanel.preferredSize.width,
      centerPanelHeight
    )
    super.doLayout()
  }

  /** [Dimension] used to avoid extra allocations calculating [getPreferredSize] */
  override fun getPreferredSize(): Dimension =
    positionableAdapter.getScaledContentSize(cachedPreferredSize).also {
      val shouldShowCenterPanel = it.width == 0 && it.height == 0
      val width = if (shouldShowCenterPanel) sceneViewCenterPanel.preferredSize.width else it.width
      val height =
        if (shouldShowCenterPanel) sceneViewCenterPanel.preferredSize.height else it.height

      it.width = width + positionableAdapter.margin.left + positionableAdapter.margin.right
      it.height = height + positionableAdapter.margin.top + positionableAdapter.margin.bottom
    }

  override fun getMinimumSize(): Dimension {
    val shouldShowCenterPanel =
      positionableAdapter.scaledContentSize.let { it.height == 0 && it.width == 0 }
    val centerPanelWidth = if (shouldShowCenterPanel) sceneViewCenterPanel.minimumSize.width else 0
    val centerPanelHeight =
      if (shouldShowCenterPanel) sceneViewCenterPanel.minimumSize.height else 0

    return Dimension(
      maxOf(sceneViewTopPanel.minimumSize.width, SCENE_VIEW_PEER_PANEL_MIN_WIDTH, centerPanelWidth),
      sceneViewBottomPanel.preferredSize.height +
        centerPanelHeight +
        sceneViewTopPanel.minimumSize.height +
        JBUI.scale(20)
    )
  }

  override fun isVisible(): Boolean {
    return sceneView.isVisible
  }
}
