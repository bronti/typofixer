package com.jetbrains.typofixer.lang

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.jetbrains.typofixer.TypoResolver

/**
 * @author bronti.
 */
class JavaTypoResolver : TypoResolver() {
    override fun afterIdentifierChar(c: Char) = c.isLetter() || c.isDigit() || c == '_'

    override fun isTypoResolverApplicable(element: PsiElement) =
            element.node.elementType == JavaTokenType.IDENTIFIER && element.parent is PsiReference
}
