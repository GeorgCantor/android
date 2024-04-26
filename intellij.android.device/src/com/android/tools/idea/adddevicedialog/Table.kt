/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.adddevicedialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.util.thenIf

internal data class TableColumn<T>(
  val name: String,
  val weight: Float,
  val comparator: Comparator<T>? = null,
  val rowContent: @Composable (T) -> Unit,
)

internal fun <T> TableTextColumn(name: String, weight: Float = 1f, attribute: (T) -> String) =
  TableColumn<T>(name, weight, compareBy(attribute)) { Text(attribute(it)) }

internal enum class SortOrder {
  ASCENDING,
  DESCENDING;

  val opposite: SortOrder
    get() =
      when (this) {
        ASCENDING -> DESCENDING
        DESCENDING -> ASCENDING
      }
}

@Composable
internal fun SortOrder.icon() =
  when (this) {
    // In Swing, we would do `UIManager.get("Table.ascendingSortIcon", null) as Icon`; instead use
    // IJ platform icons
    SortOrder.ASCENDING -> Icon("general/arrowUp.svg", null, AllIcons::General::class.java)
    SortOrder.DESCENDING -> Icon("general/arrowDown.svg", null, AllIcons::General::class.java)
  }

@Stable
internal class TableSelectionState<T> {
  var selection: T? by mutableStateOf(null)
}

@Stable
internal class TableSortState<T> {
  var sortColumn: TableColumn<T>? by mutableStateOf(null)
  var sortOrder: SortOrder by mutableStateOf(SortOrder.ASCENDING)

  val comparator: Comparator<T>?
    get() =
      sortColumn?.comparator?.let {
        when (sortOrder) {
          SortOrder.ASCENDING -> it
          SortOrder.DESCENDING -> it.reversed()
        }
      }
}

@Composable
internal fun <T> TableHeader(
  sortColumn: TableColumn<T>?,
  sortOrder: SortOrder,
  onClick: (TableColumn<T>) -> Unit,
  columns: List<TableColumn<T>>,
) {
  Row(
    Modifier.fillMaxWidth().padding(ROW_PADDING),
    horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
  ) {
    columns.forEach {
      Row(Modifier.weight(it.weight, fill = true).clickable { onClick(it) }) {
        Text(it.name, fontWeight = FontWeight.Bold)
        if (it == sortColumn) {
          sortOrder.icon()
        }
      }
    }
  }
}

@Composable
internal fun <T> TableRow(
  value: T,
  selected: Boolean,
  onClick: (T) -> Unit = {},
  columns: List<TableColumn<T>>,
) {
  Row(
    Modifier.fillMaxWidth()
      .clickable { onClick(value) }
      .thenIf(selected) { background(JBColor.BLUE.toComposeColor()) }
      .padding(ROW_PADDING),
    horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
  ) {
    columns.forEach { Box(Modifier.weight(it.weight, fill = true)) { it.rowContent(value) } }
  }
}

@Composable
internal fun <T> Table(
  columns: List<TableColumn<T>>,
  rows: List<T>,
  rowId: (T) -> Any,
  modifier: Modifier = Modifier,
  tableSelectionState: TableSelectionState<T> = remember { TableSelectionState<T>() },
  tableSortState: TableSortState<T> = remember { TableSortState<T>() },
) {
  Column(modifier) {
    TableHeader(
      tableSortState.sortColumn,
      tableSortState.sortOrder,
      onClick = { column ->
        if (column.comparator != null) {
          tableSortState.sortOrder =
            if (tableSortState.sortColumn == column) tableSortState.sortOrder.opposite
            else SortOrder.ASCENDING
          tableSortState.sortColumn = column
        }
      },
      columns,
    )
    Divider(Orientation.Horizontal)
    LazyColumn {
      val sortedRows = tableSortState.comparator?.let { rows.sortedWith(it) } ?: rows

      items(sortedRows.size, { index -> rowId(sortedRows[index]) }) { index ->
        TableRow(
          sortedRows[index],
          selected = sortedRows[index] == tableSelectionState.selection,
          onClick = { row -> tableSelectionState.selection = row },
          columns,
        )
      }
    }
  }
}

private val CELL_SPACING = 4.dp
private val ROW_PADDING = 4.dp
