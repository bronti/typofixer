package com.jetbrains.typofixer.search

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension

/**
 * @author bronti.
 */
interface IndexCollector {

    fun keyWords(): List<String>

    class Extension : LanguageExtension<IndexCollector>("com.jetbrains.typofixer.typoFixerIndexLanguageSupport") {
        companion object {
            val INSTANCE = IndexCollector.Extension()

            fun getIndexCollector(language: Language): IndexCollector {
                return INSTANCE.forLanguage(language)
            }
        }
    }

}