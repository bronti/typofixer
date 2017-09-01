package com.jetbrains.typofixer.event.handler

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.TypoResolver

/**
 * @author bronti.
 */
class TypoFixerEventHandler : TypedHandlerDelegate(), EnterHandlerDelegate {
    private var resolver: TypoResolver? = null

    private fun initiateResolve(c: Char, psiFile: PsiFile, editor: Editor) {
        // todo: multiple caret. do nothing?
        if (editor.caretModel.caretCount > 1) return

        resolver = TypoResolver.getResolver(c, editor, psiFile)

        // todo: if c is going to be typed move to charTyped (?)
        resolver?.resolve()

    }

    override fun preprocessEnter(psiFile: PsiFile,
                                 editor: Editor,
                                 caretOffset: Ref<Int>,
                                 caretAdvance: Ref<Int>,
                                 dataContext: DataContext,
                                 originalHandler: EditorActionHandler?): EnterHandlerDelegate.Result {

        initiateResolve('\n', psiFile, editor)

        caretOffset.set(editor.caretModel.offset)
        return EnterHandlerDelegate.Result.Continue
    }

    override fun postProcessEnter(psiFile: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        return EnterHandlerDelegate.Result.Continue
    }

    override fun beforeCharTyped(
            c: Char,
            project: Project,
            editor: Editor?,
            psiFile: PsiFile?,
            fileType: FileType?
    ): TypedHandlerDelegate.Result {
        if (editor == null || psiFile == null) return TypedHandlerDelegate.Result.CONTINUE

        initiateResolve('\n', psiFile, editor)

        return TypedHandlerDelegate.Result.CONTINUE
    }
}

