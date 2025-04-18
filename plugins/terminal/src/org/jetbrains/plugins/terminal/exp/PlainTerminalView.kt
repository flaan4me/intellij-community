// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.exp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.jediterm.core.util.TermSize
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent

class PlainTerminalView(
  project: Project,
  private val session: TerminalSession,
  settings: JBTerminalSystemSettingsProviderBase
) : TerminalContentView {
  override val component: JComponent
    get() = view.component
  override val preferredFocusableComponent: JComponent
    get() = view.preferredFocusableComponent

  private val view: SimpleTerminalView

  init {
    val eventsHandler = TerminalEventsHandler(session, settings)
    view = SimpleTerminalView(project, settings, session, eventsHandler)
    view.component.addComponentListener(object : ComponentAdapter() {
      override fun componentResized(e: ComponentEvent?) {
        val newSize = getTerminalSize() ?: return
        session.postResize(newSize)
      }
    })

    Disposer.register(this, view)
  }

  // return preferred size of the terminal calculated from the component size
  override fun getTerminalSize(): TermSize? {
    if (view.component.bounds.isEmpty) return null
    val contentSize = Dimension(view.terminalWidth, view.component.height)
    return TerminalUiUtils.calculateTerminalSize(contentSize, view.charSize)
  }

  override fun isFocused(): Boolean {
    return view.isFocused()
  }

  override fun dispose() {
  }
}