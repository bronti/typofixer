package com.jetbrains.typofixer.search.index

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * @author bronti.
 */
interface LocalDictionaryCollector {
    fun keyWords(element: PsiElement): List<String>
    fun localIdentifiers(psiFile: PsiFile): List<String>
}