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
package com.android.tools.idea.diagnostics.heap;

import static com.android.tools.idea.diagnostics.heap.MemoryReportCollector.MAX_ALLOWED_OBJECT_MAP_SIZE;
import static com.android.tools.idea.diagnostics.heap.MemoryReportJniHelper.getObjectTag;

import com.google.wireless.android.sdk.stats.MemoryUsageReportEvent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.containers.WeakList;
import java.util.Optional;
import java.util.Stack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

abstract class DepthFirstSearchTraverse {

  // Should not be increased above 2^17-1. 17 bits is reserved for the object depth in the 64-bit JVM TI object tag.
  private static final int MAX_DEPTH = 100_000;
  @NotNull
  private final FieldCache fieldCache;
  @NotNull
  private final HeapTraverseChildProcessor heapTraverseChildProcessor;
  @NotNull
  protected final MemoryReportCollector collector;
  protected final short iterationId;

  public DepthFirstSearchTraverse(@NotNull final FieldCache fieldCache,
                                  @NotNull final MemoryReportCollector collector) {
    this.fieldCache = fieldCache;
    this.heapTraverseChildProcessor = collector.heapTraverseChildProcessor;
    this.collector = collector;
    this.iterationId = collector.getIterationId();
  }

  /**
   * Performs a depth first traverse starting from the passed root objects.
   */
  public void start(@NotNull final WeakList<Object> roots)
    throws HeapSnapshotTraverseException {
    try {
      for (Object root : roots) {
        if (root == null) continue;
        depthFirstTraverseHeapObjects(root);
      }
    }
    finally {
      cleanup();
    }
  }

  protected void cleanup() {
    StackNode.clearDepthFirstSearchStack();
  }

  /**
   * Traverses a subtree of the given root node and enumerates objects in the postorder.
   */
  private void depthFirstTraverseHeapObjects(@NotNull final Object root)
    throws HeapSnapshotTraverseException {
    long rootTag = getObjectTag(root);
    if (ObjectTagUtil.wasVisited(rootTag, iterationId)) {
      return;
    }
    rootTag = ObjectTagUtil.markVisited(root, rootTag, iterationId);
    pushElementToDepthFirstSearchStack(root, 0, rootTag, "(root)");

    // DFS starting from the given root object.
    while (true) {
      collector.abortTraversalIfRequested();
      int stackSize = StackNode.getDepthFirstSearchStackSize();
      if (stackSize == 0) {
        break;
      }
      if (stackSize > MAX_ALLOWED_OBJECT_MAP_SIZE) {
        throw new HeapSnapshotTraverseException(MemoryUsageReportEvent.MemoryUsageCollectionMetadata.StatusCode.OBJECTS_MAP_IS_TOO_BIG);
      }
      StackNode stackNode = StackNode.peekAndMarkProcessedDepthFirstSearchStack(StackNode.class);
      if (stackNode == null) {
        continue;
      }

      // tag objects with their postorder number when ascending from the recursive subtree.
      if (stackNode.referencesProcessed) {
        handleProcessedNode(stackNode, root);
        StackNode.popElementFromDepthFirstSearchStack();
        continue;
      }

      handleNode(stackNode);
      addStronglyReferencedChildrenToStack(stackNode, fieldCache);
    }
  }

  protected abstract void handleProcessedNode(@NotNull final StackNode stackNode, @NotNull final Object root);

  private void addStronglyReferencedChildrenToStack(@NotNull final StackNode stackNode,
                                                    @NotNull final FieldCache fieldCache)
    throws HeapSnapshotTraverseException {
    if (stackNode.getObject() == null) {
      return;
    }
    if (stackNode.depth >= MAX_DEPTH) {
      return;
    }
    heapTraverseChildProcessor.processChildObjects(stackNode.getObject(),
                                                   (Object value, HeapTraverseNode.RefWeight weight, String label) -> addToStack(
                                                     stackNode, value, label), fieldCache);
  }


