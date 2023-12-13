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

#include "controller.h"

#include <android/keycodes.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>

#include "accessors/device_state_manager.h"
#include "accessors/input_manager.h"
#include "accessors/key_event.h"
#include "accessors/motion_event.h"
#include "agent.h"
#include "flags.h"
#include "jvm.h"
#include "log.h"

namespace screensharing {

using namespace std;
using namespace std::chrono;

namespace {

constexpr int BUFFER_SIZE = 4096;
constexpr int UTF8_MAX_BYTES_PER_CHARACTER = 4;

constexpr int SOCKET_RECEIVE_TIMEOUT_MILLIS = 250;

int64_t UptimeMillis() {
  timespec t = { 0, 0 };
  clock_gettime(CLOCK_MONOTONIC, &t);
  return static_cast<int64_t>(t.tv_sec) * 1000LL + t.tv_nsec / 1000000;
}

// Returns the number of Unicode code points contained in the given UTF-8 string.
int Utf8CharacterCount(const string& str) {
  int count = 0;
  for (auto c : str) {
    if ((c & 0xC0) != 0x80) {
      ++count;
    }
  }
  return count;
}

Point AdjustedDisplayCoordinates(int32_t x, int32_t y, const DisplayInfo& display_info) {
  auto size = display_info.NaturalSize();
  switch (display_info.rotation) {
    case 1:
      return { y, size.width - x };

    case 2:
      return { size.width - x, size.height - y };

    case 3:
      return { size.height - y, x };

    default:
      return { x, y };
  }
}

// Sets the receive timeout for the given socket. Zero timeout value means that reading
// from the socket will never time out.
void SetReceiveTimeoutMillis(int timeout_millis, int socket_fd) {
  struct timeval tv = { .tv_sec = timeout_millis / 1000, .tv_usec = (timeout_millis % 1000) * 1000 };
  setsockopt(socket_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

bool ContainsMultipleDeviceStates(const string& states_text) {
  return states_text.find("DeviceState{") != states_text.rfind("DeviceState{");
}

bool CheckVideoSize(Size video_resolution) {
  if (video_resolution.width > 0 && video_resolution.height > 0) {
    return true;
  }
  Log::E("An attempt to set an invalid video resolution: %dx%d", video_resolution.width, video_resolution.height);
  return false;
}

void InjectMotionEvent(Jni jni, const MotionEvent& event, InputEventInjectionSync mode) {
  JObject motion_event = event.ToJava();
  if (event.action == AMOTION_EVENT_ACTION_HOVER_MOVE || Log::IsEnabled(Log::Level::VERBOSE)) {
    Log::V("motion_event: %s", motion_event.ToString().c_str());
  } else if (Log::IsEnabled(Log::Level::DEBUG)) {
    Log::D("motion_event: %s", motion_event.ToString().c_str());
  }
  InputManager::InjectInputEvent(jni, motion_event, mode);
}

void InjectKeyEvent(Jni jni, const KeyEvent& event, InputEventInjectionSync mode) {
  JObject key_event = event.ToJava();
  if (Log::IsEnabled(Log::Level::DEBUG)) {
    Log::D("key_event: %s", key_event.ToString().c_str());
  }
  InputManager::InjectInputEvent(jni, key_event, mode);
}

}  // namespace

Controller::Controller(int socket_fd)
    : socket_fd_(socket_fd),
      input_stream_(socket_fd, BUFFER_SIZE),
      output_stream_(socket_fd, BUFFER_SIZE),
      pointer_helper_(),
      motion_event_start_time_(0),
      key_character_map_(),
      clipboard_listener_(this),
      max_synced_clipboard_length_(0),
      clipboard_changed_(),
      device_state_listener_(this),
      ui_settings_() {
  assert(socket_fd > 0);
  char channel_marker = 'C';
  write(socket_fd_, &channel_marker, sizeof(channel_marker));  // Control channel marker.
}

Controller::~Controller() {
  Stop();
  input_stream_.Close();
  output_stream_.Close();
  delete pointer_helper_;
  delete key_character_map_;
}

void Controller::Stop() {
  if (device_supports_multiple_states_) {
    DeviceStateManager::RemoveDeviceStateListener(&device_state_listener_);
  }
  ui_settings_.Reset();
  stopped = true;
}

void Controller::Initialize() {
  jni_ = Jvm::GetJni();
  pointer_helper_ = new PointerHelper(jni_);
  pointer_properties_ = pointer_helper_->NewPointerPropertiesArray(MotionEventMessage::MAX_POINTERS);
  pointer_coordinates_ = pointer_helper_->NewPointerCoordsArray(MotionEventMessage::MAX_POINTERS);

  for (int i = 0; i < MotionEventMessage::MAX_POINTERS; ++i) {
    JObject properties = pointer_helper_->NewPointerProperties();
    pointer_properties_.SetElement(i, properties);
    JObject coords = pointer_helper_->NewPointerCoords();
    pointer_coordinates_.SetElement(i, coords);
  }

  key_character_map_ = new KeyCharacterMap(jni_);

  pointer_properties_.MakeGlobal();
  pointer_coordinates_.MakeGlobal();
  if ((Agent::flags() & START_VIDEO_STREAM) != 0) {
    WakeUpDevice();
  }

  string states_text = DeviceStateManager::GetSupportedStates();
  Log::D("Controller::Initialize: states_text=%s", states_text.c_str());
  if (ContainsMultipleDeviceStates(states_text)) {
    device_supports_multiple_states_ = true;
    SupportedDeviceStatesNotification supported_device_states_notification(std::move(states_text));
    try {
      supported_device_states_notification.Serialize(output_stream_);
      output_stream_.Flush();
    } catch (EndOfFile& e) {
      // The socket has been closed - ignore.
    }
    DeviceStateManager::AddDeviceStateListener(&device_state_listener_);
    int32_t device_state = DeviceStateManager::GetDeviceState(jni_);
    Log::D("Controller::Initialize: device_state=%d", device_state);
    device_state_ = device_state;
  }

  DisplayManager::AddDisplayListener(jni_, this);

  Agent::InitializeSessionEnvironment();
}

void Controller::Run() {
  Log::D("Controller::Run");
  Initialize();

  try {
    for (;;) {
      auto socket_timeout = SOCKET_RECEIVE_TIMEOUT_MILLIS;
      if (!stopped) {
        if (max_synced_clipboard_length_ != 0) {
          SendClipboardChangedNotification();
        }

        if (device_supports_multiple_states_) {
          SendDeviceStateNotification();
        }

        SendPendingDisplayEvents();
      }

      SetReceiveTimeoutMillis(socket_timeout, socket_fd_);  // Set a receive timeout to avoid blocking for a long time.
      int32_t message_type;
      try {
        message_type = input_stream_.ReadInt32();
      } catch (IoTimeout& e) {
        continue;
      }
      SetReceiveTimeoutMillis(0, socket_fd_);  // Remove receive timeout for reading the rest of the message.
      unique_ptr<ControlMessage> message = ControlMessage::Deserialize(message_type, input_stream_);
      if (!stopped) {
        ProcessMessage(*message);
      }
    }
  } catch (EndOfFile& e) {
    Log::D("Controller::Run: End of command stream");
  } catch (IoException& e) {
    Log::Fatal(SOCKET_IO_ERROR, "%s", e.GetMessage().c_str());
  }
}

void Controller::ProcessMessage(const ControlMessage& message) {
  if (message.type() != MotionEventMessage::TYPE) { // Exclude
    Log::W("Controller::ProcessMessage %d", message.type());
  }
  switch (message.type()) {
    case MotionEventMessage::TYPE:
      ProcessMotionEvent((const MotionEventMessage&) message);
      break;

    case KeyEventMessage::TYPE:
      ProcessKeyboardEvent((const KeyEventMessage&) message);
      break;

    case TextInputMessage::TYPE:
      ProcessTextInput((const TextInputMessage&) message);
      break;

    case SetDeviceOrientationMessage::TYPE:
      ProcessSetDeviceOrientation((const SetDeviceOrientationMessage&) message);
      break;

    case SetMaxVideoResolutionMessage::TYPE:
      ProcessSetMaxVideoResolution((const SetMaxVideoResolutionMessage&) message);
      break;

    case StartVideoStreamMessage::TYPE:
      StartVideoStream((const StartVideoStreamMessage&) message);
      break;

    case StopVideoStreamMessage::TYPE:
      StopVideoStream((const StopVideoStreamMessage&) message);
      break;

    case StartClipboardSyncMessage::TYPE:
      StartClipboardSync((const StartClipboardSyncMessage&) message);
      break;

    case StopClipboardSyncMessage::TYPE:
      StopClipboardSync();
      break;

    case RequestDeviceStateMessage::TYPE:
      RequestDeviceState((const RequestDeviceStateMessage&) message);
      break;

    case DisplayConfigurationRequest::TYPE:
      SendDisplayConfigurations((const DisplayConfigurationRequest&) message);
      break;

    case UiSettingsRequest::TYPE:
      SendUiSettings((const UiSettingsRequest&) message);
      break;

    case SetDarkModeMessage::TYPE:
      SetDarkMode((const SetDarkModeMessage&) message);
      break;

    case SetFontSizeMessage::TYPE:
      SetFontSize((const SetFontSizeMessage&) message);
      break;

    default:
      Log::E("Unexpected message type %d", message.type());
      break;
  }
}

void Controller::ProcessMotionEvent(const MotionEventMessage& message) {
  int32_t action = message.action();
  Log::V("Controller::ProcessMotionEvent action:%d", action);
  int64_t now = UptimeMillis();
  MotionEvent event(jni_);
  event.display_id = message.display_id();
  event.action = action;
  event.button_state = message.button_state();
  event.event_time_millis = now;
  if (action != AMOTION_EVENT_ACTION_HOVER_MOVE && action != AMOTION_EVENT_ACTION_SCROLL) {
    if (action == AMOTION_EVENT_ACTION_DOWN) {
      motion_event_start_time_ = now;
    }
    if (motion_event_start_time_ == 0) {
      Log::E("Motion event started with action %d instead of expected %d", action, AMOTION_EVENT_ACTION_DOWN);
      motion_event_start_time_ = now;
    }
    event.down_time_millis = motion_event_start_time_;
    if (action == AMOTION_EVENT_ACTION_UP) {
      motion_event_start_time_ = 0;
    }
    Agent::RecordTouchEvent();
  }
  if (action == AMOTION_EVENT_ACTION_HOVER_MOVE || message.action_button() != 0 || message.button_state() != 0) {
    // AINPUT_SOURCE_MOUSE
    // - when action_button() is non-zero, as the Android framework has special handling for mouse in performButtonActionOnTouchDown(),
    //   which opens the context menu on right click.
    // - when message.button_state() is non-zero, otherwise drag operations initiated by touch down with AINPUT_SOURCE_MOUSE will not
    //   receiver mouse move events.
    event.source = AINPUT_SOURCE_MOUSE;
  } else {
    event.source = AINPUT_SOURCE_STYLUS | AINPUT_SOURCE_TOUCHSCREEN;
  }

  DisplayInfo display_info = Agent::GetDisplayInfo(message.display_id());
  if (!display_info.IsValid()) {
    return;
  }

  for (auto& pointer : message.pointers()) {
    JObject properties = pointer_properties_.GetElement(jni_, event.pointer_count);
    pointer_helper_->SetPointerId(properties, pointer.pointer_id);
    JObject coordinates = pointer_coordinates_.GetElement(jni_, event.pointer_count);
    // We must clear first so that axis information from previous runs is not reused.
    pointer_helper_->ClearPointerCoords(coordinates);
    Point point = AdjustedDisplayCoordinates(pointer.x, pointer.y, display_info);
    pointer_helper_->SetPointerCoords(coordinates, point.x, point.y);
    float pressure =
        (action == AMOTION_EVENT_ACTION_POINTER_UP && event.pointer_count == action >> AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT) ? 0 : 1;
    pointer_helper_->SetPointerPressure(coordinates, pressure);
    for (auto const& [axis, value] : pointer.axis_values) {
      pointer_helper_->SetAxisValue(coordinates, axis, value);
    }
    event.pointer_count++;
  }

  event.pointer_properties = pointer_properties_;
  event.pointer_coordinates = pointer_coordinates_;
  // InputManager doesn't allow ACTION_DOWN and ACTION_UP events with multiple pointers.
  // They have to be converted to a sequence of pointer-specific events.
  if (action == AMOTION_EVENT_ACTION_DOWN) {
    if (message.action_button()) {
      InjectMotionEvent(jni_, event, InputEventInjectionSync::NONE);
      event.action = AMOTION_EVENT_ACTION_BUTTON_PRESS;
      event.action_button = message.action_button();
    } else {
      for (int i = 1; event.pointer_count = i, i < message.pointers().size(); i++) {
        InjectMotionEvent(jni_, event, InputEventInjectionSync::NONE);
        event.action = AMOTION_EVENT_ACTION_POINTER_DOWN | (i << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT);
      }
    }
  }
  else if (action == AMOTION_EVENT_ACTION_UP) {
    if (message.action_button()) {
      event.action = AMOTION_EVENT_ACTION_BUTTON_RELEASE;
      event.action_button = message.action_button();
      InjectMotionEvent(jni_, event, InputEventInjectionSync::NONE);
      event.action = AMOTION_EVENT_ACTION_UP;
      event.action_button = 0;
    } else {
      for (int i = event.pointer_count; --i > 1;) {
        event.action = AMOTION_EVENT_ACTION_POINTER_UP | (i << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT);
        pointer_helper_->SetPointerPressure(pointer_coordinates_.GetElement(jni_, i), 0);
        InjectMotionEvent(jni_, event, InputEventInjectionSync::NONE);
        event.pointer_count = i;
      }
      event.action = AMOTION_EVENT_ACTION_UP;
    }
  }
  InjectMotionEvent(jni_, event, InputEventInjectionSync::NONE);

  if (event.action == AMOTION_EVENT_ACTION_UP) {
    // This event may have started an app. Update the app-level display orientation.
    Agent::SetVideoOrientation(message.display_id(), DisplayStreamer::CURRENT_VIDEO_ORIENTATION);

    if (!display_info.IsOn()) {
      ProcessKeyboardEvent(KeyEventMessage(KeyEventMessage::ACTION_DOWN_AND_UP, AKEYCODE_WAKEUP, 0));  // Wakeup the display.
    }
  }
}

void Controller::ProcessKeyboardEvent(Jni jni, const KeyEventMessage& message) {
  int64_t now = duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
  KeyEvent event(jni);
  event.down_time_millis = now;
  event.event_time_millis = now;
  int32_t action = message.action();
  event.action = action == KeyEventMessage::ACTION_DOWN_AND_UP ? AKEY_EVENT_ACTION_DOWN : action;
  event.code = message.keycode();
  event.meta_state = message.meta_state();
  event.source = KeyCharacterMap::VIRTUAL_KEYBOARD;
  InjectKeyEvent(jni, event, InputEventInjectionSync::NONE);
  if (action == KeyEventMessage::ACTION_DOWN_AND_UP) {
    event.action = AKEY_EVENT_ACTION_UP;
    InjectKeyEvent(jni, event, InputEventInjectionSync::NONE);
  }
}

void Controller::ProcessTextInput(const TextInputMessage& message) {
  const u16string& text = message.text();
  for (uint16_t c: text) {
    JObjectArray event_array = key_character_map_->GetEvents(&c, 1);
    if (event_array.IsNull()) {
      Log::E(jni_.GetAndClearException(), "Unable to map character '\\u%04X' to key events", c);
      continue;
    }
    auto len = event_array.GetLength();
    for (int i = 0; i < len; i++) {
      JObject key_event = event_array.GetElement(i);
      if (Log::IsEnabled(Log::Level::DEBUG)) {
        Log::D("key_event: %s", key_event.ToString().c_str());
      }
      InputManager::InjectInputEvent(jni_, key_event, InputEventInjectionSync::NONE);
    }
  }
}

void Controller::ProcessSetDeviceOrientation(const SetDeviceOrientationMessage& message) {
  int orientation = message.orientation();
  if (orientation < 0 || orientation >= 4) {
    Log::E("An attempt to set an invalid device orientation: %d", orientation);
    return;
  }
  Agent::SetVideoOrientation(PRIMARY_DISPLAY_ID, orientation);
}

void Controller::ProcessSetMaxVideoResolution(const SetMaxVideoResolutionMessage& message) {
  if (CheckVideoSize(message.max_video_size())) {
    Agent::SetMaxVideoResolution(message.display_id(), message.max_video_size());
  }
}

void Controller::StopVideoStream(const StopVideoStreamMessage& message) {
  Agent::StopVideoStream(message.display_id());
}

void Controller::StartVideoStream(const StartVideoStreamMessage& message) {
  if (CheckVideoSize(message.max_video_size())) {
    Agent::StartVideoStream(message.display_id(), message.max_video_size());
    WakeUpDevice();
  }
}

void Controller::WakeUpDevice() {
  ProcessKeyboardEvent(Jvm::GetJni(), KeyEventMessage(KeyEventMessage::ACTION_DOWN_AND_UP, AKEYCODE_WAKEUP, 0));
}

void Controller::StartClipboardSync(const StartClipboardSyncMessage& message) {
  ClipboardManager* clipboard_manager = ClipboardManager::GetInstance(jni_);
  if (message.text() != last_clipboard_text_) {
    last_clipboard_text_ = message.text();
    clipboard_manager->SetText(last_clipboard_text_);
  }
  bool was_stopped = max_synced_clipboard_length_ == 0;
  max_synced_clipboard_length_ = message.max_synced_length();
  if (was_stopped) {
    clipboard_manager->AddClipboardListener(&clipboard_listener_);
  }
}

void Controller::StopClipboardSync() {
  if (max_synced_clipboard_length_ != 0) {
    ClipboardManager* clipboard_manager = ClipboardManager::GetInstance(jni_);
    clipboard_manager->RemoveClipboardListener(&clipboard_listener_);
    max_synced_clipboard_length_ = 0;
    last_clipboard_text_.resize(0);
  }
}

void Controller::OnPrimaryClipChanged() {
  Log::D("Controller::OnPrimaryClipChanged");
  clipboard_changed_ = true;
}

void Controller::SendClipboardChangedNotification() {
  if (!clipboard_changed_.exchange(false)) {
    return;
  }
  Log::D("Controller::SendClipboardChangedNotification");
  ClipboardManager* clipboard_manager = ClipboardManager::GetInstance(jni_);
  string text = clipboard_manager->GetText();
  if (text.empty() || text == last_clipboard_text_) {
    return;
  }
  int max_length = max_synced_clipboard_length_;
  if (text.size() > max_length * UTF8_MAX_BYTES_PER_CHARACTER || Utf8CharacterCount(text) > max_length) {
    return;
  }
  last_clipboard_text_ = text;

  ClipboardChangedNotification message(std::move(text));
  message.Serialize(output_stream_);
  output_stream_.Flush();
}

void Controller::RequestDeviceState(const RequestDeviceStateMessage& message) {
  DeviceStateManager::RequestState(jni_, message.state(), 0);
}

void Controller::OnDeviceStateChanged(int32_t device_state) {
  Log::D("Controller::OnDeviceStateChanged(%d)", device_state);
  int32_t previous_state = device_state_.exchange(device_state);
  if (previous_state != device_state) {
    Agent::SetVideoOrientation(PRIMARY_DISPLAY_ID, DisplayStreamer::CURRENT_DISPLAY_ORIENTATION);
  }
}

void Controller::SendDeviceStateNotification() {
  int32_t device_state = device_state_;
  if (device_state != previous_device_state_) {
    Log::D("Sending DeviceStateNotification(%d)", device_state);
    DeviceStateNotification notification(device_state);
    notification.Serialize(output_stream_);
    output_stream_.Flush();
    previous_device_state_ = device_state;
  }
}

void Controller::SendDisplayConfigurations(const DisplayConfigurationRequest& request) {
  vector<int32_t> display_ids = DisplayManager::GetDisplayIds(jni_);
  vector<pair<int32_t, DisplayInfo>> displays;
  displays.reserve(display_ids.size());
  for (auto display_id : display_ids) {
    DisplayInfo display_info = DisplayManager::GetDisplayInfo(jni_, display_id);
    if (display_info.IsOn() && (display_info.flags & DisplayInfo::FLAG_PRIVATE) == 0) {
      Log::D("Returning display configuration: displayId=%d state=%d flags=0x%2x size=%dx%d orientation=%d",
             display_id, display_info.state, display_info.flags, display_info.logical_size.width, display_info.logical_size.height,
             display_info.rotation);
      displays.emplace_back(display_id, display_info);
    }
  }
  DisplayConfigurationResponse response(request.request_id(), std::move(displays));
  response.Serialize(output_stream_);
  output_stream_.Flush();
}

void Controller::SendUiSettings(const UiSettingsRequest& message) {
  UiSettingsResponse response(message.request_id());
  ui_settings_.Get(&response);
  response.Serialize(output_stream_);
  output_stream_.Flush();
}

void Controller::SetDarkMode(const SetDarkModeMessage& message) {
  ui_settings_.SetDarkMode(message.dark_mode());
}

void Controller::SetFontSize(const SetFontSizeMessage& message) {
  ui_settings_.SetFontSize(message.font_size());
}

void Controller::OnDisplayAdded(int32_t display_id) {
  unique_lock lock(display_events_mutex_);
  pending_display_events_.emplace_back(display_id, DisplayEvent::Type::ADDED);
}

void Controller::OnDisplayRemoved(int32_t display_id) {
  unique_lock lock(display_events_mutex_);
  pending_display_events_.emplace_back(display_id, DisplayEvent::Type::REMOVED);
}

void Controller::OnDisplayChanged(int32_t display_id) {
}

void Controller::SendPendingDisplayEvents() {
  vector<DisplayEvent> display_events;
  {
    unique_lock lock(display_events_mutex_);
    swap(display_events, pending_display_events_);
  }

  for (auto event : display_events) {
    if (event.type == DisplayEvent::Type::ADDED) {
      DisplayAddedNotification notification(event.display_id);
      notification.Serialize(output_stream_);
      output_stream_.Flush();
      Log::D("Sent DisplayAddedNotification(%d)", event.display_id);
    }
    else if (event.type == DisplayEvent::Type::REMOVED) {
      DisplayRemovedNotification notification(event.display_id);
      notification.Serialize(output_stream_);
      output_stream_.Flush();
      Log::D("Sent DisplayRemovedNotification(%d)", event.display_id);
    }
  }
}

Controller::ClipboardListener::~ClipboardListener() = default;

void Controller::ClipboardListener::OnPrimaryClipChanged() {
  controller_->OnPrimaryClipChanged();
}

Controller::DeviceStateListener::~DeviceStateListener() = default;

void Controller::DeviceStateListener::OnDeviceStateChanged(int32_t device_state) {
  controller_->OnDeviceStateChanged(device_state);
}

}  // namespace screensharing
