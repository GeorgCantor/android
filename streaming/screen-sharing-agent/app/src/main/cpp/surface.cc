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

#include "surface.h"

#include <android/native_window_jni.h>

#include "log.h"

namespace screensharing {

JObject SurfaceToJava(Jni jni, ANativeWindow* surface) {
  if (surface == nullptr) {
    return JObject();
  }
  JObject java_surface(jni, ANativeWindow_toSurface(jni, surface));
  if (java_surface.IsNull()) {
    Log::Fatal(INPUT_SURFACE_CREATION_ERROR, "Unable to create an android.view.Surface");
  }
  return java_surface;
}

} // namespace screensharing