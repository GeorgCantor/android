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
 * distributed under the License is distributed on an "AS IS" BASIS,ndroid
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.layoutinspector.properties

import com.android.SdkConstants.ATTR_HEIGHT
import com.android.SdkConstants.ATTR_ID
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.ATTR_WIDTH
import com.android.tools.idea.layoutinspector.model.ComposeViewNode
import com.android.tools.idea.layoutinspector.model.ViewNode
import com.android.tools.idea.layoutinspector.properties.PropertySection.DIMENSION
import com.android.tools.idea.layoutinspector.properties.PropertySection.RECOMPOSITIONS
import com.android.tools.idea.layoutinspector.properties.PropertySection.VIEW
import com.android.tools.property.panel.api.PropertiesTable
import com.google.common.util.concurrent.Futures
import java.util.concurrent.Future

// Constants for fabricated internal properties
const val NAMESPACE_INTERNAL = "internal"
const val ATTR_X = "x"
const val ATTR_Y = "y"

fun interface ResultListener {
  fun onResult(propertiesProvider: PropertiesProvider, viewNode: ViewNode, propertiesTable:  PropertiesTable<InspectorPropertyItem>)
}
/**
 * A [PropertiesProvider] provides properties to registered listeners..
 */
interface PropertiesProvider {

  /**
   * Add listener for [PropertiesProvider] results.
   */
  fun addResultListener(listener: ResultListener)

  /**
   * Remove listener for [PropertiesProvider] results.
   */
  fun removeResultListener(listener: ResultListener)

  /**
   * Requests properties for the specified [view].
   *
   * This is potentially an asynchronous request. The associated [InspectorPropertiesModel]
   * is notified when the table is ready.
   */
  fun requestProperties(view: ViewNode): Future<*>
}

object EmptyPropertiesProvider : PropertiesProvider {

  override fun addResultListener(listener: ResultListener) {}

  override fun removeResultListener(listener: ResultListener) {}

  override fun requestProperties(view: ViewNode): Future<*> {
    return Futures.immediateFuture(null)
  }
}

/**
 * Add a few fabricated internal attributes.
 */
fun addInternalProperties(
  table: PropertiesTable<InspectorPropertyItem>,
  view: ViewNode,
  attrId: String?,
  lookup: ViewNodeAndResourceLookup
) {
  add(table, ATTR_NAME, PropertyType.STRING, view.qualifiedName, VIEW, view.drawId, lookup)
  add(table, ATTR_X, PropertyType.DIMENSION, view.layoutBounds.x.toString(), DIMENSION, view.drawId, lookup)
  add(table, ATTR_Y, PropertyType.DIMENSION, view.layoutBounds.y.toString(), DIMENSION, view.drawId, lookup)
  add(table, ATTR_WIDTH, PropertyType.DIMENSION, view.layoutBounds.width.toString(), DIMENSION, view.drawId, lookup)
  add(table, ATTR_HEIGHT, PropertyType.DIMENSION, view.layoutBounds.height.toString(), DIMENSION, view.drawId, lookup)
  attrId?.let { add(table, ATTR_ID, PropertyType.STRING, it, VIEW, view.drawId, lookup) }

  (view as? ComposeViewNode)?.addComposeProperties(table, lookup)
}

private fun ComposeViewNode.addComposeProperties(table: PropertiesTable<InspectorPropertyItem>, lookup: ViewNodeAndResourceLookup) {
  if (!recompositions.isEmpty) {
    // Do not show the "Recomposition" section in the properties panel for nodes without any counts.
    // This includes inlined composables for which we are unable to get recomposition counts for.
    add(table, "count", PropertyType.INT32, recompositions.count.toString(), RECOMPOSITIONS, drawId, lookup)
    add(table, "skips", PropertyType.INT32, recompositions.skips.toString(), RECOMPOSITIONS, drawId, lookup)
  }
}

private fun add(table: PropertiesTable<InspectorPropertyItem>, name: String, type: PropertyType, value: String?, section: PropertySection, id: Long,
                lookup: ViewNodeAndResourceLookup) {
  table.put(InspectorPropertyItem(NAMESPACE_INTERNAL, name, type, value, section, null, id, lookup))
}
