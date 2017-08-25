package com.jetbrains.typofixer.lang

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.typofixer.TypoCase
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.searcher
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/**
 * @author bronti.
 */

class KotlinSupport : JavaKotlinBaseSupport() {

    override fun getLocalDictionaryCollector() = KotlinLocalDictionaryCollector()

    // order matters
    override fun getTypoCases(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit): List<TypoCase> =
            super.getTypoCases(editor, file, startOffset, oldWord, checkTime) +
                    listOf(
                            BadKeywordInPrimaryConstructorTypoCase(editor, file, startOffset, oldWord, checkTime),
                            BadKeywordInFunParameterTypoCase(editor, file, startOffset, oldWord, checkTime)
                    )

    override fun isInReference(element: PsiElement) = element.parent is KtReferenceExpression || element.parent is KtReference
    override fun isIdentifier(element: PsiElement) = element.node.elementType == KtTokens.IDENTIFIER
    override fun isKeyword(element: PsiElement) = element.node.elementType is KtKeywordToken
    override fun isInParameter(element: PsiElement) = element.parent is KtParameter && isIdentifier(element)
    override fun isUnresolvedReference(element: PsiElement): Boolean {
        return element is KtReferenceExpression && element.resolveMainReferenceToDescriptors().isEmpty()
                || element is KtReference && element.resolve() == null
    }

    override fun correspondingWordTypes() = arrayOf(
            CombinedIndex.IndexType.KEYWORD,
            CombinedIndex.IndexType.LOCAL_IDENTIFIER,
            CombinedIndex.IndexType.KOTLIN_SPECIFIC_FIELD,
            CombinedIndex.IndexType.GLOBAL
    )

    private abstract inner class BadKeywordBeforeParameter(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BaseJavaKotlinTypoCase(editor, file, startOffset, oldWord, checkTime) {

        override fun triggersResolve(c: Char) = !identifierChar(c) && c != ':'

        override fun checkResolvedIdentifier(newWord: String) = false
        override fun checkResolvedKeyword(newWord: String) = !isErrorElement(elementCopy)

        override fun getReplacement(checkTime: () -> Unit) =
                project.searcher.findClosestAmongKeywords(oldWord, allowedKeywords, checkTime)

        abstract val allowedKeywords: Set<String>
    }

    private inner class BadKeywordInPrimaryConstructorTypoCase(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BadKeywordBeforeParameter(editor, file, startOffset, oldWord, checkTime) {
        override val allowedKeywords = KEYWORDS_ALLOWED_IN_PRIMARY_CONSTRUCTOR
        override fun checkApplicable(fast: Boolean) =
                super.checkApplicable(fast) && isInParameter(element) && isInPrimaryConstructor(element)
    }

    private inner class BadKeywordInFunParameterTypoCase(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BadKeywordBeforeParameter(editor, file, startOffset, oldWord, checkTime) {
        override val allowedKeywords = KEYWORDS_ALLOWED_IN_FUN_PARAMETERS
        override fun checkApplicable(fast: Boolean) = super.checkApplicable(fast) && isInParameter(element)
    }

    fun isInPrimaryConstructor(element: PsiElement) = PsiTreeUtil.getParentOfType(element, KtPrimaryConstructor::class.java) != null

    class KotlinLocalDictionaryCollector : LocalDictionaryCollector {
        override fun keyWords(element: PsiElement) = KEYWORDS + SOFT_KEYWORDS

        override fun localIdentifiers(psiFile: PsiFile): Set<String> {
            val result = mutableSetOf<String>()

            val visitor = object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    val name = declaration.nameAsSafeName.toString()
                    result.add(name)
                    super.visitNamedDeclaration(declaration)
                }
            }
            psiFile.accept(visitor)
            return result
        }
    }

    companion object {
        // todo: check
        private val KEYWORDS_ALLOWED_IN_PRIMARY_CONSTRUCTOR = arrayOf<IElementType>(
                KtTokens.VAL_KEYWORD,
                KtTokens.VAR_KEYWORD,
                KtTokens.VARARG_KEYWORD,
                KtTokens.PUBLIC_KEYWORD,
                KtTokens.PRIVATE_KEYWORD,
                KtTokens.PROTECTED_KEYWORD,
                KtTokens.INTERNAL_KEYWORD,
                KtTokens.OVERRIDE_KEYWORD
        ).getNames()

        // todo: check
        private val KEYWORDS_ALLOWED_IN_FUN_PARAMETERS = arrayOf<IElementType>(
                KtTokens.VARARG_KEYWORD
        ).getNames()

        private val KEYWORDS = KtTokens.KEYWORDS.types.getNames()

        private val SOFT_KEYWORDS = KtTokens.SOFT_KEYWORDS.types.getNames()
    }
}

private fun Array<IElementType>.getNames() = this.map { it as KtKeywordToken }.map { it.value }.toSet()