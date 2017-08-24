package com.jetbrains.typofixer.event.handler

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.TypoResolver

/**
 * @author bronti
 */

class TypoFixTypedHandler : TypedHandlerDelegate() {
    private var resolver: TypoResolver? = null

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor?, psiFile: PsiFile?, fileType: FileType?): Result {
        if (editor == null || psiFile == null) return Result.CONTINUE

        // todo: multiple caret. do nothing?
        if (editor.caretModel.caretCount > 1) return Result.CONTINUE

        resolver = TypoResolver.getResolver(c, editor, psiFile)

        // todo: if c is going to be typed move to charTyped (?)
        resolver?.resolve()

        return Result.CONTINUE
    }

    override fun charTyped(c: Char, project: Project?, editor: Editor, psiFile: PsiFile): Result {
        return Result.CONTINUE
    }
}
