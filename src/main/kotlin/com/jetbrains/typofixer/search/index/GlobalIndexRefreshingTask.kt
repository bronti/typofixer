package com.jetbrains.typofixer.search.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.progress.util.ReadTask
import com.intellij.openapi.project.DumbService
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.jetbrains.typofixer.typoFixerComponent
import org.jetbrains.annotations.TestOnly


abstract class GlobalIndexRefreshingTaskBase(private val targetIndex: GlobalInnerIndex) : ReadTask() {

    private var done = false
        set
    protected abstract val tasks: List<(indicator: ProgressIndicator?) -> Set<String>>
    private var currentTask = 0

    protected val project get() = targetIndex.project

    private fun isCurrentRefreshingTask() = targetIndex.isCurrentRefreshingTask(this)

    override fun runBackgroundProcess(indicator: ProgressIndicator): Continuation? {
        ApplicationManager.getApplication().runReadAction { performCollection(indicator) }
        return null
    }

    override fun onCanceled(p0: ProgressIndicator) {
        if (!done) {
            ProgressIndicatorUtils.scheduleWithWriteActionPriority(this)
        }
    }

    private fun performCollection(indicator: ProgressIndicator?) {
        if (project.isInitialized) {
            tasks.indices.forEach { taskIndex ->
                withDoubleCheckedSynchronization({ shouldPerformTask(indicator, taskIndex) }) {
                    targetIndex.addAll(tasks[taskIndex](indicator))
                    currentTask++
                }
            }
        }
        withDoubleCheckedSynchronization({ shouldCollect(indicator) }) {
            targetIndex.startEntriesCompression()
        }

        // todo: check that index is refreshing after each stub index refreshment
        if (DumbService.isDumb(project) || shouldCollect(indicator)) {
            targetIndex.clearRefreshingTask(this)
        }

        if (targetIndex.isUsable()) {
            project.typoFixerComponent.onSearcherStatusMaybeChanged()
        }

        done = true
    }

    private fun shouldPerformTask(indicator: ProgressIndicator?, taskIndex: Int) =
            currentTask == taskIndex && shouldCollect(indicator)

    // once false -> always false after that
    protected fun shouldCollect(indicator: ProgressIndicator?): Boolean {
        indicator?.checkCanceled()
        if (DumbService.isDumb(project) || !isCurrentRefreshingTask()) {
            done = true
        }
        return !done
    }

    private fun withDoubleCheckedSynchronization(doCheck: () -> Boolean, doTask: () -> Unit) {
        if (doCheck()) {
            synchronized(targetIndex) {
                if (doCheck()) doTask()
            }
        }
    }

    // can be interrupted by dumb mode
    @TestOnly
    fun waitForRefreshing() {
        while (!done) {
            performCollection(null)
        }
    }
}

class KotlinGettersSettersCollector(targetIndex: GlobalInnerIndex) : GlobalIndexRefreshingTaskBase(targetIndex) {

    override val tasks = listOf<(ProgressIndicator?) -> Set<String>>(
            { extractFieldNamesFromGettersOrSetters() }
    )

    private fun extractFieldNamesFromGettersOrSetters() =
            PsiShortNamesCache
                    .getInstance(project)
                    .allMethodNames
                    .filter { it.length >= 4 && it[3].isUpperCase() }
                    .filter { it.startsWith("get") || it.startsWith("set") }
                    .map { it[3].toLowerCase() + it.substring(4) }
                    .toSet()
}

class ClassNamesCollector(targetIndex: GlobalInnerIndex) : GlobalIndexRefreshingTaskBase(targetIndex) {
    private val namesCache get() = PsiShortNamesCache.getInstance(project)

    override val tasks = listOf<(indicator: ProgressIndicator?) -> Set<String>>(
            { namesCache.allClassNames.toSet() }
    )
}

class AllNamesExceptClassNamesCollector(targetIndex: GlobalInnerIndex) : GlobalIndexRefreshingTaskBase(targetIndex) {
    private val namesCache get() = PsiShortNamesCache.getInstance(project)

    override val tasks = listOf<(indicator: ProgressIndicator?) -> Set<String>>(
            { namesCache.allMethodNames.toSet() },
            { namesCache.allFieldNames.toSet() },
            { getPackageNames(it) }
    )

    private fun getPackageNames(indicator: ProgressIndicator?): Set<String> {
        val initialPackage = JavaPsiFacade.getInstance(project).findPackage("")
        val javaDirService = JavaDirectoryService.getInstance()
        val globalScope = GlobalSearchScope.allScope(project)

        val initialDirectories = initialPackage?.getDirectories(globalScope) ?: arrayOf()

        val dirsToCollectPackages = initialDirectories.flatMap { it.subdirectories.toList() }.toMutableList()
        val packageNames = HashSet<String>()

//        // doesn't work:
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
        return packageNames
    }
}