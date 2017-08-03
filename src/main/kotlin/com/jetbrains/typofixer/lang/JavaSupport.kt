package com.jetbrains.typofixer.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.*
import com.intellij.psi.tree.java.IKeywordElementType
import com.jetbrains.typofixer.search.index.LocalDictionaryCollector

/**
 * @author bronti.
 */
// todo: make base class for java and kotlin
class JavaSupport : TypoFixerLanguageSupport {
    override fun identifierChar(c: Char) = c.isJavaIdentifierPart()

    override fun isBadElement(element: PsiElement, isReplaced: Boolean, isFast: Boolean): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        val type = element.node.elementType
        val parent = element.parent

        val isReference = parent is PsiReference
        val isIdentifier = type == JavaTokenType.IDENTIFIER
        val isKeyword = type is IKeywordElementType

        val isIdentifierReference = isIdentifier && isReference
        val isBadKeyword = isKeyword && parent is PsiErrorElement

        if (isFast) {
            assert(!isReplaced)
            return isBadKeyword || isIdentifierReference
        } else {
            val isUnresolved =
                    parent is PsiReferenceExpression && parent.multiResolve(true).isEmpty()
                            || parent is PsiReference && parent.resolve() == null

            val isUnresolvedReference = isIdentifierReference && isUnresolved
            val isIdentifierNotReference = isIdentifier && !isReference
            val isSomethingElse = !isIdentifier && !isKeyword

            return isBadKeyword || isUnresolvedReference || (isReplaced && (isIdentifierNotReference || isSomethingElse))
        }
    }

    override fun getLocalDictionaryCollector() = JavaLocalDictionaryCollector()

    class JavaLocalDictionaryCollector : LocalDictionaryCollector {
        // todo: reuse JavaKeywordCompletion?
        override fun keyWords(element: PsiElement) = javaKeywords

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
