package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.FoundWord
import com.jetbrains.typofixer.search.SearchResults


abstract class TypoCase(
        private val editor: Editor,
        protected val file: PsiFile,
        protected val startOffset: Int,
        protected val oldWord: String,
        private val checkTime: () -> Unit) {

    private val document = editor.document
    protected val project = editor.project!!
    protected lateinit var element: PsiElement

    private fun refreshPsi(editor: Editor) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            }
        }
    }

    private fun getRefreshedElement(): PsiElement? {
        val appManager = ApplicationManager.getApplication()
        refreshPsi(editor)
        return appManager.runReadAction(Computable {
            val newElement = file.findElementAt(startOffset)
            // todo:
            if (newElement != null && newElement.text.startsWith(oldWord) && newElement.textOffset == startOffset) newElement
            else null
        })
    }

    private fun refreshElement() {
        if (!isSetUp || !appManager.runReadAction(Computable { element.isValid }))
            element = getRefreshedElement() ?: throw ResolveCancelledException()
    }

    protected val appManager = ApplicationManager.getApplication()!!

    private var isSetUp = false
    open fun setUp() {
        refreshElement()
        isSetUp = true
    }

    // TypoResolver handles the first case for which canBeApplicable() is true
    fun canBeApplicable() = checkApplicable(true)

    fun isApplicable() = checkWithWritePriority { checkApplicable(false) }

    abstract fun triggersResolve(c: Char): Boolean
    abstract fun getReplacement(checkTime: () -> Unit): SearchResults

    protected open fun checkApplicable(fast: Boolean = false): Boolean {
        assert(isSetUp)
        appManager.assertReadAccessAllowed()
        return true
    }

    protected open fun isGoodReplacement(newWord: FoundWord): Boolean {
        assert(isSetUp)
        return true
    }

    fun resolveAll(words: Sequence<FoundWord>): Boolean {
        assert(isSetUp)
        // index unzipping laziness is forced here:
        val replacementWord = words.find { isGoodReplacement(it) } ?: return false
        doReplace(replacementWord)
        return true
    }

    private fun doReplace(newWord: FoundWord) {
        assert(isSetUp)
        performReplacement {
            //                element.replace(replacement!!) // caret placement in tests is wrong (why?)
            editor.document.replaceString(startOffset, startOffset + oldWord.length, newWord.word)
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

    protected fun checkWithWritePriority(doCheck: () -> Boolean): Boolean {
        val indicator = ProgressIndicatorProvider.getInstance().progressIndicator
        checkTime()

        if (appManager.isDispatchThread) {
            refreshElement()
            return doCheck()
        }

        var resultFound = false
        var result = false
        while (!resultFound) {
            checkTime()
            refreshElement()
            resultFound = ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                result = doCheck()
            }, indicator)
        }
        return result
    }
}