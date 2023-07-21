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
package com.android.tools.idea.editors

import com.android.flags.junit.FlagRule
import com.android.sdklib.AndroidVersion
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.testing.AndroidProjectRule.Companion.withSdk
import com.android.tools.idea.wizard.model.ModelWizardDialog
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@RunWith(JUnit4::class)
class AttachAndroidSdkSourcesNotificationProviderTest {
  // TODO(b/291755082): Update to 34 once 34 sources are published
  @get:Rule
  val myAndroidProjectRule = withSdk(AndroidVersion(33))

  @get:Rule
  val myMockitoRule = MockitoJUnit.rule()

  @Mock
  lateinit var myFileEditor: FileEditor

  @Mock
  lateinit var myModelWizardDialog: ModelWizardDialog

  private lateinit var myProvider: TestAttachAndroidSdkSourcesNotificationProvider

  @Before
  fun setup() {
    myProvider = TestAttachAndroidSdkSourcesNotificationProvider()
  }

  @Test
  fun createNotificationPanel_fileIsNotJavaClass_returnsNull() {
    val javaFile = myAndroidProjectRule.fixture.createFile("somefile.java", "file contents")

    assertThat(javaFile.fileType).isEqualTo(JavaFileType.INSTANCE)
    val panel = invokeCreateNotificationPanel(javaFile)
    assertThat(panel).isNull()
  }

  @Test
  fun createNotificationPanel_javaClassNotInAndroidSdk_returnsNull() {
    val javaClassFile = myAndroidProjectRule.fixture.createFile("someclass.class", "")

    assertThat(javaClassFile.fileType).isEqualTo(JavaClassFileType.INSTANCE)
    val panel = invokeCreateNotificationPanel(javaClassFile)
    assertThat(panel).isNull()
  }

  @Test
  fun createNotificationPanel_javaClassInAndroidSdkAndSourcesAvailable_nullReturned() {
    val virtualFile = runReadAction {
      JavaPsiFacade.getInstance(myAndroidProjectRule.project)
        .findClass("android.view.View", GlobalSearchScope.allScope(myAndroidProjectRule.project))!!
        .containingFile
        .virtualFile
    }

    val panel = invokeCreateNotificationPanel(virtualFile)
    assertThat(panel).isNull()
  }

  @Test
  fun createNotificationPanel_virtualFileHasRequiredSourcesKeyButIsNull_nullReturned() {
    val javaFile = myAndroidProjectRule.fixture.createFile("somefile.java", "file contents")
    javaFile.putUserData(AttachAndroidSdkSourcesNotificationProvider.REQUIRED_SOURCES_KEY, null)

    val panel = invokeCreateNotificationPanel(javaFile)
    assertThat(panel).isNull()
  }

  @Test
  fun createNotificationPanel_panelHasCorrectLabel() {
    val panel = invokeCreateNotificationPanel(androidSdkClassWithoutSources)
    assertThat(panel).isNotNull()
    assertThat(panel!!.text).isEqualTo("Android SDK sources for API 33 not found.")
  }

  @Test
  fun createNotificationPanel_panelHasDownloadLink() {
    val panel = invokeCreateNotificationPanel(androidSdkClassWithoutSources)
    val links: Map<String, Runnable> = panel!!.links
    assertThat(links.keys).containsExactly("Download")
  }

  @Test
  fun createNotificationPanel_downloadLinkDownloadsSources() {
    whenever(myModelWizardDialog.showAndGet()).thenReturn(true)
    val panel = invokeCreateNotificationPanel(androidSdkClassWithoutSources)

    val rootProvider = AndroidSdks.getInstance().allAndroidSdks[0].rootProvider
    assertThat(rootProvider.getFiles(OrderRootType.SOURCES)).hasLength(0)

    // Invoke the "Download" link, which is first in the components.
    ApplicationManager.getApplication().invokeAndWait { panel!!.links["Download"]!!.run() }

    // Check that the link requested the correct paths, and that then sources became available.
    assertThat(myProvider.requestedPaths).isNotNull()
    assertThat(myProvider.requestedPaths).containsExactly("sources;android-33")
    assertThat(rootProvider.getFiles(OrderRootType.SOURCES).size).isGreaterThan(0)
  }

  @Test
  fun createNotificationPanel_virtualFileHasRequiredSourcesKey_downloadLinkHasRequestedSources() {
    val javaFile = myAndroidProjectRule.fixture.createFile("somefile.java", "file contents")
    javaFile.putUserData(AttachAndroidSdkSourcesNotificationProvider.REQUIRED_SOURCES_KEY, 30)

    val panel = invokeCreateNotificationPanel(javaFile)
    ApplicationManager.getApplication().invokeAndWait { panel!!.links["Download"]!!.run() }

    // Check that the link requested the correct paths, and that then sources became available.
    assertThat(myProvider.requestedPaths).isNotNull()
    assertThat(myProvider.requestedPaths).containsExactly("sources;android-30")
  }

  private fun invokeCreateNotificationPanel(virtualFile: VirtualFile): AttachAndroidSdkSourcesNotificationProvider.MyEditorNotificationPanel? {
    val panel = runReadAction { myProvider.collectNotificationData(myAndroidProjectRule.project, virtualFile)?.apply(myFileEditor) }
    return panel as AttachAndroidSdkSourcesNotificationProvider.MyEditorNotificationPanel?
  }

  private val androidSdkClassWithoutSources: VirtualFile
    private get() {
      for (sdk in AndroidSdks.getInstance().allAndroidSdks) {
        val sdkModificator = sdk.sdkModificator
        sdkModificator.removeRoots(OrderRootType.SOURCES)
        ApplicationManager.getApplication().invokeAndWait { sdkModificator.commitChanges() }
      }

      return runReadAction {
        JavaPsiFacade.getInstance(myAndroidProjectRule.project)
          .findClass("android.view.View", GlobalSearchScope.allScope(myAndroidProjectRule.project))!!
          .containingFile
          .virtualFile
      }
    }

  /**
   * Test implementation of [AttachAndroidSdkSourcesNotificationProvider] that mocks the call to create an SDK download dialog.
   */
  private inner class TestAttachAndroidSdkSourcesNotificationProvider :
    AttachAndroidSdkSourcesNotificationProvider() {
    var requestedPaths: List<String>? = null
      private set

    override fun createSdkDownloadDialog(project: Project, requestedPaths: List<String>?): ModelWizardDialog {
      this.requestedPaths = requestedPaths
      return myModelWizardDialog
    }
  }
}
