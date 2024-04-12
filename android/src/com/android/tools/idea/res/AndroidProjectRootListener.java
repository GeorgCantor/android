/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.res;

import com.android.tools.idea.model.AndroidModel;
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager.SyncResult;
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager.SyncResultListener;
import com.android.tools.idea.projectsystem.ProjectSystemSyncUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbModeTask;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.ResourceFolderManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Service that subscribes to project root changes in order to invalidate {@link AndroidDependenciesCache},
 * the {@link ResourceFolderManager} cache, and to update resource repositories.
 */
public class AndroidProjectRootListener implements Disposable.Default {
  /**
   * Makes AndroidProjectRootListener listen to the {@link ModuleRootListener#TOPIC} events if it has not been listening already.
   *
   * @param project the project to listen on
   */
  public static void ensureSubscribed(@NotNull Project project) {
    project.getService(AndroidProjectRootListener.class);
  }

  private AndroidProjectRootListener(@NotNull Project project) {
    MessageBusConnection messageBusConnection = project.getMessageBus().connect(this);

    messageBusConnection.subscribe(ModuleRootListener.TOPIC, new ModuleRootListener() {
      @Override
      public void rootsChanged(@NotNull ModuleRootEvent event) {
        moduleRootsOrDependenciesChanged(project, AndroidProjectRootListener.this);
      }
    });

    messageBusConnection.subscribe(ProjectSystemSyncUtil.PROJECT_SYSTEM_SYNC_TOPIC, new SyncResultListener() {
      @Override
      public void syncEnded(@NotNull SyncResult result) {
        // This event is called on the EDT. Calling `moduleRootsOrDependenciesChanged` directly ends up executing the DumbModeTask
        // synchronously, which has leads to failures due to the state we're in from higher up the stack. Executing this on the EDT later
        // avoids that situation.
        ApplicationManager.getApplication().invokeLater(
          () -> moduleRootsOrDependenciesChanged(project, AndroidProjectRootListener.this));
      }
    });
  }

  /**
   * Called when module roots have changed in the given project.
   *
   * @param project the project whose module roots changed
   */
  private static void moduleRootsOrDependenciesChanged(@NotNull Project project, Disposable parentDisposable) {
    ReadAction.run(() -> {
      if (!project.isDisposed()) {
        new MyDumbModeTask(project, parentDisposable).queue(project);
      }
    });
  }

  /**
   * Called when module roots have changed in the given module.
   *
   * @param module the module whose roots changed
   */
  private static void moduleRootsOrDependenciesChanged(@NotNull Module module) {
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet != null) {
      if (AndroidModel.isRequired(facet) && AndroidModel.get(facet) == null) {
        // Project not yet fully initialized. No need to do a sync now because our
        // GradleProjectAvailableListener will be called as soon as it is and do a proper sync.
        return;
      }

      AndroidDependenciesCache.getInstance(module).dropCache();
      ResourceFolderManager.getInstance(facet).checkForChanges();
      StudioResourceRepositoryManager.getInstance(facet).updateRootsAndLibraries();
    }
  }

  private static class MyDumbModeTask extends DumbModeTask {
    private final @NotNull Project myProject;

    private MyDumbModeTask(@NotNull Project project, @NotNull Disposable parent) {
      myProject = project;
      Disposer.register(parent, this);
    }

    @Override
    public void performInDumbMode(@NotNull ProgressIndicator indicator) {
      if (!myProject.isDisposed()) {
        indicator.setText("Updating resource repository roots");
        ModuleManager moduleManager = ModuleManager.getInstance(myProject);
        for (Module module : moduleManager.getModules()) {
          moduleRootsOrDependenciesChanged(module);
        }
      }
    }

    @Nullable
    @Override
    public DumbModeTask tryMergeWith(@NotNull DumbModeTask taskFromQueue) {
      if (taskFromQueue instanceof MyDumbModeTask && ((MyDumbModeTask)taskFromQueue).myProject.equals(myProject)) return this;
      return null;
    }
  }
}
