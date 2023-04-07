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

#include <inttypes.h>

#include <map>
#include <memory>
#include <vector>
#include <android/input.h>

#include "base128_input_stream.h"
#include "base128_output_stream.h"
#include "common.h"

namespace screensharing {

// Common base class of all control messages.
class ControlMessage {
public:
  virtual ~ControlMessage() {}

  int32_t type() const { return type_; }

  virtual void Serialize(Base128OutputStream& stream) const;
  static std::unique_ptr<ControlMessage> Deserialize(Base128InputStream& stream);
  static std::unique_ptr<ControlMessage> Deserialize(int32_t type, Base128InputStream& stream);

protected:
  ControlMessage(int32_t type)
      : type_(type) {
  }

  int32_t type_;
};

// Represents an Android MotionEvent.
class MotionEventMessage : ControlMessage {
public:
  struct Pointer {
    Pointer(int32_t x, int32_t y, int32_t pointer_id, std::map<int32_t, float> axis_values)
        : x(x),
          y(y),
          pointer_id(pointer_id),
          axis_values(axis_values) {
    }
    Pointer() = default;

    // The horizontal coordinate of a touch corresponding to the display in its original orientation.
    int32_t x;
    // The vertical coordinate of a touch corresponding to the display in its original orientation.
    int32_t y;
    // The ID of the touch that stays the same when the touch point moves.
    int32_t pointer_id;
    // Values for the various axes of the pointer (e.g. scroll wheel, joystick, etc).
    std::map<int32_t, float> axis_values;
  };

  // Pointers are expected to be ordered according to their ids.
  // The action translates directly to android.view.MotionEvent.action.
  MotionEventMessage(std::vector<Pointer>&& pointers, int32_t action, int32_t display_id)
      : ControlMessage(TYPE),
        pointers_(pointers),
        action_(action),
        display_id_(display_id) {
  }
  virtual ~MotionEventMessage() {};

  // The touches, one for each finger. The pointers are ordered according to their ids.
  const std::vector<Pointer>& pointers() const { return pointers_; }

  // The action. See android.view.MotionEvent.action.
  int32_t action() const { return action_; }

  // The display device where the mouse event occurred. Zero indicates the main display.
  int32_t display_id() const { return display_id_; }

  static constexpr int TYPE = 1;

  static constexpr int MAX_POINTERS = 2;

private:
  friend class ControlMessage;

  static MotionEventMessage* Deserialize(Base128InputStream& stream);

  const std::vector<Pointer> pointers_;
  const int32_t action_;
  const int32_t display_id_;

  DISALLOW_COPY_AND_ASSIGN(MotionEventMessage);
};

// Represents a key being pressed or released on a keyboard.
class KeyEventMessage : ControlMessage {
public:
  KeyEventMessage(int32_t action, int32_t keycode, uint32_t meta_state)
      : ControlMessage(TYPE),
        action_(action),
        keycode_(keycode),
        meta_state_(meta_state) {
  }
  virtual ~KeyEventMessage() {};

  // AKEY_EVENT_ACTION_DOWN, AKEY_EVENT_ACTION_UP or ACTION_DOWN_AND_UP.
  int32_t action() const { return action_; }

  // The code of the pressed or released key. */
  int32_t keycode() const { return keycode_; }

  int32_t meta_state() const { return meta_state_; }

  static constexpr int TYPE = 2;

  static constexpr int ACTION_DOWN_AND_UP = 8;

private:
  friend class ControlMessage;

  static KeyEventMessage* Deserialize(Base128InputStream& stream);

  int32_t action_;
  int32_t keycode_;
  uint32_t meta_state_;

  DISALLOW_COPY_AND_ASSIGN(KeyEventMessage);
};

// Represents one or more characters typed on a keyboard.
class TextInputMessage : ControlMessage {
public:
  TextInputMessage(const std::u16string& text)
      : ControlMessage(TYPE),
        text_(text) {
  }
  virtual ~TextInputMessage() {};

  const std::u16string& text() const { return text_; }

  static constexpr int TYPE = 3;

private:
  friend class ControlMessage;

  static TextInputMessage* Deserialize(Base128InputStream& stream);

  std::u16string text_;

  DISALLOW_COPY_AND_ASSIGN(TextInputMessage);
};

// Represents one or more characters typed on a keyboard.
class SetDeviceOrientationMessage : ControlMessage {
public:
  SetDeviceOrientationMessage(int32_t orientation)
      : ControlMessage(TYPE),
        orientation_(orientation) {
  }
  virtual ~SetDeviceOrientationMessage() {};

  int32_t orientation() const { return orientation_; }

  static constexpr int TYPE = 4;

private:
  friend class ControlMessage;

  static SetDeviceOrientationMessage* Deserialize(Base128InputStream& stream);

  int32_t orientation_;

  DISALLOW_COPY_AND_ASSIGN(SetDeviceOrientationMessage);
};

// Sets maximum display streaming resolution.
class SetMaxVideoResolutionMessage : ControlMessage {
public:
  SetMaxVideoResolutionMessage(int32_t width, int32_t height)
      : ControlMessage(TYPE),
        width_(width),
        height_(height) {
  }
  virtual ~SetMaxVideoResolutionMessage() {};

  int32_t width() const { return width_; }
  int32_t height() const { return height_; }

  static constexpr int TYPE = 5;

private:
  friend class ControlMessage;

