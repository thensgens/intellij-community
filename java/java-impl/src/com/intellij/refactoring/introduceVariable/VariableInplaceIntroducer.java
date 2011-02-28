/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.refactoring.introduceVariable;

import com.intellij.codeInsight.intention.impl.TypeExpression;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.template.*;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.scope.processor.VariablesProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.refactoring.JavaRefactoringSettings;
import com.intellij.refactoring.rename.NameSuggestionProvider;
import com.intellij.refactoring.rename.inplace.VariableInplaceRenamer;
import com.intellij.refactoring.ui.TypeSelectorManagerImpl;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * User: anna
 * Date: 12/8/10
 */
public class VariableInplaceIntroducer extends VariableInplaceRenamer {
  private final PsiVariable myElementToRename;
  private final Editor myEditor;
  private final TypeExpression myExpression;
  private final boolean myCantChangeFinalModifier;
  private final Project myProject;
  private final SmartPsiElementPointer<PsiDeclarationStatement> myPointer;
  private final RangeMarker myExprMarker;
  private final List<RangeMarker> myOccurrenceMarkers;
  private final PsiType myDefaultType;

  public VariableInplaceIntroducer(final Project project,
                                   final TypeExpression expression,
                                   final Editor editor,
                                   final PsiVariable elementToRename,
                                   final boolean cantChangeFinalModifier,
                                   final boolean hasTypeSuggestion,
                                   final RangeMarker exprMarker,
                                   final List<RangeMarker> occurrenceMarkers) {
    super(elementToRename, editor);
    myProject = project;
    myEditor = editor;
    myElementToRename = elementToRename;
    myExpression = expression;
    myCantChangeFinalModifier = cantChangeFinalModifier;

    myExprMarker = exprMarker;
    myOccurrenceMarkers = occurrenceMarkers;

    myDefaultType = elementToRename.getType();

    final PsiDeclarationStatement declarationStatement = PsiTreeUtil.getParentOfType(elementToRename, PsiDeclarationStatement.class);
    myPointer = declarationStatement != null ? SmartPointerManager.getInstance(project).createSmartPsiElementPointer(declarationStatement) : null;
    editor.putUserData(ReassignVariableUtil.DECLARATION_KEY, myPointer);
    editor.putUserData(ReassignVariableUtil.OCCURRENCES_KEY,
                       occurrenceMarkers.toArray(new RangeMarker[occurrenceMarkers.size()]));
    setAdvertisementText(getAdvertisementText(declarationStatement, myDefaultType,
                                              hasTypeSuggestion, !cantChangeFinalModifier));
  }

  @Override
  protected void addAdditionalVariables(TemplateBuilderImpl builder) {
    final PsiTypeElement typeElement = myElementToRename.getTypeElement();
    builder.replaceElement(typeElement, "Variable_Type",
                           createExpression(myExpression, typeElement.getText(), !myCantChangeFinalModifier), true,
                           true);
    if (!myCantChangeFinalModifier) {
      builder.replaceElement(myElementToRename.getModifierList(), "_FINAL_", new FinalExpression(), false, true);
    }
  }

  @Override
  protected LookupElement[] createLookupItems(LookupElement[] lookupItems, String name) {
    TemplateState templateState = TemplateManagerImpl.getTemplateState(myEditor);
    final PsiVariable psiVariable = getVariable();
    if (psiVariable != null) {
      final TextResult insertedValue =
        templateState != null ? templateState.getVariableValue(PRIMARY_VARIABLE_NAME) : null;
      if (insertedValue != null) {
        final String text = insertedValue.getText();
        if (!text.isEmpty() && !Comparing.strEqual(text, name)) {
          final LinkedHashSet<String> names = new LinkedHashSet<String>();
          names.add(text);
          for (NameSuggestionProvider provider : Extensions.getExtensions(NameSuggestionProvider.EP_NAME)) {
            provider.getSuggestedNames(psiVariable, psiVariable, names);
          }
          final LookupElement[] items = new LookupElement[names.size()];
          final Iterator<String> iterator = names.iterator();
          for (int i = 0; i < items.length; i++) {
            items[i] = LookupElementBuilder.create(iterator.next());
          }
          return items;
        }
      }
    }
    return super.createLookupItems(lookupItems, name);
  }

