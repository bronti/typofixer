package com.jetbrains.typofixer.lang

import com.intellij.psi.*
import com.intellij.psi.tree.java.IKeywordElementType
import com.jetbrains.typofixer.search.index.CombinedIndex

/**
 * @author bronti.
 */
// todo: make base class for java and kotlin
class JavaSupport : JavaKotlinBaseSupport() {

    override fun getLocalDictionaryCollector() = JavaLocalDictionaryCollector()

    override fun isInReference(element: PsiElement) = element.parent is PsiJavaCodeReferenceElement // todo: what is with PsiReference?
    override fun isIdentifier(element: PsiElement) = element.node.elementType == JavaTokenType.IDENTIFIER
    override fun isKeyword(element: PsiElement) = element.node.elementType is IKeywordElementType
    override fun isInParameter(element: PsiElement) = element.parent is PsiParameter && isIdentifier(element)
    override fun isUnresolvedReference(element: PsiElement): Boolean {
        return when (element) {
            is PsiReferenceExpression -> element.multiResolve(true).none { it.isAccessible }
            is PsiReference -> element.resolve() == null
            else -> throw IllegalStateException()
        }
    }

    override fun correspondingWordTypes() = arrayOf(
            CombinedIndex.IndexType.KEYWORD,
            CombinedIndex.IndexType.LOCAL_IDENTIFIER,
            CombinedIndex.IndexType.GLOBAL
    )

    class JavaLocalDictionaryCollector : LocalDictionaryCollector {
        // todo: reuse JavaKeywordCompletion?
        override fun keyWords(element: PsiElement) = javaKeywords

        // todo: make it right
        override fun localIdentifiers(psiFile: PsiFile): Set<String> {
            val result = mutableSetOf<String>()

            val visitor = object : JavaRecursiveElementVisitor() {
                override fun visitIdentifier(identifier: PsiIdentifier) {
                    if (identifier.parent !is PsiReference && identifier.parent !is PsiErrorElement) {
                        result.add(identifier.text)
                    }
                    super.visitIdentifier(identifier)
                }
            }
            psiFile.accept(visitor)
            return result
        }
    }
}

// JavaLexer.KEYWORDS and JavaLexer.JAVA9_KEYWORDS are private :(
private val javaKeywords = setOf(
        PsiKeyword.ABSTRACT,
        PsiKeyword.ASSERT,
        PsiKeyword.BOOLEAN,
        PsiKeyword.BREAK,
        PsiKeyword.BYTE,
        PsiKeyword.CASE,
        PsiKeyword.CATCH,
        PsiKeyword.CHAR,
        PsiKeyword.CLASS,
        PsiKeyword.CONST,
        PsiKeyword.CONTINUE,
        PsiKeyword.DEFAULT,
        PsiKeyword.DO,
        PsiKeyword.DOUBLE,
        PsiKeyword.ELSE,
        PsiKeyword.ENUM,
        PsiKeyword.EXTENDS,
        PsiKeyword.FINAL,
        PsiKeyword.FINALLY,
        PsiKeyword.FLOAT,
        PsiKeyword.FOR,
        PsiKeyword.GOTO,
        PsiKeyword.IF,
        PsiKeyword.IMPLEMENTS,
        PsiKeyword.IMPORT,
        PsiKeyword.INSTANCEOF,
        PsiKeyword.INT,
        PsiKeyword.INTERFACE,
        PsiKeyword.LONG,
        PsiKeyword.NATIVE,
        PsiKeyword.NEW,
        PsiKeyword.PACKAGE,
        PsiKeyword.PRIVATE,
        PsiKeyword.PROTECTED,
        PsiKeyword.PUBLIC,
        PsiKeyword.RETURN,
        PsiKeyword.SHORT,
        PsiKeyword.STATIC,
        PsiKeyword.STRICTFP,
        PsiKeyword.SUPER,
        PsiKeyword.SWITCH,
        PsiKeyword.SYNCHRONIZED,
        PsiKeyword.THIS,
        PsiKeyword.THROW,
        PsiKeyword.THROWS,
        PsiKeyword.TRANSIENT,
        PsiKeyword.TRY,
        PsiKeyword.VOID,
        PsiKeyword.VOLATILE,
        PsiKeyword.WHILE,
        PsiKeyword.TRUE,
        PsiKeyword.FALSE,
        PsiKeyword.NULL,
        PsiKeyword.OPEN,
        PsiKeyword.MODULE,
        PsiKeyword.REQUIRES,
        PsiKeyword.EXPORTS,
        PsiKeyword.OPENS,
        PsiKeyword.USES,
        PsiKeyword.PROVIDES,
        PsiKeyword.TRANSITIVE,
        PsiKeyword.TO,
        PsiKeyword.WITH
)
