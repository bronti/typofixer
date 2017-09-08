package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.FoundWord


abstract class TypoCase(
        private val editor: Editor,
        protected val file: PsiFile,
        protected val startOffset: Int,
        protected val oldWord: String,
        private val checkTime: () -> Unit) {

    private val document = editor.document
    protected val project = editor.project!!
    protected lateinit var element: PsiElement

    protected val appManager = ApplicationManager.getApplication()!!

    private var isSetUp = false
    open fun setUp() {
        refreshElement()
        isSetUp = true
    }

    // TypoResolver handles the first case for which canBeApplicable() is true
    open fun canBeApplicable(): Boolean {
        assert(isSetUp)
        return true
    }

    open fun isApplicable(): Boolean {
        assert(isSetUp)
        return true
    }

    abstract fun triggersResolve(c: Char): Boolean
    abstract fun getReplacement(checkTime: () -> Unit): Sequence<FoundWord>

    protected open fun isGoodReplacement(newWord: FoundWord): Boolean {
        assert(isSetUp)
        return true
    }

    protected open fun handleNoReplacement() = false

    fun resolveAll(words: Sequence<FoundWord>): Boolean {
        assert(isSetUp)
        // index unzipping laziness is forced here:
        val replacementWord = words.find { isGoodReplacement(it) }
        return if (replacementWord != null) {
            doReplace(replacementWord)
            true
        } else {
            return handleNoReplacement()
        }
    }

    protected fun doReplace(newWord: FoundWord) {
        assert(isSetUp)
        performReplacement {
            //                element.replace(replacement!!) // caret placement in tests is wrong (why?)
            editor.document.replaceString(startOffset, startOffset + oldWord.length, newWord.word)
        }
    }

    protected fun <T> withReadAccess(access: () -> T): T = appManager.runReadAction<T>(access)

    protected fun checkWithWritePriority(doCheck: () -> Boolean) = checkInSmartMode {
        val indicator = ProgressIndicatorProvider.getInstance().progressIndicator
        checkTime()

        if (appManager.isDispatchThread) {
            refreshElement()
            doCheck()
        } else {
            var resultFound = false
            var result = false
            while (!resultFound) {
                checkTime()
                refreshElement()
                resultFound = ProgressIndicatorUtils.runInReadActionWithWriteActionPriority({
                    result = doCheck()
                }, indicator)
            }
            result
        }
    }

    private fun refreshPsi(editor: Editor) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            }
        }
    }

    private fun getRefreshedElement(): PsiElement? {
        refreshPsi(editor)
        return withReadAccess {
            val newElement = file.findElementAt(startOffset)
            // todo:
            if (newElement != null && newElement.text.startsWith(oldWord) && newElement.textOffset == startOffset) newElement
            else null
        }
    }

    private fun performReplacement(command: () -> Unit) {
        checkTime()
        appManager.invokeAndWait {
            appManager.runWriteAction {
                PsiDocumentManager.getInstance(project).commitDocument(document)
                refreshElement()
                element = getRefreshedElement() ?: throw ResolveCancelledException()
                CommandProcessor
                        .getInstance()
                        .executeCommand(project, command, "Resolve typo", document, UndoConfirmationPolicy.DEFAULT, document)
            }
        }
        project.statistics.onWordReplaced()
    }

    private fun refreshElement() {
        if (!isSetUp || !withReadAccess { element.isValid })
            element = getRefreshedElement() ?: throw ResolveCancelledException()
    }

    private fun checkInSmartMode(doCheck: () -> Boolean): Boolean {
        var result = false
        DumbService.getInstance(project).repeatUntilPassesInSmartMode { result = doCheck() }
        return result
    }
}