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
package com.android.tools.idea.device.explorer.monitor.ui

import com.android.ddmlib.ClientData
import com.android.tools.idea.device.explorer.monitor.processes.ProcessInfo
import com.android.tools.idea.device.explorer.monitor.processes.isPidOnly
import com.android.tools.idea.device.explorer.monitor.processes.safeProcessName
import com.intellij.ide.ui.search.SearchUtil
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultListSelectionModel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableColumnModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.table.TableRowSorter

class ProcessListTableBuilder {

  fun build(tableModel: DeviceMonitorTableModel): JBTable {
    val table = JBTable(tableModel).apply {
      showVerticalLines = false
      showHorizontalLines = false
      emptyText.text = "No debuggable process on device"
      autoCreateColumnsFromModel = false
      autoCreateRowSorter = true
      setSelectionMode(DefaultListSelectionModel.SINGLE_SELECTION)
      rowSelectionAllowed = true
      background = UIUtil.getTableBackground()
      tableHeader.reorderingAllowed = false
    }
    val tableSpeedSearch = TableSpeedSearch(table)

    val columns = getListOfColumns(tableSpeedSearch)
    tableModel.removeOldColumnsAndAddColumns(columns)
    table.columnModel = DefaultTableColumnModel().apply {
      for (column in columns) {
        addColumn(column)
      }
    }

    table.rowSorter = TableRowSorter(tableModel).apply {
      setComparator(NAME_COLUMN_INDEX, getNameComparator())
      setComparator(PID_COLUMN_INDEX, getPidComparator())
      setComparator(ABI_COLUMN_INDEX, getAbiComparator())
      setComparator(VM_COLUMN_INDEX, getVMComparator())
      setComparator(USER_ID_COLUMN_INDEX, getUserIdComparator())
      setComparator(DEBUGGER_COLUMN_INDEX, getStatusComparator())
    }

    table.addKeyListener(getKeyListener(tableModel, table))

    return table
  }

  private fun getListOfColumns(tableSpeedSearch: TableSpeedSearch): List<TableColumn> = listOf(
    TableColumn(NAME_COLUMN_INDEX, 600, nameCellRenderer(tableSpeedSearch), null).apply { headerValue = "Process Name"},
    TableColumn(PID_COLUMN_INDEX, 150, pidCellRenderer(), null).apply { headerValue = "PID" },
    TableColumn(ABI_COLUMN_INDEX, 200, abiCellRenderer(), null).apply { headerValue = "ABI" },
    TableColumn(VM_COLUMN_INDEX, 200, vmIdentifierRenderer(), null).apply { headerValue = "VM" },
    TableColumn(USER_ID_COLUMN_INDEX, 100, userIdRenderer(), null).apply { headerValue = "User ID" },
    TableColumn(DEBUGGER_COLUMN_INDEX, 100, statusRenderer(), null).apply { headerValue = "Debugger" }
  )

  private fun getKeyListener(tableModel: DeviceMonitorTableModel, table: JBTable) = object : KeyAdapter() {
    override fun keyPressed(e: KeyEvent?) {
      e?.let {
        val copyShortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts("\$Copy")
        val copyKeyStroke = KeymapUtil.getKeyStroke(CustomShortcutSet(*copyShortcuts))

        if (KeyStroke.getKeyStroke(it.keyCode, it.modifiersEx).equals(copyKeyStroke)) {
          copyProcessInfoToClipboard(tableModel, table)
          it.consume()
        }
        /* JTable by default uses ctrl/cmd + c and does not respect keymaps to copy a row.
        *  It does this by calling toString() on each AbstractTableModel.getColumnClass().
        *  Since we don't want to keep this default behavior, we are consuming the event.
        */
        else if (it.keyCode == KeyEvent.VK_COPY || it.keyCode == KeyEvent.VK_C) {
          if (it.isControlDown || it.isMetaDown) {
            it.consume()
          }
        }
      }
    }
  }

  private fun copyProcessInfoToClipboard(tableModel: DeviceMonitorTableModel, table: JBTable) {
    var strToCopy = ""
    for (index in table.selectedRows) {
      val processInfo = tableModel.getValueForRow(table.convertRowIndexToModel(index))
      strToCopy = "$strToCopy${processInfo.processName}: ${processInfo.pid}\n"
    }

    if (strToCopy.isNotEmpty()) {
      CopyPasteManager.getInstance().setContents(StringSelection(strToCopy))
    }
  }

  private fun nameCellRenderer(tableSpeedSearch: TableSpeedSearch) = TableCellRenderer { table, value, isSelected, _, _, _ ->
    val processInfo = value as? ProcessInfo

    SimpleColoredComponent().apply {
      toolTipText = null
      icon = null
      ipad = JBUI.insetsRight(DeviceMonitorPanel.TEXT_RENDERER_HORIZ_PADDING)
      processInfo?.let {
        icon = processIcon
        if (it.isPidOnly || it.processName == null) {
          append(it.safeProcessName, SimpleTextAttributes.ERROR_ATTRIBUTES)
        }
        else {
          // Add name fragment (with speed search support)
          val attr = SimpleTextAttributes.REGULAR_ATTRIBUTES
          SearchUtil.appendFragments(
            tableSpeedSearch.enteredPrefix, it.processName, attr.style,
            attr.fgColor,
            attr.bgColor, this
          )
        }
      }

      if (isSelected) background = table.selectionBackground
    }
  }

  private fun getNameComparator(): Comparator<ProcessInfo> = Comparator { o1, o2 ->
    val val1 = o1?.let { if (it.isPidOnly || it.processName == null) it.safeProcessName else it.processName }
    val val2 = o2?.let { if (it.isPidOnly || it.processName == null) it.safeProcessName else it.processName }
    compareNullableStrings(val1, val2)
  }

