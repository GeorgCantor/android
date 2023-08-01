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
package com.android.tools.idea.streaming;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP8;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;

import com.android.testutils.JarTestSuiteRunner;
import com.android.tools.adtui.swing.IconLoaderRule;
import com.android.tools.idea.adb.AdbService;
import com.android.tools.tests.IdeaTestSuiteBase;
import org.junit.runner.RunWith;

@RunWith(JarTestSuiteRunner.class)
public class StreamingTestSuite extends IdeaTestSuiteBase {

  static {
    // Since icons are cached and not reloaded for each test, loading of realistic icons
    // has to be enabled before the first test in the suite that may trigger icon loading.
    IconLoaderRule.enableIconLoading();

    // Disable production AdbService to prevent it from interfering with AndroidDebugBridge (b/281701515).
    AdbService.disabled = true;

    // Preload FFmpeg codec native libraries before the test to avoid a race condition when unpacking them.
    avcodec_find_encoder(AV_CODEC_ID_VP8).close();
  }
}
