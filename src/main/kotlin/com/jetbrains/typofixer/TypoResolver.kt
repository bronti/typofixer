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
        private val newText: String,
        private val resolveTimeChecker: TimeLimitsChecker) {

    companion object {
        class TimeLimitsChecker(private val timeConstraint: Long, private val reportAbort: () -> Unit) {
            private var abortReported = false
            private val startTime = System.currentTimeMillis()
            fun isTooLate(): Boolean {
                val result = startTime + timeConstraint <= System.currentTimeMillis()
                if (result && !abortReported) reportAbort()
                return result
            }
        }

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

            val findChecker = TimeLimitsChecker(settings.maxMillisForFind.toLong(), { stats.onFindAbortedBecauseOfTimeLimits() })
            val resolveChecker = TimeLimitsChecker(settings.maxMillisForResolve.toLong(), { stats.onResolveAbortedBecauseOfTimeLimits() })

            val nextCharOffset = editor.caretModel.offset

            var element: PsiElement? = null
            for (typoCase in langSupport.getTypoCases()) {
                if (!typoCase.triggersTypoResolve(nextChar)) continue

                if (element == null) {
                    refreshPsi(editor)
                    element = psiFile.findElementAt(nextCharOffset - 1) ?: return null
                }

                val elementStartOffset = element.textOffset

                if (typoCase.needToReplace(element, fast = true)) {

                    val oldText = element.text.substring(0, nextCharOffset - elementStartOffset)
                    val newText = typoCase.getReplacement(element, oldText, { findChecker.isTooLate() }).word

                    if (newText == null || findChecker.isTooLate()) return null

                    project.statistics.onTypoResolverCreated()
                    return TypoResolver(psiFile, editor, typoCase, element, oldText, newText, resolveChecker)
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

    private val project
        get() = psiFile.project

    private var elementStartOffset = element.textOffset

    private val appManager = ApplicationManager.getApplication()

    fun resolve() = Thread { doResolve() }.start()

    private fun doResolve() {
        checkElementIsBad(true) && !resolveTimeChecker.isTooLate()
                && fixTypo() && !resolveTimeChecker.isTooLate()
                && checkElementIsBad(false) && !resolveTimeChecker.isTooLate()
                && undoFix()
    }

    private fun fixTypo(): Boolean = performCommand("Resolve typo", oldText) {
        replaceText(oldText, newText)
        project.statistics.onWordReplaced()
    }

    private fun undoFix(): Boolean = performCommand("Undo incorrect typo resolve", newText) {
        replaceText(newText, oldText)
        project.statistics.onReplacementRolledBack()
    }

    private fun checkElementIsBad(isBeforeReplace: Boolean): Boolean {
        var result = true
        val indicator = ProgressIndicatorProvider.getInstance().progressIndicator

        fun doCheck() {
            ProgressIndicatorProvider.checkCanceled()
            result = if (isBeforeReplace) typoCase.needToReplace(element) else typoCase.iaBadReplace(element)
        }

        var resultCalculated = false
        while (!resultCalculated) {
            val expectedText = if (isBeforeReplace) oldText else newText

            var elementIsRefreshed = false
            appManager.invokeAndWait { appManager.runReadAction { elementIsRefreshed = refreshElement(expectedText) } }
            if (!elementIsRefreshed || resolveTimeChecker.isTooLate()) return false

            resultCalculated =
                    if (appManager.isDispatchThread) {
                        doCheck(); true
                    } else ProgressManager.getInstance().runInReadActionWithWriteActionPriority(::doCheck, indicator)
        }
        return result
    }

    private fun performCommand(name: String, prefix: String, command: () -> Unit): Boolean {
        var done = false
        appManager.invokeAndWait {
            appManager.runWriteAction {
                if (refreshElement(prefix) && project.isOpen && !resolveTimeChecker.isTooLate()) {
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