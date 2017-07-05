package com.jetbrains.typofixer

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * @author bronti.
 */
class TypoFixEnterHandler: EnterHandlerDelegate {
    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext) = Result.Continue

    override fun preprocessEnter(psiFile: PsiFile,
                                 editor: Editor,
                                 caretOffset: Ref<Int>,
                                 caretAdvance: Ref<Int>,
                                 dataContext: DataContext,
                                 originalHandler: EditorActionHandler?): Result {
        val caret = editor.caretModel
        val project = psiFile.project

        val psiManager = PsiDocumentManager.getInstance(project)

        // todo: multiple caret. do nothing?
        if (caret.caretCount > 1) return Result.Continue

        // refresh psi
        psiManager.commitDocument(editor.document)

        val element = psiFile.findElementAt(caret.offset - 1)

        if (element != null) {
            checkedTypoResolve(element)
        }

        return Result.Continue
    }

}