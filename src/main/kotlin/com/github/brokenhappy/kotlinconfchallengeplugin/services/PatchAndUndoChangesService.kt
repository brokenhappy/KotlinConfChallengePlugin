package com.github.brokenhappy.kotlinconfchallengeplugin.services

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*

@Service(Service.Level.PROJECT)
internal class PatchAndUndoChangesService(
    private val project: Project,
    private val projectScope: CoroutineScope,
) {
    fun asyncSavePatchAndThenUndoChanges() {
        projectScope.launch {
            withBackgroundProgress(project, "Saving Patch and undoing Changes", cancellable = true) {
                savePatchAndThenUndoChanges()
            }
        }
    }

    private suspend fun savePatchAndThenUndoChanges() {
        val projectBasePath = project.basePath ?: return

        try {
            edtWriteAction { FileDocumentManager.getInstance().saveAllDocuments() }

            val hasChangesProcess = ProcessBuilder("git", "-C", projectBasePath, "diff", "--quiet")
                .redirectErrorStream(true)
                .start()

            try {
                if (runInterruptible { hasChangesProcess.waitFor() == 0 }) {
                    LOG.info("No changes to create patch from")
                    return
                }
            } catch (c: CancellationException) {
                hasChangesProcess.destroy()
                throw c
            }

            val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(Date())
            val patchDirectory = project
                .service<ChallengeStateService>()
                .appState()
                .value
                .settings
                .fileSharedBetweenRuntimeAndPlugin
                .let { File(it) }
                .parent
                .toPath()
                .resolve("Challenges_Patch_$timestamp.patch")
                .toFile()

            val createPatchProcess = ProcessBuilder("git", "-C", projectBasePath, "diff")
                .redirectOutput(ProcessBuilder.Redirect.to(patchDirectory))
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            try {
                val createPatchResult = runInterruptible { createPatchProcess.waitFor() }
                if (createPatchResult != 0) {
                    val errorOutput = createPatchProcess.errorStream.bufferedReader().readText()
                    LOG.error("Error creating patch: $errorOutput")
                    return
                }
            } catch (c: CancellationException) {
                createPatchProcess.destroy()
                throw c
            }

            LOG.info("Patch created at: $patchDirectory")

            // Undo changes using git checkout
            val undoChangesProcess = ProcessBuilder("git", "-C", projectBasePath, "checkout", ".")
                .redirectErrorStream(true)
                .start()


            try {
                val undoChangesResult = runInterruptible { undoChangesProcess.waitFor() }
                if (undoChangesResult != 0) {
                    val errorOutput = undoChangesProcess.errorStream.bufferedReader().readText()
                    LOG.error("Error undoing changes: $errorOutput")
                    return
                }
            } catch (c: CancellationException) {
                undoChangesProcess.destroy()
                throw c
            }


            edtWriteAction { VirtualFileManager.getInstance().refreshWithoutFileWatcher(false) }

            LOG.info("Changes undone using git checkout")
        } catch (ex: Exception) {
            LOG.error("Error creating patch and undoing changes", ex)
        }
    }
}

private val LOG = Logger.getInstance(PatchAndUndoChangesService::class.java)