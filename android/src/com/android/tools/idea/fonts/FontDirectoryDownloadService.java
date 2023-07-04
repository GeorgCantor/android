/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.fonts;

import com.android.ide.common.fonts.FontLoader;
import com.android.ide.common.fonts.FontProvider;
import com.android.tools.fonts.FontDirectoryDownloader;
import com.android.tools.idea.downloads.DownloadService;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URL;

import static com.android.ide.common.fonts.FontLoaderKt.FONT_DIRECTORY_FILENAME;
import static com.android.ide.common.fonts.FontLoaderKt.FONT_DIRECTORY_FOLDER;

/**
 * {@link FontDirectoryDownloadService} is a download service for downloading a font directory
 * i.e. a list of font families.
 */
class FontDirectoryDownloadService implements FontDirectoryDownloader {
  private static final String SERVICE_POSTFIX = " Downloadable Fonts";
  private static final String TEMPORARY_FONT_DIRECTORY_FILENAME = "temp_font_directory.xml";

  private final DownloadService myDownloadService;
  private final FontProvider myProvider;

  public FontDirectoryDownloadService(@NotNull FontLoader fontLoader, @NotNull FontProvider provider, @NotNull File fontCachePath) {
    myDownloadService = new DownloadService(provider.getName() + SERVICE_POSTFIX,
                                            provider.getUrl(),
                                            getFallbackResourceUrl(provider),
                                            getCachePath(provider, fontCachePath),
                                            TEMPORARY_FONT_DIRECTORY_FILENAME,
                                            FONT_DIRECTORY_FILENAME) {
      @Override
      public void loadFromFile(@NotNull URL url) {
        fontLoader.loadDirectory(myProvider, url);
      }
    };
    myProvider = provider;
  }

  @Override
  public void refreshFonts(@Nullable Runnable success, @Nullable Runnable failure) {
    myDownloadService.refresh(success, failure);
  }

  @NotNull
  private static File getCachePath(@NotNull FontProvider provider, @NotNull File fontCachePath) {
    File providerPath = new File(fontCachePath, provider.getAuthority());
    File directoryPath = new File(providerPath, FONT_DIRECTORY_FOLDER);
    FileUtil.createDirectory(directoryPath);
    return directoryPath;
  }

  static URL getFallbackResourceUrl(@NotNull FontProvider provider) {
    String filename = provider.equals(FontProvider.GOOGLE_PROVIDER) ?
                      "google_font_directory.xml" : "empty_font_directory.xml";
    return FontDirectoryDownloadService.class.getClassLoader().getResource("fonts/" + filename);
  }
}
