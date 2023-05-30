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
package com.android.tools.idea.avdmanager;

import static org.junit.Assert.assertEquals;

import com.android.sdklib.devices.Device;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class NameComparatorTest {
  private final Comparator<Device> myComparator = new NameComparator();

  @Test
  public void comparePhone() {
    // Arrange
    var expectedDevices = List.of(mockDevice("Resizable (Experimental)"),
                                  mockDevice("7.6\" Fold-in with outer display"),
                                  mockDevice("Pixel"),
                                  mockDevice("Pixel XL"),
                                  mockDevice("Pixel 2"),
                                  mockDevice("Pixel 2 XL"),
                                  mockDevice("Pixel 3"),
                                  mockDevice("Pixel 3 XL"),
                                  mockDevice("Pixel 3a"),
                                  mockDevice("Pixel 3a XL"),
                                  mockDevice("Pixel 4"),
                                  mockDevice("Pixel 4 XL"),
                                  mockDevice("Pixel 4a"),
                                  mockDevice("Pixel 5"),
                                  mockDevice("Pixel 6"),
                                  mockDevice("Pixel 6 Pro"),
                                  mockDevice("Pixel 6a"),
                                  mockDevice("Pixel 7"),
                                  mockDevice("Pixel 7 Pro"),
                                  mockDevice("Pixel Fold"),
                                  mockDevice("Medium Phone"),
                                  mockDevice("Small Phone"));

    var actualDevices = shuffle(expectedDevices);

    // Act
    actualDevices.sort(myComparator);

    // Assert
    assertEquals(expectedDevices, actualDevices);
  }

  @Test
  public void compareTablet() {
    // Arrange
    var expectedDevices = List.of(mockDevice("Pixel C"),
                                  mockDevice("Pixel Tablet"),
                                  mockDevice("Medium Tablet"));

    var actualDevices = shuffle(expectedDevices);

    // Act
    actualDevices.sort(myComparator);

    // Assert
    assertEquals(expectedDevices, actualDevices);
  }

  @Test
  public void compareWearOs() {
    // Arrange
    var expectedDevices = List.of(mockDevice("Wear OS Large Round"),
                                  mockDevice("Wear OS Rectangular"),
                                  mockDevice("Wear OS Small Round"),
                                  mockDevice("Wear OS Square"));

    var actualDevices = shuffle(expectedDevices);

    // Act
    actualDevices.sort(myComparator);

    // Assert
    assertEquals(expectedDevices, actualDevices);
  }

  @Test
  public void compareDesktop() {
    // Arrange
    var expectedDevices = List.of(mockDevice("Large Desktop"),
                                  mockDevice("Medium Desktop"),
                                  mockDevice("Small Desktop"));

    var actualDevices = shuffle(expectedDevices);

    // Act
    actualDevices.sort(myComparator);

    // Assert
    assertEquals(expectedDevices, actualDevices);
  }

  @Test
  public void compareTv() {
    // Arrange
    var expectedDevices = List.of(mockDevice("Television (1080p)"),
                                  mockDevice("Television (4K)"),
                                  mockDevice("Television (720p)"));

    var actualDevices = shuffle(expectedDevices);

    // Act
    actualDevices.sort(myComparator);

    // Assert
    assertEquals(expectedDevices, actualDevices);
  }

  @Test
  public void compareLegacy() {
    var expectedDevices = List.of(mockDevice("10.1\" WXGA (Tablet)"),
                                  mockDevice("13.5\" Freeform"),
                                  mockDevice("2.7\" QVGA"),
                                  mockDevice("2.7\" QVGA slider"),
                                  mockDevice("3.2\" HVGA slider (ADP1)"),
                                  mockDevice("3.2\" QVGA (ADP2)"),
                                  mockDevice("3.3\" WQVGA"),
                                  mockDevice("3.4\" WQVGA"),
                                  mockDevice("3.7\" FWVGA slider"),
                                  mockDevice("3.7\" WVGA (Nexus One)"),
                                  mockDevice("4\" WVGA (Nexus S)"),
                                  mockDevice("4.65\" 720p (Galaxy Nexus)"),
                                  mockDevice("4.7\" WXGA"),
                                  mockDevice("5.1\" WVGA"),
                                  mockDevice("5.4\" FWVGA"),
                                  mockDevice("6.7\" Horizontal Fold-in"),
                                  mockDevice("7\" WSVGA (Tablet)"),
                                  mockDevice("7.4\" Rollable"),
                                  mockDevice("8\" Fold-out"),
                                  mockDevice("Galaxy Nexus"),
                                  mockDevice("Nexus 10"),
                                  mockDevice("Nexus 4"),
                                  mockDevice("Nexus 5"),
                                  mockDevice("Nexus 5X"),
                                  mockDevice("Nexus 6"),
                                  mockDevice("Nexus 6P"),
                                  mockDevice("Nexus 7"),
                                  mockDevice("Nexus 7 (2012)"),
                                  mockDevice("Nexus 9"),
                                  mockDevice("Nexus One"),
                                  mockDevice("Nexus S"));

    var actualDevices = shuffle(expectedDevices);

    // Act
    actualDevices.sort(myComparator);

    // Assert
    assertEquals(expectedDevices, actualDevices);
  }

  @NotNull
  private static Device mockDevice(@NotNull String name) {
    var device = Mockito.mock(Device.class);

    Mockito.when(device.getDisplayName()).thenReturn(name);
    Mockito.when(device.toString()).thenReturn(name);

    return device;
  }

  @NotNull
  private static List<Device> shuffle(@NotNull Collection<Device> expectedDevices) {
    var actualDevices = new ArrayList<>(expectedDevices);
    Collections.shuffle(actualDevices);

    var actualNames = actualDevices.stream()
      .map(Device::getDisplayName)
      .collect(Collectors.joining(", "));

    System.out.println("Shuffled devices: " + actualNames);
    return actualDevices;
  }
}
