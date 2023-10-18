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
package com.android.tools.idea.editors.build

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.PsiTreeChangeListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import java.util.concurrent.atomic.AtomicBoolean

private fun isInKotlinAnnotation(element: PsiElement): Boolean {
  var current: PsiElement? = element.parent
  while (current != null) {
    if (current is KtAnnotationEntry) return true
    current = current.parent
  }

  return false
}

/**
 * A [PsiTreeChangeListener] that filters out changes to elements like comments and calls [onCodeChange] when a file has been modified.
 * A [fileFilter] can be passed to ignore certain files, for example, if we are not interested in checking a file anymore after it has
 * been modified.
 */
private class CodePsiTreeChangeAdapter(
  private val fileFilter: (PsiFile) -> Boolean,
  private val onCodeChange: (PsiFile) -> Unit
) : PsiTreeChangeListener {
  override fun beforeChildAddition(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun beforeChildRemoval(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun beforeChildReplacement(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun beforeChildMovement(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun beforeChildrenChange(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun beforePropertyChange(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun childAdded(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun childRemoved(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun childReplaced(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun childrenChanged(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun childMoved(event: PsiTreeChangeEvent) = handleEvent(event)
  override fun propertyChanged(event: PsiTreeChangeEvent) = handleEvent(event)

  /**
   * Detect whether an element is or is contained within a code block.
   *
   * We consider it to be in a code block if it's within:
   *  - A class
   *  - A top level method
   *  - A top level expression (like top level properties)
   *
   * We intentionally ignore changes in comments and annotations since we do not consider them
   * to affect the code.
   */
  private fun isCodeElement(element: PsiElement): Boolean {
    var current: PsiElement? = element
    while (current != null) {
      when (current) {
        is KtFile -> return false
        is PsiFile -> return false
        is PsiComment -> return false
        is PsiMethod -> return true
        is KtFunction -> return true
        is PsiClass -> return true
        is KtClass -> return true
        // Detect changes in expressions unless they are within an annotation
        is KtExpression -> return !isInKotlinAnnotation(element)
      }

      current = current.parent
    }

    return false
  }

  private fun handleEvent(psiEvent: PsiTreeChangeEvent) {
    val file = psiEvent.file ?: return
    if (file.language != KotlinLanguage.INSTANCE && file.language != JavaLanguage.INSTANCE) return

    if (!fileFilter(file)) return

    val element = psiEvent.newChild ?: psiEvent.child ?: psiEvent.newParent ?: psiEvent.parent ?: return

    if (isCodeElement(element)) onCodeChange(file)
  }
}

/**
 * Returns whether this file is an actual file or a fake one. Fake files are not backed by a real file system and they are
 * in memory representations.
 */
private fun PsiFile.isFakeFile(): Boolean =
  virtualFile?.fileSystem is NonPhysicalFileSystem

@Service(Service.Level.PROJECT)
class PsiCodeFileChangeDetectorService private constructor(psiManager: PsiManager) : Disposable {
  private val isDisposed = AtomicBoolean(false)
  private val _fileUpdatesFlow = MutableStateFlow(setOf<PsiFile>())

  val fileUpdatesFlow: StateFlow<Set<PsiFile>>
    get() = _fileUpdatesFlow

  /**
   * Set of files that are currently out of date.
   */
  val outOfDateFiles: Set<PsiFile>
    get() = fileUpdatesFlow.value

  @Suppress("unused")
  constructor(project: Project) : this(PsiManager.getInstance(project))

  init {
    psiManager.addPsiTreeChangeListener(
      // Listen to all code changes but ignore changes for files that are already out of date or for code that is not part of the file system.
      // We ignore fake files since we do not care a bout in-memory modifications.
      CodePsiTreeChangeAdapter({ !fileUpdatesFlow.value.contains(it) && !it.isFakeFile() }, ::onCodeChange),
      this
    )
  }

  /**
   * Called when a code change has happened to the given [file].
   */
  private fun onCodeChange(file: PsiFile) {
    if (isDisposed.get()) return
    _fileUpdatesFlow.value += file
  }

  private fun markAllAsUpToDate() {
    _fileUpdatesFlow.value = setOf()
  }

  /**
   * Marks the given [files] as being up to date. They will remain as up to date until the next change.
   */
  fun markAsUpToDate(files: Collection<PsiFile>) {
    _fileUpdatesFlow.value -= files
  }

  /**
   * Mark one file as out of date. This method is only meant to be used during testing.
   */
  @TestOnly
  fun markFileAsOutOfDate(file: PsiFile) {
    onCodeChange(file)
  }

  override fun dispose() {
    isDisposed.set(true)
    markAllAsUpToDate()
  }

  companion object {
    fun getInstance(project: Project): PsiCodeFileChangeDetectorService = project.getService(PsiCodeFileChangeDetectorService::class.java)
  }
}

val PsiCodeFileChangeDetectorService.outOfDateKtFiles: Set<PsiFile>
  get() = outOfDateFiles.filter { it.language == KotlinLanguage.INSTANCE }.toSet()