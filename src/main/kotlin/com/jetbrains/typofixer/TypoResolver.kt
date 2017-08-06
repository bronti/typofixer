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

/**
 * @author bronti
 */
class TypoResolver private constructor(
        private val psiFile: PsiFile,
        private val editor: Editor,
        private val typoCase: TypoCase,
        private var element: PsiElement,
        private val oldText: String,
        private val newText: String) {

    companion object {
        fun getInstance(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            val nextCharOffset = editor.caretModel.offset
            val project = psiFile.project

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
                    val newText = searcher.findClosest(element, oldText).word

                    if (newText == null || newText == oldText) return null
                    return TypoResolver(psiFile, editor, typoCase, element, oldText, newText)
                }
            }
            return null
        }

        private fun refreshPsi(editor: Editor) = PsiDocumentManager.getInstance(editor.project!!).commitDocument(editor.document)
    }

    private fun refreshPsi() = refreshPsi(editor)

    private val document: Document = editor.document
    //    private val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language)!!
    private val project
        get() = psiFile.project

    private var elementStartOffset = element.textOffset

    private val appManager = ApplicationManager.getApplication()

    fun resolve() = Thread { checkElementIsBad(true) && fixTypo() && checkElementIsBad(false) && undoFix() }.start()

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

        var resultCalculated = false
        while (!resultCalculated) {
            val expectedText = if (isBeforeReplace) oldText else newText
            appManager.invokeAndWait { appManager.runReadAction { result = refreshElement(expectedText) } }
            if (!result) return false
            resultCalculated = ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                ProgressIndicatorProvider.checkCanceled()
                result = if (isBeforeReplace) typoCase.needToReplace(element) else typoCase.iaBadReplace(element)
            }, indicator)
        }
        return result
    }

    private fun performCommand(name: String, prefix: String, command: () -> Unit): Boolean {
        var done = false
        appManager.invokeAndWait {
            appManager.runWriteAction {
                if (refreshElement(prefix)) {
                    if (project.isOpen) {
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
}