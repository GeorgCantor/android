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

#pragma once

#include "control_messages.h"

namespace screensharing {

// Handles requests and commands related to the UI for the settings shortcut dialog in Studio.
class UiSettings {
public:
  UiSettings();

  void Get(UiSettingsResponse* response);

  void SetDarkMode(bool dark_mode);

  void SetTalkBack(bool on);

  void SetSelectToSpeak(bool on);

  void SetFontSize(int32_t font_size);

  void SetScreenDensity(int32_t density);

  void Reset();

private:
  bool initial_settings_recorded_ = false;
  UiSettingsResponse initial_settings_;
  UiSettingsResponse last_settings_;

  DISALLOW_COPY_AND_ASSIGN(UiSettings);
};

}  // namespace screensharing
