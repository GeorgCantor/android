/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.tests.gui.naveditor;

import com.android.tools.idea.tests.gui.framework.GuiTestRule;
import com.android.tools.idea.tests.gui.framework.RunIn;
import com.android.tools.idea.tests.gui.framework.TestGroup;
import com.android.tools.idea.tests.gui.framework.fixture.CreateResourceFileDialogFixture;
import com.android.tools.idea.tests.gui.framework.fixture.FailedToAddDependencyDialogFixture;
import com.intellij.testGuiFramework.framework.GuiTestRemoteRunner;
import java.util.concurrent.TimeUnit;
import org.fest.swing.core.MouseButton;
import org.fest.swing.timing.Wait;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.google.common.truth.Truth.assertThat;


@RunWith(GuiTestRemoteRunner.class)
public class ManuallyAddNavLibTest {

  @Rule public final GuiTestRule guiTest = new GuiTestRule().withTimeout(10, TimeUnit.MINUTES);


  /**
   * Verifies Creating Navigation Graph
   * <p>
   * This is run to qualify releases. Please involve the test team in substantial changes.
   * <p>
   * TT ID: 64a13341-7762-4a87-8f8d-ec21cad01f63
   * <p>
   *   <pre>
   *   Test Steps:
   *   1. Create a project with any activity
   *   2. Right Click on app > New > Android Resource File
   *   3. Give resource a name "nav_g" and select resource type as "Navigation" > OK (Verify 1)
   *   4. Click Cancel to add navigation lib from AS (Verify 2)
   *   5. Click OK (on 'Failed to Add Dependency' dialog)
   *   6. Check build.gradle (Module: app) (Verify 3)
   *   7. Add latest navigation lib dependency in build.gradle (Module: app) and sync (Verify 3)
   *    (Ex: implementation androidx.navigation:navigation-fragment-ktx:2.5.3')
   *   8. Execute Gradle sync
   *   9. Build > Make Project (Verify 4)
   *
   *   Verify:
   *   1. Pop up should show up to add navigation library dependency
   *   2. Failed to Add Dependency window should pop up
   *   3. Gradle dependency should NOT be added to app .gradle file
   *      implementation 'android.arch.navigation:navigation-fragment:x.x.x'
   *   4. Project should build successfully
   *   </pre>
   *
   */
  @Test
  @RunIn(TestGroup.FAST_BAZEL)
  public void manuallyAddNavLibrary() throws Exception {
    guiTest.importSimpleApplication()
      .getProjectView()
      .selectAndroidPane()
      .clickPath(MouseButton.RIGHT_BUTTON, "app")
      .openFromContextualMenu(CreateResourceFileDialogFixture::find, "New", "Android Resource File")
      .setFilename("nav_g")
      .setType("Navigation")
      .clickOkAndWaitForDependencyDialog()
      .clickCancel();

    FailedToAddDependencyDialogFixture failedToAddDependency = FailedToAddDependencyDialogFixture.find(guiTest.ideFrame());
    failedToAddDependency.clickOk();

    String contents = guiTest.ideFrame().getEditor()
      .open("app/build.gradle")
      .getCurrentFileContents();

    assertThat(contents.contains("androidx.navigation:navigation-fragment")).isFalse();

    guiTest.ideFrame().getEditor()
      .open("app/build.gradle")
      .moveBetween("dependencies {", "")
      .enterText("\n    implementation 'androidx.navigation:navigation-fragment-ktx:2.5.3'\n")
      .enterText("\n    implementation 'androidx.navigation:navigation-ui-ktx:2.5.3'\n");

    guiTest.ideFrame().requestProjectSyncAndWaitForSyncToFinish(Wait.seconds(120));

    assertThat(guiTest.ideFrame().invokeProjectMake(Wait.seconds(180)).isBuildSuccessful()).isTrue();
  }
}
