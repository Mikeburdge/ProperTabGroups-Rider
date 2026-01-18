package com.github.mikeburdge.propertabgroupsrider.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.treeStructure.Tree
import java.util.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.TreePath

class ProperTabGroupsToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private sealed class NodeData {
        data class GroupHeader(val id: UUID, val name: String) : NodeData()
        data object UnassignedHeader : NodeData()

        data class FileItem(
            val fileUrl: String,
            val displayName: String,
            val parentGroupId: UUID? // null means unassigned
        ) : NodeData()
    }

    private data class Group(val id: UUID, val name: String)

    private val groups: MutableList<Group> = mutableListOf(
        Group(UUID.randomUUID(), "Gameplay"),
        Group(UUID.randomUUID(), "UI"),
    )

    private val membershipMappingByUrl: MutableMap<String, MutableSet<UUID>> = mutableMapOf()

    private data class GroupItem(val name: String, val tabs: List<String>)

    private val demoGroups = listOf(
        GroupItem("Gameplay", listOf("playerController.h", "playerController.cpp", "Movement.h", "Movement.cpp")),
        GroupItem("UI", listOf("HUD.xaml", "Inventory.xaml")),
    )

    private val demoUnassigned = listOf(
        "main.cpp",
        "Test.cpp"
    )

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)

    private var filterText: String = ""

    private var activeFileUrl: String? = null

    private val searchField = SearchTextField()

    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true

        emptyText.text = "toolWindow.empty"

        com.intellij.ui.TreeUIHelper.getInstance().installTreeSpeedSearch(this)
    }

    init {
        tree.model = treeModel

        tree.cellRenderer = ProperTabTreeRenderer()

        add(searchField, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        // document listener
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterText = searchField.text
                rebuildTree()
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject

                if (data is NodeData.FileItem) {
                    val virtualFile = VirtualFileManager.getInstance().findFileByUrl(data.fileUrl) ?: return
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }
        })

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    activeFileUrl = event.newFile?.url

                }
            }
        )
        // todo: drag and drop

        rebuildTree()
    }

    private inner class ProperTabTreeRenderer : ColoredTreeCellRenderer() {
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
                    icon = AllIcons.FileTypes.Any_type
                    val bIsActive = data.fileUrl == activeFileUrl

                    val attributes = if (bIsActive)
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else
                        SimpleTextAttributes.REGULAR_ATTRIBUTES

                    append(data.displayName, attributes)
                }

                else -> {
                    append(data?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }

    private fun rebuildTree() {

        rootNode.removeAllChildren()

        val openFiles = FileEditorManager.getInstance(project).openFiles

        val unassignedNode = DefaultMutableTreeNode(NodeData.UnassignedHeader)
        val unassignedFiles = openFiles.filter { file -> membershipMappingByUrl[file.url].isNullOrEmpty() }

        // handle unassigned tabs
        for (file in unassignedFiles) {
            unassignedNode.add(
                DefaultMutableTreeNode(
                    NodeData.FileItem(
                        fileUrl = file.url,
                        displayName = file.name,
                        parentGroupId = null
                    )
                )
            )
        }

        // filter unassigned. replaced the if statement with a "safe access expression"
        filterNode(unassignedNode)?.let { rootNode.add(it) }

        // handle member tabs
        for (group in groups) {
            val groupNode = DefaultMutableTreeNode(NodeData.GroupHeader(group.id, group.name))

            val memberUrls = membershipMappingByUrl.asSequence().filter { (_, groupIds) -> groupIds.contains(group.id) }
                .map { (url, _) -> url }.toList()

            for (url in memberUrls) {
                groupNode.add(
                    DefaultMutableTreeNode(
                        NodeData.FileItem(
                            fileUrl = url,
                            displayName = displayNameFromUrl(url),
                            parentGroupId = group.id
                        )
                    )
                )
            }

            filterNode(groupNode)?.let { rootNode.add(it) }
        }


        treeModel.reload()

        // I think this is where I would sort the expansion of the header out.

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
        val path = findTreePathForFileUrl(url)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
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

    override fun dispose() {}
}





































