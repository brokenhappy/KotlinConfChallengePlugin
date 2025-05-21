package com.github.brokenhappy.kotlinconfchallengeplugin.actions

import com.github.brokenhappy.kotlinconfchallengeplugin.services.PatchAndUndoChangesService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class CreatePatchAndUndoAction : AnAction("Create Patch and Undo Changes", "Creates a patch of all local changes using git, saves it next to the challenges json file, and undoes the changes", null) {
    override fun actionPerformed(event: AnActionEvent) {
        event
            .project
            ?.service<PatchAndUndoChangesService>()
            ?.asyncSavePatchAndThenUndoChanges()
    }
}
