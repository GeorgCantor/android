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
package com.android.gmdcodecompletion.completions

import com.android.gmdcodecompletion.ConfigurationParameterName
import com.android.gmdcodecompletion.GmdConfigurationInterfaceInfo
import com.android.gmdcodecompletion.GmdConfigurationInterfaceInfo.FTL_DEVICE
import com.android.gmdcodecompletion.GmdConfigurationInterfaceInfo.FTL_EXECUTION
import com.android.gmdcodecompletion.GmdConfigurationInterfaceInfo.FTL_FIXTURE
import com.android.gmdcodecompletion.GmdConfigurationInterfaceInfo.FTL_RESULTS
import com.android.gmdcodecompletion.GmdConfigurationInterfaceInfo.MANAGED_VIRTUAL_DEVICE
import com.android.gmdcodecompletion.MinAndTargetApiLevel
import com.android.gmdcodecompletion.PsiElementLevel
import com.android.gmdcodecompletion.completions.lookupelementprovider.BaseLookupElementProvider
import com.android.gmdcodecompletion.completions.lookupelementprovider.CurrentDeviceProperties
import com.android.gmdcodecompletion.getQualifiedNameList
import com.android.gmdcodecompletion.superParent
import com.android.gmdcodecompletion.superParentAsGrMethodCall
import com.android.tools.idea.model.AndroidModel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.refactoring.memberInfo.qualifiedClassNameForRendering
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.plugins.groovy.GroovyLanguage
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrAssignmentExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression

/**
 * Describes available interfaces associated with each GMD property type
 *
 * FTL: FTL device definition block
 * FTL_TEST_OPTIONS: testOptions block for FTL
 * MANAGED_VIRTUAL: local managed virtual devices block
 */
enum class ConfigurationType(val availableContainers: PersistentList<GmdConfigurationInterfaceInfo>) {
  FTL(persistentListOf(FTL_DEVICE)),
  FTL_TEST_OPTIONS(persistentListOf(FTL_FIXTURE, FTL_EXECUTION, FTL_RESULTS)),
  MANAGED_VIRTUAL(persistentListOf(MANAGED_VIRTUAL_DEVICE));

  fun getMatchingDsl(leafBlockName: String, isSimplified: Boolean): List<String>? {
    val matchingInterface = this.availableContainers.filter { it.leafDslBlock == leafBlockName }
    return if (matchingInterface.isEmpty() && this.availableContainers.size == 1) {
      this.availableContainers[0].getDslSequence(leafBlockName, isSimplified)
    }
    else if (matchingInterface.size == 1) {
      matchingInterface[0].getDslSequence(leafBlockName, isSimplified)
    }
    else null
  }

  fun getMatchingInterfaceIndex(dslContent: String): Int {
    this.availableContainers.forEachIndexed { index, it ->
      if (it.leafDslBlock == dslContent) {
        return index
      }
    }
    return -1
  }
}

/**
 * Provides pattern matching functions for both Groovy and Kotlin GMD device definition fields
 */
object GmdDeviceDefinitionPatternMatchingProvider {

