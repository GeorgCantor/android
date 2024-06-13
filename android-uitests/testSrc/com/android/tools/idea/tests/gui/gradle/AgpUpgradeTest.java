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
package com.android.tools.idea.tests.gui.gradle;

import static com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.idea.tests.gui.framework.GuiTestRule;
import com.android.tools.idea.tests.gui.framework.fixture.EditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.IdeFrameFixture;
import com.android.tools.idea.tests.gui.framework.fixture.gradle.AGPProjectUpdateNotificationCenterPanelFixture;
import com.android.tools.idea.tests.gui.framework.fixture.gradle.AGPUpgradeAssistantToolWindowFixture;
import com.intellij.testGuiFramework.framework.GuiTestRemoteRunner;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GuiTestRemoteRunner.class)
public class AgpUpgradeTest {

  @Rule
  public final GuiTestRule guiTest = new GuiTestRule().withTimeout(15, TimeUnit.MINUTES);
  private File projectDir;
  private IdeFrameFixture ideFrame;
  private String projectName = "SimpleApplication";
  private String oldAgpVersion = "7.4.1";

  @Before
  public void setUp() throws Exception {
    projectDir = guiTest.setUpProject(projectName, null, oldAgpVersion, null, null);
    guiTest.openProjectAndWaitForProjectSyncToFinish(projectDir);
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    ideFrame = guiTest.ideFrame();
    ideFrame.waitUntilProgressBarNotDisplayed();
  }

  //The test verifies the AGP upgrade from one stable version to the latest public stable version.
  /**
   * Verifies automatic update of gradle version
   * <p>
   * This is run to qualify releases. Please involve the test team in substantial
   * changes.
   * <p>
   * TT ID: 42f8d0a3-1a6a-4bca-917d-19abbf8dcb36
   * TT ID: 88097b16-22cf-4e6d-a063-a35826bb3ab4
   * TT ID: 621d8eb1-c487-4b13-a5f6-7f6bbad904ac
   * TT ID: b360a3c8-c31d-47de-9263-9159490596d5
   * <pre>
   *   Test Steps:
   *   1.Import Simple project
   *   2.Click Upgraded button in Plugin Update Recommended dialogue (check bottom right corner)
   *   3.Click Begin upgrade in Android Gradle Plugin Upgrade Assistant dialogue
   *   4.Check if the AGP upgrade assistant tool window is showing up.
   *   5.Click on agp version down (Verify1)
   *   6.Check if there are more than 3 agp versions.
   *   7.Check the all check boxes and click on Run selected steps button(Verify 2, 3, 5)
   *   8.Revert the changes using revert changes button after the sync is successful. (Verify 3,4)
   *   Verification:
   *   1. AGP version dropdown should contains all public agp versions
   *   2. All selected changes should be apply in project files.
   *   3. Sync is successful.
   *   4. All selected changes should be reverted in project files.
   *   5. Check if the buttons are disabled.
   * </pre>
   */
  @Test
  public void testUpgradeFunctionalityCheck() {
    //Looking for the notification panel showing "Project update recommended", and clicking the "upgraded" link.
    ideFrame.find(guiTest.robot())
      .requestFocusIfLost();
    AGPProjectUpdateNotificationCenterPanelFixture upgradeNotification = AGPProjectUpdateNotificationCenterPanelFixture.find(ideFrame);
    assertTrue(upgradeNotification.notificationIsShowing());
    upgradeNotification.clickStartUpgradeAssistant();
    guiTest.waitForAllBackgroundTasksToBeCompleted();

    //Checking the if the upgrade assistant tool window is active, finding all the AGP versions available,and also checking if the latest AGP version is present.
    AGPUpgradeAssistantToolWindowFixture upgradeAssistant = ideFrame.getUgradeAssistantToolWindow(false);
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    assertTrue(upgradeAssistant.isActiveAndOpen());
    List<String> agpVersionsList = upgradeAssistant.getAGPVersions();
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    assertTrue(agpVersionsList.size() > 1);
    assertEquals(ANDROID_GRADLE_PLUGIN_VERSION, agpVersionsList.get(0));

    //Selecting the latest AGP version, running the selected steps option and verifying if the sync is successful.
    upgradeAssistant.selectAGPVersion(ANDROID_GRADLE_PLUGIN_VERSION);
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    upgradeAssistant.clickRunSelectedStepsButton();
    ideFrame.waitForGradleSyncToFinish(null);
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    assertTrue(upgradeAssistant.getSyncStatus());
    assertTrue(upgradeAssistant.isRefreshButtonEnabled());
    assertFalse(upgradeAssistant.isShowUsagesEnabled());
    assertFalse(upgradeAssistant.isRunSelectedStepsButtonEnabled());
    upgradeAssistant.hide();
    assertTrue(ideFrame.getEditor()
                 .open("build.gradle")
                 .getCurrentFileContents()
                 .contains(ANDROID_GRADLE_PLUGIN_VERSION));
    guiTest.waitForAllBackgroundTasksToBeCompleted();

    //Reverting the changes made once the sync is successful.
    upgradeAssistant.activate();
    upgradeAssistant.clickRevertProjectFiles();
    ideFrame.waitForGradleSyncToFinish(null);
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    ideFrame.waitUntilProgressBarNotDisplayed();
    upgradeAssistant.activate();
    assertTrue(upgradeAssistant.isRefreshButtonEnabled());
    assertTrue(upgradeAssistant.isShowUsagesEnabled());
    assertTrue(upgradeAssistant.isRunSelectedStepsButtonEnabled());
    upgradeAssistant.hide();
    assertTrue(AGPProjectUpdateNotificationCenterPanelFixture.find(ideFrame).notificationIsShowing());
    assertTrue(ideFrame.getEditor()
                 .open("build.gradle")
                 .getCurrentFileContents()
                 .contains(oldAgpVersion));
  }

  /**
   * Verifies automatic update of gradle version
   * <p>
   * This is run to qualify releases. Please involve the test team in substantial
   * changes.
   * <p>
   * TT ID: b320c139-38f1-4384-bed3-2ea198b140c9
   *
   * <pre>
   *   Test Steps:
   *   1.Import Simple project
   *   2.Open module level build.gradle file
   *   3.Update the gradle version in build.gradle file. (Verify 1)
   *   4.Invoke gradle sync
   *   Verify:
   *   1.AGP classpath updated in build.gradle file
   *   2.Gradle sync successful.
   *
   * </pre>
   */
  @Test
  public void testAgpUpgradeUsingGradleBuildFile() {
    ideFrame.clearNotificationsPresentOnIdeFrame();
    guiTest.waitForAllBackgroundTasksToBeCompleted();

    EditorFixture editor = ideFrame.getEditor();
    String latestGradleVersion = "classpath 'com.android.tools.build:gradle:"+ANDROID_GRADLE_PLUGIN_VERSION+"'";
    editor.open("build.gradle")
      .moveBetween("classpath 'com.android.tools.", "build:gradle")
      .moveBetween("classpath 'com.android.tools.", "build:gradle")
      .invokeAction(EditorFixture.EditorAction.DELETE_LINE)
      .typeText(latestGradleVersion)
      .invokeAction(EditorFixture.EditorAction.SAVE)
      .awaitNotification(
        "Gradle files have changed since last project sync. A project sync may be necessary for the IDE to work properly.");
    ideFrame.requestProjectSyncAndWaitForSyncToFinish();
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    assertThat(editor.getCurrentFileContents()).contains(ANDROID_GRADLE_PLUGIN_VERSION);
  }
}
