package com.jetbrains.typofixer.search.index

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.java.stubs.index.JavaFieldNameIndex
import com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex
import com.intellij.psi.impl.java.stubs.index.JavaShortClassNameIndex

/**
 * @author bronti.
 */
abstract class IndexCollector {
    abstract fun keyWords(): List<String>
    abstract fun localIdentifiers(psiFile: PsiFile): List<String>

    fun projectIdentifiers(project: Project): List<String> {
        val classNames = JavaShortClassNameIndex.getInstance().getAllKeys(project)
        val methodNames = JavaMethodNameIndex.getInstance().getAllKeys(project)
        val fieldsNames = JavaFieldNameIndex.getInstance().getAllKeys(project)
        return classNames + methodNames + fieldsNames
    }
}