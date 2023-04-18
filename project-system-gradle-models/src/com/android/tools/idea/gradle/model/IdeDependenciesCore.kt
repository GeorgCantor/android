/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.gradle.model

interface IdeDependenciesCore {
  /**
   * A function that should be used to resolve transitive dependencies obtained from [dependencies]
   */
  fun lookup(ref: Int): IdeDependencyCore

  /**
   * Returns the dependencies, both direct and transitive. This is the classpath of the containing artifact and as such the
   * order of these dependencies is relevant and should be kept as provided by Gradle.
   */
  val dependencies: List<IdeDependencyCore>
}
