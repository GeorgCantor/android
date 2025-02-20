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
package com.android.tools.idea.gradle.declarative

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.gradle.declarative.dsl.schema.AnalysisSchema
import org.gradle.declarative.dsl.schema.DataClass
import org.gradle.declarative.dsl.schema.DataMemberFunction
import org.gradle.declarative.dsl.schema.DataType
import org.gradle.declarative.dsl.schema.DataTypeRef
import org.gradle.declarative.dsl.schema.FqName
import org.gradle.declarative.dsl.schema.FunctionSemantics
import org.gradle.declarative.dsl.schema.SchemaFunction
import org.gradle.declarative.dsl.schema.SchemaMemberFunction
import org.gradle.internal.declarativedsl.analysis.DefaultFqName
import org.gradle.internal.declarativedsl.serialization.SchemaSerialization
import java.io.File

/**
 * Gets and caches declarative schema.
 */
@Service(Service.Level.PROJECT)
class DeclarativeService(val project: Project) {
  private val schema: DeclarativeSchema? = null

  companion object {
    fun getInstance(project: Project) = project.service<DeclarativeService>()
    val log = Logger.getInstance(DeclarativeService::class.java)
  }

  fun getSchema(): DeclarativeSchema? {
    return null /* TODO(b/349894866): this code fails to compile against IntelliJ 2024.2 due to Gradle library version conflicts.
    if (!Registry.`is`("android.gradle.ide.gradle.declarative.ide.support")) return null
    if (schema == null) {
      val parentPath = project.basePath
      val schemaFolder = File(parentPath, ".gradle/declarative-schema")
      val paths = schemaFolder.list { _: File?, name: String -> name.endsWith(".dcl.schema") } ?: return null
      val schemas = mutableListOf<AnalysisSchema>()
      var failure = false
      for (path in paths) {
        try {
          val schema = File(schemaFolder, path)
          val analysisSchema = SchemaSerialization.schemaFromJsonString(schema.readText())
          schemas.add(analysisSchema)
        }
        catch (e: Exception) {
          failure = true
          log.warn("Declarative schema parsing error: $e")
        }
      }
      return if (schemas.isNotEmpty())
        DeclarativeSchema(schemas, failure)
      else null
    }
    return schema
    */
  }
}

class DeclarativeSchema(private val schemas: List<AnalysisSchema>, val failureHappened: Boolean) {
  private val _dataClassesByFqName: Map<FqName, DataClass> by lazy {
    schemas.fold(mapOf()) { acc, e -> acc + e.dataClassesByFqName }
  }
  private val _rootMemberFunctions: List<SchemaMemberFunction> by lazy {
    schemas.fold(listOf()) { acc, e -> acc + e.topLevelReceiverType.memberFunctions }
  }

  fun getDataClassesByFqName(): Map<FqName, DataClass> = _dataClassesByFqName

  fun getRootMemberFunctions(): List<SchemaMemberFunction> = _rootMemberFunctions
}

fun getTopLevelReceiverByName(name: String, schema: DeclarativeSchema): FqName? {
  getReceiverByName(name, schema.getRootMemberFunctions())?.let {
    return it
  }
  // this is specific case for settings.gradle.dcl - hopefully, eventually schema file will be fixed
  // to have all settingsInternal attributes in rootMembers
  schema.getDataClassesByFqName()[getSettingsWrapper()]?.let { settings ->
    return (settings.properties.find { it.name == name }?.valueType as DataTypeRef.Name).fqName
  }
  return null
}

abstract sealed class Receiver
data class Function(val type: DataMemberFunction) : Receiver()
data class ObjectRef(val fqName: FqName) : Receiver()
data class SimpleType(val type: DataType) : Receiver()

fun getTopLevelReceiversByName(name: String, schema: DeclarativeSchema, fileName: String): List<Receiver> {
  getReceiverByName(name, schema.getRootMemberFunctions())?.let {
    // TODO need to consume property and functions here as well
    return listOf(ObjectRef(it))
  }
  return if (fileName.lowercase().startsWith("settings"))
    getSettingsReceivers(schema, name)
  else emptyList()
}

private fun getSettingsWrapper(): FqName =
  DefaultFqName("org.gradle.api.internal", "SettingsInternal")

private fun getSettingsReceivers(schema: DeclarativeSchema, name: String): List<Receiver> {
  // this is specific case for settings.gradle.dcl - hopefully, eventually schema file will be fixed
  // to have all settingsInternal attributes in rootMembers
  schema.getDataClassesByFqName()[getSettingsWrapper()]?.let {
    return getAllMembersByName(it, name)
  }
  return listOf()
}

fun getAllMembersByName(dataClass: DataClass, memberName: String): List<Receiver> {
  val result = mutableListOf<Receiver>()

  // object/simple types
  result.addAll(
    dataClass.properties.filter { it.name == memberName }.map { it.valueType }.map {
      when (it) {
        is DataTypeRef.Type -> SimpleType(it.dataType)
        is DataTypeRef.Name -> ObjectRef(it.fqName)
      }
    }
  )
  // functions/objects
  result.addAll(
    dataClass.memberFunctions.filter { it.simpleName == memberName }.mapNotNull {
      when {
        it.isFunction() && it is DataMemberFunction -> Function(it)
        it.semantics is FunctionSemantics.AccessAndConfigure -> {
          (it.semantics as FunctionSemantics.AccessAndConfigure).accessor.objectType.fqName()?.let { ObjectRef(it) }
        }

        else -> null
      }
    })

  return result
}

fun DataTypeRef.fqName() = (this as? DataTypeRef.Name)?.fqName

fun getReceiverByName(name: String, memberFunctions: List<SchemaMemberFunction>?): FqName? {
  val dataMemberFunction = memberFunctions?.find { it.simpleName == name } ?: return null
  (dataMemberFunction.semantics as? FunctionSemantics.AccessAndConfigure)?.accessor?.let {
    return it.objectType.fqName()
  }
  if (!dataMemberFunction.isFunction()) dataMemberFunction.receiver.fqName()?.let { return it }
  return null
}

// get those types empirically
fun SchemaFunction.isFunction() =
  this.semantics is FunctionSemantics.Pure ||
  this.semantics is FunctionSemantics.AddAndConfigure