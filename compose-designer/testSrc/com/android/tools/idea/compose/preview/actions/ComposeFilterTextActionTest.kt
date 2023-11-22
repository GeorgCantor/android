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
package com.android.tools.idea.compose.preview.actions

import com.android.testutils.MockitoKt.mock
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.Presentation
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import javax.swing.JTextField

class ComposeFilterTextActionTest {

  @Rule @JvmField val rule = AndroidProjectRule.inMemory()

  @Test
  fun testTriggerFilterWhenTextChanged() {
    val filter = mock<ComposeViewFilter>()

    val action = ComposeFilterTextAction(filter)
    val textFiled = action.createCustomComponent(Presentation(), ActionPlaces.UNKNOWN) as JTextField

    textFiled.text = "Hello"
    verify(filter, times(1)).filter("Hello")

    textFiled.text = "World"
    verify(filter, times(1)).filter("World")
  }
}
