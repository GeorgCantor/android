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
package com.android.tools.idea.rendering

import com.android.ide.common.rendering.api.ResourceValue
import com.android.tools.dom.ActivityAttributesSnapshot
import com.android.tools.idea.model.MergedManifestSnapshot
import com.android.tools.rendering.api.RenderModelManifest

/** [RenderModelManifest] implementation based on [MergedManifestSnapshot]. */
class RenderMergedManifest(private val manifest: MergedManifestSnapshot) : RenderModelManifest {
  override val isRtlSupported: Boolean = manifest.isRtlSupported
  override val applicationLabel: ResourceValue? = manifest.applicationLabel
  override val applicationIcon: ResourceValue? = manifest.applicationIcon
  override fun getActivityAttributes(activity: String): ActivityAttributesSnapshot? = manifest.getActivityAttributes(activity)
}