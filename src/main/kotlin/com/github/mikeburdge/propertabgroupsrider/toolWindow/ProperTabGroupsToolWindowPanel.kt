package com.github.mikeburdge.propertabgroupsrider.toolWindow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.treeStructure.Tree
import java.util.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentManagerEvent
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class ProperTabGroupsToolWindowPanel(private val project: Project): JPanel(BorderLayout()) {

    private sealed class NodeData {
        data class GroupHeader(val id: UUID, val name: String) : NodeData()
        data object UnassignedHeader : NodeData()

        data class FileItem(
            val fileUrl: String,
            val displayName: String,
            val parentGroupId: UUID? // null means unassigned
        ) : NodeData()
    }

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

        add(searchField, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        // todo: add listeners
        // document listener
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterText = searchField.text
                rebuildTree()
            }
        })

        // todo: renderer
        // todo: drag and drop


        rebuildTree()
    }

    @Suppress("KotlinUnreachableCode")
    private fun rebuildTree() {

        rootNode.removeAllChildren()

        val groups = TODO("load groups List<Group>") // persistence stuff for later

        val mapping: Map<String, Set<UUID>> = TODO("load the mapping")// initialise

        val openFiles = FileEditorManager.getInstance(project).openFiles

        val unassignedNode = DefaultMutableTreeNode(NodeData.UnassignedHeader)
        val unassignedFiles = openFiles.filter { file -> mapping[file.url].isNullOrEmpty() }

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

    private fun shouldKeepGroupNode(node: DefaultMutableTreeNode): Boolean {

    }
}





































