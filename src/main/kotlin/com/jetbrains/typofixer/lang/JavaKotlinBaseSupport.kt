package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.util.IncorrectOperationException
import com.jetbrains.typofixer.TypoCase
import com.jetbrains.typofixer.search.FoundWord
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

//    protected fun isGoodKeyword(element: PsiElement) = isKeyword(element) && !isErrorElement(element)

    private open inner class BadIdentifier(
            document: Document,
            checkTime: () -> Unit,
            getElement: () -> PsiElement?
    ) : TypoCase(document, checkTime, getElement) {

        private val factory by lazy { JavaPsiFacade.getElementFactory(project) }
        private val referenceCopy by lazy { element.containingFile.copy().findReferenceAt(element.textOffset) as PsiJavaCodeReferenceElement }
        private val elementCopy by lazy { referenceCopy.findElementAt(element.startOffsetInParent)!! }

        override fun triggersResolve(c: Char) = !identifierChar(c)
        override fun isApplicable(fast: Boolean) = isBadIdentifier(element, fast)
        override fun getReplacement(oldText: String, checkTime: () -> Unit): SearchResults {
            val searcher = element.project.searcher
            return searcher.findClosest(element, oldText, correspondingWordTypes(), checkTime)
        }

        override fun doReplace(newWord: FoundWord) {
            ApplicationManager.getApplication().assertReadAccessAllowed()
            val replacement = try {
                factory.createIdentifier(newWord.word)
            } catch (e: IncorrectOperationException) {
                throw IllegalStateException()
            }
            performReplacement { element.replace(replacement) }
        }

        override fun isGoodReplacement(newWord: FoundWord): Boolean {
            val replacement = try {
                factory.createIdentifier(newWord.word)
            } catch (e: IncorrectOperationException) {
                return false
            }
            try {
                elementCopy.replace(replacement)
            } catch (e: IncorrectOperationException) {
                throw IllegalStateException()
            }
            return (!isUnresolvedReference(referenceCopy))
        }
    }

    // order matters
    override fun getTypoCases(document: Document,
                              checkTime: () -> Unit,
                              getElement: () -> PsiElement?): List<TypoCase>
            = listOf(BadIdentifier(document, checkTime, getElement))
    abstract protected fun correspondingWordTypes(): Array<CombinedIndex.IndexType>

    abstract protected fun isInReference(element: PsiElement): Boolean
    abstract protected fun isIdentifier(element: PsiElement): Boolean
    abstract protected fun isKeyword(element: PsiElement): Boolean
    abstract protected fun isUnresolvedReference(element: PsiElement): Boolean
    abstract protected fun isInParameter(element: PsiElement): Boolean
}
