package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
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

    protected fun isBadIdentifier(element: PsiElement, isFast: Boolean): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return isIdentifier(element) && (isErrorElement(element) || if (isFast) isInReference(element) else isUnresolvedReference(element.parent))
    }

//    protected fun isGoodKeyword(element: PsiElement) = isKeyword(element) && !isErrorElement(element)

    private open inner class BadIdentifier(
            editor: Editor,
            psiFile: PsiFile,
            elementStartOffset: Int,
            oldText: String,
            checkTime: () -> Unit
    ) : TypoCase(editor, psiFile, elementStartOffset, oldText, checkTime) {

        private val factory = JavaPsiFacade.getElementFactory(project)
        private val fileCopy by lazy { appManager.runReadAction(Computable { psiFile.copy() }) }
        private val referenceCopy
            get() = fileCopy.findReferenceAt(elementStartOffset) as PsiJavaCodeReferenceElement
        private val elementCopy
            get() = referenceCopy.findElementAt(elementOffsetInParent)!!.apply { assert(isIdentifier(this)) }

        override fun triggersResolve(c: Char) = !identifierChar(c)
        override fun checkApplicable(fast: Boolean): Boolean {
            assert(isSetUp)
            appManager.assertReadAccessAllowed()
            // todo: element.isValid?!
            return isBadIdentifier(element, fast)
        }

        override fun getReplacement(checkTime: () -> Unit)
                = project.searcher.findClosest(psiFile, oldText, correspondingWordTypes(), checkTime)

        override fun doReplace(newWord: FoundWord) {
            assert(isSetUp)
            performReplacement {
                //                element.replace(replacement!!) // doesn't work (why?)
                editor.document.replaceString(elementStartOffset, elementStartOffset + oldText.length, newWord.word)
            }
        }

        private fun getReplacement(newWord: FoundWord): PsiElement? = when (newWord.type) {
            FoundWordType.IDENTIFIER -> try {
                factory.createIdentifier(newWord.word)
            } catch (e: IncorrectOperationException) {
                //todo:
                throw IllegalStateException()
            }
            FoundWordType.KEYWORD -> null //todo:
        }

        override fun isGoodReplacement(newWord: FoundWord): Boolean {
            assert(isSetUp)
            appManager.assertReadAccessAllowed()
            return when (newWord.type) {
                FoundWordType.IDENTIFIER -> isGoodIdentifierReplacement(newWord)
                FoundWordType.KEYWORD -> false // todo:
            }
        }

        private fun isGoodIdentifierReplacement(newWord: FoundWord): Boolean {
            val replacement = getReplacement(newWord)
            try {
                elementCopy.replace(replacement!!)
            } catch (e: IncorrectOperationException) {
                throw IllegalStateException()
            }
            return (!isUnresolvedReference(referenceCopy))
        }
    }

    // order matters
    override fun getTypoCases(
            editor: Editor,
            psiFile: PsiFile,
            elementStartOffset: Int,
            oldText: String,
            checkTime: () -> Unit
    ): List<TypoCase> = listOf(BadIdentifier(editor, psiFile, elementStartOffset, oldText, checkTime))
    abstract protected fun correspondingWordTypes(): Array<CombinedIndex.IndexType>

    abstract protected fun isInReference(element: PsiElement): Boolean
    abstract protected fun isIdentifier(element: PsiElement): Boolean
    abstract protected fun isKeyword(element: PsiElement): Boolean
    abstract protected fun isUnresolvedReference(element: PsiElement): Boolean
    abstract protected fun isInParameter(element: PsiElement): Boolean
}
