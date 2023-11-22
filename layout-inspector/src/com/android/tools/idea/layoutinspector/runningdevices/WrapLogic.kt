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
package com.android.tools.idea.layoutinspector.runningdevices

import java.awt.Container
import javax.swing.JComponent

/** Class used to wrap and unwrap [component] inside another view. */
class WrapLogic(
  private val component: JComponent,
  private val container: Container,
) {
  private var newContainer: JComponent? = null

  fun wrapComponent(wrap: (JComponent) -> JComponent) {
    check(newContainer == null) { "Can't wrap, component is already wrapped" }

    container.remove(component)
    newContainer = wrap(component)
    container.add(newContainer)
  }

  fun unwrapComponent() {
    val newContainer = checkNotNull(newContainer) { "Can't unwrap, component is not wrapped" }

    newContainer.remove(component)
    container.remove(newContainer)
    this.newContainer = null

    container.add(component)

    container.invalidate()
    container.repaint()
  }
}
