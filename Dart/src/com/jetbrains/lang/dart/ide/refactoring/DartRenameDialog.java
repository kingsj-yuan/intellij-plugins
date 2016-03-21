package com.jetbrains.lang.dart.ide.refactoring;

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.ui.NameSuggestionsField;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.*;
import com.intellij.util.SmartList;
import com.intellij.xml.util.XmlStringUtil;
import com.intellij.xml.util.XmlTagUtilBase;
import com.jetbrains.lang.dart.DartBundle;
import com.jetbrains.lang.dart.DartComponentType;
import com.jetbrains.lang.dart.assists.AssistUtils;
import com.jetbrains.lang.dart.ide.findUsages.DartServerFindUsagesHandler;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.dartlang.analysis.server.protocol.SourceChange;
import org.dartlang.analysis.server.protocol.SourceEdit;
import org.dartlang.analysis.server.protocol.SourceFileEdit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class DartRenameDialog extends ServerRefactoringDialog<ServerRenameRefactoring> {
  private final JLabel myNewNamePrefix = new JLabel("");
  private NameSuggestionsField myNameSuggestionsField;

  DartRenameDialog(@NotNull final Project project, @Nullable final Editor editor, @NotNull final ServerRenameRefactoring refactoring) {
    super(project, editor, refactoring);

    setTitle("Rename " + refactoring.getElementKindName());
    createNewNameComponent();
    init();
  }

  @Override
  protected void canRun() throws ConfigurationException {
    if (Comparing.strEqual(getNewName(), myRefactoring.getOldName())) {
      throw new ConfigurationException(null);
    }
    super.canRun();
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbConstraints = new GridBagConstraints();

    gbConstraints.insets = new Insets(0, 0, 4, 0);
    gbConstraints.weighty = 0;
    gbConstraints.weightx = 1;
    gbConstraints.gridwidth = GridBagConstraints.REMAINDER;
    gbConstraints.fill = GridBagConstraints.BOTH;
    JLabel nameLabel = new JLabel();
    panel.add(nameLabel, gbConstraints);
    nameLabel.setText(XmlStringUtil.wrapInHtml(XmlTagUtilBase.escapeString(getLabelText(), false)));

    gbConstraints.insets = new Insets(0, 0, 4, 0);
    gbConstraints.gridwidth = 1;
    gbConstraints.fill = GridBagConstraints.NONE;
    gbConstraints.weightx = 0;
    gbConstraints.gridx = 0;
    gbConstraints.anchor = GridBagConstraints.WEST;
    panel.add(myNewNamePrefix, gbConstraints);

    gbConstraints.insets = new Insets(0, 0, 8, 0);
    gbConstraints.gridwidth = 2;
    gbConstraints.fill = GridBagConstraints.BOTH;
    gbConstraints.weightx = 1;
    gbConstraints.gridx = 0;
    gbConstraints.weighty = 1;
    panel.add(myNameSuggestionsField.getComponent(), gbConstraints);

    return panel;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myNameSuggestionsField.getFocusableComponent();
  }

  private void createNewNameComponent() {
    String[] suggestedNames = getSuggestedNames();
    myNameSuggestionsField = new NameSuggestionsField(suggestedNames, myProject, FileTypes.PLAIN_TEXT, myEditor) {
      @Override
      protected boolean shouldSelectAll() {
        return myEditor == null || myEditor.getSettings().isPreselectRename();
      }
    };

    myNameSuggestionsField.addDataChangedListener(new NameSuggestionsField.DataChanged() {
      @Override
      public void dataChanged() {
        processNewNameChanged();
      }
    });
  }

  @NotNull
  private String getLabelText() {
    final String kindName = myRefactoring.getElementKindName().toLowerCase(Locale.US);
    final String name = myRefactoring.getOldName().isEmpty() ? kindName : kindName + " " + myRefactoring.getOldName();
    return RefactoringBundle.message("rename.0.and.its.usages.to", name);
  }

  private String getNewName() {
    return myNameSuggestionsField.getEnteredName().trim();
  }

  @NotNull
  private String[] getSuggestedNames() {
    return new String[]{myRefactoring.getOldName()};
  }

  private void processNewNameChanged() {
    myRefactoring.setNewName(getNewName());
  }

  @Override
  protected boolean hasPreviewButton() {
    return true;
  }

  @Override
  protected boolean isForcePreview() {
    final Set<String> potentialEdits = myRefactoring.getPotentialEdits();
    return !potentialEdits.isEmpty() && !ApplicationManager.getApplication().isUnitTestMode();
  }

  @Override
  protected void previewRefactoring() {
    final UsageViewPresentation presentation = new UsageViewPresentation();
    presentation.setTabText(RefactoringBundle.message("usageView.tabText"));
    presentation.setShowCancelButton(true);
    presentation.setTargetsNodeText(RefactoringBundle.message("0.to.be.renamed.to.1.2", myRefactoring.getElementKindName(), "", getNewName()));
    presentation.setNonCodeUsagesString(DartBundle.message("usages.in.comments.to.rename"));
    presentation.setCodeUsagesString(DartBundle.message("usages.in.code.to.rename"));
    presentation.setDynamicUsagesString(DartBundle.message("dynamic.usages.to.rename"));
    presentation.setUsageTypeFilteringAvailable(false);

    final List<UsageTarget> usageTargets = new SmartList<UsageTarget>();
    final Map<Usage, String> usageToEditIdMap = new THashMap<Usage, String>();
    fillTargetsAndUsageToEditIdMap(usageTargets, usageToEditIdMap);

    final UsageTarget[] targets = usageTargets.toArray(new UsageTarget[usageTargets.size()]);
    final Set<Usage> usageSet = usageToEditIdMap.keySet();
    final Usage[] usages = usageSet.toArray(new Usage[usageSet.size()]);

    final UsageView usageView = UsageViewManager.getInstance(myProject).showUsages(targets, usages, presentation);

    final SourceChange sourceChange = myRefactoring.getChange();
    assert sourceChange != null;

    usageView.addPerformOperationAction(createRefactoringRunnable(usageView, usageToEditIdMap),
                                        sourceChange.getMessage(),
                                        DartBundle.message("rename.need.reRun"),
                                        RefactoringBundle.message("usageView.doAction"), false);
  }

  private void fillTargetsAndUsageToEditIdMap(@NotNull final List<UsageTarget> usageTargets,
                                              @NotNull final Map<Usage, String> usageToEditIdMap) {
    final SourceChange change = myRefactoring.getChange();
    assert change != null;

    final PsiManager psiManager = PsiManager.getInstance(myProject);
    for (SourceFileEdit fileEdit : change.getEdits()) {
      final VirtualFile file = AssistUtils.findVirtualFile(fileEdit);
      final PsiFile psiFile = file == null ? null : psiManager.findFile(file);
      if (psiFile == null) continue;

      for (SourceEdit sourceEdit : fileEdit.getEdits()) {
        final TextRange range = TextRange.create(sourceEdit.getOffset(), sourceEdit.getOffset() + sourceEdit.getLength());
        final boolean potentialUsage = myRefactoring.getPotentialEdits().contains(sourceEdit.getId());
        final PsiElement usageElement = DartServerFindUsagesHandler.getUsagePsiElement(psiFile, range);
        if (usageElement != null) {
          if (DartComponentType.typeOf(usageElement) != null) {
            usageTargets.add(new PsiElement2UsageTargetAdapter(usageElement));
          }
          else {
            final UsageInfo usageInfo = DartServerFindUsagesHandler.getUsageInfo(usageElement, range, potentialUsage);
            if (usageInfo != null) {
              usageToEditIdMap.put(new UsageInfo2UsageAdapter(usageInfo), sourceEdit.getId());
            }
          }
        }
      }
    }
  }

  @NotNull
  private Runnable createRefactoringRunnable(@NotNull final UsageView usageView, @NotNull final Map<Usage, String> usageToEditIdMap) {
    return new Runnable() {
      @Override
      public void run() {
        final Set<String> excludedIds = new THashSet<String>();

        // usageView.getExcludedUsages() and usageView.getUsages() doesn't contain deleted usages, that's why we need to start with full set usageToEditIdMap.keySet()
        final Set<Usage> excludedUsages = new THashSet<Usage>(usageToEditIdMap.keySet());
        excludedUsages.removeAll(usageView.getUsages());
        excludedUsages.addAll(usageView.getExcludedUsages());

        for (Usage excludedUsage : excludedUsages) {
          excludedIds.add(usageToEditIdMap.get(excludedUsage));
        }

        DartRenameDialog.super.doRefactoring(excludedIds);
      }
    };
  }
}