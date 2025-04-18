// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.j2k.post.processing.processings

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.types.KotlinType

internal fun KtExpression.unpackedReferenceToProperty(): KtProperty? {
    val referenceExpression = when (this) {
        is KtNameReferenceExpression -> this
        is KtDotQualifiedExpression -> selectorExpression as? KtNameReferenceExpression
        else -> null
    }
    return referenceExpression?.references
        ?.firstOrNull { it is KtSimpleNameReference }
        ?.resolve() as? KtProperty
}

internal inline fun <reified T : PsiElement> List<PsiElement>.descendantsOfType(): List<T> =
    flatMap { it.collectDescendantsOfType() }

internal fun KtReferenceExpression.resolve(): PsiElement? =
    mainReference.resolve()

internal fun KtDeclaration.type(): KotlinType? =
    (resolveToDescriptorIfAny() as? CallableDescriptor)?.returnType