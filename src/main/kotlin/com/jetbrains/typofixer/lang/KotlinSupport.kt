package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.typofixer.TypoCase
import com.jetbrains.typofixer.search.FoundWord
import com.jetbrains.typofixer.search.index.CombinedIndex
import com.jetbrains.typofixer.searcher
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

/**
 * @author bronti.
 */

class KotlinSupport : JavaKotlinBaseSupport() {

    override fun getLocalDictionaryCollector() = KotlinLocalDictionaryCollector()

    // order matters
    override fun getTypoCases(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit): List<TypoCase> =
            listOf(
                    BadIdentifierInLambdaParameters(editor, file, startOffset, oldWord, checkTime),
                    BadKeywordInPrimaryConstructorTypoCase(editor, file, startOffset, oldWord, checkTime),
                    BadKeywordInFunParameterTypoCase(editor, file, startOffset, oldWord, checkTime)
            ) + super.getTypoCases(editor, file, startOffset, oldWord, checkTime)

    override fun isInReference(element: PsiElement) = element.parent is KtReferenceExpression || element.parent is KtReference
    override fun isIdentifier(element: PsiElement) = element.node.elementType == KtTokens.IDENTIFIER
    override fun isKeyword(element: PsiElement) = element.node.elementType is KtKeywordToken
    override fun isInParameter(element: PsiElement) = element.parent is KtParameter && isIdentifier(element)
    override fun referenceIsUnresolved(element: PsiElement) = when (element) {
        is KtReferenceExpression -> element.resolveMainReferenceToDescriptors().isEmpty()
        is KtReference -> element.resolve() == null
        else -> throw IllegalStateException()
    }

    private fun KtElement.resolveMainReferenceToDescriptors(): Collection<DeclarationDescriptor> {
        val bindingContext = analyze(BodyResolveMode.PARTIAL)
        return mainReference?.resolveToDescriptors(bindingContext) ?: emptyList()
    }

    override fun canBeReplacedByUnresolvedClassName(referenceElement: PsiElement): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return referenceElement.parent !is KtDotQualifiedExpression
    }
    override fun looksLikeIdentifier(word: String) =
            word.isNotBlank() && word.all { it.isJavaIdentifierPart() } && word[0].isJavaIdentifierStart()

    override fun correspondingWordTypes() = listOf(
            CombinedIndex.IndexType.KEYWORD,
            CombinedIndex.IndexType.LOCAL_IDENTIFIER,
            CombinedIndex.IndexType.KOTLIN_SPECIFIC_FIELD,
            CombinedIndex.IndexType.CLASSNAME,
            CombinedIndex.IndexType.NOT_CLASSNAME
    )

    // todo: typo context
    // todo: continue/accept/stop (?)
    private inner class BadIdentifierInLambdaParameters(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BaseJavaKotlinTypoCase(editor, file, startOffset, oldWord, checkTime) {

        override fun triggersResolve(c: Char) = c.isWhitespace() || c == ',' || c == ':'

        override fun checkResolvedIdentifier(newWord: String) = false
        override fun checkResolvedKeyword(newWord: String) = false

        override fun getReplacement(checkTime: () -> Unit) = sequenceOf<FoundWord>()

        override fun canBeApplicable() = withReadAccess(fun(): Boolean {
            if (!super.canBeApplicable() || !isInReference(element)) return false
            val lambdaParent = element.getNonStrictParentOfType(KtLambdaExpression::class.java) ?: return false
            val functionLiteral = if (lambdaParent.children.isNotEmpty()) lambdaParent.children[0] as? KtFunctionLiteral else null
            functionLiteral ?: return false
            val block = if (functionLiteral.children.isNotEmpty()) functionLiteral.children[0] as? KtBlockExpression else null
            block ?: return false
            tailrec fun PsiElement.parentWhile(predicate: (PsiElement) -> Boolean): PsiElement? =
                    if (predicate(this)) this.parent?.parentWhile(predicate)
                    else this
            return element.parent?.parent?.parentWhile { it is KtParenthesizedExpression } === block
        })

        override fun isApplicable() = false
    }

    private abstract inner class BadKeywordBeforeParameter(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BaseJavaKotlinTypoCase(editor, file, startOffset, oldWord, checkTime) {

        override fun triggersResolve(c: Char) = !identifierChar(c) && c != ':'

        override fun checkResolvedIdentifier(newWord: String) = false
        override fun checkResolvedKeyword(newWord: String) = !isErrorElement(elementCopy)

        override fun getReplacement(checkTime: () -> Unit) =
                project.searcher.findAmongKeywords(oldWord, allowedKeywords, checkTime).asSequence()

        abstract val allowedKeywords: Set<String>
    }

    private inner class BadKeywordInPrimaryConstructorTypoCase(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BadKeywordBeforeParameter(editor, file, startOffset, oldWord, checkTime) {
        override val allowedKeywords = KEYWORDS_ALLOWED_IN_PRIMARY_CONSTRUCTOR
        override fun canBeApplicable() = withReadAccess {
            super.canBeApplicable() && isInParameter(element) && isInPrimaryConstructor(element)
        }

        override fun isApplicable() = withReadAccess {
            super.isApplicable() && isInParameter(elementCopy) && isInPrimaryConstructor(elementCopy)
        }
    }

    private inner class BadKeywordInFunParameterTypoCase(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit)
        : BadKeywordBeforeParameter(editor, file, startOffset, oldWord, checkTime) {
        override val allowedKeywords = KEYWORDS_ALLOWED_IN_FUN_PARAMETERS
        override fun canBeApplicable() = withReadAccess { super.canBeApplicable() && isInParameter(element) }
        override fun isApplicable() = withReadAccess { super.isApplicable() && isInParameter(elementCopy) }
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