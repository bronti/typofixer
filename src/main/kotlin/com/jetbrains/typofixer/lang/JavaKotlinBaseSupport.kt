package com.jetbrains.typofixer.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.util.IncorrectOperationException
import com.jetbrains.typofixer.TypoCase
import com.jetbrains.typofixer.search.FoundWord
import com.jetbrains.typofixer.search.FoundWordType
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.searcher

abstract class JavaKotlinBaseSupport : TypoFixerLanguageSupport {
    companion object {
        fun identifierChar(c: Char) = c.isJavaIdentifierPart()
        fun isErrorElement(element: PsiElement) = element.parent is PsiErrorElement
    }

    protected fun isGoodKeyword(element: PsiElement) = isKeyword(element) && !isErrorElement(element)

    private abstract inner class BaseJavaKotlin(
            editor: Editor,
            psiFile: PsiFile,
            elementStartOffset: Int,
            oldText: String,
            checkTime: () -> Unit
    ) : TypoCase(editor, psiFile, elementStartOffset, oldText, checkTime) {

        override fun triggersResolve(c: Char) = !identifierChar(c)
        override fun getReplacement(checkTime: () -> Unit)
                = project.searcher.findClosest(psiFile, oldText, correspondingWordTypes(), checkTime)
    }

    private open inner class UnresolvedIdentifier(
            editor: Editor,
            psiFile: PsiFile,
            elementStartOffset: Int,
            oldText: String,
            checkTime: () -> Unit
    ) : BaseJavaKotlin(editor, psiFile, elementStartOffset, oldText, checkTime) {

        private val factory = JavaPsiFacade.getElementFactory(project)
        private val fileCopy by lazy { appManager.runReadAction(Computable { psiFile.copy() }) }
        private val elementCopy
            get() = fileCopy.findElementAt(elementStartOffset)!!
        private val referenceCopy
            get() = elementCopy.parent as PsiJavaCodeReferenceElement

        override fun checkApplicable(fast: Boolean): Boolean {
            assert(isSetUp)
            appManager.assertReadAccessAllowed()
            return isIdentifier(element) && (if (fast) isInReference(element) else isUnresolvedReference(element.parent))
        }

        override fun isGoodReplacement(newWord: FoundWord): Boolean {
            assert(isSetUp)
            appManager.assertReadAccessAllowed()
            val replacement = getReplacement(newWord) ?: return false
            try {
                elementCopy.replace(replacement)
            } catch (e: IncorrectOperationException) {
                return false
            }
            return when (newWord.type) {
                FoundWordType.IDENTIFIER -> !isUnresolvedReference(referenceCopy)
                FoundWordType.KEYWORD -> isGoodKeyword(elementCopy)
            }
        }

        private fun getReplacement(newWord: FoundWord): PsiElement? = try {
            when (newWord.type) {
                FoundWordType.IDENTIFIER -> factory.createIdentifier(newWord.word)
                FoundWordType.KEYWORD -> factory.createKeyword(newWord.word, elementCopy)
            }
        } catch (e: IncorrectOperationException) {
            null
        }
    }

    private open inner class ErrorElement(
            editor: Editor,
            psiFile: PsiFile,
            elementStartOffset: Int,
            oldText: String,
            checkTime: () -> Unit
    ) : BaseJavaKotlin(editor, psiFile, elementStartOffset, oldText, checkTime) {

        private val factory = JavaPsiFacade.getElementFactory(project)
        private val fileCopy by lazy { appManager.runReadAction(Computable { psiFile.copy() }) }
        private val elementCopy
            get() = fileCopy.findElementAt(elementStartOffset)!!

        override fun checkApplicable(fast: Boolean): Boolean {
            assert(isSetUp)
            appManager.assertReadAccessAllowed()
            return isIdentifier(element) && isErrorElement(element)
        }

        override fun isGoodReplacement(newWord: FoundWord): Boolean {
            assert(isSetUp)
            appManager.assertReadAccessAllowed()
            val replacement = getReplacement(newWord) ?: return false
            try {
                elementCopy.parent.replace(replacement)
            } catch (e: IncorrectOperationException) {
                return false
            }
            return when (newWord.type) {
                FoundWordType.IDENTIFIER -> false
                FoundWordType.KEYWORD -> isGoodKeyword(elementCopy)
            }
        }

        private fun getReplacement(newWord: FoundWord): PsiElement? = try {
            when (newWord.type) {
                FoundWordType.IDENTIFIER -> null
                FoundWordType.KEYWORD -> factory.createKeyword(newWord.word, elementCopy)
            }
        } catch (e: IncorrectOperationException) {
            null
        }
    }

    // order matters
    override fun getTypoCases(
            editor: Editor,
            psiFile: PsiFile,
            elementStartOffset: Int,
            oldText: String,
            checkTime: () -> Unit
    ): List<TypoCase> = listOf(
            UnresolvedIdentifier(editor, psiFile, elementStartOffset, oldText, checkTime),
            ErrorElement(editor, psiFile, elementStartOffset, oldText, checkTime)
    )
    abstract protected fun correspondingWordTypes(): Array<CombinedIndex.IndexType>

    abstract protected fun isInReference(element: PsiElement): Boolean
    abstract protected fun isIdentifier(element: PsiElement): Boolean
    abstract protected fun isKeyword(element: PsiElement): Boolean
    abstract protected fun isUnresolvedReference(element: PsiElement): Boolean
    abstract protected fun isInParameter(element: PsiElement): Boolean
}
