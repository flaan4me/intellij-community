// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.completion.addingPolicy

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import org.jetbrains.annotations.ApiStatus

/**
 * Policy, that controls how exactly elements should be
 * added to the result set
 */
@ApiStatus.Internal
interface ElementsAddingPolicy {
  /**
   * Called when the policy should come into effect
   *
   * @see #onDeactivate
   */
  fun onActivate(result: CompletionResultSet) {}

  /**
   * Called when result's {@link com.intellij.codeInsight.completion.CompletionResultSet#stopHere} was called
   */
  fun onResultStop(result: CompletionResultSet) {}

  /**
   * Called when another [element] should be added to [result]
   */
  fun addElement(result: CompletionResultSet, element: LookupElement)

  /**
   * Called when all [elements] should be added to [result]
   */
  fun addAllElements(result: CompletionResultSet, elements: MutableIterable<LookupElement>)

  /**
   * Called when the policy should end its action
   *
   * @see #onActivate
   */
  fun onDeactivate(result: CompletionResultSet) {}
}