  static SetMaxVideoResolutionMessage* Deserialize(Base128InputStream& stream);

  int32_t width_;
  int32_t height_;

  DISALLOW_COPY_AND_ASSIGN(SetMaxVideoResolutionMessage);
};

// Starts video stream if it was stopped, otherwise has no effect.
class StartVideoStreamMessage : ControlMessage {
public:
  StartVideoStreamMessage()
      : ControlMessage(TYPE) {
  }
  virtual ~StartVideoStreamMessage() {};

  static constexpr int TYPE = 6;

private:
  friend class ControlMessage;

  static StartVideoStreamMessage* Deserialize(Base128InputStream& stream);

  DISALLOW_COPY_AND_ASSIGN(StartVideoStreamMessage);
};

// Stops video stream if it was started, otherwise has no effect.
class StopVideoStreamMessage : ControlMessage {
public:
  StopVideoStreamMessage()
      : ControlMessage(TYPE) {
  }
  virtual ~StopVideoStreamMessage() {};

  static constexpr int TYPE = 7;

private:
  friend class ControlMessage;

  static StopVideoStreamMessage* Deserialize(Base128InputStream& stream);

  DISALLOW_COPY_AND_ASSIGN(StopVideoStreamMessage);
};

// Sets contents of the clipboard and requests notifications of clipboard changes.
class StartClipboardSyncMessage : ControlMessage {
public:
  StartClipboardSyncMessage(int max_synced_length, std::string&& text)
      : ControlMessage(TYPE),
        max_synced_length_(max_synced_length),
        text_(text) {
  }
  virtual ~StartClipboardSyncMessage() {};

  const std::string& text() const { return text_; }
  int max_synced_length() const { return max_synced_length_; }

  static constexpr int TYPE = 8;

private:
  friend class ControlMessage;

  static StartClipboardSyncMessage* Deserialize(Base128InputStream& stream);

  int max_synced_length_;
  std::string text_;

  DISALLOW_COPY_AND_ASSIGN(StartClipboardSyncMessage);
};

// Stops notifications of clipboard changes.
class StopClipboardSyncMessage : ControlMessage {
public:
  StopClipboardSyncMessage()
      : ControlMessage(TYPE) {
  }
  virtual ~StopClipboardSyncMessage() {};

  static constexpr int TYPE = 9;

private:
  friend class ControlMessage;

  static StopClipboardSyncMessage* Deserialize(Base128InputStream& stream);

  DISALLOW_COPY_AND_ASSIGN(StopClipboardSyncMessage);
};

// Requests a device state (folding pose) change. A DeviceStateNotification message will be sent
// when and if the device state actually changes. If state is equal to PHYSICAL_STATE, the device
// will return to its actual physical state.
class RequestDeviceStateMessage : ControlMessage {
public:
  RequestDeviceStateMessage(int state)
      : ControlMessage(TYPE),
        state_(state) {
  }
  virtual ~RequestDeviceStateMessage() = default;

  int state() const { return state_; }

  static constexpr int PHYSICAL_STATE = -1;

  static constexpr int TYPE = 10;

private:
  friend class ControlMessage;

  static RequestDeviceStateMessage* Deserialize(Base128InputStream& stream);

  int state_;

  DISALLOW_COPY_AND_ASSIGN(RequestDeviceStateMessage);
};

// Notification of clipboard content change.
class ClipboardChangedNotification : ControlMessage {
public:
  ClipboardChangedNotification(const std::string& text)
      : ControlMessage(TYPE),
        text_(text) {
  }
  ClipboardChangedNotification(std::string&& text)
      : ControlMessage(TYPE),
        text_(text) {
  }
  virtual ~ClipboardChangedNotification() {};

  const std::string& text() const { return text_; }

  virtual void Serialize(Base128OutputStream& stream) const;

  static constexpr int TYPE = 11;

private:
  friend class ControlMessage;

  std::string text_;

  DISALLOW_COPY_AND_ASSIGN(ClipboardChangedNotification);
};

// Notification of supported device states.
class SupportedDeviceStatesNotification : ControlMessage {
public:
  SupportedDeviceStatesNotification(const std::string& text)
      : ControlMessage(TYPE),
        text_(text) {
  }
  SupportedDeviceStatesNotification(std::string&& text)
      : ControlMessage(TYPE),
        text_(text) {
  }
  virtual ~SupportedDeviceStatesNotification() = default;

  const std::string& text() const { return text_; }

  virtual void Serialize(Base128OutputStream& stream) const;

  static constexpr int TYPE = 12;

private:
  friend class ControlMessage;

  std::string text_;

  DISALLOW_COPY_AND_ASSIGN(SupportedDeviceStatesNotification);
};

// Notification of a device state change. One such notification is always sent when the screen
// sharing agent starts on a foldable device,
class DeviceStateNotification : ControlMessage {
public:
  DeviceStateNotification(int32_t device_state)
      : ControlMessage(TYPE),
        device_state_(device_state) {
  }
  virtual ~DeviceStateNotification() = default;

  int32_t device_state() const { return device_state_; }

  virtual void Serialize(Base128OutputStream& stream) const;

  static constexpr int TYPE = 13;

private:
  friend class ControlMessage;

  int32_t device_state_;

  DISALLOW_COPY_AND_ASSIGN(DeviceStateNotification);
};

}  // namespace screensharing
