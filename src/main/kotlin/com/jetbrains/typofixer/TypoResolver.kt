package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
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

        // todo: fix ctrl + z
        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().executeCommand(project, {
                document.replaceString(elementStartOffset, elementStartOffset + oldText.length, replacement)
            }, null, document, UndoConfirmationPolicy.DEFAULT, document)
        }
        editor.caretModel.moveToOffset(nextCharOffset + replacement.length - oldText.length)
    }
}