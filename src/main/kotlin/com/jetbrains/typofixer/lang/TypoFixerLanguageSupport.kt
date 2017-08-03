package com.jetbrains.typofixer.lang

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.psi.PsiElement
import com.jetbrains.typofixer.search.index.LocalDictionaryCollector

/**
 * @author bronti.
 */
interface TypoFixerLanguageSupport {
    companion object {
        fun getSupport(language: Language) = TypoFixerLanguageSupport.Extension.getSupport(language)
    }

    fun identifierChar(c: Char): Boolean

    fun isBadElement(element: PsiElement, isReplaced: Boolean, isFast: Boolean): Boolean

    fun getLocalDictionaryCollector(): LocalDictionaryCollector

    private class Extension : LanguageExtension<TypoFixerLanguageSupport>("com.jetbrains.typofixer.typoFixerLanguageSupport") {
        companion object {
            val INSTANCE = TypoFixerLanguageSupport.Extension()

            fun getSupport(language: Language): TypoFixerLanguageSupport? {
                return INSTANCE.forLanguage(language)
            }
        }
    }
}