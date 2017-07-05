package com.jetbrains.typofixer.event.handler

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.checkedTypoResolve

/**
 * @author bronti.
 */
class TypoFixEnterHandler: EnterHandlerDelegate {

    // todo:
    var firstAddedCharOffset: Int? = null

    override fun preprocessEnter(psiFile: PsiFile,
                                 editor: Editor,
                                 caretOffset: Ref<Int>,
                                 caretAdvance: Ref<Int>,
                                 dataContext: DataContext,
                                 originalHandler: EditorActionHandler?): Result {
        val caret = editor.caretModel

        // todo: multiple caret. do nothing?
        if (caret.caretCount > 1) return Result.Continue

        firstAddedCharOffset = caretOffset.get()

        return Result.Continue
    }

    override fun postProcessEnter(psiFile: PsiFile, editor: Editor, dataContext: DataContext): Result {
        if (firstAddedCharOffset != null) {
            checkedTypoResolve('\n', firstAddedCharOffset!!, editor, psiFile.project, psiFile)
            firstAddedCharOffset = null
        }
        return Result.Continue
    }

}