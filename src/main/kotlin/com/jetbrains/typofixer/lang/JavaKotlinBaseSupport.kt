package com.jetbrains.typofixer.lang

import com.intellij.openapi.editor.Editor
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

    protected abstract fun isInReference(element: PsiElement): Boolean
    protected abstract fun isIdentifier(element: PsiElement): Boolean
    protected abstract fun isKeyword(element: PsiElement): Boolean
    protected abstract fun referenceIsUnresolved(element: PsiElement): Boolean
    protected abstract fun isInParameter(element: PsiElement): Boolean
    protected abstract fun looksLikeIdentifier(word: String): Boolean
    protected abstract fun canBeReplacedByUnresolvedClassName(referenceElement: PsiElement): Boolean

    abstract protected fun correspondingWordTypes(): List<CombinedIndex.IndexType>

    private inner class UnresolvedIdentifier(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BaseJavaKotlinTypoCase(editor, file, startOffset, oldWord, checkTime) {

        private val referenceCopy get() = withReadAccess { elementCopy.parent }
        private var nearestUnresolvedClassReplacement: FoundWord? = null

        override fun canBeApplicable() = withReadAccess { super.canBeApplicable() && isIdentifier(element) && isInReference(element) }
        override fun isApplicable() = checkWithWritePriority {
            super.isApplicable() && isIdentifier(elementCopy) && isInReference(elementCopy) && referenceIsUnresolved(referenceCopy)
        }

        override fun checkResolvedIdentifier(newWord: String) = isInReference(elementCopy) && !referenceIsUnresolved(referenceCopy)

        override fun isGoodReplacement(newWord: FoundWord): Boolean {
            return when {
                super.isGoodReplacement(newWord) -> true
                nearestUnresolvedClassReplacement == null && newWord.type == FoundWordType.IDENTIFIER_CLASS -> {
                    nearestUnresolvedClassReplacement = newWord
                    false
                }
                else -> false
            }
        }

        override fun handleNoReplacement(): Boolean {
            val newClassName = nearestUnresolvedClassReplacement ?: return false
            return if (withReadAccess { canBeReplacedByUnresolvedClassName(referenceCopy) }) {
                doReplace(newClassName)
                true
            } else false
        }
    }

    private inner class ErrorElement(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BaseJavaKotlinTypoCase(editor, file, startOffset, oldWord, checkTime) {

        override fun canBeApplicable() = withReadAccess { super.canBeApplicable() && isIdentifier(element) && isErrorElement(element) }
        override fun isApplicable() = withReadAccess { super.canBeApplicable() && isIdentifier(elementCopy) && isErrorElement(elementCopy) }

        override fun checkResolvedIdentifier(newWord: String) = false
    }

    protected abstract inner
    class BaseJavaKotlinTypoCase(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : TypoCase(editor, file, startOffset, oldWord, checkTime) {

        private val fileCopy by lazy { withReadAccess { file.copy() } as PsiFile }
        protected val elementCopy get() = withReadAccess { fileCopy.findElementAt(startOffset)!! }

        override fun triggersResolve(c: Char) = !identifierChar(c)
        override fun getReplacement(checkTime: () -> Unit) =
                project.searcher.find(file, oldWord, correspondingWordTypes(), checkTime).asSequence()

        protected open fun checkResolvedKeyword(newWord: String) = isGoodKeyword(elementCopy)
        protected abstract fun checkResolvedIdentifier(newWord: String): Boolean

        override fun isGoodReplacement(newWord: FoundWord): Boolean {
            if (!super.isGoodReplacement(newWord) || newWord.word == oldWord || !looksLikeIdentifier(newWord.word)) return false

            replaceInDocumentCopy(oldWord, newWord.word)
            val result = elementCopy.text == newWord.word &&
                    when (newWord.type) {
                        FoundWordType.IDENTIFIER_NOT_CLASS, FoundWordType.IDENTIFIER_CLASS
                        -> checkWithWritePriority { checkResolvedIdentifier(newWord.word) }
                        FoundWordType.KEYWORD -> withReadAccess { checkResolvedKeyword(newWord.word) }
                    }
            replaceInDocumentCopy(newWord.word, oldWord)

            return result
        }

        private fun replaceInDocumentCopy(oldWord: String, newWord: String) {
            val documentCopy = withReadAccess { fileCopy.viewProvider.document!! }

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
