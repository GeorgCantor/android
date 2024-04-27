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
package com.android.tools.idea.tests.gui.npw;

import static com.google.common.truth.Truth.assertThat;

import com.android.tools.adtui.device.FormFactor;
import com.android.tools.idea.tests.gui.framework.GuiTestRule;
import com.intellij.testGuiFramework.framework.GuiTestRemoteRunner;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test following scenarios for NPW => Phone and Tablet tab
 * 1. Expected templates are displayed
 * 2. Correct default template is present
 * 3. For all expected templates (Except C++ and Navigation templates)
 * 3.a. Verify Gradle sync is successful
 * 3.b. Build -> Make Project is successful
 */


@RunWith(GuiTestRemoteRunner.class)
public class VerifyNpwPhoneAndTabletViewsTemplatesTest {
  @Rule public final GuiTestRule guiTest = new GuiTestRule().withTimeout(15, TimeUnit.MINUTES);

  private List<String> expectedTemplates = List.of("Basic Views Activity",
                                                   "Empty Views Activity",
                                                   "Responsive Views Activity");
  FormFactor selectMobileTab = FormFactor.MOBILE;

  @Test
  public void  testBasicViewsActivityTemplate() {
    boolean buildProjectStatus = NewProjectTestUtil.createNewProject(guiTest, selectMobileTab, expectedTemplates.get(0));
    assertThat(buildProjectStatus).isTrue();
    validateThemeFile("app/src/main/res/values/themes.xml");
    validateViewBindingInGradleFile();
    guiTest.ideFrame().getProjectView().assertFilesExist(
      "gradle/libs.versions.toml"
    );
  }

  @Test
  public void  testEmptyViewsActivityTemplate() {
    boolean buildProjectStatus = NewProjectTestUtil.createNewProject(guiTest, selectMobileTab, expectedTemplates.get(1));
    assertThat(buildProjectStatus).isTrue();
    guiTest.ideFrame().getProjectView().assertFilesExist(
      "gradle/libs.versions.toml"
    );
  }

  @Test
  public void  testResponsiveViewsActivityTemplate() {
    boolean buildProjectStatus = NewProjectTestUtil.createNewProject(guiTest, selectMobileTab, expectedTemplates.get(2));
    assertThat(buildProjectStatus).isTrue();
    validateViewBindingInGradleFile();
    guiTest.ideFrame().getProjectView().assertFilesExist(
      "gradle/libs.versions.toml"
    );
  }

  private void validateViewBindingInGradleFile() {
    String buildGradleContents = guiTest.getProjectFileText("app/build.gradle.kts");
    assertThat((buildGradleContents).contains("viewBinding = true")).isTrue();
  }

  private void validateThemeFile(String fileRelPath) {
    String themeFileContents = guiTest.getProjectFileText(fileRelPath);
      assertThat(themeFileContents.contains("Theme.Material3")).isTrue();
  }
}
