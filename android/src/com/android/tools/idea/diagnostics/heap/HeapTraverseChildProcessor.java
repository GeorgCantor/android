/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.diagnostics.heap;

import static com.android.tools.idea.diagnostics.heap.HeapTraverseUtil.getFieldValue;
import static com.android.tools.idea.diagnostics.heap.HeapTraverseUtil.isArrayOfPrimitives;

import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.rendering.imagepool.ImagePool;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.TriConsumer;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HeapTraverseChildProcessor {

  private static final Set<String> REFERENCE_CLASS_FIELDS_TO_IGNORE = Set.of("referent", "discovered", "next");
  private static final String ARRAY_ELEMENT_REFERENCE_LABEL = "[]";
  private static final String STATIC_FIELD_REFERENCE_LABEL = "(static)";
  private static final String DISPOSER_TREE_REFERENCE_LABEL = "(disposer-tree)";

  @NotNull
  private final Object myDisposerTree;
  private final boolean myShouldUseDisposerTreeReferences;
  @NotNull
  private final HeapSnapshotStatistics myStatistics;

  public HeapTraverseChildProcessor(@NotNull final HeapSnapshotStatistics statistics) {
    myDisposerTree = Disposer.getTree();
    myShouldUseDisposerTreeReferences = StudioFlags.USE_DISPOSER_TREE_REFERENCES.get();
    myStatistics = statistics;
  }

  /**
   * Iterates over objects referred from the passed object and calls the passed consumer for them.
   *
   * @param obj        processing object
   * @param consumer   processor for references: gets a referred object and the type of the reference as {@link HeapTraverseNode.RefWeight}
   * @param fieldCache cache that stores fields declared for the given class.
   */
  void processChildObjects(@Nullable final Object obj,
                           @NotNull final TriConsumer<Object, HeapTraverseNode.RefWeight, String> consumer,
                           @NotNull final FieldCache fieldCache)
    throws HeapSnapshotTraverseException {
    if (obj == null) {
      return;
    }
    if (obj == myDisposerTree) {
      // We don't traverse the subtree of disposer tree to prevent the situation when DisposerTree
      // (or the component that owns it) will be resolved as an owner of the registered Disposables.
      // DisposerTree doesn't actually "own" them, just manages their lifecycles.
      return;
    }
    Class<?> nodeClass = obj.getClass();
    boolean objIsReference = obj instanceof Reference;
    boolean isImagePoolClass = obj instanceof ImagePool;
    for (Field field : fieldCache.getInstanceFields(nodeClass)) {
      // do not follow weak/soft refs
      if (objIsReference && REFERENCE_CLASS_FIELDS_TO_IGNORE.contains(field.getName())) {
        continue;
      }

      Object value;
      try {
        // Ignore FinalizablePhantomReferences stored in ImagePoolImpl#myReferences.
        // It was decided to do so in order to mitigate memory usage tests flakiness: FinalizablePhantomReferences are managed by GC and
        // the moment they are collected may differ from run to run.
        if (isImagePoolClass && "myReferences".equals(field.getName())) {
          continue;
        }
        value = field.get(obj);
        consumer.accept(value, HeapTraverseNode.RefWeight.INSTANCE_FIELD, field.getName());
      }
      catch (IllegalArgumentException | IllegalAccessException e) {
        myStatistics.incrementUnsuccessfulFieldAccessCounter();
      }
    }

    // JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT
    if (nodeClass.isArray() && !isArrayOfPrimitives(nodeClass)) {
      for (Object value : (Object[])obj) {
        consumer.accept(value, HeapTraverseNode.RefWeight.ARRAY_ELEMENT, ARRAY_ELEMENT_REFERENCE_LABEL);
      }
    }
    // We need to check that class is initialized and only in this case traverse child elements of
    // the class. Class object may be reachable by traversal but not yet initialized if it's stored
    // somewhere in form of class object instance but non of the following events occurred with the
    // corresponding class:
    // 1) an instance of the class is created,
    // 2) a static method of the class is invoked,
    // 3) a static field of the class is assigned,
    // 4) a non-constant static field is used;
    if (obj instanceof Class && MemoryReportJniHelper.isClassInitialized((Class<?>)obj)) {
      for (Object fieldValue : fieldCache.getStaticFields((Class<?>)obj)) {
        if (fieldValue == null) {
          continue;
        }
        // TODO(b/291062810): properly handle names of the static fields
        consumer.accept(fieldValue, HeapTraverseNode.RefWeight.STATIC_FIELD, STATIC_FIELD_REFERENCE_LABEL);
      }
    }

    // Check is the object implements Disposable and is registered in a Disposer tree. In that case,
    // we consider that the disposable object refers to the children from Disposer tree.
    if (myShouldUseDisposerTreeReferences && obj instanceof Disposable) {
      Object objToNodeMap = getFieldValue(myDisposerTree, "myObject2NodeMap");
      if (!(objToNodeMap instanceof Map)) {
        return;
      }
      Object disposableTreeNode = ((Map<?, ?>)objToNodeMap).get(obj);
      if (disposableTreeNode == null) {
        return;
      }
      Object disposableTreeNodeChildren = getFieldValue(disposableTreeNode, "myChildren");
      if (!(disposableTreeNodeChildren instanceof List)) {
        return;
      }
      for (Object child : (List<?>)disposableTreeNodeChildren) {
        Object currDisposable = getFieldValue(child, "myObject");
        if (!(currDisposable instanceof Disposable)) {
          continue;
        }
        consumer.accept(currDisposable, HeapTraverseNode.RefWeight.DISPOSER_TREE_REFERENCE, DISPOSER_TREE_REFERENCE_LABEL);
      }
    }
  }
}
