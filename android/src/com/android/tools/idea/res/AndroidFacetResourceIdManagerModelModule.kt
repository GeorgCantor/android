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
package com.android.tools.idea.res

import com.android.tools.res.ResourceNamespacing
import com.android.tools.res.ids.ResourceIdManagerModelModule
import org.jetbrains.android.facet.AndroidFacet

/** Studio-specific [ResourceIdManagerModelModule] implementation based on [AndroidFacet]. */
class AndroidFacetResourceIdManagerModelModule(private val facet: AndroidFacet) : ResourceIdManagerModelModule {
  override val isAppOrFeature: Boolean
    get() = facet.configuration.isAppOrFeature

  override val namespacing: ResourceNamespacing
    get() = StudioResourceRepositoryManager.getInstance(facet).namespacing
}