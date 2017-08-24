package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.FoundWord
import com.jetbrains.typofixer.search.SearchResults
import com.jetbrains.typofixer.settings.TypoFixerSettings
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti
 */
class TypoResolver private constructor(
        private val psiFile: PsiFile,
        private val typoCase: TypoCase,
        private val replacements: Sequence<FoundWord>,
        private val checkTime: () -> Unit
) {
    companion object {
        fun getResolver(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            if (!psiFile.project.typoFixerComponent.isActive) return null

            return doGetResolver(nextChar, editor, psiFile, langSupport, true)
        }

        private fun refreshPsi(editor: Editor) {
            ApplicationManager.getApplication().assertIsDispatchThread()
            ApplicationManager.getApplication().runWriteAction {
                PsiDocumentManager.getInstance(editor.project!!).commitDocument(editor.document)
            }
        }

        private fun doGetResolver(
                nextChar: Char,
                editor: Editor,
                psiFile: PsiFile,
                langSupport: TypoFixerLanguageSupport,
                needTimeChecking: Boolean = false
        ): TypoResolver? {

            val project = psiFile.project
            val settings = TypoFixerSettings.getInstance(project)
            val statistics = project.statistics

            val findTimeChecker = getTimeChecker(needTimeChecking, settings.maxMillisForFind, statistics::onFindAbortedBecauseOfTimeLimits)
            val resolveTimeChecker = getTimeChecker(needTimeChecking, settings.maxMillisForResolve, statistics::onResolveAbortedBecauseOfTimeLimits)

            val nextCharOffset = editor.caretModel.offset

            // I don't want to call refresh if resolve is never triggered
            val element by lazy {
                refreshPsi(editor)
                psiFile.findElementAt(nextCharOffset - 1)
            }

            // accessed only if element != null
            val elementStartOffset by lazy { element!!.textOffset }
            // accessed only if element != null
            val oldText by lazy { element!!.text.substring(0, nextCharOffset - elementStartOffset) }

            fun getElement(): PsiElement? {
                val appManager = ApplicationManager.getApplication()
                appManager.invokeAndWait { refreshPsi(editor) }
                return appManager.runReadAction(Computable {
                    val newElement = psiFile.findElementAt(elementStartOffset)
                    if (newElement != null && newElement.text.startsWith(oldText) && newElement.textOffset == elementStartOffset) newElement
                    else null
                })
            }

            for (typoCase in langSupport.getTypoCases(editor.document, resolveTimeChecker, ::getElement)) {
                if (!typoCase.triggersResolve(nextChar)) continue

                element ?: return null

                if (typoCase.isApplicable(fast = true)) {

                    val searchResults: SearchResults
                    try {
                        // todo: check validity (?)
                        searchResults = typoCase.getReplacement(oldText, findTimeChecker)
                    } catch (e: ResolveCancelledException) {
                        return null
                    }

                    if (searchResults.none()) return null

                    val replacements = searchResults.sortedBy { project.searcher.distanceProvider.measure(oldText, it.word) }

                    project.statistics.onTypoResolverCreated()
                    return TypoResolver(psiFile, typoCase, replacements, resolveTimeChecker)
                }
            }
            return null
        }

        @TestOnly
        fun getInstanceIgnoreIsActive(nextChar: Char, editor: Editor, psiFile: PsiFile, needTimeChecking: Boolean = false): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            return doGetResolver(nextChar, editor, psiFile, langSupport, needTimeChecking)
        }
    }

    private fun checkResolveCancelled() {
        checkTime()
        // todo: refresh statistics
        if (!project.isOpen) throw ResolveCancelledException()
    }

    //    private val elementStartOffset = element.textOffset
    private val project get() = psiFile.project

    fun resolve() = Thread {
        try {
            doResolve()
        } catch (e: ResolveCancelledException) {
            // do nothing
        }
    }.start()

    private fun doResolve() {
        checkResolveCancelled()
        if (!checkWithWritePriority({ typoCase.isApplicable(false) }, checkTime)) return
        checkResolveCancelled()
        typoCase.resolveAll(replacements)
    }

    @TestOnly
    fun waitForResolve() = doResolve()
}

class ResolveCancelledException : RuntimeException()

private fun getTimeChecker(needed: Boolean, maxMillis: Long, onAborted: () -> Unit) =
        TimeLimitsChecker.getChecker(needed, maxMillis, onAborted)

private class TimeLimitsChecker private constructor(private val timeConstraint: Long, private val reportAbort: () -> Unit) {
    companion object {
        fun getChecker(needed: Boolean, maxMillis: Long, onAborted: () -> Unit) =
                if (!needed) fun() { /* do nothing */ }
                else TimeLimitsChecker(maxMillis, onAborted)::checkTime
    }
    private var abortReported = false
    private val startTime = System.currentTimeMillis()
    fun checkTime() {
        val result = startTime + timeConstraint <= System.currentTimeMillis()
        if (result && !abortReported) reportAbort()
        if (result) throw ResolveCancelledException()
    }
}

abstract class TypoCase(
        private val document: Document,
        private val checkTime: () -> Unit,
        private val refreshedElement: () -> PsiElement?) {

    private var theElement: PsiElement? = null

    //todo: ex ok?
    // should be lazy!!!
    protected val element
        get(): PsiElement {
            if (theElement == null || !theElement!!.isValid) theElement = refreshedElement() ?: throw ResolveCancelledException()
            return theElement!!
        }

    protected val project by lazy { element.project }

    private val appManager = ApplicationManager.getApplication()
    abstract fun triggersResolve(c: Char): Boolean
    // TypoResolver handles the first case for which isApplicable(element, fast = true) is true
    abstract fun isApplicable(fast: Boolean = false): Boolean

    abstract fun getReplacement(oldText: String, checkTime: () -> Unit): SearchResults

    protected abstract fun isGoodReplacement(newWord: FoundWord): Boolean
    protected abstract fun doReplace(newWord: FoundWord)

    fun resolveAll(words: Sequence<FoundWord>): Boolean {
        // index unzipping laziness is forced here:
        val replacementWord = words.find { checkWithWritePriority({ isGoodReplacement(it) }, checkTime) } ?: return false
        doReplace(replacementWord)
        return true
    }

    protected fun performReplacement(command: () -> Unit) {
        checkTime()
        appManager.invokeAndWait {
            appManager.runWriteAction {
                CommandProcessor
                        .getInstance()
                        .executeCommand(project, command, "Resolve typo", document, UndoConfirmationPolicy.DEFAULT, document)
            }
        }
        project.statistics.onWordReplaced()
    }
}

private fun checkWithWritePriority(doCheck: () -> Boolean, checkTime: () -> Unit): Boolean {
    val indicator = ProgressIndicatorProvider.getInstance().progressIndicator
    checkTime()

    if (ApplicationManager.getApplication().isDispatchThread) {
        return doCheck()
    }

    var resultFound = false
    var result = false
    while (!resultFound) {
        checkTime()
        resultFound = ProgressManager.getInstance().runInReadActionWithWriteActionPriority({ result = doCheck() }, indicator)
    }
    return result
}