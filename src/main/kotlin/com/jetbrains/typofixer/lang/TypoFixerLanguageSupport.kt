package com.jetbrains.typofixer.lang

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.TypoCase

/**
 * @author bronti.
 */
interface TypoFixerLanguageSupport {
    companion object {
        fun getSupport(language: Language) = TypoFixerLanguageSupport.Extension.getSupport(language)
    }

    fun getLocalDictionaryCollector(): LocalDictionaryCollector
    fun getTypoCases(editor: Editor, file: PsiFile, startOffset: Int, oldWord: String, checkTime: () -> Unit): List<TypoCase>

    private class Extension : LanguageExtension<TypoFixerLanguageSupport>("com.jetbrains.typofixer.typoFixerLanguageSupport") {
        companion object {
            val INSTANCE = TypoFixerLanguageSupport.Extension()

            fun getSupport(language: Language): TypoFixerLanguageSupport? {
                return INSTANCE.forLanguage(language)
            }
        }
    }
}

interface LocalDictionaryCollector {
    fun keyWords(element: PsiElement): Set<String>
    fun localIdentifiers(psiFile: PsiFile): Set<String>
}
