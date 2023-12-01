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
package com.android.tools.idea.adddevicedialog

import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.wizard.ui.SimpleStudioWizardLayout
import com.android.tools.idea.wizard.ui.StudioWizardDialogBuilder
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object AddDeviceDialog {
  internal fun build(): DialogWrapper {
    val model = AddDeviceWizardModel()
    val step = ConfigureDeviceStep(model)
    val dialog = StudioWizardDialogBuilder(step, "Add Device").build(SimpleStudioWizardLayout())
    val state = ModalityState.stateForComponent(step.component)

    AndroidCoroutineScope(dialog.disposable, AndroidDispatchers.uiThread(state)).launch {
      model.systemImages =
        withContext(AndroidDispatchers.workerThread) {
          SystemImage.getSystemImages().toImmutableList()
        }
    }

    return dialog
  }
}
