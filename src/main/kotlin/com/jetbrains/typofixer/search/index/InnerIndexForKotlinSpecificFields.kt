package com.jetbrains.typofixer.search.index

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.PsiShortNamesCache
import com.jetbrains.typofixer.search.signature.Signature

class InnerIndexForKotlinSpecificFields(project: Project, signature: Signature) : GlobalInnerIndexBase(project, signature) {
    override fun getRefreshingTask(): CollectProjectNamesBase = CollectProjectNames()

    private inner class CollectProjectNames : CollectProjectNamesBase() {

        private var allCollected = false

        override fun doCollect(indicator: ProgressIndicator?) {
            val cache = PsiShortNamesCache.getInstance(project)
            fun extractFieldNamesFromGettersOrSetters() =
                    cache.allMethodNames
                            .filter { it.startsWith("get") || it.startsWith("set") }
                            .filter { it.length >= 4 && it[3].isUpperCase() }
                            .map { it.substring(3) }
                            .map { it[0].toLowerCase() + it.substring(1) }
                            .toTypedArray()

            checkedCollect(indicator, allCollected, { extractFieldNamesFromGettersOrSetters() }) { allCollected = true }
        }
    }
}