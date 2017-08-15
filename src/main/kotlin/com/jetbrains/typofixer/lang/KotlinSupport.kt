package com.jetbrains.typofixer.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.searcher
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.ErrorUtils

/**
 * @author bronti.
 */

class KotlinSupport : JavaKotlinBaseSupport() {

    override fun correspondingWordTypes() = arrayOf(
            CombinedIndex.WordType.KEYWORD,
            CombinedIndex.WordType.LOCAL_IDENTIFIER,
            CombinedIndex.WordType.KOTLIN_SPECIFIC_FIELD,
            CombinedIndex.WordType.GLOBAL
    )

    private abstract class BadKeywordBeforeParameter : TypoCase {
        override fun triggersTypoResolve(c: Char) = !identifierChar(c) && c != ':'
        override fun iaBadReplace(element: PsiElement) = isErrorElement(element)
        override fun getReplacement(element: PsiElement, oldText: String, isTooLate: () -> Boolean): Sequence<String> {
            val searcher = element.project.searcher
            return searcher.findClosestAmongKeywords(oldText, allowedKeywords, isTooLate)
        }

        abstract val allowedKeywords: List<String>
    }

    private val BAD_KEYWORD_IN_PRIMARY_CONSTRUCTOR = object : BadKeywordBeforeParameter() {
        override fun needToReplace(element: PsiElement, fast: Boolean) = isParameter(element) && isInPrimaryConstructor(element)
        override val allowedKeywords = KEYWORDS_ALLOWED_IN_PRIMARY_CONSTRUCTOR
    }
    private val BAD_KEYWORD_IN_FUN_PARAMETER = object : BadKeywordBeforeParameter() {
        override fun needToReplace(element: PsiElement, fast: Boolean) = isParameter(element)
        override val allowedKeywords = KEYWORDS_ALLOWED_IN_FUN_PARAMETERS
    }

    // order matters
    override fun getTypoCases() = super.getTypoCases() + BAD_KEYWORD_IN_PRIMARY_CONSTRUCTOR + BAD_KEYWORD_IN_FUN_PARAMETER

    fun isInPrimaryConstructor(element: PsiElement) = PsiTreeUtil.getParentOfType(element, KtPrimaryConstructor::class.java) != null

    override fun isReference(element: PsiElement) = element.parent is KtReferenceExpression || element.parent is KtReference
    override fun isIdentifier(element: PsiElement) = element.node.elementType == KtTokens.IDENTIFIER
    override fun isKeyword(element: PsiElement) = element.node.elementType is KtKeywordToken
    override fun isParameter(element: PsiElement) = element.parent is KtParameter && isIdentifier(element)
    override fun isUnresolvedReference(element: PsiElement): Boolean {
        val parent = element.parent
//        return parent is KtReferenceExpression && parent.resolveMainReferenceToDescriptors().isEmpty()
        // todo: research
        return parent is KtReferenceExpression && parent.resolveMainReferenceToDescriptors().filter { !ErrorUtils.isError(it) }.isEmpty()
                || parent is KtReference && parent.resolve() == null
    }

    override fun getLocalDictionaryCollector() = KotlinLocalDictionaryCollector()

    class KotlinLocalDictionaryCollector : LocalDictionaryCollector {
        override fun keyWords(element: PsiElement) = KEYWORDS + SOFT_KEYWORDS

        override fun localIdentifiers(psiFile: PsiFile): List<String> {
            val result = mutableListOf<String>()

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

private fun Array<IElementType>.getNames() = this.map { it as KtKeywordToken }.map { it.value }