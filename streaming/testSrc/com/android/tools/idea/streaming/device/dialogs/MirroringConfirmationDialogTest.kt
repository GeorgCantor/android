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
package com.android.tools.idea.streaming.device.dialogs

import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import org.junit.Rule
import org.junit.Test
import javax.swing.JButton
import javax.swing.JEditorPane

/**
 * Tests for [MirroringConfirmationDialog].
 */
@RunsInEdt
class MirroringConfirmationDialogTest {
  @get:Rule
  val ruleChain = RuleChain(ProjectRule(), EdtRule(), HeadlessDialogRule())

  @Test
  fun testAccept() {
    val dialogPanel = MirroringConfirmationDialog("Privacy Notice")
    val dialogWrapper = dialogPanel.createWrapper()
    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      assertThat(dlg.title).isEqualTo("Privacy Notice")
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)
      val message = ui.getComponent<JEditorPane>()
      assertThat(message.text).contains("<b>Warning:</b> Mirroring might result in information disclosure")

      val acceptButton = rootPane.defaultButton
      assertThat(acceptButton.text).isEqualTo("Acknowledge")
      ui.clickOn(acceptButton)
    }

    assertThat(dialogWrapper.exitCode).isEqualTo(MirroringConfirmationDialog.ACCEPT_EXIT_CODE)
  }

  @Test
  fun testReject() {
    val dialogPanel = MirroringConfirmationDialog("About to Start Mirroring")
    val dialogWrapper = dialogPanel.createWrapper()
    createModalDialogAndInteractWithIt(dialogWrapper::show) { dlg ->
      assertThat(dlg.title).isEqualTo("About to Start Mirroring")
      val rootPane = dlg.rootPane
      val ui = FakeUi(rootPane)
      val rejectButton = ui.getComponent<JButton> { !it.isDefaultButton }
      assertThat(rejectButton.text).isEqualTo("Disable Mirroring")
      ui.clickOn(rejectButton)
    }

    assertThat(dialogWrapper.exitCode).isEqualTo(MirroringConfirmationDialog.REJECT_EXIT_CODE)
  }
}
