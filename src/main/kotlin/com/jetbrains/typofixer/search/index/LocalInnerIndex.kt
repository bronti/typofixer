package com.jetbrains.typofixer.search.index

import com.intellij.psi.PsiElement
import com.jetbrains.typofixer.lang.LocalDictionaryCollector
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly
import java.util.*

class LocalInnerIndex(
        signature: Signature,
        private val getWords: (wordsCollector: LocalDictionaryCollector, element: PsiElement) -> Set<String>
) : InnerIndex(signature) {

    private val index = HashMap<Int, HashSet<String>>()

    override fun getSize() = index.entries.sumBy { it.value.size }
    fun clear() = index.clear()

    override fun getWithDefault(signature: Int) = index[signature]?.asSequence()?.constrainOnce() ?: emptySequence()

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
        addAll(getWords(collector, element))
    }

    fun refreshWithWords(words: Set<String>) {
        index.clear()
        addAll(words)
    }

    @TestOnly
    override fun contains(str: String) = index[signature.get(str)]?.contains(str) == true
}