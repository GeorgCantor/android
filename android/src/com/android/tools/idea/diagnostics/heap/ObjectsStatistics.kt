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
package com.android.tools.idea.diagnostics.heap

data class ObjectsStatistics(
  var objectsCount: Int = 0,
  var totalSizeInBytes: Long = 0L
) {
  fun addObject(size: Long) {
    objectsCount++
    totalSizeInBytes += size
  }

  fun addStats(add: ObjectsStatistics) {
    objectsCount += add.objectsCount
    totalSizeInBytes += add.totalSizeInBytes
  }

  fun addStats(objectsCount: Int, totalSizeInBytes: Long) {
    this.objectsCount += objectsCount
    this.totalSizeInBytes += totalSizeInBytes
  }
}
