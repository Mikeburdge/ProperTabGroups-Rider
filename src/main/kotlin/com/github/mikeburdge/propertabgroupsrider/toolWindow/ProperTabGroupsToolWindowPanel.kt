package com.github.mikeburdge.propertabgroupsrider.toolWindow

import com.github.mikeburdge.propertabgroupsrider.services.ProperTabGroupsStateService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
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
import java.awt.*
import java.awt.event.*
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.*


class ProperTabGroupsToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private sealed class NodeData {
        data class GroupHeader(val id: UUID, val name: String) : NodeData()
        data object UnassignedHeader : NodeData()

        data class FileItem(
            val fileUrl: String, val displayName: String, val parentGroupId: UUID? // null means unassigned
        ) : NodeData()
    }

    private data class Group(val id: UUID, val name: String)

    // used to choose which file of the ones in every group I want to highlight (it will be the most recently 1clicked)
    private var preferredActiveLocation: Pair<String, UUID?>? = null

    private var hoveredTab: Pair<String, UUID?>? = null // which tab is hovered
    private var hoveredFileItem: NodeData.FileItem? = null // data for the action stuff
    private var hoveredRowBounds: Rectangle? = null // positioning
    private var removeTarget: NodeData.FileItem? = null

    // Persistence Stuff
    private val stateService = project.service<ProperTabGroupsStateService>()
    private var hasInitExpansion = false

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
                val trimmed = newValue.toString().trim()
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

    private val tree = object : Tree(treeModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {

        isRootVisible = false

        showsRootHandles = true

        emptyText.text = "toolWindow.empty"

        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION

        TreeUIHelper.getInstance().installTreeSpeedSearch(this)
    }

private val globalMouseWatcher = java.awt.event.AWTEventListener { event ->

    if (event !is MouseEvent) {
        return@AWTEventListener
    }
    val isRelevant = removeRowToolbar.component.isVisible || hoveredFileItem != null || removeTarget != null

    if (!isRelevant) {
        return@AWTEventListener
    }
    if (!this@ProperTabGroupsToolWindowPanel.isShowing) {
        return@AWTEventListener
    }

    val pointer = MouseInfo.getPointerInfo()?.location
    if (pointer == null) {
        clearHoverState()
        return@AWTEventListener
    }

    val point = Point(pointer)
    SwingUtilities.convertPointFromScreen(point, this@ProperTabGroupsToolWindowPanel)

    val isInsidePanel = point.x >= 0 && point.y >=0 && point.x < this@ProperTabGroupsToolWindowPanel.width && point.y < this@ProperTabGroupsToolWindowPanel.height

    if (!isInsidePanel) {
        clearHoverState()
        return@AWTEventListener
    }

    SwingUtilities.invokeLater { refreshHoverFromMousePointer() }
}

    private var globalMouseWatcherInstalled = false

    private fun installGlobalMouseWatcher(){
        if (globalMouseWatcherInstalled) {
            return
        }
        Toolkit.getDefaultToolkit()
            .addAWTEventListener(globalMouseWatcher, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
        globalMouseWatcherInstalled= true
    }

    private fun uninstallGlobalMouseWatcher() {
        if (!globalMouseWatcherInstalled) {
            return
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener { globalMouseWatcher }
        globalMouseWatcherInstalled = false
    }

    override fun addNotify() {
        super.addNotify()
        installGlobalMouseWatcher()
    }

    override fun removeNotify() {
        uninstallGlobalMouseWatcher()
        super.removeNotify()
    }

    private inner class RemoveFromGroupAction : AnAction(
        "Remove from group",
        "Remove this tab from the group",
        AllIcons.Actions.Close
    ) {
        override fun actionPerformed(p0: AnActionEvent) {
            val item = removeTarget ?: return
            val groupId = item.parentGroupId ?: return

            membershipMappingByUrl[item.fileUrl]?.remove(groupId)
            if (membershipMappingByUrl[item.fileUrl]?.isEmpty() == true) {
                membershipMappingByUrl.remove(item.fileUrl)
            }

            persistModelOnly()
            rebuildTree()

            SwingUtilities.invokeLater { refreshHoverFromMousePointer() }
        }
    }

    private val removeRowToolbar = ActionManager.getInstance().createActionToolbar(
        "ProperTabGroupsRowRemove",
        DefaultActionGroup(RemoveFromGroupAction()),
        true
    ).apply {
        targetComponent = tree
        component.isOpaque = false
        component.border = JBUI.Borders.empty()
    }
    private val toolbarComponent: JComponent = createToolbar()
    private val treeScrollPane = JBScrollPane(tree)

    init {
        tree.model = treeModel

        tree.cellRenderer = ProperTabTreeRenderer()

        tree.layout = null
        tree.add(removeRowToolbar.component)
        removeRowToolbar.component.isVisible = false

        installInlineGroupRenaming()
        installRemoveHoverTracking()

        val topBar = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.CENTER)
            add(toolbarComponent, BorderLayout.EAST)
        }

        add(topBar, BorderLayout.NORTH)
        add(treeScrollPane, BorderLayout.CENTER)

        treeScrollPane.viewport.addChangeListener {
            SwingUtilities.invokeLater { refreshHoverFromMousePointer() }
        }


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

                val closestRow = tree.getClosestRowForLocation(e.x, e.y)
                if (closestRow < 0) {
                    return
                }

                val rowBounds = tree.getRowBounds(closestRow)

                if (e.y !in rowBounds.y until (rowBounds.y + rowBounds.height)) {

                    return
                }

                val path = tree.getPathForRow(closestRow) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject

                if (e.clickCount == 2 && data is NodeData.FileItem) {
                    preferredActiveLocation =
                        data.fileUrl to data.parentGroupId // store the last item and group we clicked

                    val virtualFile = VirtualFileManager.getInstance().findFileByUrl(data.fileUrl) ?: return
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
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

        // persistence
        loadPersistentState()
        installExpansionPersistenceTracking()

        rebuildTree()
    }

    private fun clearHoverState() {
        hoveredTab = null
        hoveredFileItem = null
        hoveredRowBounds = null
        removeTarget = null

        removeRowToolbar.component.isVisible = false

        tree.cursor = Cursor.getDefaultCursor()
        tree.repaint()
    }
    private fun installRemoveHoverTracking() {

        tree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                updateHoverFromTreePoint(e.x, e.y)
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent?) {
                SwingUtilities.invokeLater { refreshHoverFromMousePointer() }
            }
        })

        removeRowToolbar.component.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val point = SwingUtilities.convertPoint(removeRowToolbar.component, e.point, tree)
                updateHoverFromTreePoint(point.x, point.y)
            }
        })

        removeRowToolbar.component.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent?) {
                SwingUtilities.invokeLater { refreshHoverFromMousePointer() }
            }
        })

        this@ProperTabGroupsToolWindowPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent?) {
                clearHoverState()
            }
        })

        treeScrollPane.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent?) {
                clearHoverState()
            }
        })

        isFocusable = true
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                clearHoverState()
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

    private fun updateHoverFromTreePoint(x: Int, y: Int) {

        var newHover: Pair<String, UUID?>? = null
        var newItem: NodeData.FileItem? = null
        var newBounds: Rectangle? = null

        val closestRow = tree.getClosestRowForLocation(x, y)
        if (closestRow >= 0) {
            val rowBounds = tree.getRowBounds(closestRow)
            if (rowBounds != null && y in rowBounds.y until (rowBounds.y + rowBounds.height)) {

                val path = tree.getPathForRow(closestRow)
                val node = path?.lastPathComponent as? DefaultMutableTreeNode
                val data = node?.userObject

                if (data is NodeData.FileItem) {
                    newHover = data.fileUrl to data.parentGroupId
                    newItem = data
                    newBounds = rowBounds
                }
            }
        }
        val changed = (hoveredTab != newHover) ||
                (hoveredFileItem != newItem) ||
                (hoveredRowBounds != newBounds)


        if (!changed) return

        hoveredTab = newHover
        hoveredFileItem = newItem
        hoveredRowBounds = newBounds

        updateRemoveButtonOverlay()
        tree.repaint()
    }

    private val removeButtonWidth = JBUI.scale(22)
    private val removeButtonMargin = JBUI.scale(10)

    private fun updateRemoveButtonOverlay() {
        val comp = removeRowToolbar.component

        val item = hoveredFileItem
        val bounds = hoveredRowBounds
        if (item == null || bounds == null || item.parentGroupId == null) {
            removeTarget = null
            comp.isVisible = false
            return
        }

        removeTarget = item

        val visRect = tree.visibleRect

        val prefWidth = comp.preferredSize.width
        val width = maxOf(prefWidth, JBUI.scale(24))
        val height = bounds.height

        val x = visRect.x + visRect.width - width - JBUI.scale(20) // add indent here as necessary
        val y = bounds.y

        if (comp.x != x || comp.y != y || comp.width != removeButtonWidth || comp.height != bounds.height)
            comp.setBounds(x, y, width, height)
        comp.isVisible = true
//    comp.revalidate()
        comp.repaint()
    }

    private fun refreshHoverFromMousePointer() {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        val point = Point(pointer)

        SwingUtilities.convertPointFromScreen(point, tree)
        updateHoverFromTreePoint(point.x, point.y)
    }

    private fun renameGroup(groupId: UUID, newName: String) {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return
        groups[index] = groups[index].copy(name = newName)
        persistModelOnly()
        rebuildTree()

        findTreePathForGroupId(groupId)?.let { path ->
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private inner class ProperTabTreeRenderer : TreeCellRenderer {

        private val leftSideRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
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
                    }

                    else -> {
                        append(data?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }

        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            leftSideRenderer.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            return leftSideRenderer
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
            add(MoveSelectedTabsAction())
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

    private inner class MoveSelectedTabsAction : AnAction(
        "Move Tabs...",
        "Move selected tabs to group",
        AllIcons.Actions.MoveTo2
    ) {
        override fun actionPerformed(p0: AnActionEvent) {
            showAssignGroupsPopupForSelectedTabs()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = getSelectedFileItems().isNotEmpty()
        }
    }

    private fun showAssignGroupsPopupForSelectedTabs() {

        val selectedTabs = getSelectedFileItems().distinctBy { it.fileUrl }
        if (selectedTabs.isEmpty()) {
            return
        }


        val label = if (selectedTabs.size == 1) {
            selectedTabs.first().displayName
        } else {
            "${selectedTabs.size} tabs"
        }

        val initialSelection: Set<UUID> = selectedTabs
            .asSequence().flatMap { membershipMappingByUrl[it.fileUrl].orEmpty().asSequence() }.toSet()


        val popup = AssignGroupsPopup(
            project = project,
            subjectLabel = label,
            groups = groups.toList(),
            initialSelection = initialSelection
        )

        if (!popup.showAndGet()) {
            return
        }

        val chosen = popup.selectedGroupIds


        val newlyAdded = mutableSetOf<UUID>()

        for (tab in selectedTabs) {
            val url = tab.fileUrl
            val current = membershipMappingByUrl[url].orEmpty()

            newlyAdded += (chosen - current)

            if (chosen.isEmpty()) {
                membershipMappingByUrl.remove(url)
            } else {
                membershipMappingByUrl[url] = chosen.toMutableSet()
            }
        }

        persistModelOnly()
        rebuildTree(newlyAdded)
    }


    private class AssignGroupsPopup(
        project: Project,
        subjectLabel: String,
        private val groups: List<Group>,
        initialSelection: Set<UUID>
    ) : DialogWrapper(project, true) {

        private val checkBoxes: Map<UUID, JBCheckBox> =
            groups.associate { it.id to JBCheckBox(it.name, initialSelection.contains(it.id)) }

        var selectedGroupIds: Set<UUID> = emptySet()
            private set

        init {
            title = "Assign \"$subjectLabel\" to Groups"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val root = JPanel(BorderLayout(0, 10)).apply {
                border = JBUI.Borders.empty(10)
            }

            root.add(JBLabel("Select the groups these tabs should be assigned to:"), BorderLayout.NORTH)

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

    private fun restoreExpansionState(state: ExpansionState, forceExpandGroupIds: Set<UUID> = emptySet()) {
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
        for (id in forceExpandGroupIds) {
            findTreePathForGroupId(id)?.let { tree.expandPath(it) }
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

        persistModelOnly()
        rebuildTree(forceExpandGroupIds = setOf(newGroup.id))

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

        persistModelOnly()
        rebuildTree()
    }

    private fun getSelectedFileItems(): List<NodeData.FileItem> {
        return tree.selectionPaths.orEmpty()
            .mapNotNull { path ->
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                node?.userObject as? NodeData.FileItem
            }
    }

    private fun moveSelectedTabsToGroup(targetGroupId: UUID?) {
        val selectedTabs = getSelectedFileItems()
            .distinctBy { it.fileUrl }

        if (selectedTabs.isEmpty()) {
            return
        }

        for (tab in selectedTabs) {
            val url = tab.fileUrl
            if (targetGroupId == null) {
                membershipMappingByUrl.remove(url)
            } else {
                membershipMappingByUrl[url] = mutableSetOf(targetGroupId)
            }
        }

        persistModelOnly()
        rebuildTree(forceExpandGroupIds = targetGroupId?.let { setOf(it) } ?: emptySet())
    }

    private fun getSelectedNodeData(): NodeData? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null

        return node.userObject as? NodeData
    }

    private fun rebuildTree(forceExpandGroupIds: Set<UUID> = emptySet()) {

        val expansionState = if (hasInitExpansion) {
            recordExpansionState()
        } else {
            null
        }

        rootNode.removeAllChildren()

        // handle member tabs
        for (group in groups) {
            val groupNode = DefaultMutableTreeNode(NodeData.GroupHeader(group.id, group.name))

            val memberUrls =
                membershipMappingByUrl.asSequence().filter { (_, groupIds) -> groupIds.contains(group.id) }
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

        val shouldShowUnassigned = unassignedNode.childCount > 0 || filterText.isNotBlank()
        if (shouldShowUnassigned) {
            filterNode(unassignedNode)?.let { rootNode.add(it) }
        }

        treeModel.reload()


        SwingUtilities.invokeLater {
            if (!hasInitExpansion) {
                restorePersistedOrDefaultExpansion(forceExpandGroupIds)
                hasInitExpansion = true
            } else {
                restoreExpansionState(expansionState ?: ExpansionState(emptySet(), false), forceExpandGroupIds)
            }

            persistExpansionOnly()

            refreshHoverFromMousePointer()
        }

        // this is also probably where I highlight/ select the active tab
    }

    private fun restorePersistedOrDefaultExpansion(forceExpandGroupIds: Set<UUID>) {

        val s = stateService.state

        val hasPersistedExpansion = s.expandedGroupIds != null || s.unassignedExpanded != null

        if (hasPersistedExpansion) {
            val persistedExpanded: Set<UUID> = s.expandedGroupIds
                ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.toSet() ?: emptySet()

            val persistedUnassigned = s.unassignedExpanded ?: true

            restoreExpansionState(ExpansionState(persistedExpanded, persistedUnassigned), forceExpandGroupIds)

        } else {
            if (!s.hasSavedExpansion) {
                expandAllGroups()
            }
        }
    }

    private fun expandAllGroups() {
        for (group in groups) {
            findTreePathForGroupId(group.id)?.let { tree.expandPath(it) }
        }
        findTreePathForUnassigned()?.let { tree.expandPath(it) }
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


    private fun loadPersistentState() {
        val s = stateService.state

        if (s.groups.isNotEmpty()) {
            groups.clear()
            groups.addAll(
                s.groups.map { g ->
                    Group(UUID.fromString(g.id), g.name)
                }
            )
        } else {
            persistModelOnly()
        }

        membershipMappingByUrl.clear()
        for ((url, ids) in s.membershipByUrl) {
            membershipMappingByUrl[url] = ids.map { UUID.fromString(it) }.toMutableSet()
        }
    }

    private fun installExpansionPersistenceTracking() {
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent?) = persistExpansionOnly()
            override fun treeCollapsed(event: TreeExpansionEvent?) = persistExpansionOnly()

        })
    }

    private fun persistModelOnly() {
        val s = stateService.state
        s.groups = groups.map { ProperTabGroupsStateService.GroupState(it.id.toString(), it.name) }.toMutableList()
        s.membershipByUrl = membershipMappingByUrl.mapValues { (_, set) ->
            set.map { it.toString() }.toMutableList()
        }.toMutableMap()
    }

    private fun persistExpansionOnly() {
        val es = recordExpansionState()
        val s = stateService.state

        s.expandedGroupIds = es.expandedGroupIds.map { it.toString() }.toMutableList()
        s.unassignedExpanded = es.unassignedExpanded
        s.hasSavedExpansion = true
    }

    override fun dispose() {
        uninstallGlobalMouseWatcher()
    }
}