  /**
   * Returns true if cursor is inside FTl or managed device (depending on device type) definition block in Groovy build file.
   * Else returns false
   */
  fun matchesDevicePropertyGroovyPattern(configurationType: ConfigurationType, grExpression: GrExpression): Boolean {
    // Also check propertyAssignment.superParentAsGrMethodCall(3) since caret might be inside double quotation mark
    var currentPsiElement = grExpression.superParentAsGrMethodCall()
                            ?: grExpression.superParentAsGrMethodCall(3) ?: return false
    val deviceDefinitionArgs =
      currentPsiElement?.argumentList?.children?.let {
        if (it.size > 1) return false
        else it.firstOrNull()
      }

    /**
     * Simplified DSL does not have arguments in device declaration.
     * If device declaration has argument, check if it or it's resolved reference
     * matches FTl or managed virtual device interface name
     */
    val usesSimplifiedDsl = if (deviceDefinitionArgs != null) {
      val argReference = deviceDefinitionArgs as? GrReferenceExpression ?: return false
      if (argReference.qualifiedReferenceName != configurationType.availableContainers[0].interfaceName) {
        val resolvedArgument = argReference.resolve() as? PsiNamedElement ?: return false
        if (resolvedArgument.qualifiedClassNameForRendering() != configurationType.availableContainers[0].interfaceName) return false
      }
      false
    }
    else {
      true
    }

    var qualifiedReferenceNameList = currentPsiElement.getQualifiedNameList() ?: return false
    val dslSequence = configurationType.getMatchingDsl(qualifiedReferenceNameList[0], usesSimplifiedDsl) ?: return false
    val seqItr = dslSequence.iterator()
    // Check if text before each layer of Groovy block matches DSL sequence for given device type
    while (seqItr.hasNext()) {
      qualifiedReferenceNameList.forEach {
        if (seqItr.hasNext() && seqItr.next() != it) return false
      }
      if (!seqItr.hasNext()) break
      currentPsiElement = currentPsiElement.superParentAsGrMethodCall() ?: return false
      // Handle text similar to "android.testOptions"
      qualifiedReferenceNameList = currentPsiElement.getQualifiedNameList() ?: return false
    }
    // If iterator reaches end, and we are currently in the GroovyFile element, pattern matches DSL.
    // Otherwise, pattern is too short / long
    return !(seqItr.hasNext() || currentPsiElement.parent !is GroovyFile)
  }

  /**
   * Returns true if caret is inside FTl or managed device (depending on device type) definition block in Kotlin build file.
   * Else returns false
   */
  fun matchesDevicePropertyKotlinPattern(configurationType: ConfigurationType, ktExpression: KtExpression): Boolean {
    // Also check expression.parent since caret might be inside double quotation mark
    val propertyAssignment = ktExpression as? KtBinaryExpression
                             ?: ktExpression.parent as? KtBinaryExpression ?: return false
    val propertyField = propertyAssignment.left as? KtNameReferenceExpression ?: return false
    // Unlike Groovy, we can resolve directly from device property name to corresponding interface
    val interfaceName = (propertyField.reference?.resolve()?.superParent() as? KtClass)?.kotlinFqName?.toString() ?: return false
    return configurationType.availableContainers.any { it.interfaceName == interfaceName }
  }

  fun getMinAndTargetSdk(androidFacet: AndroidFacet?): MinAndTargetApiLevel {
    androidFacet ?: return MinAndTargetApiLevel(-1, -1)
    val model = AndroidModel.get(androidFacet) ?: return MinAndTargetApiLevel(-1, -1)
    return MinAndTargetApiLevel(targetSdk = model.targetSdkVersion?.apiLevel ?: -1,
                                minSdk = model.minSdkVersion?.apiLevel ?: -1)
  }

  // Returns current device property name value map
  fun getSiblingPropertyMap(
    position: PsiElement, psiElementLevel: PsiElementLevel): CurrentDeviceProperties {
    val currentProperty = position.superParent(psiElementLevel.psiElementLevel)
    val propertyValueMap = HashMap<ConfigurationParameterName, String>()
    // Use parent.children to get all siblings works better than siblings() method
    val allSiblings = currentProperty?.parent?.children ?: return propertyValueMap
    allSiblings.forEach { sibling ->
      when (position.language) {
        GroovyLanguage -> {
          if (currentProperty != sibling && sibling is GrAssignmentExpression) {
            val property = ConfigurationParameterName.fromOrNull(sibling.lValue?.text ?: "") ?: return@forEach
            propertyValueMap[property] = sibling.rValue?.text ?: ""
          }
        }

        KotlinLanguage.INSTANCE -> {
          if (currentProperty != sibling && sibling is KtBinaryExpression) {
            val property = ConfigurationParameterName.fromOrNull(sibling.left?.text ?: "") ?: return@forEach
            propertyValueMap[property] = sibling.right?.text ?: ""
          }
        }

        else -> {
          Logger.getInstance(BaseLookupElementProvider::class.java)
            .warn("${position.language} is not supported yet for GMD code completion. Groovy and Kotlin are currently supported")
          return propertyValueMap
        }
      }
    }
    return propertyValueMap
  }
}