package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport
import com.jetbrains.typofixer.search.FoundWord
import com.jetbrains.typofixer.search.index.GlobalInnerIndex
import com.jetbrains.typofixer.settings.TypoFixerSettings
import org.jetbrains.annotations.TestOnly

/**
 * @author bronti
 */
class TypoResolver private constructor(
        private val editor: Editor,
        private val typoCase: TypoCase,
        private val replacements: Sequence<FoundWord>,
        private val checkTime: () -> Unit
) {
    companion object {
        fun getResolver(nextChar: Char, editor: Editor, psiFile: PsiFile): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            if (!psiFile.project.typoFixerComponent.isActive) return null

            return doGetResolver(nextChar, editor, psiFile, langSupport, true)
        }

        private fun refreshPsi(editor: Editor) {
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    PsiDocumentManager.getInstance(editor.project!!).commitDocument(editor.document)
                }
            }
        }

        private fun doGetResolver(
                nextChar: Char,
                editor: Editor,
                psiFile: PsiFile,
                langSupport: TypoFixerLanguageSupport,
                needTimeChecking: Boolean = false
        ): TypoResolver? {

            val project = psiFile.project
            val settings = TypoFixerSettings.getInstance(project)
            val statistics = project.statistics

            val findTimeChecker = getTimeChecker(needTimeChecking, settings.maxMillisForFind, statistics::onFindAbortedBecauseOfTimeLimits)
            val resolveTimeChecker = getTimeChecker(needTimeChecking, settings.maxMillisForResolve, statistics::onResolveAbortedBecauseOfTimeLimits)

            val nextCharOffset = editor.caretModel.offset

            // todo: I don't want to call refresh if resolve is never triggered
            refreshPsi(editor)
            val element = psiFile.findElementAt(nextCharOffset - 1) ?: return null

            val elementStartOffset = element.textOffset
            val oldText = element.text.substring(0, nextCharOffset - elementStartOffset)

            for (typoCase in langSupport.getTypoCases(editor, psiFile, elementStartOffset, oldText, resolveTimeChecker)) {
                if (!typoCase.triggersResolve(nextChar)) continue

                typoCase.setUp()

                if (typoCase.canBeApplicable()) {

                    val replacements: Sequence<FoundWord>
                    try {
                        // todo: check validity (?)
                        replacements = typoCase.getReplacement(findTimeChecker)
                    } catch (e: ResolveCancelledException) {
                        return null
                    }

                    project.statistics.onTypoResolverCreated()
                    return TypoResolver(editor, typoCase, replacements, resolveTimeChecker)
                }
            }
            return null
        }

        @TestOnly
        fun getInstanceIgnoreIsActive(nextChar: Char, editor: Editor, psiFile: PsiFile, needTimeChecking: Boolean = false): TypoResolver? {
            val langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language) ?: return null
            return doGetResolver(nextChar, editor, psiFile, langSupport, needTimeChecking)
        }
    }

    fun resolve() = Thread {
        try {
            doResolve()
        } catch (e: ResolveCancelledException) {
            // do nothing
        } catch (e: GlobalInnerIndex.TriedToAccessIndexWhileItIsRefreshing) {
            // do nothing
        }
    }.start()

    private fun doResolve() {
        // refreshPsi is necessary here! file.copy() will be called in typoCase after that moment
        refreshPsi(editor)
        checkTime()
        if (!typoCase.isApplicable()) return
        checkTime()
        typoCase.resolveAll(replacements)
    }

    @TestOnly
    fun waitForResolve() = doResolve()
}

class ResolveCancelledException : RuntimeException()

private fun getTimeChecker(needed: Boolean, maxMillis: Long, onAborted: () -> Unit) =
        TimeLimitsChecker.getChecker(needed, maxMillis, onAborted)

private class TimeLimitsChecker private constructor(private val timeConstraint: Long, private val reportAbort: () -> Unit) {
    companion object {
        fun getChecker(needed: Boolean, maxMillis: Long, onAborted: () -> Unit) =
                if (!needed) fun() { /* do nothing */ }
                else TimeLimitsChecker(maxMillis, onAborted)::checkTime
    }
    private var abortReported = false
    private val startTime = System.currentTimeMillis()
    fun checkTime() {
        val shouldCancel = startTime + timeConstraint <= System.currentTimeMillis()
        if (!shouldCancel) return
        if (!abortReported) reportAbort()
        throw ResolveCancelledException()
    }
}