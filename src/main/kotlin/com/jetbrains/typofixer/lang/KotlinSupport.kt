package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.index.LocalDictionaryCollector
import org.jetbrains.kotlin.idea.completion.KeywordCompletion
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * @author bronti.
 */

class KotlinSupport : TypoFixerLanguageSupport {
    override fun identifierChar(c: Char) = c.isJavaIdentifierPart() // || c == '`'

    override fun isBadElement(element: PsiElement, isReplaced: Boolean, isFast: Boolean): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        val type = element.node.elementType
        val parent = element.parent

        val isReference = parent is KtReferenceExpression || parent is KtReference
        val isIdentifier = type == KtTokens.IDENTIFIER
        val isKeyword = type is KtKeywordToken

        val isIdentifierReference = isIdentifier && isReference
        val isBadKeyword = isKeyword && parent is PsiErrorElement

        if (isFast) {
            assert(!isReplaced)
            return isBadKeyword || isIdentifierReference
        } else {
            val isUnresolved =
                    parent is KtReferenceExpression && parent.resolveMainReferenceToDescriptors().isEmpty()
                            || parent is KtReference && parent.resolve() == null

            val isUnresolvedReference = isIdentifierReference && isUnresolved
            val isIdentifierNotReference = isIdentifier && !isReference
            val isSomethingElse = !isIdentifier && !isKeyword

            return isBadKeyword || isUnresolvedReference || (isReplaced && (isIdentifierNotReference || isSomethingElse))
        }
    }

    override fun getLocalDictionaryCollector() = KotlinLocalDictionaryCollector()

    class KotlinLocalDictionaryCollector : LocalDictionaryCollector {
        override fun keyWords(element: PsiElement): List<String> {
            val result = arrayListOf<String>()
            // todo: wtf is isJvmModule ?!
            KeywordCompletion.complete(element, "", true, { result.add(it.lookupString) })
            return result
        }

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

// cannot extract all keywords from KtTokens.KEYWORDS :(
private val kotlinKeywords = listOf<KtKeywordToken>(
        KtTokens.PACKAGE_KEYWORD,
        KtTokens.AS_KEYWORD,
        KtTokens.TYPE_ALIAS_KEYWORD,
        KtTokens.CLASS_KEYWORD,
        KtTokens.INTERFACE_KEYWORD,
        KtTokens.THIS_KEYWORD,
        KtTokens.SUPER_KEYWORD,
        KtTokens.VAL_KEYWORD,
        KtTokens.VAR_KEYWORD,
        KtTokens.FUN_KEYWORD,
        KtTokens.FOR_KEYWORD,
        KtTokens.NULL_KEYWORD,
        KtTokens.TRUE_KEYWORD,
        KtTokens.FALSE_KEYWORD,
        KtTokens.IS_KEYWORD,
        KtTokens.IN_KEYWORD,
        KtTokens.THROW_KEYWORD,
        KtTokens.RETURN_KEYWORD,
        KtTokens.BREAK_KEYWORD,
        KtTokens.CONTINUE_KEYWORD,
        KtTokens.OBJECT_KEYWORD,
        KtTokens.IF_KEYWORD,
        KtTokens.ELSE_KEYWORD,
        KtTokens.WHILE_KEYWORD,
        KtTokens.DO_KEYWORD,
        KtTokens.TRY_KEYWORD,
        KtTokens.WHEN_KEYWORD,
        KtTokens.NOT_IN,
        KtTokens.NOT_IS,
        KtTokens.AS_SAFE as KtKeywordToken,
        KtTokens.TYPEOF_KEYWORD
).map { it.value }

private val kotlinSoftKeywords = listOf(
        KtTokens.FILE_KEYWORD,
        KtTokens.IMPORT_KEYWORD,
        KtTokens.WHERE_KEYWORD,
        KtTokens.BY_KEYWORD,
        KtTokens.GET_KEYWORD,
        KtTokens.SET_KEYWORD,
        KtTokens.ABSTRACT_KEYWORD,
        KtTokens.ENUM_KEYWORD,
        KtTokens.OPEN_KEYWORD,
        KtTokens.INNER_KEYWORD,
        KtTokens.OVERRIDE_KEYWORD,
        KtTokens.PRIVATE_KEYWORD,
        KtTokens.PUBLIC_KEYWORD,
        KtTokens.INTERNAL_KEYWORD,
        KtTokens.PROTECTED_KEYWORD,
        KtTokens.CATCH_KEYWORD,
        KtTokens.FINALLY_KEYWORD,
        KtTokens.OUT_KEYWORD,
        KtTokens.FINAL_KEYWORD,
        KtTokens.VARARG_KEYWORD,
        KtTokens.REIFIED_KEYWORD,
        KtTokens.DYNAMIC_KEYWORD,
        KtTokens.COMPANION_KEYWORD,
        KtTokens.CONSTRUCTOR_KEYWORD,
        KtTokens.INIT_KEYWORD,
        KtTokens.SEALED_KEYWORD,
        KtTokens.FIELD_KEYWORD,
        KtTokens.PROPERTY_KEYWORD,
        KtTokens.RECEIVER_KEYWORD,
        KtTokens.PARAM_KEYWORD,
        KtTokens.SETPARAM_KEYWORD,
        KtTokens.DELEGATE_KEYWORD,
        KtTokens.LATEINIT_KEYWORD,
        KtTokens.DATA_KEYWORD,
        KtTokens.INLINE_KEYWORD,
        KtTokens.NOINLINE_KEYWORD,
        KtTokens.TAILREC_KEYWORD,
        KtTokens.EXTERNAL_KEYWORD,
        KtTokens.ANNOTATION_KEYWORD,
        KtTokens.CROSSINLINE_KEYWORD,
        KtTokens.CONST_KEYWORD,
        KtTokens.OPERATOR_KEYWORD,
        KtTokens.INFIX_KEYWORD,
        KtTokens.SUSPEND_KEYWORD,
        KtTokens.HEADER_KEYWORD,
        KtTokens.IMPL_KEYWORD
).map { it.value }