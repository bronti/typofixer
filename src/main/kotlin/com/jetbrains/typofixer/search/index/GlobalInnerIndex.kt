package com.jetbrains.typofixer.search.index

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDirectory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.jetbrains.typofixer.search.signature.Signature
import java.util.*


class GlobalInnerIndex(project: Project, signature: Signature) : GlobalInnerIndexBase(project, signature) {
    private var methodNamesCollected = false
    private var fieldNamesCollected = false
    private var classNamesCollected = false
    private val dirsToCollectPackages = ArrayList<PsiDirectory>()
    private val packageNames = HashSet<String>()

    override fun getRefreshingTask(): CollectProjectNamesBase = CollectProjectNames()

    private inner class CollectProjectNames : CollectProjectNamesBase() {
        override fun doCollect(indicator: ProgressIndicator?) {
            val cache = PsiShortNamesCache.getInstance(project)

            // todo: refactor
            checkedCollect(indicator, methodNamesCollected, { cache.allMethodNames }) { methodNamesCollected = true }
            checkedCollect(indicator, fieldNamesCollected, { cache.allFieldNames }) { fieldNamesCollected = true }
            checkedCollect(indicator, classNamesCollected, { cache.allClassNames }) { classNamesCollected = true }

            val initialPackage = JavaPsiFacade.getInstance(project).findPackage("")
            val javaDirService = JavaDirectoryService.getInstance()
            val scope = GlobalSearchScope.allScope(project)

            dirsToCollectPackages.addAll(
                    initialPackage?.getDirectories(scope)?.flatMap { it.subdirectories.toList() } ?: emptyList()
            )

            //doesn't work:
//            PackageIndexUtil.getSubPackageFqNames(FqName.ROOT, scope, project, { true })
//                    .flatMap { it.pathSegments() }.map { it.identifier }.toSet()

            while (shouldCollect(indicator) && dirsToCollectPackages.isNotEmpty()) {
                val subDir = dirsToCollectPackages.last()
                val subPackage = javaDirService.getPackage(subDir)
                val subPackageName = subPackage?.name
                if (subPackageName != null && subPackageName.isNotBlank()) {
                    packageNames.add(subPackageName)
                }
                dirsToCollectPackages.removeAt(dirsToCollectPackages.size - 1)
                if (subPackage != null) {
                    // todo: filter resources (?)
                    dirsToCollectPackages.addAll(subDir.subdirectories)
                }
            }

            if (shouldCollect(indicator) && packageNames.isNotEmpty()) {
                synchronized(this) {
                    if (shouldCollect(indicator)) packageNames.addAllToIndex()
                    packageNames.clear()
                }
            }
        }
    }
}