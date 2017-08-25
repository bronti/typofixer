package com.jetbrains.typofixer.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
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

    // order matters
    override fun getTypoCases(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
            : List<TypoCase> = listOf(
            UnresolvedIdentifier(editor, file, startOffset, oldWord, checkTime),
            ErrorElement(editor, file, startOffset, oldWord, checkTime)
    )

    protected fun isGoodKeyword(element: PsiElement) = isKeyword(element) && !isErrorElement(element)

    abstract protected fun isInReference(element: PsiElement): Boolean
    abstract protected fun isIdentifier(element: PsiElement): Boolean
    abstract protected fun isKeyword(element: PsiElement): Boolean
    abstract protected fun isUnresolvedReference(element: PsiElement): Boolean
    abstract protected fun isInParameter(element: PsiElement): Boolean

    abstract protected fun correspondingWordTypes(): Array<CombinedIndex.IndexType>

    private inner class UnresolvedIdentifier(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BaseJavaKotlinTypoCase(editor, file, startOffset, oldWord, checkTime) {

        //        private val factory = PsiElementFactory.SERVICE.getInstance(project)!!
        private val referenceCopy get() = elementCopy.parent

        override fun checkApplicable(fast: Boolean) =
                super.checkApplicable(fast) &&
                        isIdentifier(element) && (
                        if (fast) isInReference(element)
                        else isUnresolvedReference(element.parent)
                        )

        override fun doCheckIdentifier(newWord: String) = checkWithWritePriority { !isUnresolvedReference(referenceCopy) }
    }

    private inner class ErrorElement(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BaseJavaKotlinTypoCase(editor, file, startOffset, oldWord, checkTime) {

        override fun checkApplicable(fast: Boolean) =
                super.checkApplicable(fast) && isIdentifier(element) && isErrorElement(element)

        override fun doCheckIdentifier(newWord: String) = false
    }

    protected abstract inner
    class BaseJavaKotlinTypoCase(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : TypoCase(editor, file, startOffset, oldWord, checkTime) {

        private val fileCopy by lazy { appManager.runReadAction(Computable { file.copy() }) as PsiFile }
        protected val elementCopy get() = fileCopy.findElementAt(startOffset)!!

        override fun triggersResolve(c: Char) = !identifierChar(c)
        override fun getReplacement(checkTime: () -> Unit) =
                project.searcher.findClosest(file, oldWord, correspondingWordTypes(), checkTime)

        protected open fun doCheckKeyword(newWord: String) = appManager.runReadAction(Computable { isGoodKeyword(elementCopy) })!!

        protected abstract fun doCheckIdentifier(newWord: String): Boolean

        final override fun isGoodReplacement(newWord: FoundWord): Boolean {
            val superResult = super.isGoodReplacement(newWord)
            replaceInDocumentCopy(oldWord, newWord.word)
            val result = when (newWord.type) {
                FoundWordType.IDENTIFIER -> doCheckIdentifier(newWord.word)
                FoundWordType.KEYWORD -> doCheckKeyword(newWord.word)
            }
            replaceInDocumentCopy(newWord.word, oldWord)

            return superResult && result
        }

        private fun replaceInDocumentCopy(oldWord: String, newWord: String) {
            val documentCopy = appManager.runReadAction(Computable { fileCopy.viewProvider.document!! })

            appManager.invokeAndWait {
                appManager.runWriteAction {
                    // todo: command?
                    documentCopy.replaceString(startOffset, startOffset + oldWord.length, newWord)
                    PsiDocumentManager.getInstance(project).commitDocument(documentCopy)
                }
            }
        }
    }
}
