package com.jetbrains.typofixer.search.index

import com.intellij.psi.PsiFile

/**
 * @author bronti.
 */
interface LocalDictionaryCollector {
    fun keyWords(): List<String>
    fun localIdentifiers(psiFile: PsiFile): List<String>
}