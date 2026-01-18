package com.github.mikeburdge.propertabgroupsrider.toolWindow

import com.github.mikeburdge.propertabgroupsrider.MyBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel


class ProperTabGroupsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val ui = ProperTabGroupsToolWindowPanel(project)

        val content = ContentFactory.getInstance().createContent(ui, null, false)

        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    private class ProperTabGroupsToolWindowUI(private val project: Project) {

        val content: JComponent = buildRoot()

        private fun buildRoot(): JComponent {
            val root = JBPanel<JBPanel<*>>(BorderLayout())
            val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                val title = JBLabel(MyBundle.message("toolWindow.title")).apply {
                    font = font.deriveFont(Font.BOLD)
                }
                add(title, BorderLayout.WEST)
            }

            val searchField = JBTextField().apply {
                emptyText.text = MyBundle.message("toolWindow.search.placeholder")
            }

            val top = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(header)
                add(searchField)
            }


            val contentPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(sectionHeader(MyBundle.message("toolWindow.groups.header")))
                add(placeholderBlock("Groups list will be here"))

                add(sectionHeader(MyBundle.message("toolWindow.unassigned.header")))
                add(placeholderBlock("Unassigned opened and ungrouped list will be here"))
            }
            root.add(top, BorderLayout.NORTH)
            root.add(JBScrollPane(contentPanel), BorderLayout.CENTER)

            return root
        }

        private fun sectionHeader(string: String): JComponent {
            return JBLabel(string).apply {
                font = font.deriveFont(Font.BOLD)
            }
        }

        private fun placeholderBlock(string: String): JComponent {
            return JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JBLabel(string), BorderLayout.CENTER)
            }
        }

    }
}
