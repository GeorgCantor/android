/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.quickFixes

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.java.JavaLanguageVersionPropertyModel
import com.android.tools.idea.gradle.dsl.api.settings.PluginsBlockModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.module.Module
import com.intellij.psi.PsiElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewBundle
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.addIfNotNull

private const val DEFAULT_RESOLVER_PLUGIN_NAME = "org.gradle.toolchains.foojay-resolver-convention"

class AddJavaToolchainDefinition(
  project: Project,
  private val versionToSet: Int,
  private val modules: List<Module>
) : BaseRefactoringProcessor(project) {
  private val projectBuildModel = ProjectBuildModel.get(myProject)

  override fun findUsages(): Array<UsageInfo> {

    val usages = ArrayList<UsageInfo>()
    usages.addAll(modules.mapNotNull { projectBuildModel.getModuleBuildModel(it)?.findJavaToolchainVersionUsage() })

    usages.addIfNotNull(projectBuildModel.findFooJayPluginDefinitionUsageInfo())

    return usages.toTypedArray()
  }

  private fun GradleBuildModel.findJavaToolchainVersionUsage(): UsageInfo? {
    val languageVersionModel = java().toolchain().languageVersion()
    val definedVersion = languageVersionModel.version()
    if (definedVersion == null || definedVersion != versionToSet) {
      val languageVersionOrHigherPsiElement = listOf(
        languageVersionModel,
        java().toolchain(),
        java(),
        this
      ).firstNotNullOfOrNull { it.psiElement }
      if (languageVersionOrHigherPsiElement != null) {
        return SetToolchainUsageInfo(languageVersionOrHigherPsiElement, languageVersionModel, versionToSet)
      }
    }
    return null
  }

  private fun ProjectBuildModel.findFooJayPluginDefinitionUsageInfo(): UsageInfo? {
    return projectSettingsModel?.let { settingsModel ->
      val pluginFound = settingsModel.plugins().plugins().any { plugin ->
        plugin.name().toString() ==  DEFAULT_RESOLVER_PLUGIN_NAME
      }

      val needToAddPlugin = !pluginFound
      settingsModel.plugins().takeIf { needToAddPlugin }?.let { pluginsBlock ->
        listOf(
          settingsModel.plugins(),
          settingsModel
        ).firstNotNullOfOrNull { it.psiElement }
          ?. let { psiElement -> AddPluginUsageInfo(psiElement, pluginsBlock) }
      }
    }
  }

  override fun performRefactoring(usages: Array<out UsageInfo>) {
    usages.forEach { usage ->
      when (usage) {
        is SetToolchainUsageInfo -> usage.perform()
        is AddPluginUsageInfo -> usage.perform()
      }
    }

    projectBuildModel.applyChanges()
  }

  override fun getCommandName(): String = "Set Java Toolchain to $versionToSet"

  override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = object : UsageViewDescriptor {
    override fun getElements(): Array<PsiElement> = PsiElement.EMPTY_ARRAY

    override fun getProcessedElementsHeader(): String = "Set Java Toolchain to $versionToSet"

    override fun getCodeReferencesText(usagesCount: Int, filesCount: Int): String {
      return "References to be changed: ${UsageViewBundle.getReferencesString(usagesCount, filesCount)}"
    }
  }

  private class SetToolchainUsageInfo(
    psiElement: PsiElement,
    val modelToSet: JavaLanguageVersionPropertyModel,
    val versionToSet: Int
  ) : UsageInfo(psiElement, TextRange.EMPTY_RANGE, false) {
    fun perform() {
      modelToSet.setVersion(versionToSet)
    }
  }
  private class AddPluginUsageInfo(
    psiElement: PsiElement,
    val pluginsBlockModel: PluginsBlockModel
  ) : UsageInfo(psiElement, TextRange.EMPTY_RANGE, false) {
    fun perform() {
      pluginsBlockModel.applyPlugin(DEFAULT_RESOLVER_PLUGIN_NAME, "0.8.0")
    }
  }

}