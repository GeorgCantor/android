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

// ATTENTION: This file has been automatically generated from declarative.bnf. Do not edit it manually.
package com.android.tools.idea.gradle.declarative.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static com.android.tools.idea.gradle.declarative.parser.DeclarativeElementTypeHolder.*;
import com.android.tools.idea.gradle.declarative.psi.DeclarativeIdentifierMixin;
import com.android.tools.idea.gradle.declarative.psi.*;
import com.android.tools.idea.gradle.declarative.parser.PsiImplUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.psi.tree.IElementType;

public class DeclarativeIdentifierImpl extends DeclarativeIdentifierMixin implements DeclarativeIdentifier {

  public DeclarativeIdentifierImpl(@NotNull IElementType type) {
    super(type);
  }

  public void accept(@NotNull DeclarativeVisitor visitor) {
    visitor.visitIdentifier(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof DeclarativeVisitor) accept((DeclarativeVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public PsiElement getToken() {
    return findPsiChildByType(TOKEN);
  }

  @Override
  @Nullable
  public String getName() {
    return PsiImplUtil.getName(this);
  }

}
