/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.layoutinspector.model

import com.android.ide.common.rendering.api.ResourceReference
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.layoutinspector.tree.TreeSettings
import java.awt.Rectangle
import java.awt.Shape
import kotlin.math.absoluteValue
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.jetbrains.annotations.TestOnly

// TODO(b/258665704) Request this list from the compose inspector. Keep this as a backup for older
// compose versions.
private val systemPackageHashes =
  setOf(
    -1,
    packageNameHash("androidx.compose.animation"),
    packageNameHash("androidx.compose.animation.core"),
    packageNameHash("androidx.compose.desktop"),
    packageNameHash("androidx.compose.foundation"),
    packageNameHash("androidx.compose.foundation.gestures"),
    packageNameHash("androidx.compose.foundation.interaction"),
    packageNameHash("androidx.compose.foundation.layout"),
    packageNameHash("androidx.compose.foundation.lazy"),
    packageNameHash("androidx.compose.foundation.lazy.grid"),
    packageNameHash("androidx.compose.foundation.lazy.layout"),
    packageNameHash("androidx.compose.foundation.lazy.staggeredgrid"),
    packageNameHash("androidx.compose.foundation.relocation"),
    packageNameHash("androidx.compose.foundation.selection"),
    packageNameHash("androidx.compose.foundation.shape"),
    packageNameHash("androidx.compose.foundation.text"),
    packageNameHash("androidx.compose.foundation.text.selection"),
    packageNameHash("androidx.compose.material"),
    packageNameHash("androidx.compose.material.ripple"),
    packageNameHash("androidx.compose.material3"),
    packageNameHash("androidx.compose.runtime"),
    packageNameHash("androidx.compose.runtime.livedata"),
    packageNameHash("androidx.compose.runtime.rxjava2"),
    packageNameHash("androidx.compose.runtime.rxjava3"),
    packageNameHash("androidx.compose.runtime.saveable"),
    packageNameHash("androidx.compose.runtime.tracing"),
    packageNameHash("androidx.compose.ui"),
    packageNameHash("androidx.compose.ui.focus"),
    packageNameHash("androidx.compose.ui.graphics"),
    packageNameHash("androidx.compose.ui.input"),
    packageNameHash("androidx.compose.ui.input.focus"),
    packageNameHash("androidx.compose.ui.input.key"),
    packageNameHash("androidx.compose.ui.input.nestedscroll"),
    packageNameHash("androidx.compose.ui.input.pointer"),
    packageNameHash("androidx.compose.ui.input.pointer.util"),
    packageNameHash("androidx.compose.ui.input.rotary"),
    packageNameHash("androidx.compose.ui.layout"),
    packageNameHash("androidx.compose.ui.platform"),
    packageNameHash("androidx.compose.ui.tooling"),
    packageNameHash("androidx.compose.ui.selection"),
    packageNameHash("androidx.compose.ui.semantics"),
    packageNameHash("androidx.compose.ui.text"),
    packageNameHash("androidx.compose.ui.text.googlefonts"),
    packageNameHash("androidx.compose.ui.viewinterop"),
    packageNameHash("androidx.compose.ui.window"),
  )

// Flags in ComposeViewNode.composeFlags
const val FLAG_SYSTEM_DEFINED =
  LayoutInspectorComposeProtocol.ComposableNode.Flags.SYSTEM_CREATED_VALUE
const val FLAG_HAS_MERGED_SEMANTICS =
  LayoutInspectorComposeProtocol.ComposableNode.Flags.HAS_MERGED_SEMANTICS_VALUE
const val FLAG_HAS_UNMERGED_SEMANTICS =
  LayoutInspectorComposeProtocol.ComposableNode.Flags.HAS_UNMERGED_SEMANTICS_VALUE
const val FLAG_IS_INLINED = LayoutInspectorComposeProtocol.ComposableNode.Flags.INLINED_VALUE

// Must match packageNameHash in androidx.ui.tooling.inspector.LayoutInspectorTree
fun packageNameHash(packageName: String): Int =
  packageName.fold(0) { hash, char -> hash * 31 + char.code }.absoluteValue

fun isSystemComposeNode(packageHash: Int) = systemPackageHashes.contains(packageHash)

/**
 * The recomposition counts for a [ComposeViewNode] or a combination of nodes.
 *
 * @param count the number of recompositions
 * @param skips the number of times that recomposition was skipped
 * @param highlightCount a number that expresses relative recent counts for image highlighting
 */
class RecompositionData(var count: Int, var skips: Int, var highlightCount: Float = 0f) {
  val isEmpty: Boolean
    get() = count == 0 && skips == 0 && highlightCount == 0f

