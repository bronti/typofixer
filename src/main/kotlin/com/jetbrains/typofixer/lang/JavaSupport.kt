package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.*
import com.jetbrains.typofixer.search.index.LocalDictionaryCollector
import com.sun.org.apache.xpath.internal.operations.Bool

/**
 * @author bronti.
 */
class JavaSupport : TypoFixerLanguageSupport {
    override fun identifierChar(c: Char) = c.isJavaIdentifierPart()

    override fun isTypoResolverApplicable(element: PsiElement): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return element.node.elementType == JavaTokenType.IDENTIFIER && (element.parent is PsiReference || element.parent is PsiErrorElement)
    }

    override fun isTypoNotFixed(element: PsiElement): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val parent = element.parent
        return (parent is PsiErrorElement
                || parent is PsiReferenceExpression && parent.multiResolve(true).isEmpty()
                || parent is PsiReference && parent.resolve() == null)
    }

    override fun getLocalDictionaryCollector() = JavaLocalDictionaryCollector()

    class JavaLocalDictionaryCollector : LocalDictionaryCollector {
        override fun keyWords() = javaKeywords

        // todo: make it right
        override fun localIdentifiers(psiFile: PsiFile): List<String> {
            val result = mutableListOf<String>()

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
private val javaKeywords = listOf(
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
