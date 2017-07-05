package com.jetbrains.typofixer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement

/**
 * @author bronti
 */

public fun checkedTypoResolve(element: PsiElement) {
    ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(element.toString(), "") }
}
