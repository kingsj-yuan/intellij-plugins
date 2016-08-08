/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.jetbrains.lang.dart.ide.marker;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.SeparatorPlacement;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.FunctionUtil;
import com.jetbrains.lang.dart.psi.*;
import com.jetbrains.lang.dart.psi.impl.AbstractDartMethodDeclarationImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class DartMethodLineMarkerProvider implements LineMarkerProvider {

  private final DaemonCodeAnalyzerSettings myDaemonSettings;
  private final EditorColorsManager myColorsManager;

  public DartMethodLineMarkerProvider(DaemonCodeAnalyzerSettings daemonSettings, EditorColorsManager colorsManager) {
    myDaemonSettings = daemonSettings;
    myColorsManager = colorsManager;
  }

  @Nullable
  @Override
  public LineMarkerInfo getLineMarkerInfo(@NotNull final PsiElement element) {
    if (!myDaemonSettings.SHOW_METHOD_SEPARATORS) {
      return null;
    }

    // only continue if element is one of the markable elements (such as methods)
    if (isMarkableElement(element)) {

      // the method line markers are not nestable, aka, methods inside of methods, are not marked
      if (PsiTreeUtil.findFirstParent(element, true, e -> isMarkableElement(e)) != null) {
        return null;
      }

      // move the marker to previous siblings until comments have been included
      PsiElement markerLocation = element;
      while (markerLocation.getPrevSibling() != null &&
             (markerLocation.getPrevSibling() instanceof PsiComment || (markerLocation.getPrevSibling() instanceof PsiWhiteSpace &&
                                                                        markerLocation.getPrevSibling().getPrevSibling() != null &&
                                                                        markerLocation.getPrevSibling()
                                                                          .getPrevSibling() instanceof PsiComment))) {
        markerLocation = markerLocation.getPrevSibling();
      }

      // if the markerLocation element doesn't have a previous sibling (not whitespace), do not mark
      PsiElement prevElement = markerLocation;
      while (prevElement.getPrevSibling() != null && prevElement.getPrevSibling() instanceof PsiWhiteSpace) {
        prevElement = prevElement.getPrevSibling();
      }
      if (prevElement.getPrevSibling() == null) {
        return null;
      }

      // finally, create the marker
      LineMarkerInfo info = new LineMarkerInfo<>(markerLocation, markerLocation.getTextRange(), null, Pass.UPDATE_ALL,
                                                 FunctionUtil.<Object, String>nullConstant(), null,
                                                 GutterIconRenderer.Alignment.RIGHT);
      EditorColorsScheme scheme = myColorsManager.getGlobalScheme();
      info.separatorColor = scheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR);
      info.separatorPlacement = SeparatorPlacement.TOP;
      return info;
    }
    return null;
  }

  /**
   * Return true if this is such a PsiElement type that is separated by this LineMarkerProvider.
   */
  private static boolean isMarkableElement(@NotNull final PsiElement element) {
    return element instanceof DartMethodDeclaration ||
           element instanceof DartFunctionDeclarationWithBody ||
           element instanceof DartFunctionDeclarationWithBodyOrNative ||
           element instanceof DartGetterDeclaration ||
           element instanceof DartSetterDeclaration ||
           element instanceof DartFactoryConstructorDeclaration ||
           element instanceof AbstractDartMethodDeclarationImpl ||
           element instanceof DartNamedConstructorDeclaration ||
           element instanceof DartIncompleteDeclaration;
  }

  @Override
  public void collectSlowLineMarkers(@NotNull List<PsiElement> elements, @NotNull Collection<LineMarkerInfo> result) {
  }
}
