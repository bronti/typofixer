package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement

abstract class JavaKotlinBaseSupport : TypoFixerLanguageSupport {
    override fun identifierChar(c: Char) = c.isJavaIdentifierPart()

    override fun isBadReferenceOrKeyword(element: PsiElement, isReplaced: Boolean, isFast: Boolean): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        val isIdentifierReference = isIdentifier(element) && isReference(element)
        val isBadKeyword = isKeyword(element) && element.parent is PsiErrorElement

        if (isFast) {
            assert(!isReplaced)
            return isBadKeyword || isIdentifierReference
        } else {

            val isUnresolvedReference = isIdentifierReference && isUnresolved(element)
            val isIdentifierNotReference = isIdentifier(element) && !isReference(element)
            val isSomethingElse = !isIdentifier(element) && !isKeyword(element)

            return isBadKeyword || isUnresolvedReference || (isReplaced && (isIdentifierNotReference || isSomethingElse))
        }
    }

    override fun isBadParameter(element: PsiElement, isReplaced: Boolean): Boolean {
        // todo: difference between primary constructor and other cases
        return if (!isReplaced) isParameter(element) else !isKeyword(element) || element.parent is PsiErrorElement
    }

    abstract fun isReference(element: PsiElement): Boolean
    abstract fun isIdentifier(element: PsiElement): Boolean
    abstract fun isKeyword(element: PsiElement): Boolean
    abstract fun isUnresolved(element: PsiElement): Boolean
    abstract fun isParameter(element: PsiElement): Boolean
}
