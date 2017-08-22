package com.jetbrains.typofixer.lang

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.FoundWord
import com.jetbrains.typofixer.search.SearchResults

/**
 * @author bronti.
 */
interface TypoFixerLanguageSupport {
    companion object {
        fun getSupport(language: Language) = TypoFixerLanguageSupport.Extension.getSupport(language)
    }

    fun getTypoCases(): List<TypoCase>

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

// returns true if resolve was successful
class Resolver private constructor(private val doResolve: (() -> Unit)?, val isSuccessful: Boolean) {
    companion object {
        val UNSUCCESSFUL = Resolver(null, false)
    }

    constructor(doResolve: () -> Unit) : this(doResolve, true)

    fun resolve() = doResolve!!()
}

interface TypoCase {
    fun triggersTypoResolve(c: Char): Boolean
    // TypoResolver handles the first case for which needToReplace(element, fast = true) is true
    fun needToReplace(element: PsiElement, fast: Boolean = false): Boolean

    //    fun isBadlyReplacedKeyword(element: PsiElement): Boolean
//    fun isGoodReplacementForIdentifier(element: PsiElement, newText: String): Boolean
    fun getResolver(element: PsiElement, newWord: FoundWord): Resolver
    fun getReplacement(element: PsiElement, oldText: String, checkTime: () -> Unit): SearchResults
}

interface LocalDictionaryCollector {
    fun keyWords(element: PsiElement): Set<String>
    fun localIdentifiers(psiFile: PsiFile): Set<String>
}
