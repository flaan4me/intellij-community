// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
import com.intellij.codeInspection.ProblemHighlightType.INFORMATION
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.base.psi.textRangeIn
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractApplicabilityBasedInspection
import org.jetbrains.kotlin.idea.core.canMoveLambdaOutsideParentheses
import org.jetbrains.kotlin.idea.core.getLastLambdaExpression
import org.jetbrains.kotlin.idea.core.isComplexCallWithLambdaArgument
import org.jetbrains.kotlin.idea.core.moveFunctionLiteralOutsideParentheses
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

class MoveLambdaOutsideParenthesesInspection : AbstractApplicabilityBasedInspection<KtCallExpression>(KtCallExpression::class.java) {
    private val KtCallExpression.verb: String
        get() = if (isComplexCallWithLambdaArgument()) KotlinBundle.message("text.can") else KotlinBundle.message("text.should")

    override fun inspectionHighlightType(element: KtCallExpression): ProblemHighlightType =
        if (element.isComplexCallWithLambdaArgument()) INFORMATION else GENERIC_ERROR_OR_WARNING

    override fun isApplicable(element: KtCallExpression) = element.canMoveLambdaOutsideParentheses(skipComplexCalls = false)

    override fun applyTo(element: KtCallExpression, project: Project, editor: Editor?) {
        if (element.canMoveLambdaOutsideParentheses(skipComplexCalls = false)) {
            element.moveFunctionLiteralOutsideParentheses()
        }
    }

    override fun inspectionText(element: KtCallExpression) = KotlinBundle.message("lambda.argument.0.be.moved.out", element.verb)

    override fun inspectionHighlightRangeInElement(element: KtCallExpression) = element.getLastLambdaExpression()
        ?.getStrictParentOfType<KtValueArgument>()?.asElement()
        ?.textRangeIn(element)

    override val defaultFixText get() = KotlinBundle.message("move.lambda.argument.out.of.parentheses")
}