  private fun pidCellRenderer() = TableCellRenderer { table, value, isSelected, _, _, _ ->
    val processInfo = value as? ProcessInfo
    SimpleColoredComponent().apply {
      processInfo?.let {
        append(it.pid.toString())
        setTextAlign(SwingConstants.TRAILING)
      }
      ipad = JBUI.insets(0, DeviceMonitorPanel.TEXT_RENDERER_HORIZ_PADDING)
      if (isSelected) background = table.selectionBackground
    }
  }

  private fun getPidComparator(): Comparator<ProcessInfo> = Comparator { o1, o2 ->
    val val1 = o1?.pid ?: Int.MAX_VALUE
    val val2 = o2?.pid ?: Int.MAX_VALUE
    val1.compareTo(val2)
  }

  private fun abiCellRenderer() = TableCellRenderer { table, value, isSelected, _, _, _ ->
    val processInfo = value as? ProcessInfo
    SimpleColoredComponent().apply {
      processInfo?.let {
        if (it.isPidOnly) {
          append("-")
        }
        else {
          append(it.abi ?: "-")
        }
      }
      ipad = JBUI.insets(0, DeviceMonitorPanel.TEXT_RENDERER_HORIZ_PADDING)
      if (isSelected) background = table.selectionBackground
    }
  }

  private fun getAbiComparator(): Comparator<ProcessInfo> = Comparator { o1, o2 ->
    val val1 = o1?.let { if (it.isPidOnly) null else it.abi }
    val val2 = o2?.let { if (it.isPidOnly) null else it.abi }
    compareNullableStrings(val1, val2)
  }

  private fun vmIdentifierRenderer() = TableCellRenderer { table, value, isSelected, _, _, _ ->
    val processInfo = value as? ProcessInfo
    SimpleColoredComponent().apply {
      processInfo?.let {
        if (it.isPidOnly) {
          append("-")
        }
        else {
          append(it.vmIdentifier ?: "-")
        }
      }
      ipad = JBUI.insets(0, DeviceMonitorPanel.TEXT_RENDERER_HORIZ_PADDING)
      if (isSelected) background = table.selectionBackground
    }
  }

  private fun getVMComparator(): Comparator<ProcessInfo> = Comparator { o1, o2 ->
    val val1 = o1?.let { if (it.isPidOnly) null else it.vmIdentifier }
    val val2 = o2?.let { if (it.isPidOnly) null else it.vmIdentifier }
    compareNullableStrings(val1, val2)
  }

  private fun userIdRenderer() = TableCellRenderer { table, value, isSelected, _, _, _ ->
    val processInfo = value as? ProcessInfo
    SimpleColoredComponent().apply {
      processInfo?.let {
        if (it.isPidOnly) {
          append("-")
        }
        else {
          append(it.userId?.toString() ?: "n/a")
        }
        setTextAlign(SwingConstants.TRAILING)
      }
      ipad = JBUI.insets(0, DeviceMonitorPanel.TEXT_RENDERER_HORIZ_PADDING)
      if (isSelected) background = table.selectionBackground
    }
  }

  private fun getUserIdComparator(): Comparator<ProcessInfo> = Comparator { o1, o2 ->
    val val1 = if (o1 != null && !o1.isPidOnly && o1.userId != null) o1.userId else Int.MAX_VALUE
    val val2 = if (o2 != null && !o2.isPidOnly && o2.userId != null) o2.userId else Int.MAX_VALUE
    val1.compareTo(val2)
  }

  private fun statusRenderer() = TableCellRenderer { table, value, isSelected, _, _, _ ->
    val processInfo = value as? ProcessInfo
    SimpleColoredComponent().apply {
      processInfo?.let{
        if (it.isPidOnly) {
          append("-")
        }
        else {
          val status = when (it.debuggerStatus) {
            ClientData.DebuggerStatus.DEFAULT -> "No"
            ClientData.DebuggerStatus.WAITING -> "Waiting"
            ClientData.DebuggerStatus.ATTACHED -> "Attached"
            ClientData.DebuggerStatus.ERROR -> "<Error>"
          }
          append(status)
        }
      }
      ipad = JBUI.insets(0, DeviceMonitorPanel.TEXT_RENDERER_HORIZ_PADDING)
      if (isSelected) background = table.selectionBackground
    }
  }

  private fun getStatusComparator(): Comparator<ProcessInfo> = Comparator { o1, o2 ->
    val val1 = if (o1 != null && !o1.isPidOnly) o1.debuggerStatus.ordinal else Int.MAX_VALUE
    val val2 = if (o2 != null && !o2.isPidOnly) o2.debuggerStatus.ordinal else Int.MAX_VALUE
    val1.compareTo(val2)
  }

  private fun compareNullableStrings(str1: String?, str2: String?): Int {
    return if (str1 == null && str2 == null) {
      0
    }
    else if (str1 == null) {
      1
    }
    else if (str2 == null) {
      -1
    }
    else {
      str1.compareTo(str2)
    }
  }

  companion object {
    private val processIcon = StudioIcons.Shell.Filetree.ACTIVITY
    private const val NAME_COLUMN_INDEX = 0
    private const val PID_COLUMN_INDEX = 1
    private const val ABI_COLUMN_INDEX = 2
    private const val VM_COLUMN_INDEX = 3
    private const val USER_ID_COLUMN_INDEX = 4
    private const val DEBUGGER_COLUMN_INDEX = 5
  }
}