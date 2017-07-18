package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.DLSearcher

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
    val project = psiFile.project

    val psiManager = PsiDocumentManager.getInstance(project)

    // refresh psi
    psiManager.commitDocument(editor.document)

    val element = psiFile.findElementAt(nextCharOffset - 1)

    if (element != null && langSupport.isTypoResolverApplicable(element)) {
        val oldText = element.text.substring(0, nextCharOffset - element.textOffset)

        val replacement = project.getComponent(DLSearcher::class.java).findClosest(oldText, psiFile) ?: return

        ApplicationManager.getApplication().runWriteAction {
            editor.document.replaceString(element.textOffset, element.textOffset + oldText.length, replacement)
        }
        editor.caretModel.moveToOffset(nextCharOffset + replacement.length - oldText.length)
    }
}