  private void addToStack(@NotNull final StackNode parentStackNode,
                          @Nullable final Object value,
                          @NotNull final String label) {
    if (value == null) {
      return;
    }
    if (parentStackNode.depth + 1 > MAX_DEPTH) {
      return;
    }
    if (HeapTraverseUtil.isPrimitive(value.getClass()) ||
        value instanceof Thread ||
        value instanceof Class<?> ||
        value instanceof ClassLoader) {
      return;
    }
    long tag = getObjectTag(value);
    if (ObjectTagUtil.wasVisited(tag, iterationId)) {
      return;
    }

    if (!shouldAddObjectToStack(parentStackNode, tag, value, label)) {
      return;
    }

    pushElementToDepthFirstSearchStack(value, parentStackNode.depth + 1, ObjectTagUtil.markVisited(value, tag, iterationId), label);
  }

  protected void pushElementToDepthFirstSearchStack(@NotNull final Object obj, int depth, long tag, @NotNull final String label) {
    StackNode.pushElementToDepthFirstSearchStack(obj, depth, tag);
  }

  protected void handleNode(@NotNull final StackNode stackNode) { }

  /**
   * @return if the object with a specified {@code childTag} should be added to the DFS stack. It was already checked that the `visited` bit
   * is not set and the referenced object is not of a root type(Class/ClassLoader/Thread).
   */
  protected boolean shouldAddObjectToStack(@NotNull final StackNode stackNode,
                                           long childTag,
                                           @NotNull final Object childObject,
                                           @NotNull final String label) {
    return true;
  }

  static class ObjectsEnumerationTraverse extends DepthFirstSearchTraverse {

    private int lastObjectId = 0;

    ObjectsEnumerationTraverse(@NotNull FieldCache fieldCache, @NotNull MemoryReportCollector collector) {
      super(fieldCache, collector);
    }

    @Override
    protected void handleProcessedNode(@NotNull final StackNode stackNode, @NotNull final Object root) {
      if (stackNode.getObject() == null) {
        return;
      }
      long tag = stackNode.tag;
      ObjectTagUtil.setObjectId(stackNode.getObject(), tag, ++lastObjectId, iterationId);
      if (stackNode.getObject() == root) {
        collector.putOrUpdateObjectIdToTraverseNodeMap(lastObjectId, root, collector.createRootNode(root));
      }
    }

    public int getLastObjectId() {
      return lastObjectId;
    }
  }

  static class ExtendedReportCollectionTraverse extends DepthFirstSearchTraverse {

    @NotNull
    private final ExtendedReportStatistics extendedReportStatistics;
    @NotNull
    private final Stack<ExtendedStackNode> extendedNodesStack = new Stack<>();
    @NotNull
    private final Stack<RootPathTree.RootPathElement> pathToRoot = new Stack<>();

    public ExtendedReportCollectionTraverse(@NotNull final FieldCache fieldCache,
                                            @NotNull final MemoryReportCollector collector,
                                            @NotNull final ExtendedReportStatistics extendedReportStatistics) {
      super(fieldCache, collector);
      this.extendedReportStatistics = extendedReportStatistics;
    }

    @Override
    protected void handleProcessedNode(@NotNull StackNode stackNode, @NotNull Object root) {
      pathToRoot.pop();
      if (stackNode.getObject() != null) {
        MemoryReportJniHelper.setObjectTag(stackNode.getObject(), 0);
      }
    }

    @Override
    protected void pushElementToDepthFirstSearchStack(@NotNull Object obj, int depth, long tag, @NotNull String label) {
      super.pushElementToDepthFirstSearchStack(obj, depth, tag, label);

      extendedNodesStack.push(new ExtendedStackNode(getObjectClassNameLabel(obj), label));
    }

    @NotNull
    private String getObjectClassNameLabel(@NotNull final Object obj) {
      if (obj instanceof Class<?>) {
        return String.format("%s(%s)", obj.getClass().getName(), ((Class<?>)obj).getName());
      }
      return obj.getClass().getName();
    }

