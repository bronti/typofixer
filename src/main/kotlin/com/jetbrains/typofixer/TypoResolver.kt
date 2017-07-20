package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport

/**
 * @author bronti
 */

fun checkedTypoResolve(nextChar: Char, editor: Editor, psiFile: PsiFile) {
    val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language)
    if (langSupport != null) doCheckedTypoResolve(nextChar, editor, psiFile, langSupport)
}

private fun doCheckedTypoResolve(nextChar: Char, editor: Editor, psiFile: PsiFile, langSupport: TypoFixerLanguageSupport) {
    if (langSupport.identifierChar(nextChar)) return

    val nextCharOffset = editor.caretModel.offset
    val document = editor.document
    val project = psiFile.project
    val psiManager = PsiDocumentManager.getInstance(project)

    // refresh psi
    psiManager.commitDocument(document)

    val element = psiFile.findElementAt(nextCharOffset - 1)

    if (element != null && langSupport.isTypoResolverApplicable(element)) {
        val elementStartOffset = element.textOffset
        val oldText = element.text.substring(0, nextCharOffset - elementStartOffset)

        val searcher = project.getComponent(TypoFixerComponent::class.java).searcher
        val replacement = searcher.findClosest(oldText, psiFile) ?: return
        if (replacement == oldText) return

        // todo: fix ctrl + z
        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().executeCommand(project, {
                document.replaceString(elementStartOffset, elementStartOffset + oldText.length, replacement)
                editor.caretModel.moveToOffset(nextCharOffset + replacement.length - oldText.length)
            }, null, document, UndoConfirmationPolicy.DEFAULT, document)
        }
        // todo: make language specific
        psiManager.commitDocument(document)
        val newElement = psiFile.findElementAt(elementStartOffset)
        if (newElement != null && newElement.text.substring(0, replacement.length) == replacement) {
            val newParent = newElement.parent

            // todo: write action priority
            Thread {
                // document, newElement
                var parentIsUnresolved = false
                ApplicationManager.getApplication().runReadAction {
                    parentIsUnresolved = newParent.isValid && newParent is PsiReference && newParent.resolve() == null
                }
                if (newParent is PsiErrorElement // <- as far as I can tell it's not likely to happen
                        || parentIsUnresolved) {

                    // todo: undo command
                    ApplicationManager.getApplication().invokeLater {
                        ApplicationManager.getApplication().runWriteAction {
                            CommandProcessor.getInstance().executeCommand(project, {
                                document.replaceString(elementStartOffset, elementStartOffset + replacement.length, oldText)
                                //                            editor.caretModel.moveToOffset(...))
                            }, null, document, UndoConfirmationPolicy.DEFAULT, document)
                        }
                    }
                }
            }.start()
        }
    }
}