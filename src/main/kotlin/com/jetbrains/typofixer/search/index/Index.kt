package com.jetbrains.typofixer.search.index

import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.signature.Signature

/**
 * @author bronti.
 */
class Index(val signatureProvider: Signature) {

    private val index = HashMap<Int, HashSet<String>>()

    fun get(signature: Int): Set<String> {
        return index[signature] ?: HashSet()
    }

    fun feed(psiFile: PsiFile) {
        index.clear()
        val collector = IndexCollector.Extension.getIndexCollector(psiFile.language)
        collector.keyWords().forEach { add(it) }
        collector.localIdentifiers(psiFile).forEach { add(it) }
    }

    fun add(str: String) {
        val signature = signatureProvider.signature(str)
        if (index[signature] == null) {
            index[signature] = HashSet()
        }
        index[signature]!!.add(str)
    }
}