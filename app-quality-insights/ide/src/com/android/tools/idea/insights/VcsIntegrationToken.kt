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
package com.android.tools.idea.insights

import com.android.tools.idea.projectsystem.AndroidProjectSystem
import com.android.tools.idea.projectsystem.Token
import com.intellij.openapi.extensions.ExtensionPointName

interface VcsIntegrationToken<P : AndroidProjectSystem> : Token {
  companion object {
    val EP_NAME =
      ExtensionPointName<VcsIntegrationToken<AndroidProjectSystem>>(
        "com.android.tools.idea.insights.vcsIntegrationToken"
      )
  }
  fun canShowSuggestVcsIntegrationFeaturePanel(projectSystem: P): Boolean
  fun isChangeAwareAnnotationEnabled(projectSystem: P): Boolean
}
