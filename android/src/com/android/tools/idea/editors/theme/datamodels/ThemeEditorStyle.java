/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.editors.theme.datamodels;

import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.resources.ResourceRepository;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.ResourceType;
import com.android.sdklib.IAndroidTarget;
import com.android.tools.configurations.ConfigurationSettings;
import com.android.tools.res.ResourceRepositoryManager;
import com.intellij.openapi.diagnostic.Logger;
import com.android.tools.sdk.AndroidTargetData;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a style in ThemeEditor. Knows about the style in all {@link FolderConfiguration}s.
 */
public class ThemeEditorStyle {
  private static final Logger LOG = Logger.getInstance(ThemeEditorStyle.class);

  @NotNull private final ConfigurationSettings mySettings;
  @NotNull private final ResourceReference myStyleReference;

  public ThemeEditorStyle(@NotNull ConfigurationSettings settings, @NotNull ResourceReference styleReference) {
    mySettings = settings;
    myStyleReference = styleReference;
  }

  /**
   * Returns the style reference.
   */
  @NotNull
  public final ResourceReference getStyleReference() {
    return myStyleReference;
  }

  /**
   * If the theme's namespace matches the current module, returns the simple name of the theme.
   * Otherwise returns the name of the theme prefixed by the theme's package name.
   *
   * <p>Note: The returned qualified name is intended for displaying to the user and should not
   * be used for anything else.
   */
  @NotNull
  public String getQualifiedName() {
    ResourceRepositoryManager repositoryManager = mySettings.getConfigModule().getResourceRepositoryManager();
    if (repositoryManager == null || repositoryManager.getNamespace().equals(myStyleReference.getNamespace())) {
      return myStyleReference.getName();
    }
    return myStyleReference.getQualifiedName();
  }

  /**
   * Returns the style name without namespaces or prefixes.
   */
  @NotNull
  public String getName() {
    return myStyleReference.getName();
  }

  public boolean isFramework() {
    return myStyleReference.getNamespace().equals(ResourceNamespace.ANDROID);
  }

  public boolean isProjectStyle() {
    if (isFramework()) {
      return false;
    }
    ResourceRepositoryManager repositoryManager = mySettings.getConfigModule().getResourceRepositoryManager();
    assert repositoryManager != null;
    ResourceRepository repository = repositoryManager.getProjectResources();
    return repository.hasResources(myStyleReference.getNamespace(), myStyleReference.getResourceType(), myStyleReference.getName());
  }

  /**
   * Returns whether this style is public.
   */
  public boolean isPublic() {
    if (!isFramework()) {
      return true;
    }

    IAndroidTarget target = mySettings.getTarget();
    if (target == null) {
      LOG.error("Unable to get IAndroidTarget.");
      return false;
    }

    AndroidTargetData androidTargetData = AndroidTargetData.getTargetData(target, mySettings.getConfigModule().getAndroidPlatform());
    if (androidTargetData == null) {
      LOG.error("Unable to get AndroidTargetData.");
      return false;
    }

    return androidTargetData.isResourcePublic(ResourceType.STYLE, getName());
  }
}
