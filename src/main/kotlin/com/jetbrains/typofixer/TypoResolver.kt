package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.impl.CommandMerger
import com.intellij.openapi.command.impl.EditorChangeAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UnexpectedUndoException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorBundle
import com.intellij.openapi.editor.impl.event.DocumentEventImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.jetbrains.typofixer.lang.TypoFixerLanguageSupport

/**
 * @author bronti
 */

class TypoResolver {
    private val nextChar: Char
    private val editor: Editor
    private val psiFile: PsiFile
    private val project: Project
    private val document: Document

    private val langSupport: TypoFixerLanguageSupport?
    private var needToResolve: Boolean

    private var elementStartOffset: Int? = null
    private var oldText: String? = null
    private var replacement: String? = null

    constructor(nextChar: Char, editor: Editor, psiFile: PsiFile) {
        this.nextChar = nextChar
        this.editor = editor
        this.psiFile = psiFile
        document = editor.document
        project = psiFile.project

        langSupport = TypoFixerLanguageSupport.getSupport(psiFile.language)
        needToResolve = langSupport != null && !langSupport.identifierChar(nextChar)

        if (!needToResolve) return

        val nextCharOffset = editor.caretModel.offset
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val element = psiFile.findElementAt(nextCharOffset - 1)

        if (element == null || !langSupport!!.isTypoResolverApplicable(element)) {
            needToResolve = false
            return
        }

        elementStartOffset = element.textOffset
        oldText = element.text.substring(0, nextCharOffset - elementStartOffset!!)

        val searcher = project.getComponent(TypoFixerComponent::class.java).searcher
        replacement = searcher.findClosest(oldText!!, psiFile)
        if (replacement == null || replacement == oldText) {
            needToResolve = false
        }
    }

    fun resolve() {
        if (!needToResolve) return

        // todo: fix ctrl + z
        // todo: see EditorComponentImpl.replaceText (?)

        fun retrieveElementWithText(text: String): PsiElement? {
            PsiDocumentManager.getInstance(project).commitDocument(document)
            val element = psiFile.findElementAt(elementStartOffset!!)
            if (element == null || element.text.substring(0, text.length) != text) return null
            return element
        }

        retrieveElementWithText(oldText!!) ?: return

        val commandProcessor = CommandProcessor.getInstance()
        commandProcessor.markCurrentCommandAsGlobal(project)
        commandProcessor.executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction {
                document.replaceString(elementStartOffset!!, elementStartOffset!! + oldText!!.length, replacement!!)
            }
        }, "Resolve Typo", null, UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION, document)

        editor.caretModel.moveToOffset(elementStartOffset!! + replacement!!.length)


        // todo: make language specific
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val newElement = retrieveElementWithText(replacement!!) ?: return
        val newParentElement = newElement.parent

        // todo: write action priority (?)
        Thread {
            var parentIsUnresolved = false
            ApplicationManager.getApplication().runReadAction {
                parentIsUnresolved = newParentElement.isValid && newParentElement is PsiReference && newParentElement.resolve() == null
            }
            if (newParentElement is PsiErrorElement // <- as far as I can tell it's not likely to happen
                    || parentIsUnresolved) {

                ApplicationManager.getApplication().invokeLater {
                    commandProcessor.executeCommand(project, {
                        ApplicationManager.getApplication().runWriteAction {
                            document.replaceString(elementStartOffset!!, elementStartOffset!! + replacement!!.length, oldText!!)
                        }
                    }, null, document, UndoConfirmationPolicy.DEFAULT, document)
                }
            }
        }.start()
    }
}