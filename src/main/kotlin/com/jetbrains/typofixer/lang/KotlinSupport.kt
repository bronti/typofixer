package com.jetbrains.typofixer.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.typofixer.search.index.CombinedIndex
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

//    private abstract class BadKeywordBeforeParameter : TypoCase {
//        override fun triggersResolve(c: Char) = !identifierChar(c) && c != ':'
////        override fun isBadlyReplacedKeyword(element: PsiElement) = !isErrorElement(element)
////        override fun isGoodReplacementForIdentifier(element: PsiElement, newText: String) = false
////        override fun getReplacement(element: PsiElement, oldWord: String, checkTime: () -> Unit): SearchResults {
////            val searcher = element.project.searcher
////            return searcher.findClosestAmongKeywords(oldWord, allowedKeywords, checkTime)
////        }
//
//        abstract val allowedKeywords: Set<String>
//    }

//    private val BAD_KEYWORD_IN_PRIMARY_CONSTRUCTOR = object : BadKeywordBeforeParameter() {
//        override fun isApplicable(element: PsiElement, fast: Boolean) = isInParameter(element) && isInPrimaryConstructor(element)
//        override val allowedKeywords = KEYWORDS_ALLOWED_IN_PRIMARY_CONSTRUCTOR
//    }
//    private val BAD_KEYWORD_IN_FUN_PARAMETER = object : BadKeywordBeforeParameter() {
//        override fun isApplicable(element: PsiElement, fast: Boolean) = isInParameter(element)
//        override val allowedKeywords = KEYWORDS_ALLOWED_IN_FUN_PARAMETERS
//    }

    fun isInPrimaryConstructor(element: PsiElement) = PsiTreeUtil.getParentOfType(element, KtPrimaryConstructor::class.java) != null
//
//    override fun checkedResolveIdentifierReference(text: String, element: PsiElement): Resolver {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }

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