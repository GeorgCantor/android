package com.android.tools.idea.run.deployment.liveedit.analysis

import com.android.tools.idea.run.deployment.liveedit.analysis.diffing.LocalVariableDiff
import com.android.tools.idea.run.deployment.liveedit.analysis.diffing.LocalVariableVisitor
import com.android.tools.idea.run.deployment.liveedit.analysis.diffing.MethodVisitor
import com.android.tools.idea.run.deployment.liveedit.analysis.leir.IrLabels
import com.android.tools.idea.run.deployment.liveedit.analysis.leir.IrLocalVariable
import com.android.tools.idea.run.deployment.liveedit.setUpComposeInProjectFixture
import com.android.tools.idea.testing.AndroidProjectRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Basic tests for all elements of [IrLocalVariable]/[LocalVariableDiff] except:
 * - signature -> doesn't appear to be emitted by kotlinc
 */
class LocalVariableTest {
  @get:Rule
  var projectRule = AndroidProjectRule.inMemory()

  @Before
  fun setUp() {
    setUpComposeInProjectFixture(projectRule)
  }

  @Test
  fun testAddRemoveLocalVar() {
    val original = projectRule.compileIr("""
      class A {
        fun method() {
          var a = ""
        }
      }""", "A.kt", "A")

    val new = projectRule.compileIr("""
      class A {
        fun method() {
          var a = ""
          var b = ""
          var c = ""
        }
      }""", "A.kt", "A")

    assertNull(diff(original, original))
    assertNull(diff(new, new))

    val diff = diff(original, new)
    assertNotNull(diff)

    assertMethods(diff, buildMap {
      put("method()V", object : MethodVisitor {
        override fun visitLocalVariables(added: List<IrLocalVariable>, removed: List<IrLocalVariable>, modified: List<LocalVariableDiff>) {
          assertEquals(setOf(2, 3), added.map(IrLocalVariable::index).toSet())
          assertEquals(setOf("b", "c"), added.map(IrLocalVariable::name).toSet())
          assertTrue(removed.isEmpty())
          assertEquals(setOf(0, 1), modified.map(LocalVariableDiff::index).toSet()) // variables change scope
        }
      })
    })

    val inv = diff(new, original)
    assertNotNull(inv)

    assertMethods(inv, buildMap {
      put("method()V", object : MethodVisitor {
        override fun visitLocalVariables(added: List<IrLocalVariable>, removed: List<IrLocalVariable>, modified: List<LocalVariableDiff>) {
          assertTrue(added.isEmpty())
          assertEquals(setOf(2, 3), removed.map(IrLocalVariable::index).toSet())
          assertEquals(setOf("b", "c"), removed.map(IrLocalVariable::name).toSet())
          assertEquals(setOf(0, 1), modified.map(LocalVariableDiff::index).toSet()) // variables change scope
        }
      })
    })
  }

  @Test
  fun testName() {
    val original = projectRule.compileIr("""
      class A {
        fun method() {
          var a = ""
        }
      }""", "A.kt", "A")

    val new = projectRule.compileIr("""
      class A {
        fun method() {
          var b = ""
        }
      }""", "A.kt", "A")

    assertNull(diff(original, original))
    assertNull(diff(new, new))

    val diff = diff(original, new)
    assertNotNull(diff)

    assertLocalVars(diff, "method", buildMap {
      put(1, object : LocalVariableVisitor {
        override fun visitName(old: String, new: String) {
          assertEquals("a", old)
          assertEquals("b", new)
        }
      })
    })

    val inv = diff(new, original)
    assertNotNull(inv)

    assertLocalVars(inv, "method", buildMap {
      put(1, object : LocalVariableVisitor {
        override fun visitName(old: String, new: String) {
          assertEquals("b", old)
          assertEquals("a", new)
        }
      })
    })
  }

  @Test
  fun testDesc() {
    val original = projectRule.compileIr("""
      class A {
        fun method() {
          var a = ""
        }
      }""", "A.kt", "A")

    val new = projectRule.compileIr("""
      class A {
        fun method() {
          var a = 0
        }
      }""", "A.kt", "A")

    assertNull(diff(original, original))
    assertNull(diff(new, new))

    val diff = diff(original, new)
    assertNotNull(diff)

    assertLocalVars(diff, "method", buildMap {
      put(1, object : LocalVariableVisitor {
        override fun visitDesc(old: String, new: String) {
          assertEquals("Ljava/lang/String;", old)
          assertEquals("I", new)
        }
      })
    })

    val inv = diff(new, original)
    assertNotNull(inv)

    assertLocalVars(inv, "method", buildMap {
      put(1, object : LocalVariableVisitor {
        override fun visitDesc(old: String, new: String) {
          assertEquals("I", old)
          assertEquals("Ljava/lang/String;", new)
        }
      })
    })
  }

  // Use of random() to force kotlinc to actually keep local variables around.
  @Test
  fun testSignature() {
    val original = projectRule.compileIr("""
      class A {
        fun method(): Int {
          var a = java.util.Random().nextInt()
          return a
        }
      }""", "A.kt", "A")

    val new = projectRule.compileIr("""
      class A {
        fun method(): Int {
          if (true) {
            var a = java.util.Random().nextInt()
            return a
          }
          return 0
        }
      }""", "A.kt", "A")

    assertNull(diff(original, original))
    assertNull(diff(new, new))

    val diff = diff(original, new)
    assertNotNull(diff)

    assertLocalVars(diff, "method", buildMap {
      put(1, object : LocalVariableVisitor {
        override fun visitStart(old: IrLabels.IrLabel, new: IrLabels.IrLabel) {
          assertEquals(1, old.index)
          assertEquals(2, new.index)
        }

        override fun visitEnd(old: IrLabels.IrLabel, new: IrLabels.IrLabel) {
          assertEquals(2, old.index)
          assertEquals(3, new.index)
        }
      })
    })

    val inv = diff(new, original)
    assertNotNull(inv)

    assertLocalVars(inv, "method", buildMap {
      put(1, object : LocalVariableVisitor {
        override fun visitStart(old: IrLabels.IrLabel, new: IrLabels.IrLabel) {
          assertEquals(2, old.index)
          assertEquals(1, new.index)
        }

        override fun visitEnd(old: IrLabels.IrLabel, new: IrLabels.IrLabel) {
          assertEquals(3, old.index)
          assertEquals(2, new.index)
        }
      })
    })
  }
}