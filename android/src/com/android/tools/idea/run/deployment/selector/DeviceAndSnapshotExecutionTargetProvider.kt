/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.run.deployment.selector;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.execution.ExecutionTarget;
import com.intellij.execution.ExecutionTargetProvider;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import com.intellij.serviceContainer.NonInjectable;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

public class DeviceAndSnapshotExecutionTargetProvider extends ExecutionTargetProvider {
  @NotNull
  private final Supplier<DeviceAndSnapshotComboBoxAction> myDeviceAndSnapshotComboBoxActionGetInstance;

  @NotNull
  private final Function<Project, AsyncDevicesGetter> myAsyncDevicesGetterGetInstance;

  @SuppressWarnings("unused")
  public DeviceAndSnapshotExecutionTargetProvider() {
    this(DeviceAndSnapshotComboBoxAction::getInstance, AsyncDevicesGetter::getInstance);
  }

  @NonInjectable
  @VisibleForTesting
  DeviceAndSnapshotExecutionTargetProvider(@NotNull Supplier<DeviceAndSnapshotComboBoxAction> deviceAndSnapshotComboBoxActionGetInstance,
                                           @NotNull Function<Project, AsyncDevicesGetter> asyncDevicesGetterGetInstance) {
    myDeviceAndSnapshotComboBoxActionGetInstance = deviceAndSnapshotComboBoxActionGetInstance;
    myAsyncDevicesGetterGetInstance = asyncDevicesGetterGetInstance;
  }

  @NotNull
  @Override
  public List<ExecutionTarget> getTargets(@NotNull Project project, @NotNull RunConfiguration configuration) {
    var targets = myDeviceAndSnapshotComboBoxActionGetInstance.get().getSelectedTargets(project).orElseGet(Set::of);
    return List.of(new DeviceAndSnapshotComboBoxExecutionTarget(targets, myAsyncDevicesGetterGetInstance.apply(project)));
  }
}
