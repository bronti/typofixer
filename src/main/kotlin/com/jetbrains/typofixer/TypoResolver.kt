package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.jetbrains.typofixer.resolve.FuzzySearcher

/**
 * @author bronti
 */

// todo: 'tab' and closing '>' don't work

// todo: LanguageExtensionPoint
fun checkedTypoResolve(nextChar: Char, nextCharOffset: Int, editor: Editor, project: Project, psiFile: PsiFile) {
    if (nextChar.isIdentifier()) return

    val psiManager = PsiDocumentManager.getInstance(project)

    // refresh psi
    psiManager.commitDocument(editor.document)

    val element = psiFile.findElementAt(nextCharOffset - 1)

    if (element != null && isTypoResolverApplicable(element)) {
        val searcher = project.getComponent(FuzzySearcher::class.java)
        val replacement = searcher.findClosest(element.text)

        ApplicationManager.getApplication().runWriteAction {
            editor.document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
        }
    }
}

fun checkedTypoBeforeNextCharTypedResolve(nextChar: Char, nextCharOffset: Int, editor: Editor, project: Project, psiFile: PsiFile) {
    // todo: resolve left parents
}


fun isTypoResolverApplicable(element: PsiElement): Boolean {
    val elementType = element.node.elementType
    val parent = element.parent

    // todo: not sure whether it is ok to resolve it here
    return elementType == JavaTokenType.IDENTIFIER && parent is PsiReference && parent.resolve() == null
}

// todo: language specific
fun Char.isIdentifier() = this.isLetter() || this.isDigit() || this == '_'

