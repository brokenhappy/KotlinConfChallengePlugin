package com.github.brokenhappy.kotlinconfchallengeplugin.startup

import com.github.brokenhappy.kotlinconfchallengeplugin.startup.ComputationStateOfKey.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KotlinFileOnChangeSaver : ProjectActivity {
    override suspend fun execute(project: Project) {
        flowThatEmitsOnAllChangesInAllOpenEditors(project)
            .conflate()
            .collect { document ->
                withContext(Dispatchers.EDT) {
                    FileDocumentManager.getInstance().saveDocument(document)
                }
                delay(0.2.seconds)
            }
    }
}

private fun flowThatEmitsOnAllChangesInAllOpenEditors(project: Project): Flow<Document> = channelFlow {
    allOpenedEditorsFlowIn(project).incrementalCollect { editor ->
        editor
            .file
            ?.let { readAction { FileDocumentManager.getInstance().getDocument(it) } }
            ?.also { document ->
                val listener = object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        trySend(event.document)
                    }
                }
                try {
                    document.addDocumentListener(listener)
                    awaitCancellation()
                } finally {
                    document.removeDocumentListener(listener)
                }
            }
    }
}

private fun allOpenedEditorsFlowIn(project: Project): Flow<Set<FileEditor>> = channelFlow {
    launch {
        trySend(FileEditorManager.getInstance(project).allEditors.toSet())
    }
    val connect = project.messageBus.connect()
    try {
        connect.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object: FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                super.fileOpened(source, file)
                trySend(source.allEditors.filter { it.isValid }.toSet())
            }
        })
        awaitCancellation()
    } finally {
        connect.disconnect()
    }
}

/**
 * TLDR;
 * Runs a task for each entry in the last emitted set in parallel.
 *
 * In a world without parallelism and no cancellation, it behaves like:
 * ```kt
 * .flatMapConcat { it.asFlow() }
 * .collect { coroutineScope { collector(it) } }
 * ```
 * But that world is boring! So in reality, it has the following differences:
 * 1. Each collector runs in parallel
 * 2. Each time a new set is emitted that has an entry that was not in the previous set,
 *   it cancels this collector after [cancellationGracePeriod].
 *   However, if a new entry was added before [cancellationGracePeriod] elapsed, the collector for this entry will not be canceled anymore.
 */
suspend fun <T> Flow<Set<T>>.incrementalCollect(
    cancellationGracePeriod: Duration = Duration.ZERO,
    collector: suspend CoroutineScope.(T) -> Unit,
): Unit = incrementalAsyncMapImplementation(cancellationGracePeriod, collector).collect {}

private fun <T, R> Flow<Iterable<T>>.incrementalAsyncMapImplementation(
    removalGracePeriod: Duration = Duration.ZERO,
    mapper: suspend CoroutineScope.(T) -> R,
): Flow<ComputationState<T, T, R>> = incrementalAsyncMapGroupingByImplementation(removalGracePeriod, { it }) { it, _ -> mapper(it) }

