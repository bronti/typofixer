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
        private val timeOfStart: Long) {

    companion object {
        //todo: make settings
        private const val MAX_MILLIS_TO_FIND = 200
        private const val MAX_MILLIS_TO_RESOLVE = 1000

        private fun isTooLateForFind(timeOfStart: Long) = System.currentTimeMillis() >= timeOfStart + MAX_MILLIS_TO_FIND

        fun getInstance(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            if (!psiFile.project.getComponent(TypoFixerComponent::class.java).isActive) return null

            return doGetInstance(nextChar, editor, psiFile, langSupport, System.currentTimeMillis())
        }

        private fun doGetInstance(nextChar: Char, editor: Editor, psiFile: PsiFile, langSupport: TypoFixerLanguageSupport, timeOfStart: Long): TypoResolver? {

            val project = psiFile.project
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

                    val searcher = project.getComponent(TypoFixerComponent::class.java).searcher
                    val newText = searcher.findClosest(element, oldText, { isTooLateForFind(timeOfStart) }).word

                    if (newText == null || newText == oldText || isTooLateForFind(timeOfStart)) return null
                    return TypoResolver(psiFile, editor, typoCase, element, oldText, newText, timeOfStart)
                }
            }
            return null
        }

        private fun refreshPsi(editor: Editor) = PsiDocumentManager.getInstance(editor.project!!).commitDocument(editor.document)

        @TestOnly
        fun getInstanceIgnoreIsActive(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            return doGetInstance(nextChar, editor, psiFile, langSupport, System.currentTimeMillis())
        }
    }

    private fun refreshPsi() = refreshPsi(editor)
    private fun isTooLate() = System.currentTimeMillis() >= timeOfStart + MAX_MILLIS_TO_RESOLVE

    private val document: Document = editor.document
    //    private val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language)!!
    private val project
        get() = psiFile.project

    private var elementStartOffset = element.textOffset

    private val appManager = ApplicationManager.getApplication()

    fun resolve() = Thread { doResolve() }.start()

    private fun doResolve() {
        checkElementIsBad(true) && !isTooLate()
                && fixTypo() && !isTooLate()
                && checkElementIsBad(false) && !isTooLate()
                && undoFix()
    }

    private fun fixTypo(): Boolean = performCommand("Resolve typo", oldText) { replaceText(oldText, newText) }

    private fun undoFix(): Boolean = performCommand("Undo typo resolve", newText) { replaceText(newText, oldText) }

    private fun refreshElement(startingText: String = ""): Boolean {
        refreshPsi()
        val newElement = psiFile.findElementAt(elementStartOffset)
        if (newElement == null || !newElement.text.startsWith(startingText)) return false
        element = newElement
        elementStartOffset = element.textOffset
        return true
    }

    private fun checkElementIsBad(isBeforeReplace: Boolean): Boolean {
        var result = true
        val indicator = ProgressIndicatorProvider.getInstance().progressIndicator

        fun doCheck() {
            result = if (isBeforeReplace) typoCase.needToReplace(element) else typoCase.iaBadReplace(element)
        }

        var resultCalculated = false
        while (!resultCalculated) {
            val expectedText = if (isBeforeReplace) oldText else newText

            var elementIsRefreshed = false
            appManager.invokeAndWait { appManager.runReadAction { elementIsRefreshed = refreshElement(expectedText) } }
            if (!elementIsRefreshed || isTooLate()) return false

            resultCalculated =
                    if (ApplicationManager.getApplication().isDispatchThread) {
                        doCheck()
                        true
                    } else {
                        ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                            ProgressIndicatorProvider.checkCanceled()
                            doCheck()
                        }, indicator)
                    }
        }
        return result
    }

    private fun performCommand(name: String, prefix: String, command: () -> Unit): Boolean {
        var done = false
        appManager.invokeAndWait {
            appManager.runWriteAction {
                if (refreshElement(prefix)) {
                    if (project.isOpen && !isTooLate()) {
                        CommandProcessor.getInstance().executeCommand(project, command, name, document, UndoConfirmationPolicy.DEFAULT, document)
                        done = true
                    }
                }
            }
        }
        return done
    }

    private fun replaceText(old: String, new: String) {
        document.replaceString(elementStartOffset, elementStartOffset + old.length, new)
    }

    @TestOnly
    fun waitForResolve() = doResolve()
}