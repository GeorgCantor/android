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
package com.android.tools.idea.run.deployment;

import com.android.tools.idea.run.AndroidDevice;
import com.android.tools.idea.run.LaunchCompatibility;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.icons.AllIcons;
import com.intellij.ui.LayeredIcon;
import icons.StudioIcons;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PhysicalDevice extends Device {
  private static final Icon ourPhoneIcon = ExecutionUtil.getLiveIndicator(StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_PHONE);
  private static final Icon ourWearIcon = ExecutionUtil.getLiveIndicator(StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_WEAR);
  private static final Icon ourTvIcon = ExecutionUtil.getLiveIndicator(StudioIcons.DeviceExplorer.PHYSICAL_DEVICE_TV);

  private PhysicalDevice(@NotNull Builder builder) {
    super(builder);
  }

  static @NotNull PhysicalDevice newDevice(@NotNull Device device, @NotNull KeyToConnectionTimeMap map) {
    Key key = device.getKey();

    return new Builder()
      .setKey(key)
      .setType(device.getType())
      .setLaunchCompatibility(device.getLaunchCompatibility())
      .setConnectionTime(map.get(key))
      .setName(device.getName())
      .setAndroidDevice(device.getAndroidDevice())
      .build();
  }

  @VisibleForTesting
  static final class Builder extends Device.Builder {
    @NotNull
    @VisibleForTesting
    Builder setKey(@NotNull Key key) {
      myKey = key;
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder setType(@NotNull Type type) {
      myType = type;
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder setLaunchCompatibility(@NotNull LaunchCompatibility launchCompatibility) {
      myLaunchCompatibility = launchCompatibility;
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder setConnectionTime(@NotNull Instant connectionTime) {
      myConnectionTime = connectionTime;
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder setName(@NotNull String name) {
      myName = name;
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder setAndroidDevice(@NotNull AndroidDevice androidDevice) {
      myAndroidDevice = androidDevice;
      return this;
    }

    @NotNull
    @Override
    PhysicalDevice build() {
      return new PhysicalDevice(this);
    }
  }

  @NotNull
  @Override
  Icon getIcon() {
    Icon icon = switch (getType()) {
      case TV -> ourTvIcon;
      case WEAR -> ourWearIcon;
      case PHONE -> ourPhoneIcon;
    };

    return switch (getLaunchCompatibility().getState()) {
      case ERROR -> new LayeredIcon(icon, StudioIcons.Common.ERROR_DECORATOR);
      case WARNING -> new LayeredIcon(icon, AllIcons.General.WarningDecorator);
      case OK -> icon;
    };
  }

  /**
   * @return true. Physical devices come and go as they are connected and disconnected; there are no instances of this class for
   * disconnected physical devices.
   */
  @Override
  boolean isConnected() {
    return true;
  }

  @NotNull
  @Override
  Collection<Snapshot> getSnapshots() {
    return Collections.emptyList();
  }

  @NotNull
  @Override
  Target getDefaultTarget() {
    return new RunningDeviceTarget(getKey());
  }

  @NotNull
  @Override
  Collection<Target> getTargets() {
    return Collections.singletonList(new RunningDeviceTarget(getKey()));
  }

  @Override
  public int hashCode() {
    return Objects.hash(getKey(), getType(), getLaunchCompatibility(), getConnectionTime(), getName(), getAndroidDevice());
  }

  @Override
  public boolean equals(@Nullable Object object) {
    if (!(object instanceof PhysicalDevice device)) {
      return false;
    }

    return getKey().equals(device.getKey()) &&
           getType().equals(device.getType()) &&
           getLaunchCompatibility().equals(device.getLaunchCompatibility()) &&
           Objects.equals(getConnectionTime(), device.getConnectionTime()) &&
           getName().equals(device.getName()) &&
           getAndroidDevice().equals(device.getAndroidDevice());
  }
}