  val hasHighlight: Boolean
    get() = highlightCount > 0f

  fun reset() {
    count = 0
    skips = 0
    highlightCount = 0f
  }

  fun maxOf(node: ViewNode) {
    (node as? ComposeViewNode)?.let { maxOf(it.recompositions) }
  }

  fun maxOf(other: RecompositionData) {
    count = maxOf(count, other.count)
    skips = maxOf(skips, other.skips)
    highlightCount = maxOf(highlightCount, other.highlightCount)
  }

  fun update(newNumbers: RecompositionData) {
    if (StudioFlags.DYNAMIC_LAYOUT_INSPECTOR_ENABLE_RECOMPOSITION_HIGHLIGHTS.get()) {
      highlightCount += maxOf(0, newNumbers.count - count)
    }
    count = newNumbers.count
    skips = newNumbers.skips
  }

  fun decreaseHighlights(): Float {
    highlightCount =
      if (highlightCount > DECREASE_BREAK_OFF) highlightCount / DECREASE_FACTOR else 0f
    return highlightCount
  }
}

/** A view node represents a composable in the view hierarchy as seen on the device. */
class ComposeViewNode(
  drawId: Long,
  qualifiedName: String,
  layout: ResourceReference?,
  layoutBounds: Rectangle,
  renderBounds: Shape,
  viewId: ResourceReference?,
  textValue: String,
  layoutFlags: Int,

  /**
   * The number of times this node was recomposed (i.e. the composable method called) since last
   * reset.
   */
  recomposeCount: Int,

  /**
   * The number of times this node was skipped (i.e. the composable method was not called when it
   * might have been) since last reset.
   */
  recomposeSkips: Int,

  /** The filename of where the code for this composable resides. This name not contain a path. */
  var composeFilename: String,

  /** The hash of the package name where the composable resides. */
  var composePackageHash: Int,

  /** The offset to the method start in the file where the composable resides. */
  var composeOffset: Int,

  /** The line number of the method start in the file where the composable resides. */
  var composeLineNumber: Int,

  /** Flags as defined byh the FLAG_* constants above. */
  var composeFlags: Int,

  /** The hash of an anchor which can identify the composable after a recomposition. */
  var anchorHash: Int
) :
  ViewNode(
    drawId,
    qualifiedName,
    layout,
    layoutBounds,
    renderBounds,
    viewId,
    textValue,
    layoutFlags
  ) {
  @TestOnly
  constructor(
    drawId: Long,
    qualifiedName: String,
    layout: ResourceReference?,
    layoutBounds: Rectangle,
    viewId: ResourceReference?,
    textValue: String,
    layoutFlags: Int,
    recomposeCount: Int,
    recomposeSkips: Int,
    composeFilename: String,
    composePackageHash: Int,
    composeOffset: Int,
    composeLineNumber: Int,
    composeFlags: Int,
    anchorHash: Int
  ) : this(
    drawId,
    qualifiedName,
    layout,
    layoutBounds,
    layoutBounds,
    viewId,
    textValue,
    layoutFlags,
    recomposeCount,
    recomposeSkips,
    composeFilename,
    composePackageHash,
    composeOffset,
    composeLineNumber,
    composeFlags,
    anchorHash
  )

  val recompositions = RecompositionData(recomposeCount, recomposeSkips)

  override val isSystemNode: Boolean
    get() =
      composeFlags.hasFlag(FLAG_SYSTEM_DEFINED) ||
        // Keep this for backwards compatibility (i.e. prior to beta05)
        systemPackageHashes.contains(composePackageHash)
        // The top node is usually created by the user, but it has no location i.e. packageHash is
        // -1
        && readAccess { parent } is ComposeViewNode

  override val hasMergedSemantics: Boolean
    get() = composeFlags.hasFlag(FLAG_HAS_MERGED_SEMANTICS)

  override val hasUnmergedSemantics: Boolean
    get() = composeFlags.hasFlag(FLAG_HAS_UNMERGED_SEMANTICS)

  override val isInlined: Boolean
    get() = composeFlags.hasFlag(FLAG_IS_INLINED)

  override fun isSingleCall(treeSettings: TreeSettings): Boolean =
    treeSettings.composeAsCallstack &&
      readAccess { (parent as? ComposeViewNode)?.children?.size == 1 && children.size == 1 }

  fun resetRecomposeCounts() {
    recompositions.reset()
  }

  @Suppress("NOTHING_TO_INLINE") inline fun Int.hasFlag(flag: Int) = flag and this == flag
}
