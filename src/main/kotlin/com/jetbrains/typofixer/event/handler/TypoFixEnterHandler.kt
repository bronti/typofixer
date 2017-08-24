package com.jetbrains.typofixer.event.handler

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.TypoResolver

/**
 * @author bronti.
 */
class TypoFixEnterHandler : EnterHandlerDelegate {
    private var resolver: TypoResolver? = null

    override fun preprocessEnter(psiFile: PsiFile,
                                 editor: Editor,
                                 caretOffset: Ref<Int>,
                                 caretAdvance: Ref<Int>,
                                 dataContext: DataContext,
                                 originalHandler: EditorActionHandler?): Result {
        val caret = editor.caretModel

        if (caret.caretCount > 1) return Result.Continue

        resolver = TypoResolver.getResolver('\n', editor, psiFile)
        caretOffset.set(editor.caretModel.offset)

        return Result.Continue
    }

    override fun postProcessEnter(psiFile: PsiFile, editor: Editor, dataContext: DataContext): Result {
        resolver?.resolve()
        return Result.Continue
    }

}