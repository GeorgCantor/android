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
package com.android.tools.idea.bleak;

import com.android.tools.idea.bleak.expander.Expander;
import com.android.tools.idea.bleak.expander.SmartFMapExpander;
import com.android.tools.idea.bleak.expander.SmartListExpander;
import gnu.trove.TObjectHash;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class StudioBleakOptions {
  private static IgnoreList<LeakInfo> globalIgnoreList = new IgnoreList<> (Arrays.stream(new IgnoredRef[]{
    new IgnoredRef(-2, "com.intellij.openapi.util.ObjectTree", "myObject2ParentNode"),
    new IgnoredRef(-2, "com.android.tools.idea.testing.DisposerExplorer", "object2ParentNode"),
    new IgnoredRef(-2, "com.android.tools.idea.diagnostics.report.MetricsLogFileProviderKt", "DefaultMetricsLogFileProvider"),
    new IgnoredRef(-5, "com.android.tools.idea.tests.gui.framework.GuiPerfLogger", "myMetric"),
    new IgnoredRef(1, "com.android.layoutlib.bridge.impl.DelegateManager", "sJavaReferences"),
    new IgnoredRef(-2, "com.intellij.util.ref.DebugReflectionUtil", "allFields"),
    new IgnoredRef(-1, "java.util.concurrent.ForkJoinPool", "workQueues"),
    new IgnoredRef(-3, "java.io.DeleteOnExitHook", "files"),

    // don't report growing weak or soft maps. Nodes whose weak or soft referents have been GC'd will be removed from the map during some
    // future map operation.
    new IgnoredRef(-1, "com.intellij.util.containers.ConcurrentWeakHashMap", "myMap"),
    new IgnoredRef(-1, "com.intellij.util.containers.ConcurrentWeakValueHashMap", "myMap"),
    new IgnoredRef(-1, "com.intellij.util.containers.ConcurrentWeakKeyWeakValueHashMap", "myMap"),
    new IgnoredRef(-2, "com.intellij.util.containers.WeakHashMap", "myMap"),
    new IgnoredRef(-1, "com.intellij.util.containers.ConcurrentSoftHashMap", "myMap"),
    new IgnoredRef(-1, "com.intellij.util.containers.ConcurrentSoftValueHashMap", "myMap"),
    new IgnoredRef(-1, "com.intellij.util.containers.ConcurrentSoftKeySoftValueHashMap", "myMap"),

    new IgnoredRef(-2, "com.android.tools.idea.configurations.ConfigurationManager", "myCache"),
    new IgnoredRef(-2, "com.intellij.openapi.vfs.newvfs.impl.VfsData$Segment", "myObjectArray"),
    new IgnoredRef(-2, "com.intellij.openapi.vcs.impl.FileStatusManagerImpl", "cachedStatuses"),
    new IgnoredRef(-2, "com.intellij.openapi.vcs.impl.FileStatusManagerImpl", "whetherExactlyParentChanged"),
    new IgnoredRef(-3, "com.intellij.util.indexing.VfsAwareMapIndexStorage", "myCache"),
    new IgnoredRef(-2, "com.intellij.util.indexing.IndexingStamp", "ourTimestampsCache"),
    new IgnoredRef(-2, "com.intellij.util.indexing.IndexingStamp", "ourFinishedFiles"),
    new IgnoredRef(-2, "com.intellij.openapi.fileEditor.impl.EditorWindow", "removedTabs"),
    new IgnoredRef(-2, "com.intellij.notification.EventLog$ProjectTracker", "myInitial"),
    new IgnoredRef(1, "sun.java2d.Disposer", "records"),
    new IgnoredRef(1, "sun.java2d.marlin.OffHeapArray", "REF_LIST"),
    new IgnoredRef(1, "sun.awt.X11.XInputMethod", "lastXICFocussedComponent"), // b/150879705
    new IgnoredRef(-1, "sun.font.XRGlyphCache", "cacheMap"),

    new IgnoredRef(-2, "com.intellij.openapi.application.impl.ReadMostlyRWLock", "readers"),
    new IgnoredRef(-3, "org.jdom.JDOMInterner", "myElements"),
    new IgnoredRef(-3, "org.jdom.JDOMInterner", "myStrings"),
    // coroutine scheduler thread pool: b/140457368
    new IgnoredRef(-1, "kotlinx.coroutines.scheduling.CoroutineScheduler", "workers"),
    new IgnoredRef(-2, "com.intellij.util.concurrency.AppScheduledExecutorService$BackendThreadPoolExecutor", "workers"),
    new IgnoredRef(-1, "com.intellij.ide.plugins.MainRunner$1", "threads"),
    new IgnoredRef(-1, "com.intellij.openapi.command.impl.UndoRedoStacksHolder", "myGlobalStack"),
    new IgnoredRef(-3, "com.intellij.openapi.command.impl.UndoRedoStacksHolder", "myDocumentStacks"),
    new IgnoredRef(-3, "com.intellij.openapi.command.impl.SharedUndoRedoStacksHolder", "myDocumentStacks"),
    new IgnoredRef(-1, "com.intellij.openapi.fileEditor.impl.IdeDocumentHistoryImpl", "myBackPlaces"),
    new IgnoredRef(-1, "com.intellij.openapi.editor.impl.RangeMarkerTree$RMNode", "intervals"),
    new IgnoredRef(1, "com.android.tools.idea.io.netty.buffer.ByteBufAllocator", "DEFAULT"),
    new IgnoredRef(-1, "com.intellij.workspaceModel.ide.impl.jps.serialization.JpsProjectModelSynchronizer", "incomingChanges"),
    new IgnoredRef(-1, "com.intellij.workspaceModel.ide.impl.jps.serialization.JpsProjectSerializersImpl", "internalSourceToExternal"),
    new IgnoredRef(-2, "com.intellij.util.concurrency.AppDelayQueue", "q"),
    new IgnoredRef(-2, "com.intellij.execution.process.ProcessIOExecutorService", "workers"),
    new IgnoredRef(-4, "com.intellij.util.containers.RecentStringInterner", "myInterns"),
    new IgnoredRef(-1, "com.intellij.util.xml.EvaluatedXmlNameImpl", "ourInterned"),
    // IDEA-234673
    new IgnoredRef(-1, "com.intellij.ide.util.treeView.AbstractTreeUi", "myElementToNodeMap"),

    // as-driver-specific:
    new IgnoredRef(2, "com.android.tools.idea.io.grpc.InternalChannelz", "perServerSockets"),
    new IgnoredRef(-1, "com.android.tools.idea.io.grpc.netty.shaded.io.netty.util.internal.shaded.org.jctools.queues.MpscChunkedArrayQueue", "producerBuffer"),
    new IgnoredRef(-1, "com.android.tools.idea.io.grpc.netty.shaded.io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueue", "buffer"),
    new IgnoredRef(-1, "com.android.tools.idea.io.grpc.netty.shaded.io.netty.buffer.PoolChunk", "subpages"),
  }).map(IgnoredRef::toIgnoreListEntry).toList());

  private static Supplier<List<Expander>> customExpanders = () -> List.of(new SmartListExpander(), new SmartFMapExpander());

  private static List<Object> forbiddenObjects = List.of(TObjectHash.REMOVED);

  public static BleakOptions getDefaults() {
    return new BleakOptions().withCheck(new MainBleakCheck(globalIgnoreList, customExpanders, forbiddenObjects, Duration.ofSeconds(60)));
  }

  public static BleakOptions defaultsWithAdditionalIgnoreList(IgnoreList<LeakInfo> additionalIgnoreList) {
      return new BleakOptions()
        .withCheck(new MainBleakCheck(globalIgnoreList.plus(additionalIgnoreList), customExpanders, forbiddenObjects, Duration.ofSeconds(60)));
  }
}