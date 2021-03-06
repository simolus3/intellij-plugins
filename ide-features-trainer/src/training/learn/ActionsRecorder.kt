/*
 * Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package training.learn

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import training.check.Check
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture

class ActionsRecorder(private val project: Project,
                      private val document: Document) : Disposable {

  private val documentListeners: MutableList<DocumentListener> = mutableListOf()
  // TODO: do we really need a lot of listeners?
  private val actionListeners: MutableList<AnActionListener> = mutableListOf()
  private val eventDispatchers: MutableList<IdeEventQueue.EventDispatcher> = mutableListOf()

  private var disposed = false

  private val busConnection = ApplicationManager.getApplication().messageBus.connect(this)

  /** Currently registered command listener */
  private var commandListener: CommandListener? = null

  // TODO: I suspect that editor listener could be replaced by command listener. Need to check it
  private var editorListener: FileEditorManagerListener? = null

  init {
    Disposer.register(project, this)

    // We could not unregister a listener (it will be done in dispose)
    // So the simple solution is to use a proxy

    busConnection.subscribe(AnActionListener.TOPIC, object : AnActionListener{
      override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        actionListeners.forEach { it.beforeActionPerformed(action, dataContext, event) }
      }

      override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        actionListeners.forEach { it.afterActionPerformed(action, dataContext, event) }
      }

      override fun beforeEditorTyping(c: Char, dataContext: DataContext) {
        actionListeners.forEach { it.beforeEditorTyping(c, dataContext) }
      }
    })

    // This listener allows to track a lot of IDE state changes
    busConnection.subscribe(CommandListener.TOPIC, object : CommandListener {
      override fun commandStarted(event: CommandEvent) {
        commandListener?.commandStarted(event)
      }

      override fun beforeCommandFinished(event: CommandEvent) {
        commandListener?.beforeCommandFinished(event)
      }

      override fun commandFinished(event: CommandEvent) {
        commandListener?.commandFinished(event)
      }

      override fun undoTransparentActionStarted() {
        commandListener?.undoTransparentActionStarted()
      }

      override fun beforeUndoTransparentActionFinished() {
        commandListener?.beforeUndoTransparentActionFinished()
      }

      override fun undoTransparentActionFinished() {
        commandListener?.undoTransparentActionFinished()
      }
    })
    busConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        editorListener?.fileClosed(source, file)
      }

      override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        editorListener?.fileOpened(source, file)
      }

      override fun fileOpenedSync(source: FileEditorManager, file: VirtualFile, editors: Pair<Array<FileEditor>, Array<FileEditorProvider>>) {
        editorListener?.fileOpenedSync(source, file, editors)
      }

      override fun selectionChanged(event: FileEditorManagerEvent) {
        editorListener?.selectionChanged(event)
      }
    })
  }

  override fun dispose() {
    removeListeners()
    disposed = true
    Disposer.dispose(this)
  }

  fun futureActionOnStart(actionId: String, check: () -> Boolean): CompletableFuture<Boolean> {
    val future: CompletableFuture<Boolean> = CompletableFuture()
    val actionListener = object : AnActionListener {
      override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        if (getActionId(action) == actionId && check()) {
          future.complete(true)
        }
      }
    }
    actionListeners.add(actionListener)
    return future
  }

  fun futureActionAndCheckAround(actionId: String, check: Check): CompletableFuture<Boolean> {
    val future: CompletableFuture<Boolean> = CompletableFuture()
    val actionListener = object : AnActionListener {
      override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        val caughtActionId : String? = ActionManager.getInstance().getId(action)
        if (actionId == caughtActionId) {
          check.before()
        }
        else if (caughtActionId != null) {
          // remove additional state listener to check caret positions and so on
          commandListener = null
        }
      }

      override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        if (actionId == ActionManager.getInstance().getId(action)) {
          val complete = checkComplete()
          if (!complete) {
            addSimpleCommandListener { checkComplete() }
          }
        }
      }

      override fun beforeEditorTyping(c: Char, dataContext: DataContext) {}

      private fun checkComplete() : Boolean {
        val complete = check.check()
        if (complete) {
          future.complete(true)
        }
        return complete
      }
    }
    actionListeners.add(actionListener)
    return future
  }

  fun futureAction(actionId: String): CompletableFuture<Boolean> {
    val future: CompletableFuture<Boolean> = CompletableFuture()
    registerActionListener { caughtActionId, _ -> if (actionId == caughtActionId) future.complete(true) }
    return future
  }

  fun futureListActions(listOfActions: List<String>): CompletableFuture<Boolean> {
    val future: CompletableFuture<Boolean> = CompletableFuture()
    val mutableListOfActions = listOfActions.toMutableList()
    registerActionListener { caughtActionId, _ ->
      if (mutableListOfActions.isNotEmpty() && mutableListOfActions.first() == caughtActionId) mutableListOfActions.removeAt(0)
      if (mutableListOfActions.isEmpty()) future.complete(true)
    }
    editorListener = object : FileEditorManagerListener {
      override fun selectionChanged(event: FileEditorManagerEvent) {
        println(event.newFile?.name)
        event.newFile?.name.let {
          if (mutableListOfActions.isNotEmpty() && mutableListOfActions.first() == event.newFile?.name) mutableListOfActions.removeAt(0)
          if (mutableListOfActions.isEmpty()) future.complete(true)
        }
      }
    }
    return future
  }

  fun futureCheck(checkFunction: () -> Boolean): CompletableFuture<Boolean> {
    val future: CompletableFuture<Boolean> = CompletableFuture()

    val check = {
      if (!future.isDone && !future.isCancelled && checkFunction()) {
        future.complete(true)
      }
    }

    addKeyEventListener { check() }
    document.addDocumentListener(createDocumentListener { check() })
    addSimpleCommandListener(check)
    actionListeners.add(object : AnActionListener {
      override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        check()
      }
    })

    KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner") {
      check()
    }

    return future
  }

  private fun addSimpleCommandListener(check: () -> Unit) {
    commandListener = object : CommandListener {
      override fun commandFinished(event: CommandEvent) {
        check()
      }
    }
  }

  private fun addKeyEventListener(onKeyEvent: () -> Unit) {
    val myEventDispatcher: IdeEventQueue.EventDispatcher = IdeEventQueue.EventDispatcher { e ->
      if (e is KeyEvent ||
          (e as? MouseEvent)?.id == MouseEvent.MOUSE_RELEASED ||
          (e as? MouseEvent)?.id == MouseEvent.MOUSE_CLICKED) onKeyEvent()
      false
    }
    eventDispatchers.add(myEventDispatcher)
    IdeEventQueue.getInstance().addDispatcher(myEventDispatcher, this)
  }

  private fun createDocumentListener(onDocumentChange: () -> Unit): DocumentListener {
    val documentListener = object : DocumentListener {

      override fun beforeDocumentChange(event: DocumentEvent) {}

      override fun documentChanged(event: DocumentEvent) {
        if (PsiDocumentManager.getInstance(project).isUncommited(document)) {
          ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) {
              PsiDocumentManager.getInstance(project).commitAndRunReadAction { onDocumentChange() }
            }
          }
        }
      }
    }
    documentListeners.add(documentListener)
    return documentListener
  }

  private fun registerActionListener(processAction: (actionId: String, project: Project) -> Unit): AnActionListener {
    val actionListener = object : AnActionListener {

      private var projectAvailable: Boolean = false

      override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        projectAvailable = event.project != null
      }

      override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        processAction(getActionId(action), project)
      }
    }
    actionListeners.add(actionListener)
    return actionListener
  }


  private fun removeListeners() {
    if (documentListeners.isNotEmpty()) documentListeners.forEach { document.removeDocumentListener(it) }
    if (eventDispatchers.isNotEmpty()) eventDispatchers.forEach { IdeEventQueue.getInstance().removeDispatcher(it) }
    actionListeners.clear()
    documentListeners.clear()
    eventDispatchers.clear()
    commandListener = null
    editorListener = null
  }

  private fun getActionId(action: AnAction): String =
      ActionManager.getInstance().getId(action) ?: action.javaClass.name
}