    @Override
    protected void handleNode(@NotNull final StackNode stackNode) {
      super.handleNode(stackNode);

      ExtendedStackNode extendedStackNode = extendedNodesStack.pop();
      pathToRoot.add(new RootPathTree.RootPathElement(extendedStackNode, stackNode.getObject() == null
                                                                         ? 0
                                                                         : MemoryReportJniHelper.getObjectSize(stackNode.getObject()),
                                                      extendedReportStatistics));
      if (stackNode.getObject() == null) {
        return;
      }

      if (ObjectTagUtil.isOwnedByExceededComponent(stackNode.tag)) {
        ExceededClusterStatistics exceededClusterStatistics =
          extendedReportStatistics.exceededClustersEnumeration.get(ObjectTagUtil.getOwningExceededClusterIndex(stackNode.tag));
        String currentObjectClassName = stackNode.getObject().getClass().getName();
        if (exceededClusterStatistics.isClassNominated(currentObjectClassName)) {
          extendedReportStatistics.rootPathTree.addObjectWithPathToRoot(pathToRoot, exceededClusterStatistics,
                                                                        exceededClusterStatistics.nominatedClassesEnumeration.getInt(
                                                                          currentObjectClassName));
        }
        if (stackNode.getObject() instanceof Disposable) {
          //noinspection deprecation
          if (Disposer.isDisposed((Disposable)stackNode.getObject())) {
            extendedReportStatistics.rootPathTree.addDisposedReferencedObjectWithPathToRoot(pathToRoot, exceededClusterStatistics);
          }
        }
      }
    }

    private void checkReferenceIsHoldingClassLoader(@Nullable final Object parentObject,
                                                    @NotNull final Object childObject,
                                                    @NotNull final String label) {
      if (parentObject == null) {
        return;
      }
      if (parentObject.getClass() == null || childObject.getClass() == null) {
        return;
      }
      ClassLoader parentObjectClassLoader = parentObject.getClass().getClassLoader();
      ClassLoader childObjectClassLoader = childObject.getClass().getClassLoader();
      if (childObjectClassLoader == null) {
        return;
      }
      if (parentObjectClassLoader == childObjectClassLoader) {
        return;
      }
      Optional<ComponentsSet.Component> component = extendedReportStatistics.componentToExceededClustersStatistics.keySet().stream()
        .filter(c -> c.isClassLoaderOwned(childObjectClassLoader)).findAny();
      if (component.isEmpty()) {
        return;
      }
      if (parentObjectClassLoader != null &&
          childObjectClassLoader.getClass().getName().equals(parentObjectClassLoader.getClass().getName())) {
        return;
      }
      ExceededClusterStatistics exceededClusterStatistics =
        extendedReportStatistics.componentToExceededClustersStatistics.get(component.get());
      pathToRoot.add(new RootPathTree.RootPathElement(new ExtendedStackNode(getObjectClassNameLabel(childObject), label),
                                                      MemoryReportJniHelper.getObjectSize(childObject),
                                                      extendedReportStatistics));
      extendedReportStatistics.rootPathTree.addClassLoaderPath(pathToRoot, exceededClusterStatistics);
      pathToRoot.pop();
    }

    @Override
    protected boolean shouldAddObjectToStack(@NotNull StackNode stackNode,
                                             long childTag,
                                             @NotNull final Object childObject,
                                             @NotNull final String label) {
      checkReferenceIsHoldingClassLoader(stackNode.getObject(), childObject, label);

      int childObjectDepth = ObjectTagUtil.getDepth(childTag, iterationId);
      if (childObjectDepth == ObjectTagUtil.INVALID_OBJECT_DEPTH) {
        return false;
      }

      return stackNode.depth + 1 == childObjectDepth;
    }

    @Override
    protected void cleanup() {
      super.cleanup();
      extendedNodesStack.clear();
      pathToRoot.clear();
    }
  }
}
