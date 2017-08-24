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
        protected val editor: Editor,
        protected val psiFile: PsiFile,
        protected val elementStartOffset: Int,
        protected val oldText: String,
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
            val newElement = psiFile.findElementAt(elementStartOffset)
            // todo:
            if (newElement != null && newElement.text.startsWith(oldText) && newElement.textOffset == elementStartOffset) newElement
            else null
        })
    }

    private fun refreshElement() {
        if (!isSetUp || !appManager.runReadAction(Computable { element.isValid }))
            element = getRefreshedElement() ?: throw ResolveCancelledException()
    }

    protected var elementOffsetInParent: Int = 0
    protected val appManager = ApplicationManager.getApplication()!!

    protected var isSetUp = false
    open fun setUp() {
        refreshElement()
        isSetUp = true
        elementOffsetInParent = element.startOffsetInParent
    }

    // TypoResolver handles the first case for which canBeApplicable() is true
    fun canBeApplicable() = checkApplicable(true)

    fun isApplicable() = checkWithWritePriority { checkApplicable(false) }

    abstract fun triggersResolve(c: Char): Boolean
    abstract fun getReplacement(checkTime: () -> Unit): SearchResults

    protected abstract fun checkApplicable(fast: Boolean = false): Boolean
    protected abstract fun isGoodReplacement(newWord: FoundWord): Boolean

    fun resolveAll(words: Sequence<FoundWord>): Boolean {
        assert(isSetUp)
        // index unzipping laziness is forced here:
        val replacementWord = words.find { checkWithWritePriority { isGoodReplacement(it) } } ?: return false
        doReplace(replacementWord)
        return true
    }

    private fun doReplace(newWord: FoundWord) {
        assert(isSetUp)
        performReplacement {
            //                element.replace(replacement!!) // caret placement in tests is wrong (why?)
            editor.document.replaceString(elementStartOffset, elementStartOffset + oldText.length, newWord.word)
        }
    }

    protected fun performReplacement(command: () -> Unit) {
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

    private fun checkWithWritePriority(doCheck: () -> Boolean): Boolean {
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