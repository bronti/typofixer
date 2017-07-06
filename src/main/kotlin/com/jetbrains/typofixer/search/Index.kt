package com.jetbrains.typofixer.search

import com.intellij.psi.PsiKeyword
import com.jetbrains.typofixer.search.signature.Signature

/**
 * @author bronti.
 */
class Index(val signatureProvider: Signature) {

    private val index = HashMap<Int, HashSet<String>>()

    init {
        javaKeywords.forEach { add(it) }
    }

    fun get(signature: Int): Set<String> {
        return index[signature] ?: HashSet()
    }

    private fun add(str: String) {
        val signature = signatureProvider.signature(str)
        if (index[signature] == null) {
            index[signature] = HashSet()
        }
        index[signature]!!.add(str)
    }

}

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