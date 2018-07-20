// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.dvcs.ui

import com.intellij.dvcs.DvcsUtil.getShortRepositoryName
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl
import com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI.scale
import com.intellij.vcs.log.impl.VcsLogManager.findLogProviders
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.VcsLogColorManagerImpl
import com.intellij.vcs.log.ui.VcsLogColorManagerImpl.getBackgroundColor

private val ICON_SIZE = scale(14)

class RepositoryChangesBrowserNode(repository: Repository) : ChangesBrowserNode<Repository>(repository) {
  private val colorManager = getColorManager(repository.project)

  override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
    renderer.icon = ColorIcon(ICON_SIZE, getBackgroundColor(colorManager.getRootColor(getUserObject().root)))
    renderer.append(" $textPresentation", REGULAR_ATTRIBUTES)
    appendCount(renderer)
  }

  override fun getSortWeight() = REPOSITORY_SORT_WEIGHT

  override fun compareUserObjects(o2: Repository) = getShortRepositoryName(getUserObject()).compareTo(getShortRepositoryName(o2), true)

  override fun getTextPresentation() = getShortRepositoryName(getUserObject())

  companion object {
    fun getColorManager(project: Project) = VcsProjectLog.getInstance(project).logManager?.colorManager ?: VcsLogColorManagerImpl(
      findLogProviders(ProjectLevelVcsManagerImpl.getInstance(project).allVcsRoots.asList(), project).keys)
  }
}