package com.jetbrains.typofixer.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.typofixer.search.index.LocalDictionaryCollector
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/**
 * @author bronti.
 */

class KotlinSupport : JavaKotlinBaseSupport() {

    private val BAD_KEYWORD_IN_PRIMARY_CONSTRUCTOR = object : TypoCase {
        override fun triggersTypoResolve(c: Char) = !identifierChar(c) && c != ':'
        override fun needToReplace(element: PsiElement, fast: Boolean) = isParameter(element) && isInPrimaryConstructor(element)
        // todo: search result only among keywords
        override fun iaBadReplace(element: PsiElement) = !isKeyword(element) || isErrorElement(element)
    }

    override fun getTypoCases() = super.getTypoCases() + BAD_KEYWORD_IN_PRIMARY_CONSTRUCTOR

    fun isInPrimaryConstructor(element: PsiElement) = PsiTreeUtil.getParentOfType(element, KtPrimaryConstructor::class.java) != null

    override fun isReference(element: PsiElement) = element.parent is KtReferenceExpression || element.parent is KtReference
    override fun isIdentifier(element: PsiElement) = element.node.elementType == KtTokens.IDENTIFIER
    override fun isKeyword(element: PsiElement) = element.node.elementType is KtKeywordToken
    override fun isParameter(element: PsiElement) = element.parent is KtParameter && isIdentifier(element)
    override fun isUnresolvedReference(element: PsiElement): Boolean {
        val parent = element.parent
        return parent is KtReferenceExpression && parent.resolveMainReferenceToDescriptors().isEmpty()
                || parent is KtReference && parent.resolve() == null
    }

    override fun getLocalDictionaryCollector() = KotlinLocalDictionaryCollector()

    class KotlinLocalDictionaryCollector : LocalDictionaryCollector {
        override fun keyWords(element: PsiElement) = kotlinKeywords + kotlinSoftKeywords

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
}

private val kotlinKeywords = KtTokens.KEYWORDS.types.map { it as KtKeywordToken }.map { it.value }
private val kotlinSoftKeywords = KtTokens.SOFT_KEYWORDS.types.map { it as KtKeywordToken }.map { it.value }