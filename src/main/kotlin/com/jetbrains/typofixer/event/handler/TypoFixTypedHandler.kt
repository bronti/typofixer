package com.jetbrains.typofixer.event.handler

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.checkedTypoResolve

/**
 * @author bronti
 */

class TypoFixTypedHandler: TypedHandlerDelegate() {

    override fun beforeCharTyped(c: Char, project: Project?, editor: Editor?, psiFile: PsiFile?, fileType: FileType?): Result {
        if (editor == null || psiFile ==  null) return Result.CONTINUE

        // todo: psiFile.project ==? project
        // todo: find out whether it can happen at all
        if (project == null) throw IllegalArgumentException()

        // todo: multiple caret. do nothing?
        if (editor.caretModel.caretCount > 1) return Result.CONTINUE

        // todo: prevent second replacing (?)
        checkedTypoResolve(c, editor.caretModel.offset, editor, project, psiFile)

        return Result.CONTINUE
    }

    override fun charTyped(c: Char, project: Project?, editor: Editor, psiFile: PsiFile): Result {
        return Result.CONTINUE
    }
}
