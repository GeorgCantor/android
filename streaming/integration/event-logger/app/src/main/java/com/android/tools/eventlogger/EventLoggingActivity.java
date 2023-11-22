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
package com.android.tools.eventlogger;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.widget.FrameLayout;

public class EventLoggingActivity extends Activity {
  private static final String TAG = "EventLogger";

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().requestFeature(Window.FEATURE_ACTION_BAR);
    getActionBar().hide();
    FrameLayout frameLayout = new FrameLayout(this);
    setContentView(frameLayout);
  }

  @Override
  public void onResume() {
    super.onResume();
    Log.i(TAG, "RESUMED");
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    Log.d(TAG, event.toString());
    Log.i(TAG, "KEY DOWN: " + keyCode);
    return true;
  }

  @Override
  public boolean onKeyLongPress(int keyCode, KeyEvent event) {
    Log.d(TAG, event.toString());
    Log.i(TAG, "KEY LONG PRESS: " + keyCode);
    return true;
  }

  @Override
  public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
    Log.d(TAG, event.toString());
    Log.i(TAG, "KEY MULTIPLE: " + keyCode + " (count=" + count + ")");
    return true;
  }

  @Override
  public boolean onKeyShortcut(int keyCode, KeyEvent event) {
    Log.d(TAG, event.toString());
    Log.i(TAG, "KEY SHORTCUT: " + keyCode);
    return true;
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    Log.d(TAG, event.toString());
    Log.i(TAG, "KEY UP: " + keyCode);
    return true;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    Log.d(TAG, event.toString());
    Log.i(TAG, "TOUCH EVENT: " + MotionEvent.actionToString(event.getAction()) + pointersToString(event));
    return true;
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent event) {
    Log.d(TAG, event.toString());
    StringBuilder buf = new StringBuilder("MOTION EVENT: ");
    buf.append(MotionEvent.actionToString(event.getAction()));
    switch (event.getAction()) {
      case MotionEvent.ACTION_SCROLL:
        processScrollEvent(event, buf);
        break;
      default:
        buf.append(pointersToString(event));
        break;
    }
    Log.i(TAG, buf.toString());
    return true;
  }

  private void processScrollEvent(MotionEvent event, StringBuilder buf) {
    for (int i = 0; i < event.getPointerCount(); ++i) {
      buf
        // Coordinates of the pointer at the time of the scrolling.
        .append(" (").append(event.getX(i)).append(",").append(event.getY(i)).append(")")
        // Vertical scroll component.
        .append(" v=").append(event.getAxisValue(MotionEvent.AXIS_VSCROLL, i))
        // Horizontal scroll component.
        .append(" h=").append(event.getAxisValue(MotionEvent.AXIS_HSCROLL, i));
    }
  }

  private String pointersToString(MotionEvent event) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < event.getPointerCount(); ++i) {
      result.append(" (").append(event.getX(i)).append(",").append(event.getY(i)).append(")");
    }
    return result.toString();
  }
}