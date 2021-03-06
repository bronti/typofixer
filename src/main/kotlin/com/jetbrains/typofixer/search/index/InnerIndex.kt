package com.jetbrains.typofixer.search.index

import com.jetbrains.typofixer.search.signature.Signature
import org.jetbrains.annotations.TestOnly

abstract class InnerIndex(val signature: Signature) {

    abstract fun getSize(): Int

    // todo: make signatures lazy (?)
    open fun getAll(signatures: Set<Int>) = signatures.asSequence().flatMap { getWithDefault(it) }

    open fun addAll(strings: Set<String>) {
        strings.groupBy { signature.get(it) }.forEach { addAll(it.key, it.value.toSet()) }
    }

    protected abstract fun getWithDefault(signature: Int): Sequence<String>
    protected abstract fun addAll(signature: Int, strings: Set<String>)

    @TestOnly
    abstract fun contains(str: String): Boolean
}