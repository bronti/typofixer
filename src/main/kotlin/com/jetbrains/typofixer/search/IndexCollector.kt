package com.jetbrains.typofixer.search

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.psi.PsiFile

/**
 * @author bronti.
 */
interface IndexCollector {

    fun keyWords(): List<String>

    fun localIdentifiers(psiFile: PsiFile): List<String>

    class Extension : LanguageExtension<IndexCollector>("com.jetbrains.typofixer.typoFixerIndexLanguageSupport") {
        companion object {
            val INSTANCE = IndexCollector.Extension()

            fun getIndexCollector(language: Language): IndexCollector {
                return INSTANCE.forLanguage(language)
            }
        }
    }

}