// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.nj2k.printing

import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.nj2k.tree.JKComment
import org.jetbrains.kotlin.nj2k.tree.JKDeclaration
import org.jetbrains.kotlin.nj2k.tree.JKFormattingOwner

internal class JKCommentPrinter(private val printer: JKPrinter) {
    private val printedTokens = mutableSetOf<JKComment>()

    //TODO move to ast transformation phase
    private fun JKComment.shouldBeDropped(): Boolean =
        text.startsWith("//noinspection")

    private fun JKComment.createText() =
        if (this !in printedTokens) {
            printedTokens += this

            // hack till #KT-16845, #KT-23333 are fixed
            if (!isSingleline && text.lastIndexOf("/*") != text.indexOf("/*")) {
                text.replace("/*", "/ *")
                    .replaceFirst("/ *", "/*")
            } else text
        } else null


    private fun List<JKComment>.createText(): String = buildString {
        var needNewLine = false
        for (comment in this@createText) {
            if (comment.shouldBeDropped()) continue
            val text = comment.createText() ?: continue
            if (needNewLine && comment.indent?.let { StringUtil.containsLineBreak(it) } != true) appendLine()
            append(comment.indent ?: ' ')
            append(text)
            needNewLine = text.startsWith("//") || '\n' in text
        }
    }

    private fun String.hasNoLineBreakAfterSingleLineComment() = lastIndexOf('\n') < lastIndexOf("//")


    fun printCommentsAfter(element: JKFormattingOwner) {
        val text = element.commentsAfter.createText()
        printer.print(text)

        val addNewLine = element.hasLineBreakAfter || text.hasNoLineBreakAfterSingleLineComment()
        if (addNewLine) printer.println()
    }


    fun printCommentsBefore(element: JKFormattingOwner) {
        val text = element.commentsBefore.createText()
        printer.print(text)

        val addNewLine = element.hasLineBreakBefore
                         || element is JKDeclaration && element.commentsBefore.isNotEmpty() // add new line between comment & declaration
                         || text.hasNoLineBreakAfterSingleLineComment()

        if (addNewLine) printer.println()
    }
}
