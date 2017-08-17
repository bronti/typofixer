package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
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

            return doGetResolver(nextChar, editor, psiFile, langSupport)
        }

        private fun refreshPsi(editor: Editor) = PsiDocumentManager.getInstance(editor.project!!).commitDocument(editor.document)

        private fun doGetResolver(nextChar: Char, editor: Editor, psiFile: PsiFile, langSupport: TypoFixerLanguageSupport): TypoResolver? {
            val project = psiFile.project
            val settings = TypoFixerSettings.getInstance(project)
            val stats = project.statistics

            val findTimeChecker = TimeLimitsChecker(settings.maxMillisForFind, stats::onFindAbortedBecauseOfTimeLimits)::checkTime
            val resolveTimeChecker = TimeLimitsChecker(settings.maxMillisForResolve, stats::onResolveAbortedBecauseOfTimeLimits)::checkTime

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
                        searchResults = typoCase.getReplacement(element, oldText, findTimeChecker)
                    } catch (e: ResolveAbortedException) {
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
        fun getInstanceIgnoreIsActive(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            return doGetResolver(nextChar, editor, psiFile, langSupport)
        }
    }

    private fun refreshPsi() = refreshPsi(editor)

    private val document: Document = editor.document
    private var elementStartOffset = element.textOffset
    // todo: make sure distance is calculated once for each word

    private val project get() = psiFile.project

    private val appManager = ApplicationManager.getApplication()

    fun resolve() = Thread {
        try {
            doResolve()
        } catch (e: ResolveAbortedException) {
            // do nothing
        }
    }.start()

    private fun doResolve() {
        if (!elementIsBad(true, oldText)) return
        // index unzipping laziness is forced here:
        replacements.forEach {
            checkTime()
            if (doOneResolve(it.word)) return
        }
    }

    // return true if resolve was successful
    private fun doOneResolve(newText: String): Boolean {
        if (newText == oldText) return false
        fixTypo(newText)
        try {
            checkTime()
            if (!elementIsBad(false, newText)) return true
        } catch (e: ResolveAbortedException) {
            // suppressing exception so we can rollback replacement
        }
        undoFix(newText)
        return false
    }


    private fun fixTypo(newText: String): Boolean = performCommand("Resolve typo", oldText) {
        replaceText(oldText, newText)
        project.statistics.onWordReplaced()
    }

    private fun undoFix(newText: String): Boolean = performCommand("Undo incorrect typo resolve", newText) {
        replaceText(newText, oldText)
        project.statistics.onReplacementRolledBack()
    }

    private fun elementIsBad(isBeforeReplace: Boolean, expectedText: String): Boolean {
        var result = true
        val indicator = ProgressIndicatorProvider.getInstance().progressIndicator

        fun doCheckElement() {
            ProgressIndicatorProvider.checkCanceled()
            result = if (isBeforeReplace) typoCase.needToReplace(element) else typoCase.iaBadReplace(element)
        }

        var resultCalculated = false
        while (!resultCalculated) {
            checkTime()
            var elementIsRefreshed = false
            appManager.invokeAndWait { appManager.runReadAction { elementIsRefreshed = refreshElement(expectedText) } }
            if (!elementIsRefreshed) throw ResolveAbortedException() // something strange happened. we cannot do anything

            checkTime()
            resultCalculated =
                    if (appManager.isDispatchThread) {
                        doCheckElement(); true
                    } else ProgressManager.getInstance().runInReadActionWithWriteActionPriority(::doCheckElement, indicator)
        }
        return result
    }

    private fun performCommand(name: String, prefix: String, command: () -> Unit): Boolean {
        var done = false
        appManager.invokeAndWait {
            appManager.runWriteAction {
                if (refreshElement(prefix) && project.isOpen) {
                    val commandProcessor = CommandProcessor.getInstance()
                    commandProcessor.executeCommand(project, command, name, document, UndoConfirmationPolicy.DEFAULT, document)
                    done = true
                }
            }
        }
        return done
    }

    private fun replaceText(old: String, new: String) {
        document.replaceString(elementStartOffset, elementStartOffset + old.length, new)
    }

    private fun refreshElement(startingText: String = ""): Boolean {
        refreshPsi()
        val newElement = psiFile.findElementAt(elementStartOffset)
        if (newElement == null || !newElement.text.startsWith(startingText)) return false
        element = newElement
        elementStartOffset = element.textOffset
        return true
    }

    @TestOnly
    fun waitForResolve() = doResolve()
}

class ResolveAbortedException : RuntimeException()

private class TimeLimitsChecker(private val timeConstraint: Long, private val reportAbort: () -> Unit) {
    private var abortReported = false
    private val startTime = System.currentTimeMillis()
    fun checkTime(): Unit {
        val result = startTime + timeConstraint <= System.currentTimeMillis()
        if (result && !abortReported) reportAbort()
        if (result) throw ResolveAbortedException()
    }
}