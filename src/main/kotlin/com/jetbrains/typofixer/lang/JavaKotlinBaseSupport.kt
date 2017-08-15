package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.searcher

abstract class JavaKotlinBaseSupport : TypoFixerLanguageSupport {
    companion object {
        fun identifierChar(c: Char) = c.isJavaIdentifierPart()
        fun isErrorElement(element: PsiElement) = element.parent is PsiErrorElement
    }

    protected fun isBadIdentifier(element: PsiElement, isFast: Boolean): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return isIdentifier(element) && (isErrorElement(element) || if (isFast) isReference(element) else isUnresolvedReference(element))
    }

    protected fun isProperlyReplacedIdentifier(element: PsiElement): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val isGoodKeyword = isKeyword(element) && !isErrorElement(element)

        // other types of identifiers are bad
        val isResolvedIdentifier = isIdentifier(element) && isReference(element) && !isUnresolvedReference(element)

        return isGoodKeyword || isResolvedIdentifier
    }

    private val BAD_IDENTIFIER = object : TypoCase {
        override fun triggersTypoResolve(c: Char) = !identifierChar(c)
        override fun needToReplace(element: PsiElement, fast: Boolean) = isBadIdentifier(element, fast)
        override fun iaBadReplace(element: PsiElement) = !isProperlyReplacedIdentifier(element)
        override fun getReplacement(element: PsiElement, oldText: String, isTooLate: () -> Boolean): Sequence<String> {
            val searcher = element.project.searcher
            return searcher.findClosest(element, oldText, correspondingWordTypes(), isTooLate)
        }
    }

    // order matters
    override fun getTypoCases(): List<TypoCase> = listOf(BAD_IDENTIFIER)

//    protected fun isBadParameter(element: PsiElement, isReplaced: Boolean): Boolean {
//        // todo: difference between primary constructor and other cases
//        return if (!isReplaced) isParameter(element) else !isKeyword(element) || isErrorElement(element)
//    }

    abstract protected fun correspondingWordTypes(): Array<CombinedIndex.WordType>

    abstract protected fun isReference(element: PsiElement): Boolean
    abstract protected fun isIdentifier(element: PsiElement): Boolean
    abstract protected fun isKeyword(element: PsiElement): Boolean
    abstract protected fun isUnresolvedReference(element: PsiElement): Boolean
    abstract protected fun isParameter(element: PsiElement): Boolean
}
