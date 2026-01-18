package com.github.mikeburdge.propertabgroupsrider.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory


class ProperTabGroupsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        try {
            val ui = ProperTabGroupsToolWindowPanel(project)
            val content = ContentFactory.getInstance().createContent(ui, null, false)
            toolWindow.contentManager.addContent(content)
        } catch (t: Throwable) {
            val fallback = JBLabel("ProperTabGroups failed to load: ${t.message}")
            val content = ContentFactory.getInstance().createContent(fallback, null, false)
            toolWindow.contentManager.addContent(content)
            t.printStackTrace()
        }
    }

    override fun shouldBeAvailable(project: Project) = true
}
