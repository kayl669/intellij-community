// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.openapi.actionSystem.ex;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.util.ui.ThreeStateCheckBox;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public abstract class ThreeStateCheckboxAction extends AnAction implements CustomComponentAction {
  protected ThreeStateCheckboxAction() {}

  protected ThreeStateCheckboxAction(final String text) {
    super(text);
  }

  protected ThreeStateCheckboxAction(final String text, final String description, final Icon icon) {
    super(text, description, icon);
  }

  @NotNull
  public abstract ThreeStateCheckBox.State isSelected(AnActionEvent e);

  public abstract void setSelected(AnActionEvent e, @NotNull ThreeStateCheckBox.State state);


  @Override
  public final void actionPerformed(@NotNull final AnActionEvent e) {
    ThreeStateCheckBox.State state = isSelected(e);
    setSelected(e, ThreeStateCheckBox.nextState(state, true));
  }

  @Override
  public void update(final AnActionEvent e) {
    ThreeStateCheckBox.State state = isSelected(e);

    Presentation presentation = e.getPresentation();
    Object property = presentation.getClientProperty(CUSTOM_COMPONENT_PROPERTY);
    if (property instanceof ThreeStateCheckBox) {
      ThreeStateCheckBox checkBox = (ThreeStateCheckBox)property;

      updateCustomComponent(checkBox, presentation);
      checkBox.setState(state);
    }
  }

  @Override
  public JComponent createCustomComponent(Presentation presentation) {
    // this component cannot be stored right here because of action system architecture:
    // one action can be shown on multiple toolbars simultaneously
    ThreeStateCheckBox checkBox = new ThreeStateCheckBox();
    updateCustomComponent(checkBox, presentation);
    return CheckboxAction.createCheckboxComponent(checkBox, this);
  }

  protected void updateCustomComponent(JCheckBox checkBox, Presentation presentation) {
    CheckboxAction.updateCheckboxPresentation(checkBox, presentation);
  }
}
