package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/**
 * @author bronti
 */

// todo: 'tab' and closing '>' don't work

// todo: smth.getTypoResolver(language)... how can I even do this?!
fun checkedTypoResolve(element: PsiElement, document: Document, project: Project) {
    if (isTypoResolverApplicable(element)) {

        val searcher = project.getComponent(FuzzySearcher::class.java)
        val replacement = searcher.findClosest(element.text)

        ApplicationManager.getApplication().runWriteAction {
            document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
        }
    }
}

fun isTypoResolverApplicable(element: PsiElement): Boolean {
    val elementType = element.node.elementType
    val parent = element.parent

    // todo: not sure whether it is ok to resolve it here
    return elementType == JavaTokenType.IDENTIFIER && parent is PsiReference && parent.resolve() == null
}

