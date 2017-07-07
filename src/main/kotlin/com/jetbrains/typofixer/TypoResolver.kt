package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.DLSearcherProvider

/**
 * @author bronti
 */

fun checkedTypoResolve(nextChar: Char, editor: Editor, psiFile: PsiFile) {
    val langSupport = TypoFixerLanguageSupport.Extension.getSupport(psiFile.language)
    doCheckedTypoResolve(nextChar, editor, psiFile, langSupport)
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
        val searcher = project.getComponent(DLSearcherProvider::class.java).getSearcher()

        val oldText = element.text
        val replacement = searcher.findClosestInFile(oldText, psiFile)

        replacement ?: return

        ApplicationManager.getApplication().runWriteAction {
            editor.document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
        }
        editor.caretModel.moveToOffset(nextCharOffset + replacement.length - oldText.length)
    }
}