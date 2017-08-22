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
import com.jetbrains.typofixer.lang.TypoCase
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
        private val editor: Editor,
        private val typoCase: TypoCase,
        private var element: PsiElement,
        private val oldText: String,
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
            val element: PsiElement? by lazy {
                refreshPsi(editor)
                psiFile.findElementAt(nextCharOffset - 1)
            }

            for (typoCase in langSupport.getTypoCases()) {
                if (!typoCase.triggersTypoResolve(nextChar)) continue

                val elementStartOffset = element?.textOffset ?: return null

                if (typoCase.needToReplace(element, fast = true)) {

                    val oldText = element.text.substring(0, nextCharOffset - elementStartOffset)

                    val searchResults: SearchResults
                    try {
                        // todo: check validity (?)
                        searchResults = typoCase.getReplacement(element, oldText, findTimeChecker)
                    } catch (e: ResolveCancelledException) {
                        return null
                    }

                    if (searchResults.none()) return null

                    val replacements = searchResults.sortedBy { project.searcher.distanceProvider.measure(oldText, it.word) }

                    project.statistics.onTypoResolverCreated()
                    return TypoResolver(psiFile, editor, typoCase, element, oldText, replacements, resolveTimeChecker)
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

    private fun refreshElementAndCheckResolveCancelled() {
        checkTime()
        if (!refreshElement()) throw ResolveCancelledException()
        checkTime()
        // todo: refresh statistics
        if (!project.isOpen) throw ResolveCancelledException()
    }

    private val document: Document = editor.document
    private var elementStartOffset = element.textOffset
    // todo: make sure distance is calculated once for each word

    private val project get() = psiFile.project

    private val appManager = ApplicationManager.getApplication()

    fun resolve() = Thread {
        try {
            doResolve()
        } catch (e: ResolveCancelledException) {
            // do nothing
        }
    }.start()

    private fun doResolve() {
        if (!getWithWritePriority { typoCase.needToReplace(element, false) }) return
        // index unzipping laziness is forced here:
        for (replacement in replacements) {
            refreshElementAndCheckResolveCancelled()
            val resolver = getWithWritePriority { typoCase.getResolver(element, replacement) }
            if (!resolver.isSuccessful) continue
            checkTime()
            performCommand("Resolve typo", resolver::resolve)
            project.statistics.onWordReplaced()
            return
        }
    }

    private fun <T> getWithWritePriority(doGet: () -> T): T {
        val indicator = ProgressIndicatorProvider.getInstance().progressIndicator

        while (true) {
            checkTime()
            if (appManager.isDispatchThread) {
                return doGet()
            } else {
                var result: T? = null
                val resultFound = ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                    result = doGet()
                }, indicator)
                if (resultFound) {
                    return result!!
                }
            }
        }
    }

    private fun performCommand(name: String, command: () -> Unit) {
        appManager.invokeAndWait {
            appManager.runWriteAction {
                CommandProcessor
                        .getInstance()
                        .executeCommand(project, command, name, document, UndoConfirmationPolicy.DEFAULT, document)
            }
        }
    }

    private fun refreshElement(): Boolean {
        appManager.invokeAndWait { refreshPsi(editor) }
        return appManager.runReadAction(Computable {
            val newElement = psiFile.findElementAt(elementStartOffset)
            if (newElement != null && newElement.text.startsWith(oldText)) {
                element = newElement
                elementStartOffset = element.textOffset
                true
            } else false
        })
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