package com.github.mikeburdge.propertabgroupsrider.services

import com.intellij.openapi.components.*

@Service(Service.Level.PROJECT)
@State(
    name = "ProperTabGroupsState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class ProperTabGroupsStateService : PersistentStateComponent<ProperTabGroupsStateService.State> {

    data class GroupState(
        var id: String = "",
        var name: String = ""
    )


    data class State(
        var groups: MutableList<GroupState> = mutableListOf(),
        var membershipByUrl: MutableMap<String, MutableList<String>> = mutableMapOf(),

        var expandedGroupIds: MutableList<String>? = null,
        var unassignedExpanded: Boolean? = null,

        var hasSavedExpansion: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }
}