  @Nullable
  protected PsiVariable getVariable() {
    final PsiDeclarationStatement declarationStatement = myPointer.getElement();
    return declarationStatement != null ? (PsiVariable)declarationStatement.getDeclaredElements()[0] : null;
  }

  @Override
  protected TextRange preserveSelectedRange(SelectionModel selectionModel) {
    return null;
  }

  @Override
  protected void moveOffsetAfter(boolean success) {
    try {
      if (success) {
        final Document document = myEditor.getDocument();
        final @Nullable PsiVariable psiVariable = getVariable();
        if (psiVariable == null) {
          return;
        }
        saveSettings(psiVariable);
        adjustLine(psiVariable, document);
        int startOffset = myExprMarker != null ? myExprMarker.getStartOffset() : psiVariable.getTextOffset();
        final PsiFile file = psiVariable.getContainingFile();
        final PsiReference referenceAt = file.findReferenceAt(startOffset);
        if (referenceAt != null && referenceAt.resolve() instanceof PsiLocalVariable) {
          startOffset = referenceAt.getElement().getTextRange().getEndOffset();
        }
        else {
          final PsiDeclarationStatement declarationStatement = PsiTreeUtil.getParentOfType(psiVariable, PsiDeclarationStatement.class);
          if (declarationStatement != null) {
            startOffset = declarationStatement.getTextRange().getEndOffset();
          }
        }
        myEditor.getCaretModel().moveToOffset(startOffset);
        if (psiVariable.getInitializer() != null) {
          ApplicationManager.getApplication().runWriteAction(new Runnable() {
            public void run() {
              appendTypeCasts(myOccurrenceMarkers, file, myProject, psiVariable);
            }
          });
        }
      }
    }
    finally {
      myEditor.putUserData(ReassignVariableUtil.DECLARATION_KEY, null);
      for (RangeMarker occurrenceMarker : myOccurrenceMarkers) {
        occurrenceMarker.dispose();
      }
      myEditor.putUserData(ReassignVariableUtil.OCCURRENCES_KEY, null);
      if (myExprMarker != null) myExprMarker.dispose();
    }
  }

  protected void saveSettings(PsiVariable psiVariable) {
    JavaRefactoringSettings.getInstance().INTRODUCE_LOCAL_CREATE_FINALS = psiVariable.hasModifierProperty(PsiModifier.FINAL);
    TypeSelectorManagerImpl.typeSelected(psiVariable.getType(), myDefaultType);
  }

  private static void appendTypeCasts(List<RangeMarker> occurrenceMarkers,
                                      PsiFile file,
                                      Project project,
                                      @Nullable PsiVariable psiVariable) {
    for (RangeMarker occurrenceMarker : occurrenceMarkers) {
      final PsiElement refVariableElement = file.findElementAt(occurrenceMarker.getStartOffset());
      final PsiReferenceExpression referenceExpression = PsiTreeUtil.getParentOfType(refVariableElement, PsiReferenceExpression.class);
      if (referenceExpression != null) {
        final PsiElement parent = referenceExpression.getParent();
        if (parent instanceof PsiVariable) {
          createCastInVariableDeclaration(project, (PsiVariable)parent);
        }
        else if (parent instanceof PsiReferenceExpression && psiVariable != null) {
          final PsiExpression initializer = psiVariable.getInitializer();
          LOG.assertTrue(initializer != null);
          final PsiType type = initializer.getType();
          if (((PsiReferenceExpression)parent).resolve() == null && type != null) {
            final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
            final PsiExpression castedExpr =
              elementFactory.createExpressionFromText("((" + type.getCanonicalText() + ")" + referenceExpression.getText() + ")", parent);
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(referenceExpression.replace(castedExpr));
          }
        }
      }
    }
    if (psiVariable != null && psiVariable.isValid()) {
      createCastInVariableDeclaration(project, psiVariable);
    }
  }

