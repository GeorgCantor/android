/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <map>

#include "control_messages.h"

namespace screensharing {

// Holds device settings and application specific settings that can be manipulated by ui_settings.
class UiSettingsState {
public:
  UiSettingsState();

  void copy(UiSettingsState* result) const {
    response_.copy(&result->response_);
    result->app_locales_ = app_locales_;
  }

  void copy(UiSettingsResponse* response) {
    response_.copy(response);
  }

  void set_dark_mode(bool dark_mode) {
    response_.set_dark_mode(dark_mode);
  }

  bool dark_mode() {
    return response_.dark_mode();
  }

  void set_talkback_installed(bool installed) {
    response_.set_talkback_installed(installed);
  }

  bool talkback_installed() {
    return response_.talkback_installed();
  }

  void set_talkback_on(bool on) {
    response_.set_talkback_on(on);
  }

  bool talkback_on() {
    return response_.talkback_on();
  }

  void set_select_to_speak_on(bool on) {
    response_.set_select_to_speak_on(on);
  }

  bool select_to_speak_on() {
    return response_.select_to_speak_on();
  }

  void set_font_size(int32_t font_size) {
    response_.set_font_size(font_size);
  }

  int32_t font_size() {
    return response_.font_size();
  }

  void set_density(int32_t density) {
    response_.set_density(density);
  }

  int32_t density() {
    return response_.density();
  }

  std::string app_locale_of(const std::string application_id);

  void add_app_locale(const std::string& application_id, const std::string& locale) {
    app_locales_[application_id] = locale;
  }

  void add_unseen_app_locales(UiSettingsState* result) const;

  std::vector<std::string> get_application_ids();

private:
  // Device specific settings:
  UiSettingsResponse response_;

  // Application specific settings: application_id -> app_locale
  std::map<std::string, std::string> app_locales_;

  DISALLOW_COPY_AND_ASSIGN(UiSettingsState);
};

}  // namespace screensharing
