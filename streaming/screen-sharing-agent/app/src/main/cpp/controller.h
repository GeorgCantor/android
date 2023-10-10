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

#include <atomic>
#include <chrono>
#include <map>
#include <mutex>
#include <vector>

#include "accessors/clipboard_manager.h"
#include "accessors/device_state_manager.h"
#include "accessors/display_manager.h"
#include "accessors/key_character_map.h"
#include "accessors/pointer_helper.h"
#include "base128_input_stream.h"
#include "common.h"
#include "control_messages.h"
#include "geom.h"
#include "jvm.h"

namespace screensharing {

// Processes control socket commands.
class Controller : private DisplayManager::DisplayListener {
public:
  Controller(int socket_fd);
  virtual ~Controller();

  void Run();
  // Stops the controller asynchronously. The controller can't be restarted one stopped.
  // May be called on any thread.
  void Stop();

private:
  struct ClipboardListener : public ClipboardManager::ClipboardListener {
    ClipboardListener(Controller* controller)
        : controller_(controller) {
    }
    virtual ~ClipboardListener();

    virtual void OnPrimaryClipChanged() override;

    Controller* controller_;
  };

  struct DeviceStateListener : public DeviceStateManager::DeviceStateListener {
    DeviceStateListener(Controller* controller)
        : controller_(controller) {
    }
    virtual ~DeviceStateListener();

    virtual void OnDeviceStateChanged(int32_t device_state) override;

    Controller* controller_;
  };

  struct DisplayEvent {
    enum Type { ADDED, REMOVED };

    DisplayEvent(int32_t displayId, Type type)
        : display_id(displayId),
          type(type) {
    }

    int32_t display_id;
    Type type;
  };

  void Initialize();
  void ProcessMessage(const ControlMessage& message);
  void ProcessMotionEvent(const MotionEventMessage& message);
  void ProcessKeyboardEvent(const KeyEventMessage& message) {
    ProcessKeyboardEvent(jni_, message);
  }
  static void ProcessKeyboardEvent(Jni jni, const KeyEventMessage& message);
  void ProcessTextInput(const TextInputMessage& message);
  static void ProcessSetDeviceOrientation(const SetDeviceOrientationMessage& message);
  static void ProcessSetMaxVideoResolution(const SetMaxVideoResolutionMessage& message);
  static void StartVideoStream(const StartVideoStreamMessage& message);
  static void StopVideoStream(const StopVideoStreamMessage& message);
  static void WakeUpDevice();

  void StartClipboardSync(const StartClipboardSyncMessage& message);
  void StopClipboardSync();
  void OnPrimaryClipChanged();
  void SendClipboardChangedNotification();

  void RequestDeviceState(const RequestDeviceStateMessage& message);
  void OnDeviceStateChanged(int32_t device_state);
  void SendDeviceStateNotification();

  void SendDisplayConfigurations(const DisplayConfigurationRequest& request);
  virtual void OnDisplayAdded(int32_t display_id);
  virtual void OnDisplayRemoved(int32_t display_id);
  virtual void OnDisplayChanged(int32_t display_id);
  void SendPendingDisplayEvents();

  // TODO: Remove the following 4 methods when b/303684492 is fixed.
  void StartDisplayPolling();
  void StopDisplayPolling();
  void PollDisplays();
  std::map<int32_t, DisplayInfo> GetDisplays();

  Jni jni_ = nullptr;
  int socket_fd_;  // Owned.
  Base128InputStream input_stream_;
  Base128OutputStream output_stream_;
  volatile bool stopped = false;
  PointerHelper* pointer_helper_;  // Owned.
  JObjectArray pointer_properties_;  // MotionEvent.PointerProperties[]
  JObjectArray pointer_coordinates_;  // MotionEvent.PointerCoords[]
  int64_t motion_event_start_time_;
  KeyCharacterMap* key_character_map_;  // Owned.

  ClipboardListener clipboard_listener_;
  int max_synced_clipboard_length_;
  std::string last_clipboard_text_;
  std::atomic_bool clipboard_changed_;

  DeviceStateListener device_state_listener_;
  bool device_supports_multiple_states_ = false;
  std::atomic_int32_t device_state_ = -1;
  int32_t previous_device_state_ = -1;

  std::mutex display_events_mutex_;
  std::vector<DisplayEvent> pending_display_events_;  // GUARDED_BY(display_events_mutex_)

  // TODO: Remove the following 2 fields when b/303684492 is fixed.
  std::map<int32_t, DisplayInfo> current_displays_;
  std::chrono::steady_clock::time_point poll_displays_until_;

  DISALLOW_COPY_AND_ASSIGN(Controller);
};

}  // namespace screensharing
