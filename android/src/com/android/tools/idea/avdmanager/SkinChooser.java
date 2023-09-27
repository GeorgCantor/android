/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.ComboboxWithBrowseButton;
import com.intellij.util.concurrency.EdtExecutorService;
import java.awt.ItemSelectable;
import java.awt.event.ItemListener;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.JComboBox;
import javax.swing.JList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Combo box that populates itself with the skins used by existing devices. Also allows adding a new skin by browsing.
 */
final class SkinChooser extends ComboboxWithBrowseButton implements ItemSelectable {
  static final File LOADING_SKINS = new File("_loading_skins");

  static final File FAILED_TO_LOAD_SKINS = new File("_failed_to_load_skins");

  private final @NotNull Supplier<ListenableFuture<Collection<Path>>> myUpdateSkins;
  private final @NotNull Executor myDeviceSkinUpdaterServiceExecutor;

  SkinChooser(@Nullable Project project, boolean includeSdkHandlerSkins) {
    this(project, updateSkins(includeSdkHandlerSkins), DeviceSkinUpdaterService.getInstance().getExecutor());
  }

  private static @NotNull Supplier<ListenableFuture<Collection<Path>>> updateSkins(boolean includeSdkHandlerSkins) {
    if (includeSdkHandlerSkins) {
      return DeviceSkinUpdaterService.getInstance()::updateSkinsIncludingSdkHandlerOnes;
    }

    return DeviceSkinUpdaterService.getInstance()::updateSkinsExcludingSdkHandlerOnes;
  }

  @VisibleForTesting
  SkinChooser(@Nullable Project project,
              @NotNull Supplier<ListenableFuture<Collection<Path>>> updateSkins,
              @NotNull Executor deviceSkinUpdaterServiceExecutor) {
    myUpdateSkins = updateSkins;
    myDeviceSkinUpdaterServiceExecutor = deviceSkinUpdaterServiceExecutor;

    getComboBox().setRenderer(new ColoredListCellRenderer<>() {
      @Override
      protected void customizeCellRenderer(@NotNull JList<? extends File> list,
                                           @Nullable File skin,
                                           int index,
                                           boolean selected,
                                           boolean focused) {
        if (FileUtil.filesEqual(skin, LOADING_SKINS)) {
          append("Loading skins...");
          return;
        }

        if (FileUtil.filesEqual(skin, FAILED_TO_LOAD_SKINS)) {
          append("Failed to load skins");
          return;
        }

        if (skin == null) {
          skin = AvdWizardUtils.NO_SKIN;
        }

        String skinPath = skin.getPath();
        if (FileUtil.filesEqual(skin, AvdWizardUtils.NO_SKIN)) {
          append("No Skin");
        }
        else if (skinPath.contains("/sdk/platforms/")) {
          append(skinPath.replaceAll(".*/sdk/platforms/(.*)/skins/(.*)", "$2 ($1)"));
        }
        else if (skinPath.contains("/sdk/system-images/")) {
          append(skinPath.replaceAll(".*/sdk/system-images/(.*)/(.*)/(.*)/skins/(.*)", "$4 ($1 $3)"));
        }
        else {
          append(skin.getName());
        }
      }
    });

    addBrowseFolderListener("Select Custom Skin",
                            "Select the directory containing your custom skin definition",
                            project,
                            new FileChooserDescriptor(false, true, false, false, false, false),
                            new TextComponentAccessor<>() {
                              @Override
                              public String getText(JComboBox component) {
                                var item = (File)component.getSelectedItem();
                                assert item != null;

                                return item.getPath();
                              }

                              @Override
                              public void setText(JComboBox component, @NotNull String text) {
                                File skin = new File(text);

                                FutureCallback<List<File>> callback = new LoadSkinsFutureCallback(SkinChooser.this, skin) {
                                  @Override
                                  public void onSuccess(@NotNull List<File> skins) {
                                    if (!skins.contains(skin)) {
                                      skins.add(skin);
                                    }

                                    super.onSuccess(skins);
                                    setEnabled(true);
                                  }
                                };

                                Futures.addCallback(loadSkins(), callback, EdtExecutorService.getInstance());
                              }
                            });

    setTextFieldPreferredWidth(20);
  }

  @NotNull
  ListenableFuture<List<File>> loadSkins() {
    setEnabled(false);
    setItems(Collections.singletonList(LOADING_SKINS));

    // noinspection UnstableApiUsage
    return Futures.transform(myUpdateSkins.get(), SkinChooser::transform, myDeviceSkinUpdaterServiceExecutor);
  }

  private static @NotNull List<File> transform(@NotNull Collection<Path> paths) {
    List<File> transformed = new ArrayList<>(1 + paths.size());
    transformed.add(SkinUtils.noSkin().toFile());

    paths.stream()
      .filter(Files::exists)
      .distinct()
      .sorted()
      .map(Path::toFile)
      .forEach(transformed::add);

    return transformed;
  }

  @NotNull
  @VisibleForTesting
  Object getItems() {
    JComboBox<File> comboBox = getComboBox();

    return IntStream.range(0, comboBox.getItemCount())
      .mapToObj(comboBox::getItemAt)
      .collect(Collectors.toList());
  }

  private void setItems(List<File> items) {
    getComboBox().setModel(new CollectionComboBoxModel<>(items));
  }

  @NotNull
  @SuppressWarnings("EmptyMethod")
  @Override
  public JComboBox<File> getComboBox() {
    // noinspection unchecked
    return super.getComboBox();
  }

  @Override
  public Object[] getSelectedObjects() {
    return getComboBox().getSelectedObjects();
  }

  @Override
  public void addItemListener(ItemListener l) {
    getComboBox().addItemListener(l);
  }

  @Override
  public void removeItemListener(ItemListener l) {
    getComboBox().removeItemListener(l);
  }
}
