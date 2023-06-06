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

#include <android/native_window.h>
#include <media/NdkMediaCodec.h>

#include <atomic>
#include <mutex>
#include <thread>

#include "accessors/display_manager.h"
#include "accessors/window_manager.h"
#include "common.h"
#include "geom.h"
#include "jvm.h"
#include "video_packet_header.h"

namespace screensharing {

struct CodecInfo;

// Processes control socket commands.
class DisplayStreamer : public DisplayManager::DisplayListener {
public:
  enum OrientationReset {
    CURRENT_VIDEO_ORIENTATION = -1, CURRENT_DISPLAY_ORIENTATION = -2
  };

  // The display streamer takes ownership of the socket file descriptor and closes it when destroyed.
  DisplayStreamer(
      int display_id, std::string codec_name, Size max_video_resolution, int initial_video_orientation, int max_bitrate, int socket_fd);
  virtual ~DisplayStreamer();

  // Starts the streamer's thread.
  void Start();
  // Stops the streamer without closing the file descriptor. Waits for the streamer's thread.
  // to terminate.
  void Stop();
  // Shuts down the streamer.  Waits for the streamer's thread. Once shut down, the streamer cannot be restarted.
  void Shutdown();

  // Sets orientation of the device display. The orientation parameter may have a negative value
  // equal to one of the OrientationReset values.
  void SetVideoOrientation(int32_t orientation);
  // Sets the maximum resolution of the display video stream.
  void SetMaxVideoResolution(Size max_video_resolution);
  // Returns the cached version of DisplayInfo.
  DisplayInfo GetDisplayInfo();

  virtual void OnDisplayAdded(int32_t display_id);
  virtual void OnDisplayRemoved(int32_t display_id);
  virtual void OnDisplayChanged(int32_t display_id);

private:
  struct DisplayRotationWatcher : public WindowManager::RotationWatcher {
    DisplayRotationWatcher(DisplayStreamer* display_streamer);
    virtual ~DisplayRotationWatcher();

    void OnRotationChanged(int rotation) override;

    DisplayStreamer* display_streamer;
    std::atomic_int32_t display_rotation;
  };

  void Run();
  bool ProcessFramesUntilCodecStopped(AMediaCodec* codec, VideoPacketHeader* packet_header, const AMediaFormat* sync_frame_request);
  void StopCodec();
  void StopCodecUnlocked();  // REQUIRES(mutex_)
  bool IsCodecRunning();
  void StopCodecAndWaitForThreadToTerminate();

  std::thread thread_;
  DisplayRotationWatcher display_rotation_watcher_;
  int display_id_;
  std::string codec_name_;
  CodecInfo* codec_info_ = nullptr;
  int socket_fd_;
  int64_t presentation_timestamp_offset_ = 0;
  int32_t max_bit_rate_;
  int32_t consequent_deque_error_count_ = 0;
  std::atomic_bool streamer_stopped_ = true;

  std::mutex mutex_;
  DisplayInfo display_info_;  // GUARDED_BY(mutex_)
  Size max_video_resolution_;  // GUARDED_BY(mutex_)
  int32_t video_orientation_;  // GUARDED_BY(mutex_)
  AMediaCodec* running_codec_ = nullptr;  // GUARDED_BY(mutex_)

  DISALLOW_COPY_AND_ASSIGN(DisplayStreamer);
};

}  // namespace screensharing
