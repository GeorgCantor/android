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
package com.android.tools.idea.run.deployment.legacyselector;

import static org.junit.Assert.assertEquals;

import com.android.ddmlib.AvdData;
import com.android.ddmlib.IDevice;
import com.android.tools.idea.run.ConnectedAndroidDevice;
import com.android.tools.idea.run.LaunchCompatibility;
import com.android.tools.idea.run.LaunchCompatibilityChecker;
import com.google.common.util.concurrent.Futures;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class ConnectedDevicesTaskTest {
  private final @NotNull IDevice myDevice;

  @NotNull
  private final ProvisionerHelper myHelper;

  private final @NotNull AndroidDebugBridge myBridge;

  @Nullable
  private AsyncSupplier<Collection<ConnectedDevice>> myTask;

  public ConnectedDevicesTaskTest() {
    myDevice = Mockito.mock(IDevice.class);
    Mockito.when(myDevice.isOnline()).thenReturn(true);

    myHelper = Mockito.mock(ProvisionerHelper.class);
    Mockito.when(myHelper.getIcon(myDevice)).thenReturn(Futures.immediateFuture(Optional.of(Mockito.mock(Icon.class))));

    myBridge = Mockito.mock(AndroidDebugBridge.class);
    Mockito.when(myBridge.getConnectedDevices()).thenReturn(Futures.immediateFuture(List.of(myDevice)));
  }

  @Test
  public void getNameDeviceIsNull() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(null));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals("emulator-5554", ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getName());
  }

  @Test
  public void getNameNameIsNull() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData(null, (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals("emulator-5554", ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getName());
  }

  @Test
  public void getNameNameEqualsBuild() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData("<build>", (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals("emulator-5554", ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getName());
  }

  @Test
  public void getName() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData("Pixel_6_API_33", (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals("Pixel_6_API_33", ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getName());
  }

  @Test
  public void getNameAsync() throws Exception {
    // Arrange
    Mockito.when(myDevice.getSystemProperty(IDevice.PROP_DEVICE_MODEL)).thenReturn(Futures.immediateFuture("Pixel 4a"));
    Mockito.when(myDevice.getSystemProperty(IDevice.PROP_DEVICE_MANUFACTURER)).thenReturn(Futures.immediateFuture("Google"));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("02131FQC200017");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals("Google Pixel 4a", ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getName());
  }

  @Test
  public void getKeyAsyncDeviceIsntEmulator() throws Exception {
    // Arrange
    Mockito.when(myDevice.getSystemProperty(IDevice.PROP_DEVICE_MODEL)).thenReturn(Futures.immediateFuture("Pixel 4a"));
    Mockito.when(myDevice.getSystemProperty(IDevice.PROP_DEVICE_MANUFACTURER)).thenReturn(Futures.immediateFuture("Google"));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("02131FQC200017");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(new SerialNumber("02131FQC200017"), ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getKey());
  }

  @Test
  public void getKeyDeviceIsNull() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(null));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(new SerialNumber("emulator-5554"), ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getKey());
  }

  @Test
  public void getKeyPathIsntNull() throws Exception {
    // Arrange
    var path = Path.of(System.getProperty("user.home"), ".android", "avd", "Pixel_6_API_33.avd");

    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData(null, path)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(new VirtualDevicePath(path), ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getKey());
  }

  @Test
  public void getKeyNameIsNull() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData(null, (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(new SerialNumber("emulator-5554"), ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getKey());
  }

  @Test
  public void getKeyNameEqualsBuild() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData("<build>", (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(new SerialNumber("emulator-5554"), ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getKey());
  }

  @Test
  public void getKey() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData("Pixel_6_API_33", (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(new VirtualDeviceName("Pixel_6_API_33"), ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getKey());
  }

  @Test
  public void getLaunchCompatibilityAsyncLaunchCompatibilityCheckerIsNull() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData(null, (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> null);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(LaunchCompatibility.YES, ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getLaunchCompatibility());
  }

  @Test
  public void getLaunchCompatibilityAsync() throws Exception {
    // Arrange
    Mockito.when(myDevice.isEmulator()).thenReturn(true);
    Mockito.when(myDevice.getAvdData()).thenReturn(Futures.immediateFuture(new AvdData(null, (Path)null)));
    Mockito.when(myDevice.getSerialNumber()).thenReturn("emulator-5554");

    var device = new ConnectedAndroidDevice(myDevice);

    var checker = Mockito.mock(LaunchCompatibilityChecker.class);
    Mockito.when(checker.validate(device)).thenReturn(LaunchCompatibility.YES);

    myTask = new ConnectedDevicesTask(myBridge, myHelper, () -> checker, d -> device);

    // Act
    var future = myTask.get();

    // Assert
    assertEquals(LaunchCompatibility.YES, ((List<ConnectedDevice>)future.get(60, TimeUnit.SECONDS)).get(0).getLaunchCompatibility());
  }
}
