package com.jetbrains.typofixer.search.index

import com.intellij.psi.PsiElement
import com.jetbrains.typofixer.lang.LocalDictionaryCollector
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly
import java.util.*

class LocalInnerIndex(signature: Signature, val getWords: (wordsCollector: LocalDictionaryCollector, element: PsiElement) -> Set<String>) : InnerIndex(signature) {

    private val index = HashMap<Int, HashSet<String>>()

    override fun getSize() = index.entries.sumBy { it.value.size }
    override fun clear() = index.clear()

    override fun getWithDefault(signature: Int): Sequence<String> {
        val result = index[signature] ?: return emptySequence()
        return result.asSequence().constrainOnce()
    }

    override fun addAll(signature: Int, strings: Set<String>) {
        if (index[signature] == null) {
            index[signature] = hashSetOf()
        }
        index[signature]!!.addAll(strings)
    }

    fun refresh(element: PsiElement?) {
        index.clear()
        element ?: return
        val collector = TypoFixerLanguageSupport.getSupport(element.language)?.getLocalDictionaryCollector() ?: return
        getWords(collector, element).addAllToIndex()
    }

    fun refreshWithWords(words: Set<String>) {
        index.clear()
        words.addAllToIndex()
    }

    @TestOnly
    override fun contains(str: String) = index[signature.get(str)]?.contains(str) ?: false
}