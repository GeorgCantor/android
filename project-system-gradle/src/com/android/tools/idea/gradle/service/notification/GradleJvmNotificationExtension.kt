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
package com.android.tools.idea.gradle.service.notification

import com.android.tools.idea.gradle.project.extensions.externalProjectFile
import com.android.tools.idea.gradle.project.sync.jdk.GradleJdkValidationManager
import com.android.tools.idea.gradle.project.sync.jdk.exceptions.cause.InvalidGradleJdkCause
import com.android.tools.idea.sdk.IdeSdks
import com.android.utils.FileUtils
import com.intellij.openapi.externalSystem.service.notification.NotificationData
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.SystemIndependent
import org.jetbrains.plugins.gradle.service.notification.GradleNotificationExtension
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleBundle
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.nio.file.Paths

/**
 * Adds more information to a notification when the selected Gradle JVM is not valid. If the notification message hast this pattern
 * "Invalid Gradle JDK configuration found.<Notification message suffix>", then this extension will replace this message with this:
 *
 * "Invalid Gradle JDK configuration found.<Cause of invalid JDK error, if a reason was found>\n
 *  <Use embedded JDK quickfix (if applicable)\n>
 *  <Change JDK location link (if not added already)\n>
 * "
 *
 * For example, this message:
 * """
 * Invalid Gradle JDK configuration found. <a href='#open_external_system_settings'>Open Gradle Settings</a>
 * """
 *
 * will be replace with:
 * """
 * Invalid Gradle JDK configuration found. ProjectJdkTable table does not have an entry of type JavaSDK named JDK.
 * <a href="use.jdk.as.project.jdk.embedded">Use Embedded JDK (<path to Embedded JDK>)</a>
 * <a href="use.jdk.as.project.jdk">Use JDK 1.8 (<path to JDK 1.8>)</a>
 * <a href="open.project.jdk.location">Change JDK location</a>
 * """
 *
 * The current invalid jdk reasons displayed are defined on: [InvalidGradleJdkCause]
 */
class GradleJvmNotificationExtension: GradleNotificationExtension() {

  override fun customize(notificationData: NotificationData, project: Project, error: Throwable?) {
    super.customize(notificationData, project, error)
    val expectedPrefix = GradleBundle.message("gradle.jvm.is.invalid")
    if (notificationData.message.startsWith(expectedPrefix)) {
      val ideSdks = IdeSdks.getInstance()
      val messageBuilder = StringBuilder()
      messageBuilder.appendLine(expectedPrefix)
      // Add more information on why it is not valid
      val gradleRootPath = getGradleProjectRootFromNotification(notificationData, project)
      GradleJdkValidationManager.getInstance(project).validateProjectGradleJvmPath(project, gradleRootPath)?.let { exception ->
        messageBuilder.appendLine(exception.message)
        notificationData.filePath = exception.jdkPathLocationFile?.absolutePath
      }

      // Suggest use embedded
      var registeredListeners = notificationData.registeredListenerIds
      val embeddedJdkPath = ideSdks.embeddedJdkPath
      if (ideSdks.validateJdkPath(embeddedJdkPath) != null) {
        val absolutePath = embeddedJdkPath.toAbsolutePath().toString()
        val listener = UseJdkAsProjectJdkListener(project, absolutePath, ".embedded")
        if (!registeredListeners.contains(listener.id)) {
          messageBuilder.appendLine("<a href=\"${listener.id}\">Use Embedded JDK ($absolutePath)</a>")
          notificationData.setListener(listener.id, listener)
          registeredListeners = notificationData.registeredListenerIds
        }
      }
      // Suggest IdeSdks.jdk (if different to embedded)
      val defaultJdk = ideSdks.jdk
      val defaultPath = defaultJdk?.homePath
      if (defaultPath != null) {
        if (ideSdks.validateJdkPath(Paths.get(defaultPath)) != null) {
          if (!FileUtils.isSameFile(embeddedJdkPath.toFile(), File(defaultPath))) {
            val listener = UseJdkAsProjectJdkListener(project, defaultPath)
            if (!registeredListeners.contains(listener.id)) {
              messageBuilder.appendLine("<a href=\"${listener.id}\">Use JDK ${defaultJdk.name} ($defaultPath)</a>")
              notificationData.setListener(listener.id, listener)
              registeredListeners = notificationData.registeredListenerIds
            }
          }
        }
      }
      // Add change JDK location link
      if ((registeredListeners != null) && (!registeredListeners.contains(OpenProjectJdkLocationListener.ID))) {
        OpenProjectJdkLocationListener.create(project, gradleRootPath)?.let { listener ->
          messageBuilder.appendLine("<a href=\"${OpenProjectJdkLocationListener.ID}\">Change Gradle JDK location</a>")
          notificationData.setListener(OpenProjectJdkLocationListener.ID, listener)
        }
      }

      notificationData.message = messageBuilder.toString().trim()
    }
  }

  /**
   * Obtain the gradle project root path from notification. This is a workaround added to be able to extract the path from the
   * notification title given that IntelliJ doesn't forward it from [ExternalSystemUtil.createFailureResult] b/271082369
   */
  private fun getGradleProjectRootFromNotification(notificationData: NotificationData, project: Project): @SystemIndependent String {
    val gradleRootPattern = Regex(ExternalSystemBundle.message("notification.project.refresh.fail.title", GradleConstants.GRADLE_NAME, "(.+)"))
    gradleRootPattern.find(notificationData.title)?.groupValues?.get(1)?.let { gradleRootName ->
      GradleSettings.getInstance(project).linkedProjectsSettings
        .firstOrNull { it.externalProjectFile.endsWith(gradleRootName) }
        ?.let { return it.externalProjectPath }
    }
    return project.basePath.orEmpty()
  }
}
