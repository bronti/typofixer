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
        private const val MAX_MILLIS_TO_FIND = 20000
        private const val MAX_MILLIS_TO_RESOLVE = 100000
//        private const val MAX_MILLIS_TO_FIND = 200
//        private const val MAX_MILLIS_TO_RESOLVE = 1000

        private fun isTooLateForFind(timeOfStart: Long) = System.currentTimeMillis() >= timeOfStart + MAX_MILLIS_TO_FIND

        fun getResolver(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            if (!psiFile.project.getComponent(TypoFixerComponent::class.java).isActive) return null

            return doGetResolver(nextChar, editor, psiFile, langSupport, System.currentTimeMillis())
        }

        private fun refreshPsi(editor: Editor) = PsiDocumentManager.getInstance(editor.project!!).commitDocument(editor.document)

        private fun doGetResolver(nextChar: Char, editor: Editor, psiFile: PsiFile, langSupport: TypoFixerLanguageSupport, timeOfStart: Long): TypoResolver? {
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
                    val newText = typoCase.getReplacement(element, oldText, { isTooLateForFind(timeOfStart) }).word

                    if (newText == null || isTooLateForFind(timeOfStart)) return null
                    return TypoResolver(psiFile, editor, typoCase, element, oldText, newText, timeOfStart)
                }
            }
            return null
        }

        @TestOnly
        fun getInstanceIgnoreIsActive(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            return doGetResolver(nextChar, editor, psiFile, langSupport, System.currentTimeMillis())
        }
    }

    private fun refreshPsi() = refreshPsi(editor)
    private fun isTooLate() = System.currentTimeMillis() >= timeOfStart + MAX_MILLIS_TO_RESOLVE

    private val document: Document = editor.document

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
    private fun undoFix(): Boolean = performCommand("Undo incorrect typo resolve", newText) { replaceText(newText, oldText) }

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
            if (!elementIsRefreshed || isTooLate()) return false

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
                if (refreshElement(prefix) && project.isOpen && !isTooLate()) {
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