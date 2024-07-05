/*
 * Copyright (C) 2021 The Android Open Source Project
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

#pragma once

#include <android/rect.h>
#include <android/native_window.h>

#include "common.h"
#include "display_info.h"
#include "jvm.h"

namespace screensharing {

// Power mode constants defined in the android.view.SurfaceControl class.
enum class DisplayPowerMode : int32_t {
  POWER_MODE_OFF = 0,
  POWER_MODE_DOZE = 1,
  POWER_MODE_NORMAL = 2,
  POWER_MODE_DOZE_SUSPEND = 3,
  POWER_MODE_ON_SUSPEND = 4
};

// Provides access to few non-API methods of the android.view.SurfaceControl class.
// Can only be used by the thread that created the object.
class SurfaceControl {
private:
  static void InitializeStatics(Jni jni);

  static void OpenTransaction(Jni jni);
  static void CloseTransaction(Jni jni);
  static void SetDisplaySurface(Jni jni, jobject display_token, ANativeWindow* surface);
  static void SetDisplayLayerStack(Jni jni, jobject display_token, int32_t layer_stack);
  static void SetDisplayProjection(
      Jni jni, jobject display_token, int32_t orientation, const ARect& layer_stack_rect, const ARect& display_rect);

  [[nodiscard]] static JObject ToJava(Jni jni, const ARect& rect);

public:
  static JObject GetInternalDisplayToken(Jni jni);
  static void SetDisplayPowerMode(Jni jni, jobject display_token, DisplayPowerMode mode);

  static JObject CreateDisplay(Jni jni, const char* name, bool secure);
  static void DestroyDisplay(Jni jni, jobject display_token);
  // The display area defined by display_info.logical_size is mapped to projection rectangle.
  static void ConfigureProjection(
      Jni jni, jobject display_token, ANativeWindow* surface, const DisplayInfo& display_info, ARect projection_rect);

private:
  // SurfaceControl class.
  static JClass surface_control_class_;
  static jmethodID get_internal_display_token_method_;
  static bool get_internal_display_token_method_not_available_;
  static jmethodID close_transaction_method_;
  static jmethodID open_transaction_method_;
  static jmethodID create_display_method_;
  static jmethodID destroy_display_method_;
  static jmethodID set_display_surface_method_;
  static jmethodID set_display_layer_stack_method_;
  static jmethodID set_display_projection_method_;
  static jmethodID set_display_power_mode_method_;
  // android.graphics.Rect class.
  static JClass rect_class_;
  static jmethodID rect_constructor_;

  DISALLOW_COPY_AND_ASSIGN(SurfaceControl);
};

}  // namespace screensharing
