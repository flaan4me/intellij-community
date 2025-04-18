// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.intention.impl;

import com.intellij.codeInsight.BlockUtils;
import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.java.JavaBundle;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandAction;
import com.intellij.modcommand.Presentation;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import one.util.streamex.MoreCollectors;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

import static com.intellij.util.ObjectUtils.tryCast;

public class MoveIntoIfBranchesAction implements ModCommandAction {
  @Override
  public @NotNull @IntentionFamilyName String getFamilyName() {
    return JavaBundle.message("intention.name.move.into.if.branches");
  }

  private static List<PsiStatement> extractStatements(@NotNull ActionContext context) {
    PsiFile file = context.file();
    if (!(file instanceof PsiJavaFile)) return Collections.emptyList();
    TextRange selection = context.selection();
    if (selection.isEmpty()) {
      int offset = context.offset();
      PsiElement pos = file.findElementAt(offset);
      PsiStatement statement = PsiTreeUtil.getParentOfType(pos, PsiStatement.class, false, PsiMember.class, PsiCodeBlock.class);
      return ContainerUtil.createMaybeSingletonList(statement);
    }
    int startOffset = selection.getStartOffset();
    int endOffset = selection.getEndOffset();
    PsiElement[] elements = CodeInsightUtil.findStatementsInRange(file, startOffset, endOffset);
    return StreamEx.of(elements)
      .map(e -> tryCast(e, PsiStatement.class))
      .collect(MoreCollectors.ifAllMatch(Objects::nonNull, Collectors.toList()))
      .orElse(Collections.emptyList());
  }

  private static boolean hasConflictingDeclarations(@NotNull PsiIfStatement ifStatement, @NotNull List<PsiStatement> statements) {
    PsiStatement lastStatement = statements.get(statements.size() - 1);
    List<PsiElement> afterLast = new ArrayList<>();
    for (PsiElement e = lastStatement.getNextSibling(); e != null; e = e.getNextSibling()) {
      if (!(e instanceof PsiComment) && !(e instanceof PsiWhiteSpace)) {
        afterLast.add(e);
      }
    }
    PsiStatement thenBranch = ifStatement.getThenBranch();
    PsiStatement elseBranch = ifStatement.getElseBranch();
    if (!ControlFlowUtils.statementMayCompleteNormally(thenBranch) ||
        !ControlFlowUtils.statementMayCompleteNormally(elseBranch)) {
      return true;
    }
    List<String> declaredInIf = StreamEx.of(thenBranch, elseBranch).flatArray(ControlFlowUtils::unwrapBlock)
      .select(PsiDeclarationStatement.class).flatArray(PsiDeclarationStatement::getDeclaredElements)
      .select(PsiNamedElement.class).map(PsiNamedElement::getName).nonNull().toList();
    if (afterLast.isEmpty() && declaredInIf.isEmpty()) return false;
    Set<PsiVariable> declared = StreamEx.of(statements).flatCollection(VariableAccessUtils::findDeclaredVariables).toSet();
    if (declared.isEmpty()) return false;
    if (SyntaxTraverser.psiTraverser().withRoots(afterLast).filter(PsiJavaCodeReferenceElement.class)
          .filter(ref -> declared.contains(ref.resolve())).first() != null) {
      return true;
    }
    return ContainerUtil.exists(declaredInIf, name -> ContainerUtil.exists(declared, d -> name.equals(d.getName())));
  }

  @Override
  public @Nullable Presentation getPresentation(@NotNull ActionContext context) {
    List<PsiStatement> statements = extractStatements(context);
    if (statements.isEmpty()) return null;
    PsiElement prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(statements.get(0));
    if (!(prev instanceof PsiIfStatement ifStatement) || hasConflictingDeclarations(ifStatement, statements)) return null;
    return Presentation.of(getFamilyName());
  }

  @Override
  public @NotNull ModCommand perform(@NotNull ActionContext context) {
    return ModCommand.psiUpdate(context.file(), f -> invoke(context.withFile(f)));
  }
  
  private static void invoke(@NotNull ActionContext context) {
    List<PsiStatement> statements = extractStatements(context);
    if (statements.isEmpty()) return;
    PsiIfStatement ifStatement = tryCast(PsiTreeUtil.skipWhitespacesAndCommentsBackward(statements.get(0)), PsiIfStatement.class);
    if (ifStatement == null || hasConflictingDeclarations(ifStatement, statements)) return;
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(context.project());
    PsiStatement thenBranch = ifStatement.getThenBranch();
    if (thenBranch == null) {
      ifStatement.setThenBranch(factory.createStatementFromText("{}", null));
      thenBranch = Objects.requireNonNull(ifStatement.getThenBranch());
    }
    else if (!(thenBranch instanceof PsiBlockStatement)) {
      thenBranch = (PsiStatement)BlockUtils.expandSingleStatementToBlockStatement(thenBranch).getParent().getParent();
    }
    PsiStatement elseBranch = ifStatement.getElseBranch();
    if (elseBranch == null) {
      ifStatement.setElseBranch(factory.createStatementFromText("{}", null));
      elseBranch = Objects.requireNonNull(ifStatement.getElseBranch());
    }
    else if (!(elseBranch instanceof PsiBlockStatement)) {
      elseBranch = (PsiStatement)BlockUtils.expandSingleStatementToBlockStatement(elseBranch).getParent().getParent();
    }
    PsiCodeBlock thenBlock = ((PsiBlockStatement)thenBranch).getCodeBlock();
    PsiCodeBlock elseBlock = ((PsiBlockStatement)elseBranch).getCodeBlock();
    PsiJavaToken thenBrace = thenBlock.getRBrace();
    PsiJavaToken elseBrace = elseBlock.getRBrace();
    if (thenBrace == null || elseBrace == null) return;
    thenBlock.addRangeBefore(statements.get(0), statements.get(statements.size() - 1), thenBrace);
    elseBlock.addRangeBefore(statements.get(0), statements.get(statements.size() - 1), elseBrace);
    ifStatement.getParent().deleteChildRange(statements.get(0), statements.get(statements.size() - 1));
  }
}
