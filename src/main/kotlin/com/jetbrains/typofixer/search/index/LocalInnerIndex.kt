package com.jetbrains.typofixer.search.index

import com.intellij.psi.PsiElement
import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly
import java.util.*

class LocalInnerIndex(signature: Signature, val getWords: (element: PsiElement) -> Set<String>) : InnerIndex(signature) {

    private val index = HashMap<Int, HashSet<String>>()

    override fun getSize() = index.entries.sumBy { it.value.size }
    override fun clear() = index.clear()

    override fun getWithDefault(signature: Int): HashSet<String> {
        val result = index[signature] ?: return hashSetOf()
        return result
    }

    override fun addAll(signature: Int, strings: Set<String>) {
        index[signature] = getWithDefault(signature)
        index[signature]!!.addAll(strings)
    }

    fun refresh(element: PsiElement?) {
        index.clear()
        element ?: return
        addAll(getWords(element))
    }

    fun refreshWithWords(words: List<String>) {
        index.clear()
        addAll(words.toSet())
    }

    @TestOnly
    override fun contains(str: String) = index[signature.get(str)]?.contains(str) ?: false
}