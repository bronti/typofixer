package com.jetbrains.typofixer.lang

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.jetbrains.typofixer.TypoResolver

/**
 * @author bronti.
 */
// todo: 'tab' doesn't work
class JavaTypoResolver : TypoResolver() {
    override fun afterIdentifierChar(c: Char) = c.isLetter() || c.isDigit() || c == '_'

    override fun isTypoResolverApplicable(element: PsiElement): Boolean {
        val elementType = element.node.elementType
        val parent = element.parent

        // todo: not sure whether it is ok to resolve a reference here
        return elementType == JavaTokenType.IDENTIFIER && parent is PsiReference && parent.resolve() == null
    }
}
