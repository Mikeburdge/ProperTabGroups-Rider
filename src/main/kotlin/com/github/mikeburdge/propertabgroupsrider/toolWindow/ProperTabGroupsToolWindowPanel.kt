package com.github.mikeburdge.propertabgroupsrider.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellEditor
import javax.swing.tree.TreePath

class ProperTabGroupsToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private sealed class NodeData {
        data class GroupHeader(val id: UUID, val name: String) : NodeData()
        data object UnassignedHeader : NodeData()

        data class FileItem(
            val fileUrl: String, val displayName: String, val parentGroupId: UUID? // null means unassigned
        ) : NodeData()
    }

    private data class Group(val id: UUID, val name: String)

    // used to choose which file of the ones in every group I want to highlight (it will be the most recently clicked)
    private var preferredActiveLocation: Pair<String, UUID?>? = null
    private var hoveredTab: Pair<String, UUID?>? = null

    private val groups: MutableList<Group> = mutableListOf(
        Group(UUID.randomUUID(), "Gameplay"),
        Group(UUID.randomUUID(), "UI"),
    )

    private val membershipMappingByUrl: MutableMap<String, MutableSet<UUID>> = mutableMapOf()

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = object : DefaultTreeModel(rootNode) {
        override fun valueForPathChanged(path: TreePath, newValue: Any) {
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
            val data = node.userObject

            if (data is NodeData.GroupHeader) {
                val trimmed = newValue?.toString()?.trim().orEmpty()
                if (trimmed.isNotBlank()) {
                    renameGroup(data.id, trimmed)
                } else {
                    rebuildTree()
                }
            }


        }
    }

    private var filterText: String = ""

    private var activeFileUrl: String? = null

    private val searchField = SearchTextField()

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true

        emptyText.text = "toolWindow.empty"

        TreeUIHelper.getInstance().installTreeSpeedSearch(this)
    }

    private val toolbarComponent: JComponent = createToolbar()

    init {
        tree.model = treeModel

        tree.cellRenderer = ProperTabTreeRenderer()

        installInlineGroupRenaming()
        installRemoveHoverTracking()

        val topBar = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.CENTER)
            add(toolbarComponent, BorderLayout.EAST)
        }

        add(topBar, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        // document listener
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterText = searchField.text
                rebuildTree()
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {

                if (e.button != MouseEvent.BUTTON1) {
                    return
                }

                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject

                if (e.clickCount == 1 && data is NodeData.FileItem) {
                    val bIsHovered = hoveredTab == (data.fileUrl to data.parentGroupId)
                    if (bIsHovered && data.parentGroupId != null && isClickOnRemoveX(path, data, e))
                    {
                        removeFileFromGroup(data.fileUrl, data.parentGroupId)
                    }
                }

                if (data is NodeData.FileItem) {
                    preferredActiveLocation =
                        data.fileUrl to data.parentGroupId // store the last item and group we clicked

                    val virtualFile = VirtualFileManager.getInstance().findFileByUrl(data.fileUrl) ?: return
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }

            private fun removeFileFromGroup(fileUrl: String, groupId: UUID) {
                membershipMappingByUrl[fileUrl]?.remove(groupId)

                if (membershipMappingByUrl[fileUrl]?.isEmpty() == true)
                {
                    membershipMappingByUrl.remove(fileUrl)
                }
            }
        })

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {

                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    scheduleRebuildTree()
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    scheduleRebuildTree()
                }


                override fun selectionChanged(event: FileEditorManagerEvent) {
                    activeFileUrl = event.newFile?.url
                    selectActiveFileInTree()
                    tree.repaint()
                }
            })
        // todo: drag and drop

        rebuildTree()
    }

    private fun installRemoveHoverTracking() {
        tree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y)
                val node = path?.lastPathComponent as? DefaultMutableTreeNode
                val data = node?.userObject

                val new = if (data is NodeData.FileItem) {
                    data.fileUrl to data.parentGroupId
                } else {
                    null
                }

                if (hoveredTab != new) {
                    hoveredTab = new
                    tree.repaint()
                }
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent?) {
                if (hoveredTab != null) {
                    hoveredTab = null
                    tree.repaint()
                }
            }
        })
    }

    // essentially we're allowing the tree to allow groups to be renamed. this is probably a bit funky but ah well
    private fun installInlineGroupRenaming() {
        tree.isEditable = true
        tree.invokesStopCellEditing = true

        tree.cellEditor = object : AbstractCellEditor(), TreeCellEditor {
            private val field = JTextField()

            override fun getTreeCellEditorComponent(
                tree: JTree?, value: Any?, isSelected: Boolean, expanded: Boolean, leaf: Boolean, row: Int
            ): Component {
                val node = value as? DefaultMutableTreeNode
                val data = node?.userObject as? NodeData.GroupHeader

                field.text = data?.name ?: ""

                SwingUtilities.invokeLater {
                    field.selectAll()
                    field.requestFocusInWindow()
                }
                return field
            }

            override fun getCellEditorValue(): Any? {
                return field.text
            }

            override fun isCellEditable(e: EventObject?): Boolean {

                if (allowProgrammaticRenameOnce) {
                    allowProgrammaticRenameOnce = false
                    return true
                }

                val mouseEvent = e as? MouseEvent ?: return false
                if (mouseEvent.clickCount != 2) return false
                val path = this@ProperTabGroupsToolWindowPanel.tree.getPathForLocation(mouseEvent.x, mouseEvent.y)
                    ?: return false
                val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
                val data = selectedNode.userObject as? NodeData.GroupHeader ?: return false

                return isClickOnGroupText(path, data, mouseEvent)
            }
        }
    }

    //  ok so I found this online, hopefully it works. I imagine its going to fail when people zoom in and zoom out,
    //  so I might scrap this feature or find a more solid way of doing it
    private fun isClickOnGroupText(
        path: TreePath, group: NodeData.GroupHeader, e: MouseEvent
    ): Boolean {
        val bounds = tree.getPathBounds(path) ?: return false

        val iconWidth = 16
        val gap = 4

        val textStartX = bounds.x + iconWidth + gap

        val fm = tree.getFontMetrics(tree.font)
        val textWidth = fm.stringWidth(group.name)
        val textEndX = textStartX + textWidth

        return e.x in textStartX..textEndX
    }

    // if the above hack is fine then so is this lol
    private fun isClickOnRemoveX(path: TreePath, item: NodeData.FileItem, e: MouseEvent): Boolean {
        val bounds = tree.getPathBounds(path) ?: return false

        val iconWidth = 16
        val gap = 4
        val gapBeforeRemove = 12

        val fm = tree.getFontMetrics(tree.font)

        val textStartX = bounds.x + iconWidth + gap
        val textWidth = fm.stringWidth(item.displayName)

        val removeText = "✕"
        val removeWidth = fm.stringWidth(removeText)

        val removeStartX = textStartX + textWidth + gap
        val removeEndX = removeStartX + removeWidth

        return e.x in removeStartX..removeEndX
    }

    private fun renameGroup(groupId: UUID, newName: String) {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return
        groups[index] = groups[index].copy(name = newName)

        rebuildTree()

        findTreePathForGroupId(groupId)?.let { path ->
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private inner class ProperTabTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val data = node.userObject

            when (data) {
                is NodeData.GroupHeader -> {
                    icon = AllIcons.Nodes.Folder
                    append(data.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }

                is NodeData.UnassignedHeader -> {
                    icon = AllIcons.General.InspectionsOK
                    append("Unassigned", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }

                is NodeData.FileItem -> {
                    val bIsActive = data.fileUrl == activeFileUrl

                    val baseIcon = AllIcons.FileTypes.Any_type
                    icon = if (bIsActive) {
                        badgeIcon(baseIcon, AllIcons.Actions.Checked)
                    } else {
                        baseIcon
                    }

                    val attributes = if (bIsActive) {
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }

                    if (bIsActive) {
                        append(
                            "▶ ",
                            SimpleTextAttributes.REGULAR_ATTRIBUTES
                        ) // Apparently it accepts ascii text for this lol
                    }

                    append(data.displayName, attributes)

                    val bIsHovered = hoveredTab == (data.fileUrl to data.parentGroupId)
                    val bCanRemove = data.parentGroupId != null
                    if (bIsHovered && bCanRemove) {
                        append("   ✕", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }

                else -> {
                    append(data?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        private fun badgeIcon(base: Icon, badge: Icon): Icon {
            val layered = LayeredIcon(2)
            layered.setIcon(base, 0)
            layered.setIcon(badge, 1) // overlay
            return layered
        }
    }

    /**********************************************************

    Components

     ********************************************************/

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(AddGroupAction())
            add(DeleteGroupAction())
            addSeparator()
            add(AssignGroupsAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ProperTabGroupsToolbar", group, true)

        toolbar.targetComponent = this
        return toolbar.component
    }

    private inner class AddGroupAction : AnAction(
        "Add Group", "Create a new group", AllIcons.General.Add
    ) {
        override fun actionPerformed(p0: AnActionEvent) {
            addNewGroupAndRename()
        }
    }

    private inner class DeleteGroupAction : AnAction(
        "Delete Group", "Delete selected group", AllIcons.General.Remove
    ) {
        override fun actionPerformed(p0: AnActionEvent) {
            deleteSelectedGroup()
        }

        override fun update(e: AnActionEvent) {
            val selected = getSelectedNodeData()
            e.presentation.isEnabled = selected is NodeData.GroupHeader
        }
    }

    private inner class AssignGroupsAction : AnAction(
        "Assign to Groups...",
        "Add this tab to one or more groups",
        AllIcons.Actions.Edit
    ) {
        override fun actionPerformed(p0: AnActionEvent) {
            showAssignGroupsPopupForSelectedTab()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = getSelectedNodeData() is NodeData.FileItem
        }
    }

    private fun showAssignGroupsPopupForSelectedTab() {
        val selected = getSelectedNodeData() as? NodeData.FileItem ?: return
        val current = membershipMappingByUrl[selected.fileUrl] ?: emptySet()

        val popup = AssignGroupsPopup(
            project = project, fileName = selected.displayName, groups = groups.toList(), initialSelection = current
        )

        if (!popup.showAndGet()) {
            return
        }

        val chosen = popup.selectedGroupIds

        if (chosen.isEmpty()) {
            membershipMappingByUrl.remove(selected.fileUrl)
        } else {
            membershipMappingByUrl[selected.fileUrl] = chosen.toMutableSet()
        }

        rebuildTree()
    }

    private class AssignGroupsPopup(
        project: Project, fileName: String, private val groups: List<Group>, initialSelection: Set<UUID>
    ) : DialogWrapper(project, true) {
        private val checkBoxes: Map<UUID, JBCheckBox> =
            groups.associate { it.id to JBCheckBox(it.name, initialSelection.contains(it.id)) }

        var selectedGroupIds: Set<UUID> = emptySet()

        init {
            title = "Assign \"$fileName\" to Groups"
            init()
        }


        override fun createCenterPanel(): JComponent {
            val root = JPanel(BorderLayout(0, 10)).apply {
                border = JBUI.Borders.empty(10)
            }

            root.add(JBLabel("Select the groups this tab should be assigned to:"), BorderLayout.NORTH)

            val listPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            groups.forEach { g ->
                listPanel.add(checkBoxes[g.id])
            }

            val scroll = JBScrollPane(listPanel).apply {
                preferredSize = Dimension(360, 240)
            }

            root.add(scroll, BorderLayout.CENTER)

            val hint = JBLabel("Leave everything unchecked for Unassigned").apply {
                foreground = JBColor.GRAY
            }
            root.add(hint, BorderLayout.SOUTH)

            return root
        }

        override fun doOKAction() {
            selectedGroupIds = checkBoxes.filterValues { it.isSelected }.keys

            super.doOKAction()
        }
    }


    private data class ExpansionState(
        val expandedGroupIds: Set<UUID>,
        val unassignedExpanded: Boolean
    )

    private fun recordExpansionState(): ExpansionState {
        val expandedGroups = mutableSetOf<UUID>()
        var unassignedExpanded = false
        val rootPath = TreePath(rootNode)
        val expanded = tree.getExpandedDescendants(rootPath) ?: return ExpansionState(emptySet(), false)

        val it = expanded.iterator()
        while (it.hasNext()) {
            val path = it.next()
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue

            when (val data = node.userObject) {
                is NodeData.GroupHeader -> expandedGroups.add(data.id)
                is NodeData.UnassignedHeader -> unassignedExpanded = true
            }
        }

        return ExpansionState(expandedGroups, unassignedExpanded)
    }

    private fun restoreExpansionState(state: ExpansionState, forceExpandGroupId: UUID? = null) {
        if (state.unassignedExpanded) {
            findTreePathForUnassigned()?.let {
                tree.expandPath(it)
            }
        }

        for (id in state.expandedGroupIds) {
            findTreePathForGroupId(id)?.let {
                tree.expandPath(it)
            }
        }

        // I think when I get a settings menu going, I'll put this in the settings: Do we want to auto-expand the
        // collapsed groups when they have a tab added to them?
        forceExpandGroupId?.let { id ->
            findTreePathForGroupId(id)?.let {
                tree.expandPath(it)
            }
        }
    }

    private fun findTreePathForUnassigned(): TreePath? {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return null

        fun dfs(node: DefaultMutableTreeNode, path: TreePath): TreePath? {
            if (node.userObject is NodeData.UnassignedHeader) {
                return path
            }

            val children = node.children().iterator()
            while (children.hasNext()) {
                val child = children.next() as DefaultMutableTreeNode
                val result = dfs(child, path.pathByAddingChild(child))
                if (result != null) return result
            }

            return null
        }
        return dfs(root, TreePath(root))
    }

    private var allowProgrammaticRenameOnce = false

    private fun addNewGroupAndRename() {
        val newGroup = Group(UUID.randomUUID(), "New Group")
        groups.add(newGroup)

        rebuildTree()

        val path = findTreePathForGroupId(newGroup.id) ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        SwingUtilities.invokeLater {
            allowProgrammaticRenameOnce = true
            tree.requestFocusInWindow()
            tree.startEditingAtPath(path)
        }
    }

    private fun deleteSelectedGroup() {
        val selected = getSelectedNodeData() as? NodeData.GroupHeader ?: return

        groups.removeIf { it.id == selected.id }
        for ((_, set) in membershipMappingByUrl) {
            set.remove(selected.id)
        }

        rebuildTree()
    }

    private fun getSelectedNodeData(): NodeData? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null

        return node.userObject as? NodeData
    }

    private fun rebuildTree(forceExpandGroupId: UUID? = null) {

        val expansionState = recordExpansionState()

        rootNode.removeAllChildren()

        // handle member tabs
        for (group in groups) {
            val groupNode = DefaultMutableTreeNode(NodeData.GroupHeader(group.id, group.name))

            val memberUrls = membershipMappingByUrl.asSequence().filter { (_, groupIds) -> groupIds.contains(group.id) }
                .map { (url, _) -> url }.toList()

            for (url in memberUrls) {
                groupNode.add(
                    DefaultMutableTreeNode(
                        NodeData.FileItem(
                            fileUrl = url, displayName = displayNameFromUrl(url), parentGroupId = group.id
                        )
                    )
                )
            }

            filterNode(groupNode)?.let { rootNode.add(it) }
        }

        val openFiles = FileEditorManager.getInstance(project).openFiles

        val unassignedNode = DefaultMutableTreeNode(NodeData.UnassignedHeader)
        val unassignedFiles = openFiles.filter { file ->
            membershipMappingByUrl[file.url].isNullOrEmpty()
        }

        // handle unassigned tabs
        for (file in unassignedFiles) {
            unassignedNode.add(
                DefaultMutableTreeNode(
                    NodeData.FileItem(
                        fileUrl = file.url, displayName = file.name, parentGroupId = null
                    )
                )
            )
        }

        // filter unassigned. replaced the if statement with a "safe access expression"
        filterNode(unassignedNode)?.let { rootNode.add(it) }

        treeModel.reload()


        SwingUtilities.invokeLater {
            restoreExpansionState(expansionState, forceExpandGroupId)
        }

        // this is also probably where I highlight/ select the active tab
    }

    private fun filterNode(node: DefaultMutableTreeNode): DefaultMutableTreeNode? {
        val trimmedFilterText = filterText.trim()
        if (trimmedFilterText.isEmpty()) return node

        val data = node.userObject
        val selfMatches = nodeMatchesFilter(data, trimmedFilterText)

        val treeCopy = DefaultMutableTreeNode(data)

        if (node.childCount == 0) {
            return if (selfMatches) treeCopy else null
        }


        val it = node.children().iterator()

        while (it.hasNext()) {
            val child = it.next() as DefaultMutableTreeNode
            val filteredChild = filterNode(child)

            if (filteredChild != null) {
                treeCopy.add(filteredChild)
            }

        }

        return if (selfMatches || treeCopy.childCount > 0) treeCopy else null
    }

    private fun nodeMatchesFilter(data: Any?, trimmedFilterText: String): Boolean {
        return when (data) {
            is NodeData.GroupHeader -> data.name.contains(trimmedFilterText, ignoreCase = true)
            is NodeData.UnassignedHeader -> "Unassigned".contains(trimmedFilterText, ignoreCase = true)
            is NodeData.FileItem -> data.displayName.contains(trimmedFilterText, ignoreCase = true)
            else -> false
        }
    }

    private fun displayNameFromUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        val lastSlash =
            maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\')) // this should handle the last slash issues
        return if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed
    }

    private fun selectActiveFileInTree() {
        val url = activeFileUrl ?: return
        val preferred = preferredActiveLocation

        val path = if (preferred != null && preferred.first == url) {
            findTreePathForGroupId(url, preferred.second) ?: findTreePathForFileUrl(url)
        } else {
            findTreePathForFileUrl(url)
        }

        if (path == null) {
            tree.clearSelection()
            return
        }

        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }


    private fun scheduleRebuildTree() {
        if (SwingUtilities.isEventDispatchThread()) {
            rebuildTree()
        } else {
            ApplicationManager.getApplication().invokeLater {
                rebuildTree()
            }
        }
    }

    private fun findTreePathForFileUrl(targetUrl: String): TreePath? {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return null

        // depth-first search function to find the best tree path
        fun dfs(node: DefaultMutableTreeNode, path: TreePath): TreePath? {
            val data = node.userObject
            if (data is NodeData.FileItem && data.fileUrl == targetUrl) {
                return path
            }

            val children = node.children().iterator()
            while (children.hasNext()) {
                val child = children.next() as DefaultMutableTreeNode
                val result = dfs(child, path.pathByAddingChild(child))
                if (result != null) return result
            }

            return null
        }
        return dfs(root, TreePath(root))
    }

    private fun findTreePathForGroupId(groupId: UUID): TreePath? {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return null

        // depth-first search function to find the best tree path
        fun dfs(node: DefaultMutableTreeNode, path: TreePath): TreePath? {
            val data = node.userObject
            if (data is NodeData.GroupHeader && data.id == groupId) {
                return path
            }

            val children = node.children().iterator()
            while (children.hasNext()) {
                val child = children.next() as DefaultMutableTreeNode
                val result = dfs(child, path.pathByAddingChild(child))
                if (result != null) return result
            }

            return null
        }
        return dfs(root, TreePath(root))
    }

    private fun findTreePathForGroupId(url: String, groupId: UUID?): TreePath? {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return null

        fun dfs(node: DefaultMutableTreeNode, path: TreePath): TreePath? {
            val data = node.userObject
            if (data is NodeData.FileItem && data.fileUrl == url && data.parentGroupId == groupId) {
                return path
            }

            val children = node.children().iterator()
            while (children.hasNext()) {
                val child = children.next() as DefaultMutableTreeNode
                val result = dfs(child, path.pathByAddingChild(child))
                if (result != null) return result
            }

            return null
        }
        return dfs(root, TreePath(root))
    }

    override fun dispose() {}
}





































