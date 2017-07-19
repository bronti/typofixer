package ru.jetbrains.yaveyn.fuzzysearch.test.search.search

import com.jetbrains.typofixer.search.index.Index
import com.jetbrains.typofixer.search.signature.Signature
import java.io.BufferedReader
import java.util.zip.ZipFile
import kotlin.streams.toList


class FromZipTestIndexRetriever(private val signatureProvider: Signature) {

    val splitBy = Regex("[^a-zA-Z0-9_]+")
    val word = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    fun retrieve(pathToZip: String): Index {
        val zipFile = ZipFile(pathToZip)
        val entries = zipFile.entries()

        val index = Index(signatureProvider)

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val reader = zipFile.getInputStream(entry).bufferedReader()
            retrieveFromReaderToIndex(reader, index)
        }
        return index
    }

    private fun retrieveFromReaderToIndex(reader: BufferedReader, index: Index) {
        val words = reader.lines().flatMap { splitBy.split(it).filter { str -> word.matches(str) }.stream() }
        index.addAllToLocalIndex(words.toList())
    }

}