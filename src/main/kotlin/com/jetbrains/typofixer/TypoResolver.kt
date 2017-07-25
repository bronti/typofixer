package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport

/**
 * @author bronti
 */

class TypoResolver(
        nextChar: Char,
        private val editor: Editor,
        private val psiFile: PsiFile) {

    private val project = psiFile.project
    private val document = editor.document

    private fun refreshPsi() = PsiDocumentManager.getInstance(project).commitDocument(document)

    private val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language)

    private var resolveStillValid: Boolean

    private val elementStartOffset: Int
    private val element: PsiElement?
    private val oldText: String
    private val newText: String

    init {
        val nextCharOffset = editor.caretModel.offset
        element =
                if (langSupport != null && !langSupport.identifierChar(nextChar)) {
                    refreshPsi()
                    psiFile.findElementAt(nextCharOffset - 1)
                }
                else null

        elementStartOffset = element?.textOffset ?: -1

        if (element != null && langSupport!!.isTypoResolverApplicable(element)) {
            oldText = element.text.substring(0, nextCharOffset - elementStartOffset)

            val searcher = project.getComponent(TypoFixerComponent::class.java).searcher
            val replacement = searcher.findClosest(oldText, psiFile)

            resolveStillValid = replacement != null && replacement != oldText
            newText = replacement ?: ""
        }
        else {
            resolveStillValid = false
            oldText = ""
            newText = ""
        }
    }

    fun resolve() {
        if (resolveStillValid && element != null && element.isValid && element.text.startsWith(oldText)) {
            fixTypo()

            // todo: make language specific
            PsiDocumentManager.getInstance(project).commitDocument(document)

            val newElement = psiFile.findElementAt(elementStartOffset) ?: return
            if (!newElement.text.startsWith(newText)) return

            Thread { checkedUndoFix(newElement) }.start()
        }
    }

    private fun fixTypo() {
        // todo: fix ctrl + z
        // todo: see EditorComponentImpl.replaceText (transactions?)

//        CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
        CommandProcessor.getInstance().executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction {
                document.replaceString(elementStartOffset, elementStartOffset + oldText.length, newText)
            }
        }, "Resolve Typo", null, UndoConfirmationPolicy.DEFAULT, document)

        editor.caretModel.moveToOffset(elementStartOffset + newText.length)
    }

    private fun checkedUndoFix(element: PsiElement) {
        if (typoWasNotFixed(element)) {
            ApplicationManager.getApplication().invokeLater {
                CommandProcessor.getInstance().executeCommand(project, {
                    ApplicationManager.getApplication().runWriteAction {
                        if (element.parent.isValid) {
                            document.replaceString(elementStartOffset, elementStartOffset + newText.length, oldText)
                        }
                    }
                }, null, document, UndoConfirmationPolicy.DEFAULT, document)
            }
        }
    }

    private fun typoWasNotFixed(element: PsiElement): Boolean {
        var result = false
        val indicator = ProgressIndicatorProvider.getInstance().progressIndicator
        var resolveFinished = false
        while (!resolveFinished) {
            resolveFinished = ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                val parent = element.parent
                ProgressIndicatorProvider.checkCanceled()
                result = parent.isValid &&
                        (parent is PsiErrorElement  // <- as far as I can tell it's not likely to happen
                                || parent is PsiReference && parent.resolve() == null)
            }, indicator)
        }
        return result
    }
}