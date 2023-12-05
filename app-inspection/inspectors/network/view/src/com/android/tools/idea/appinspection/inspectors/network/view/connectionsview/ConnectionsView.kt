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
package com.android.tools.idea.appinspection.inspectors.network.view.connectionsview

import com.android.tools.adtui.model.AspectObserver
import com.android.tools.adtui.stdui.TimelineTable
import com.android.tools.adtui.table.ConfigColumnTableAspect
import com.android.tools.idea.appinspection.inspectors.network.model.NetworkInspectorAspect
import com.android.tools.idea.appinspection.inspectors.network.model.NetworkInspectorModel
import com.android.tools.idea.appinspection.inspectors.network.model.NetworkInspectorModel.DetailContent
import com.android.tools.idea.appinspection.inspectors.network.model.connections.ConnectionData
import com.android.tools.idea.appinspection.inspectors.network.view.NetworkInspectorViewState
import com.android.tools.idea.appinspection.inspectors.network.view.connectionsview.ConnectionColumn.TIMELINE
import com.android.tools.idea.appinspection.inspectors.network.view.constants.DEFAULT_BACKGROUND
import com.android.tools.idea.appinspection.inspectors.network.view.constants.ROW_HEIGHT_PADDING
import com.android.tools.idea.appinspection.inspectors.network.view.rules.registerEnterKeyAction
import com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent
import javax.swing.table.TableCellRenderer

/**
 * This class responsible for displaying table of connections information (e.g. url, duration,
 * timeline) for network inspector. Each row in the table represents a single connection.
 */
class ConnectionsView(private val model: NetworkInspectorModel) : AspectObserver() {

  private val tableModel = ConnectionsTableModel(model.selectionRangeDataFetcher)
  private val connectionsTable: JTable

  val component: JComponent
    get() = connectionsTable

  init {
    connectionsTable =
      TimelineTable.create(tableModel, model.timeline, TIMELINE.displayString, true)
    customizeConnectionsTable()
    ConfigColumnTableAspect.apply(connectionsTable, NetworkInspectorViewState.getInstance().columns)
    model.aspect.addDependency(this).onChange(NetworkInspectorAspect.SELECTED_CONNECTION) {
      updateTableSelection()
    }
  }

  private fun customizeConnectionsTable() {
    connectionsTable.autoCreateRowSorter = true

    ConnectionColumn.values().forEach {
      setRenderer(it, it.getCellRenderer(connectionsTable, model))
    }

    connectionsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    connectionsTable.addMouseListener(
      object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
          connectionsTable.toolTipText = e.getConnectionData()?.url
        }

        override fun mouseClicked(e: MouseEvent) {
          val row = connectionsTable.rowAtPoint(e.point)
          if (row != -1) {
            model.detailContent = DetailContent.CONNECTION
          }
        }

        override fun mouseReleased(e: MouseEvent) {
          openContextMenu(e)
        }

        override fun mousePressed(e: MouseEvent) {
          openContextMenu(e)
        }
      }
    )
    connectionsTable.registerEnterKeyAction {
      if (connectionsTable.selectedRow != -1) {
        model.detailContent = DetailContent.CONNECTION
      }
    }
    connectionsTable.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
      if (e.valueIsAdjusting) {
        return@addListSelectionListener // Only handle listener on last event, not intermediate
        // events
      }
      val selectedRow = connectionsTable.selectedRow
      if (0 <= selectedRow && selectedRow < tableModel.rowCount) {
        val modelRow = connectionsTable.convertRowIndexToModel(selectedRow)
        model.setSelectedConnection(tableModel.getConnectionData(modelRow))
      }
    }
    connectionsTable.background = DEFAULT_BACKGROUND
    connectionsTable.showVerticalLines = true
    connectionsTable.showHorizontalLines = false
    val defaultFontHeight = connectionsTable.getFontMetrics(connectionsTable.font).height
    connectionsTable.rowMargin = 0
    connectionsTable.rowHeight = defaultFontHeight + ROW_HEIGHT_PADDING
    connectionsTable.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null)
    connectionsTable.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null)
    model.selectionRangeDataFetcher.addOnChangedListener {
      // Although the selected row doesn't change on range moved, we do this here to prevent
      // flickering that otherwise occurs in our table.
      updateTableSelection()
    }
  }

  private fun openContextMenu(e: MouseEvent) {
    if (!e.isPopupTrigger) {
      return
    }
    val connectionData = e.getConnectionData() ?: return
    val actions = connectionData.getActions()
    if (actions.isEmpty()) {
      return
    }
    JBPopupFactory.getInstance()
      .createActionGroupPopup(null, DefaultActionGroup(actions), EMPTY_CONTEXT, false, null, -1)
      .show(RelativePoint(e))
  }

  private fun setRenderer(column: ConnectionColumn, renderer: TableCellRenderer) {
    connectionsTable.columnModel.getColumn(column.ordinal).cellRenderer = renderer
  }

  private fun MouseEvent.getConnectionData(): ConnectionData? {
    val row = connectionsTable.rowAtPoint(point)
    return when {
      row < 0 -> null
      else -> tableModel.getConnectionData(connectionsTable.convertRowIndexToModel(row))
    }
  }

  private fun updateTableSelection() {
    val selectedData = model.selectedConnection
    if (selectedData != null) {
      for (i in 0 until tableModel.rowCount) {
        if (tableModel.getConnectionData(i).id == selectedData.id) {
          val row = connectionsTable.convertRowIndexToView(i)
          connectionsTable.setRowSelectionInterval(row, row)
          return
        }
      }
    } else {
      connectionsTable.clearSelection()
    }
  }
}

private fun ConnectionData.getActions() = listOf(CopyUrlAction(this))
