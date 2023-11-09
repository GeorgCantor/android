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
package com.android.tools.idea.run.deployment.liveedit

import com.android.tools.idea.run.deployment.liveedit.analysis.compileIr
import com.android.tools.idea.run.deployment.liveedit.analysis.diff
import com.android.tools.idea.run.deployment.liveedit.analysis.leir.IrClass
import com.android.tools.idea.run.deployment.liveedit.analysis.onlyComposeDebugConstantChanges
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.utils.editor.commitToPsi
import junit.framework.Assert
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComposableCompileTest {
  private var files = HashMap<String, PsiFile>()

  @get:Rule
  var projectRule = AndroidProjectRule.inMemory()

  @Before
  fun setUp() {
    setUpComposeInProjectFixture(projectRule)



    files["HasComposableSingletons.kt"] = projectRule.fixture.configureByText("HasComposableSingletons.kt",
                                                                              "import androidx.compose.runtime.Composable\n" +
                                                                              "@Composable fun hasLambdaA(content: @Composable () -> Unit) { }\n" +
                                                                              "@Composable fun hasLambdaB() { hasLambdaA {} }")

  }

  @Test
  fun simpleComposeChange() {
    val cache = initialCache(mapOf("ComposeSimple.kt" to """
      import androidx.compose.runtime.Composable
      @Composable fun composableFun() : String {
        var str = "hi"
        return str
      }
      @Composable fun composableFun2() : String {
        return "hi2"
      }"""))

    val file = projectRule.fixture.configureByText("ComposeSimple.kt","""
      import androidx.compose.runtime.Composable
      @Composable fun composableFun() : String {
        var str = "hello"
        return str
      }
      @Composable fun composableFun2() : String {
        return "hi2"
      }""")
    val output = compile(file, cache)
    // We can't really invoke any composable without a "host". Normally that host will be the
    // Android activity. There are other hosts that we can possibly run as a Compose unit test.
    // We could potentially look into doing that. However, for the purpose of verifying the
    // compose compiler was invoked correctly, we can just check the output's methods.
    Assert.assertTrue(output.classesMap["ComposeSimpleKt"]!!.isNotEmpty())

    Assert.assertEquals(1639534479, output.groupIds.first())

    val c = loadClass(output)
    var foundFunction = false;
    for (m in c.methods) {
      if (m.toString().contains("ComposeSimpleKt.composableFun(androidx.compose.runtime.Composer,int)")) {
        foundFunction = true;
      }
    }
    Assert.assertTrue(foundFunction)
  }

  @Test
  fun simpleComposeNested() {
    val cache = initialCache(mapOf("ComposeNested.kt" to """
      import androidx.compose.runtime.Composable
      @Composable
      fun composableNested(): @Composable (Int) -> Unit {
        return { }
      }"""))
    val file = projectRule.fixture.configureByText("ComposeNested.kt", """
      import androidx.compose.runtime.Composable
      @Composable
      fun composableNested(): @Composable (Int) -> Unit {
        val x = 0
        return { val y = 0 }
      }""")
    val output = compile(file, cache)
    Assert.assertEquals(-1050554150, output.groupIds.first())
  }

  @Test
  @Ignore("b/303114659")
  fun multipleEditsInOneUpdate() {
    val cache = initialCache(mapOf(
      "ComposeSimple.kt" to """
        import androidx.compose.runtime.Composable
        @Composable fun composableFun() : String {
          var str = "hi"
          return str
        }
        @Composable fun composableFun2() : String {
          return "hi2"
        }""",
      "ComposeNested.kt" to """
        import androidx.compose.runtime.Composable
        @Composable
        fun composableNested(): @Composable (Int) -> Unit {
          return { }
         }"""))

    // Testing an edit that has two files and three function modified.
    val file1 = projectRule.fixture.configureByText("ComposeSimple.kt", """
      import androidx.compose.runtime.Composable
      @Composable fun composableFun() : String {
        var str = "hello"
        return str
      }
      @Composable fun composableFun2() : String {
        return "hello"
      }""")
    val file2 = projectRule.fixture.configureByText("ComposeNested.kt", """
      import androidx.compose.runtime.Composable
      @Composable
      fun composableNested(): @Composable (Int) -> Unit {
        val x = 0
        return { val y = 0 }
      }""")
      val output = compile(listOf(
      LiveEditCompilerInput(file1, file1 as KtFile),
      LiveEditCompilerInput(file1, file1),
      LiveEditCompilerInput(file2, file2 as KtFile)), cache)

    Assert.assertEquals(4, output.classes.size)
    Assert.assertEquals(2, output.classesMap.size)
    Assert.assertEquals(2, output.supportClassesMap.size)
    Assert.assertTrue(output.classesMap.get("ComposeSimpleKt")!!.isNotEmpty())
    Assert.assertTrue(output.classesMap.get("ComposeNestedKt")!!.isNotEmpty())

    Assert.assertEquals(3, output.groupIds.size)
    Assert.assertTrue("groupids = " + output.groupIds.toString(), output.groupIds.contains(1639534479))
    Assert.assertTrue("groupids = " + output.groupIds.toString(), output.groupIds.contains(-1050554150))
    Assert.assertTrue("groupids = " + output.groupIds.toString(), output.groupIds.contains(-1350204187))
    Assert.assertFalse(output.resetState) // Compose only edits should not request for a full state reset.
  }

  @Test
  fun simpleMixed() {
    val original = projectRule.compileIr("""
     import androidx.compose.runtime.Composable
     @Composable fun isComposable() {}
     fun notComposable() {}
    """, "Mixed.kt")
    val cache = MutableIrClassCache()
    cache.update(original)

    val editComposable = projectRule.fixture.configureByText("Mixed.kt", """
     import androidx.compose.runtime.Composable
     @Composable fun isComposable() { val x = 0 }
     fun notComposable() {}
    """)

    var output = compile(editComposable, "isComposable", cache)
    Assert.assertEquals(-785806172, output.groupIds.first())
    Assert.assertFalse(output.resetState)

    val editNonComposable = projectRule.fixture.configureByText("Mixed.kt", """
     import androidx.compose.runtime.Composable
     @Composable fun isComposable() {}
     fun notComposable() { val x = 0 }
    """)

    output = compile(editNonComposable, "notComposable", cache)
    // Editing a normal Kotlin function should not result any group IDs. Instead, it should manually trigger a full state reset every edit.
    Assert.assertTrue(output.groupIds.isEmpty())
    Assert.assertTrue(output.resetState)
  }

  @Test
  fun testModuleName() {
    val output = compile(files["HasComposableSingletons.kt"], "hasLambdaA")
    val singleton = output.supportClassesMap.get("ComposableSingletons\$HasComposableSingletonsKt");
    Assert.assertNotNull(singleton)
    val cl = loadClass(output, "ComposableSingletons\$HasComposableSingletonsKt")
    val getLambda = cl.methods.find { it.name.contains("getLambda") }
    // Make sure we have getLambda$<MODULE_NAME>
    Assert.assertTrue(getLambda!!.name.contains(projectRule.module.name))
  }

  @Test
  fun sendAllThenOnlyChanges() {
    val file0 = """
      import androidx.compose.runtime.Composable
      @Composable fun composableFun() {
        val a = { }
      }
      @Composable fun composableFun2() {
        val a = { }
      }
      @Composable fun composableFun3() {
        val a = { }
      }
      @Composable fun composableFun4() {
        val a = { }
      }"""

    val cache = MutableIrClassCache()
    val apk = projectRule.compileIr(file0, "ComposeSimple.kt").associateBy { it.name }
    val compiler = LiveEditCompiler(projectRule.project, cache, object: ApkClassProvider {
      override fun getClass(ktFile: KtFile, className: String) = apk[className]
    })

    val file1 = projectRule.fixture.configureByText("ComposeSimple.kt", """
      import androidx.compose.runtime.Composable
      @Composable fun composableFun() {
        val a = { "hello "}
      }
      @Composable fun composableFun2() {
        val a = { }
      }
      @Composable fun composableFun3() {
        val a = { }
      }
      @Composable fun composableFun4() {
        val a = { }
      }""")

    // First LE should send all classes, regardless of what has changed.
    val output = compile(listOf(LiveEditCompilerInput(file1, file1 as KtFile)), compiler)
    assertEquals(9, output.classes.size)
    assertEquals(1, output.classesMap.size)
    assertEquals(8, output.supportClassesMap.size)
    assertTrue(output.classesMap["ComposeSimpleKt"]!!.isNotEmpty())

    cache.update(output.irClasses)

    val file2 = projectRule.fixture.configureByText("ComposeSimple.kt", """
      import androidx.compose.runtime.Composable
      @Composable fun composableFun() {
        val a = { "hello "}
      }
      @Composable fun composableFun2() {
        val a = { "hello" }
      }
      @Composable fun composableFun3() {
        val a = { }
      }
      @Composable fun composableFun4() {
        val a = { }
      }""")

    // Subsequent LE operations should resume sending only changed classes.
    val output2 = compile(listOf(LiveEditCompilerInput(file2, file2 as KtFile)), compiler)
    assertEquals(1, output2.classes.size)
    assertEquals(0, output2.classesMap.size)
    assertEquals(1, output2.supportClassesMap.size)
  }

  private val modifierCode = """
      class Color(val value: Int) {
          companion object {
              val Red = Color(0)
          }
      }

      class Dp()
      val Int.dp: Dp get() = Dp()

      interface Modifier {
          companion object : Modifier
      }

      fun Modifier.background(c: Color) = this
      fun Modifier.size(size: Dp) = this
      fun Modifier.padding(size: Dp) = this
      """.trimIndent()

  // Regression test for invalid incremental analysis. See b/295257198.
  @Test
  fun incrementalAnalysisFunctionBodyTest() {
    val fileName = "Test.kt"
    val className = "TestKt"
    val file0 = """
      $modifierCode
      fun foo() {
        Modifier.background(Color.Red).size(100.dp).padding(20.dp)
      }""".trimIndent()

    val file = projectRule.fixture.configureByText(fileName ,"""
      $modifierCode
      fun foo() {
        Modifier.background(Color.Red).size(100.dp)
      }""")

    val apk = projectRule.compileIr(file0, fileName).associateBy { it.name }
    val compiler = LiveEditCompiler(projectRule.project, MutableIrClassCache(), object: ApkClassProvider {
      override fun getClass(ktFile: KtFile, className: String): IrClass? {
        return apk[className]
      }
    })
    val output = compile(listOf(LiveEditCompilerInput(file, file as KtFile)), compiler)
    Assert.assertTrue(output.classesMap[className]!!.isNotEmpty())
    val klass = loadClass(output, className)
    // Before the fix, invalid code will be generated and this will lead to a class
    // cast exception during execution.
    invokeStatic("foo", klass)
  }

  // Regression test for invalid incremental analysis. See b/295257198.
  @Test
  fun incrementalAnalysisFunctionExpressionBodyTest() {
    val fileName = "Test.kt"
    val className = "TestKt"
    val file0 = """
      $modifierCode
      fun foo(): Modifier = Modifier.background(Color.Red).size(100.dp).padding(20.dp)
    """.trimIndent()

    val file = projectRule.fixture.configureByText(fileName ,"""
      $modifierCode
      fun foo(): Modifier = Modifier.background(Color.Red).size(100.dp)""")


    val apk = projectRule.compileIr(file0, fileName).associateBy { it.name }
    val compiler = LiveEditCompiler(projectRule.project, MutableIrClassCache(), object: ApkClassProvider {
      override fun getClass(ktFile: KtFile, className: String): IrClass? {
        return apk[className]
      }
    })
    val output = compile(listOf(LiveEditCompilerInput(file, file as KtFile)), compiler)
    Assert.assertTrue(output.classesMap[className]!!.isNotEmpty())
    val klass = loadClass(output, className)
    // Before the fix, invalid code will be generated and this will lead to a class
    // cast exception during execution.
    invokeStatic("foo", klass)
  }

  // Regression test for incorrect fix for b/295257198 where we filtered the
  // parent context in the incremental analysis too much.
  @Test
  fun incrementalAnalysisFunctionBodyWithArgumentsTest() {
    val fileName = "Test.kt"
    val className = "TestKt"
    val cache = initialCache(mapOf(fileName to """
      fun bar() = foo(0)
      fun foo(l: Int): Int {
        return 32
      }"""))

    val file = projectRule.fixture.configureByText(fileName ,"""
      fun bar() = foo(0)
      fun foo(l: Int): Int {
        return 42
      }""")

    val output = compile(file, cache)
    Assert.assertTrue(output.classesMap[className]!!.isNotEmpty())
    val klass = loadClass(output, className)
    assertEquals(42, invokeStatic("bar", klass))
  }

  @Test
  fun incrementalAnalysisPropertyGetter() {
    val fileName = "Test.kt"
    val className = "TestKt"
    val file0 = """
      $modifierCode
      class A {
        val y: Modifier
          get() = Modifier.background(Color.Red).size(100.dp).padding(20.dp)
      }
      fun bar(): Int {
        A().y
        return 42
      }""".trimIndent()

    val file = projectRule.fixture.configureByText(fileName ,"""
      $modifierCode
      class A {
        val y: Modifier
          get() = Modifier.background(Color.Red).size(100.dp)
      }
      fun bar(): Int {
        A().y
        return 42
      }""")

    val apk = projectRule.compileIr(file0, fileName).associateBy { it.name }
    val compiler = LiveEditCompiler(projectRule.project, MutableIrClassCache(), object: ApkClassProvider {
      override fun getClass(ktFile: KtFile, className: String): IrClass? {
        return apk[className]
      }
    })
    val output = compile(listOf(LiveEditCompilerInput(file, file as KtFile)), compiler)
    Assert.assertTrue(output.classesMap[className]!!.isNotEmpty())
    val klass = loadClass(output, className)
    assertEquals(42, invokeStatic("bar", klass))
  }

  @Test
  fun testIgnoreTraceEventStart() {
    val compiler = Precompiler(projectRule.project, SourceInlineCandidateCache())
    val file = projectRule.fixture.configureByText("File.kt", """
      import androidx.compose.runtime.Composable
      @Composable fun composableFun() : String {
        var str = "hi"
        return str
      }
    """.trimIndent()) as KtFile

    val firstClass = ReadAction.compute<IrClass, Throwable> { compiler.compile(file).map { IrClass(it) }.single { it.name == "FileKt" } }
    val firstMethod = firstClass.methods.first { it.name == "composableFun" }

    // Ensure we actually generated a traceEventStart() call
    assertNotNull(firstMethod.instructions.singleOrNull {
      it.opcode == Opcodes.INVOKESTATIC && it.params[0] == "androidx/compose/runtime/ComposerKt" && it.params[1] == "traceEventStart"
    })

    // Modifying the line numbers causes the traceEventStart() calls to change; unfortunately, it doesn't change the sourceInformation()
    // calls from within our test context. Not sure why.
    val content = """  
    import androidx.compose.runtime.Composable
     
      // Change the line offset of the @Composable to cause the argument to traceEventStart() to change
      @Composable fun composableFun() : String {
        var str = "hi"
        return str
      }
    """.trimIndent()

    WriteCommandAction.runWriteCommandAction(projectRule.project) {
      val document = projectRule.fixture.editor.document
      document.replaceString(0, document.textLength, content)
      document.commitToPsi(projectRule.project)
    }

    val secondClass = ReadAction.compute<IrClass, Throwable> { compiler.compile(file).map { IrClass(it) }.single { it.name == "FileKt" } }
    val secondMethod = secondClass.methods.first { it.name == "composableFun" }

    // Ensure we actually generated a traceEventStart() call
    assertNotNull(secondMethod.instructions.singleOrNull {
      it.opcode == Opcodes.INVOKESTATIC && it.params[0] == "androidx/compose/runtime/ComposerKt" && it.params[1] == "traceEventStart"
    })

    assertNotNull(diff(firstClass, secondClass))
    assertTrue(onlyComposeDebugConstantChanges(firstMethod.instructions, secondMethod.instructions))
  }

  private fun initialCache(files: Map<String, String>): MutableIrClassCache {
    val cache = MutableIrClassCache()
    files.map { projectRule.compileIr(it.value, it.key) }.forEach { cache.update(it) }
    return cache
  }
}