/** I used mutation testing to validate test accuracy, you might want to consider it too! */
private fun <T, K, R> Flow<Iterable<T>>.incrementalAsyncMapGroupingByImplementation(
    removalGracePeriod: Duration = Duration.ZERO,
    keySelector: (T) -> K,
    mapper: suspend CoroutineScope.(K, StateFlow<Set<T>>) -> R,
): Flow<ComputationState<K, T, R>> = channelFlow {
    val results = SynchronizedValue(persistentMapOf<K, ComputationStateOfKey<T, R>>())
    fun launchTaskFor(key: K, values: MutableStateFlow<Set<T>>): Job = launch {
        val result = mapper(key, values)
        val updatedState = results.update { currentResult ->
            currentResult.put(key, when (val currentComputationState = currentResult[key]) {
                is InCancellationGracePeriod -> currentComputationState.copy(notCancelledState = Done(result, values))
                is Loading -> Done(result, values)
                is Done,
                null -> error("A running task can only be in cancelling or loading state")
            })
        }
        if (updatedState[key] is Done) {
            send(updatedState)
        }
    }
    fun newLoadingTask(key: K): Loading<T> = MutableStateFlow(emptySet<T>()).let { Loading(launchTaskFor(key, it), it) }

    withCoroutineScope(cancellationCause = CancellationBecauseKeyWasRemovedAndFlowWasExhaustedDuringGracePeriod()) { gracePeriodScope ->
        fun launchCancellationWithGracePeriod(key: K, currentComputationTask: Job?): Job = gracePeriodScope.launch(start = CoroutineStart.UNDISPATCHED) {
            suspend fun cancelAndJoinComputationTask(cause: CancellationException) {
                if (currentComputationTask == null) return
                results.withLock {
                    /** We need to perform cancel inside [results]' lock. See [addingNewKey] to understand why */
                    currentComputationTask.cancel(cause)
                }
                currentComputationTask.join()
            }
            try {
                delay(removalGracePeriod)
                cancelAndJoinComputationTask(CancellationBecauseKeyWasRemoved(removalGracePeriod))
                send(results.update { it.remove(key) })
            } catch (t: CancellationBecauseKeyWasRemovedAndFlowWasExhaustedDuringGracePeriod) {
                withContext(NonCancellable) { cancelAndJoinComputationTask(t) }
            }
        }

        suspend fun ComputationState<K, T, R>.addingNewKey(
            newlyRegisteredKey: K,
            currentStateForThisKey: ComputationStateOfKey<T, R>?,
        ): ComputationState<K, T, R> = when (currentStateForThisKey) {
            is InCancellationGracePeriod -> { // Was canceled, but the entry came back; we will try to save the task from being canceled, hurray!
                currentStateForThisKey.cancellingJob.cancelAndJoin()
                // It might be that we canceled the cancelling job,
                // But it already canceled the computation, leaving us in a stale state.
                // Therefore, we should reinsert this key
                val needToRestartKey = currentStateForThisKey.notCancelledState is Loading
                    && currentStateForThisKey.notCancelledState.task.isCancelled
                put(
                    newlyRegisteredKey,
                    if (needToRestartKey) newLoadingTask(newlyRegisteredKey)
                    else currentStateForThisKey.notCancelledState /** Because we know that cancellation is only performed in [results]' lock, we know there can't have been a race condition here */
                )
            }
            null -> put(newlyRegisteredKey, newLoadingTask(newlyRegisteredKey))
            is NotCancelled -> error("Entity is already running")
        }

        suspend fun ComputationState<K, T, R>.removingKey(
            newlyRemovedKey: K,
            currentStateForThisKey: ComputationStateOfKey<T, R>?,
        ): ComputationState<K, T, R> = when (currentStateForThisKey) {
            is NotCancelled -> {
                if (removalGracePeriod == Duration.ZERO) {
                    remove(newlyRemovedKey)
                        .also { stateAfterRemovingKey ->
                            when (currentStateForThisKey) {
                                is Loading -> currentStateForThisKey
                                    .task
                                    .cancelAndJoin(CancellationBecauseKeyWasRemoved(removalGracePeriod))
                                is Done -> send(stateAfterRemovingKey)
                            }
                        }
                } else put(newlyRemovedKey, InCancellationGracePeriod(
                    currentStateForThisKey,
                    launchCancellationWithGracePeriod(
                        newlyRemovedKey,
                        (currentStateForThisKey as? Loading)?.task
                    ),
                ))
            }
            is InCancellationGracePeriod -> error("Will never happen. Grace period's tasks are responsible to remove themselves.")
            null -> error("Will never happen. Removing a key will never be requested for a key that does not exist.")
        }


        var previousSet = setOf<K>()
        collect { currentValues ->
            val currentMap = currentValues
                .groupBy(keySelector)
                .mapValues { (_, values) -> values.toSet() }

            val currentSet = currentMap.keys
            (currentSet + previousSet)
                .forEach { key ->
                    results.update { currentResult ->
                        val currentComputationState = currentResult[key]
                        when (key) {
                            !in previousSet -> currentResult.addingNewKey(key, currentComputationState)
                            !in currentMap -> currentResult.removingKey(key, currentComputationState)
                            else -> currentResult
                        }.also {
                            it[key]?.valuesForThisKey?.value = currentMap[key] ?: emptySet()
                        }
                    }
                }

            previousSet = currentSet
        }
    }
}

private class SynchronizedValue<T>(private var state: T) {
    private val lock = Mutex()
    suspend inline fun update(updater: (T) -> T): T = withLock { updater(state).also { state = it } }
    suspend inline fun <R> withLock(action: () -> R): R = lock.withLock { action() }
}

private typealias ComputationState<K, T, R> = PersistentMap<K, ComputationStateOfKey<T, R>>

private sealed class ComputationStateOfKey<out T, out R> {
    sealed class NotCancelled<T, R> : ComputationStateOfKey<T, R>()

    data class Done<T, R>(val value: R, val valuesFlowForThisKey: MutableStateFlow<Set<T>>) : NotCancelled<T, R>()
    data class Loading<T>(val task: Job, val valuesFlowForThisKey: MutableStateFlow<Set<T>>) : NotCancelled<T, Nothing>()
    data class InCancellationGracePeriod<T, R>(
        val notCancelledState: NotCancelled<T, R>,
        val cancellingJob: Job,
    ) : ComputationStateOfKey<T, R>()

}

class CancellationBecauseKeyWasRemovedAndFlowWasExhaustedDuringGracePeriod: CancellationException("""
    While work for an key was allowed to continue for the chance that this key would come back,
    the underlying flow has been exhausted. Therefore, there is no chance for the key to come back.
""".trimIndent())

class CancellationBecauseKeyWasRemoved(gracePeriod: Duration): CancellationException(
    "Key was removed from underlying set, and we have waited for $gracePeriod before canceling"
)

private val <T> ComputationStateOfKey<T, *>.valuesForThisKey: MutableStateFlow<Set<T>> get() = when (this) {
    is Done -> valuesFlowForThisKey
    is Loading -> valuesFlowForThisKey
    is InCancellationGracePeriod -> notCancelledState.valuesForThisKey
}

private suspend fun withCoroutineScope(cancellationCause: CancellationException? = null, body: suspend CoroutineScope.(scope: CoroutineScope) -> Unit) {
    val context = currentCoroutineContext()
    val job = Job(context.job)
    try {
        coroutineScope { body(CoroutineScope(context + job)) }
    }
    finally {
        job.cancelAndJoin(cancellationCause)
    }
}

private suspend fun Job.cancelAndJoin(cause: CancellationException?) {
    cancel(cause)
    join()
}
