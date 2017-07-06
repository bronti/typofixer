package com.jetbrains.typofixer.search

import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.signature.Signature

/**
 * @author bronti.
 */
class Index(val signatureProvider: Signature) {

    private val index = HashMap<Int, HashSet<String>>()

    init {

    }

    fun get(signature: Int): Set<String> {
        return index[signature] ?: HashSet()
    }

    fun feed(psiFile: PsiFile) {
        index.clear()
        IndexCollector.Extension.getIndexCollector(psiFile.language).keyWords().forEach { add(it) }
    }

    private fun add(str: String) {
        val signature = signatureProvider.signature(str)
        if (index[signature] == null) {
            index[signature] = HashSet()
        }
        index[signature]!!.add(str)
    }
}