  private static void createCastInVariableDeclaration(Project project, PsiVariable psiVariable) {
    final PsiExpression initializer = psiVariable.getInitializer();
    LOG.assertTrue(initializer != null);
    final PsiType type = psiVariable.getType();
    final PsiType initializerType = initializer.getType();
    if (initializerType != null && !TypeConversionUtil.isAssignable(type, initializerType)) {
      final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
      final PsiExpression castExpr =
        elementFactory.createExpressionFromText("(" + psiVariable.getType().getCanonicalText() + ")" + initializer.getText(), psiVariable);
      JavaCodeStyleManager.getInstance(project).shortenClassReferences(initializer.replace(castExpr));
    }
  }

  @Nullable
  private static String getAdvertisementText(final PsiDeclarationStatement declaration,
                                             final PsiType type,
                                             final boolean hasTypeSuggestion,
                                             final boolean canAdjustFinal) {
    final VariablesProcessor processor = ReassignVariableUtil.findVariablesOfType(declaration, type);
    final Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
    if (processor.size() > 0) {
      final Shortcut[] shortcuts = keymap.getShortcuts("IntroduceVariable");
      if (shortcuts.length > 0) {
        return "Press " + shortcuts[0] + " to reassign existing variable";
      }
    }
    if (hasTypeSuggestion) {
      final Shortcut[] shortcuts = keymap.getShortcuts("PreviousTemplateVariable");
      if  (shortcuts.length > 0) {
        return "Press " + shortcuts[0] + " to change type";
      }
    }
    return adjustFinalText(canAdjustFinal);
  }

  @Nullable
  private static String adjustFinalText(final boolean canBeFinalAdjusted) {
    if (canBeFinalAdjusted) {
      final Shortcut[] shortcuts = KeymapManager.getInstance().getActiveKeymap().getShortcuts("PreviousTemplateVariable");
      if (shortcuts.length > 0) {
        return "Press " + shortcuts[0] + " to adjust final modifier";
      }
    }
    return null;
  }

  private static Expression createExpression(final TypeExpression expression, final String defaultType, final boolean canBeFinalAdjusted) {
    return new Expression() {
      @Override
      public Result calculateResult(ExpressionContext context) {
        return new TextResult(defaultType);
      }

      @Override
      public Result calculateQuickResult(ExpressionContext context) {
        return new TextResult(defaultType);
      }

      @Override
      public LookupElement[] calculateLookupItems(ExpressionContext context) {
        return expression.calculateLookupItems(context);
      }

      @Override
      public String getAdvertisingText() {
        return adjustFinalText(canBeFinalAdjusted);
      }
    };
  }

  protected boolean createFinals() {
    return IntroduceVariableBase.createFinals(myProject);
  }

  private class FinalExpression extends Expression {

    @Override
    public Result calculateResult(ExpressionContext context) {
      return new TextResult(createFinals() ? PsiKeyword.FINAL : "");
    }

    @Override
    public Result calculateQuickResult(ExpressionContext context) {
      return calculateResult(context);
    }

    @Override
    public LookupElement[] calculateLookupItems(ExpressionContext context) {
      LookupElement[] lookupElements = new LookupElement[2];
      lookupElements[0] = LookupElementBuilder.create("");
      lookupElements[1] = LookupElementBuilder.create(PsiModifier.FINAL + " ");
      return lookupElements;
    }
  }

  public static void adjustLine(final PsiVariable psiVariable, final Document document) {
    final int modifierListOffset = psiVariable.getTextRange().getStartOffset();
    final int varLineNumber = document.getLineNumber(modifierListOffset);

    ApplicationManager.getApplication().runWriteAction(new Runnable() { //adjust line indent if final was inserted and then deleted

      public void run() {
        PsiDocumentManager.getInstance(psiVariable.getProject()).doPostponedOperationsAndUnblockDocument(document);
        CodeStyleManager.getInstance(psiVariable.getProject()).adjustLineIndent(document, document.getLineStartOffset(varLineNumber));
      }
    });
  }
}
