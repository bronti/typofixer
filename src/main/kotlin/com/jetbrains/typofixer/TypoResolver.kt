package com.jetbrains.typofixer

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.typofixer.search.FuzzySearcher

/**
 * @author bronti
 */

// todo: LanguageExtensionPoint
abstract class TypoResolver {
    fun checkedTypoResolve(nextChar: Char, nextCharOffset: Int, editor: Editor, project: Project, psiFile: PsiFile) {
        if (afterIdentifierChar(nextChar)) return

        val psiManager = PsiDocumentManager.getInstance(project)

        // refresh psi
        psiManager.commitDocument(editor.document)

        val element = psiFile.findElementAt(nextCharOffset - 1)

        if (element != null && isTypoResolverApplicable(element)) {
            val searcher = project.getComponent(FuzzySearcher::class.java)

            val oldText = element.text
            val replacement = searcher.findClosest(oldText)

            ApplicationManager.getApplication().runWriteAction {
                editor.document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
            }
            editor.caretModel.moveToOffset(nextCharOffset + replacement.length - oldText.length)
        }
    }

    abstract protected fun afterIdentifierChar(c: Char): Boolean

    abstract protected fun isTypoResolverApplicable(element: PsiElement): Boolean

    class Extension : LanguageExtension<TypoResolver>("com.jetbrains.typofixer.typoFixerLanguageSupport") {
        companion object {
            val INSTANCE = TypoResolver.Extension()

            fun getResolver(language: Language): TypoResolver {
                return INSTANCE.forLanguage(language)
            }
        }
    }
}