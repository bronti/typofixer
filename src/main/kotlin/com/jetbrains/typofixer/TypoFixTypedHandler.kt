package com.jetbrains.typofixer

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * @author bronti
 */

class TypoFixTypedHandler: TypedHandlerDelegate() {

    override fun beforeCharTyped(c: Char, project: Project?, editor: Editor?, psiFile: PsiFile?, fileType: FileType?): Result {
        if (editor == null || psiFile ==  null) return Result.CONTINUE

        val caret = editor.caretModel

        // todo: psiFile.project ==? project

        // todo: find out whether it can happen at all
        if (project == null) throw IllegalArgumentException()

        val psiManager = PsiDocumentManager.getInstance(project)

        // todo: multiple caret. do nothing?
        if (caret.caretCount > 1) return Result.CONTINUE
        if (c.isIdentifier()) return Result.CONTINUE

        // refresh psi (I'm not sure it is necessary)
        psiManager.commitDocument(editor.document)

        val element = psiFile.findElementAt(caret.offset - 1)

        if (element != null) {
            checkedTypoResolve(element)
        }
        return Result.CONTINUE
    }


}

// todo: language specific
fun Char.isIdentifier() = this.isLetter() || this.isDigit() || this == '_'