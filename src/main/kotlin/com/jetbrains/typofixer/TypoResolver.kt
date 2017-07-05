package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/**
 * @author bronti
 */

// todo: 'tab' and closing '>' don't work

// todo: smth.getTypoResolver()... how can I even do this?!
fun checkedTypoResolve(element: PsiElement) {
    if (isTypoResolverApplicable(element)) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage("${element.node.elementType.javaClass.canonicalName},  $element", "")
        }
    }
}

fun isTypoResolverApplicable(element: PsiElement): Boolean {
    val elementType = element.node.elementType
    val parent = element.parent

    // todo: not sure whether it is ok to resolve it here
    return elementType == JavaTokenType.IDENTIFIER && parent is PsiReference && parent.resolve() == null
}

