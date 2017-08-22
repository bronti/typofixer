package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.jetbrains.typofixer.search.FoundWord
import com.jetbrains.typofixer.search.FoundWordType
import com.jetbrains.typofixer.search.SearchResults
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.searcher

abstract class JavaKotlinBaseSupport : TypoFixerLanguageSupport {
    companion object {
        fun identifierChar(c: Char) = c.isJavaIdentifierPart()
        fun isErrorElement(element: PsiElement) = element.parent is PsiErrorElement
    }

    protected fun isBadIdentifier(element: PsiElement, isFast: Boolean): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return isIdentifier(element) && (isErrorElement(element) || if (isFast) isInReference(element) else isUnresolvedReference(element.parent))
    }

    protected fun isGoodKeyword(element: PsiElement) = isKeyword(element) && !isErrorElement(element)

    private val BAD_IDENTIFIER = object : TypoCase {
        override fun triggersTypoResolve(c: Char) = !identifierChar(c)
        override fun needToReplace(element: PsiElement, fast: Boolean) = isBadIdentifier(element, fast)
//        override fun isGoodReplacementForIdentifier(element: PsiElement, newText: String)
//                = isIdentifier(element) && isInReference(element) && isResolvableIdentifierInReference(newText, element)
//
//        override fun isBadlyReplacedKeyword(element: PsiElement) = !isGoodKeyword(element)

        override fun getResolver(element: PsiElement, newWord: FoundWord): Resolver {
            ApplicationManager.getApplication().assertReadAccessAllowed()
            if (!element.isValid || newWord.type == FoundWordType.KEYWORD) return Resolver.UNSUCCESSFUL
            return checkedResolveIdentifierReference(newWord.word, element)
        }

        override fun getReplacement(element: PsiElement, oldText: String, checkTime: () -> Unit): SearchResults {
            val searcher = element.project.searcher
            return searcher.findClosest(element, oldText, correspondingWordTypes(), checkTime)
        }

    }

    // order matters
    override fun getTypoCases(): List<TypoCase> = listOf(BAD_IDENTIFIER)
    abstract protected fun correspondingWordTypes(): Array<CombinedIndex.IndexType>

    abstract protected fun isInReference(element: PsiElement): Boolean
    abstract protected fun isIdentifier(element: PsiElement): Boolean
    abstract protected fun isKeyword(element: PsiElement): Boolean
    abstract protected fun isUnresolvedReference(element: PsiElement): Boolean
    abstract protected fun isInParameter(element: PsiElement): Boolean
    abstract protected fun checkedResolveIdentifierReference(text: String, element: PsiElement): Resolver
}
