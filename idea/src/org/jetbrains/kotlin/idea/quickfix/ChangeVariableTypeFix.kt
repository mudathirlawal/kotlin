/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.idea.core.quickfix.QuickFixUtil
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.ShortenReferences
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import java.util.*

class ChangeVariableTypeFix(element: KtVariableDeclaration, private val type: KotlinType) : KotlinQuickFixAction<KtVariableDeclaration>(element) {

    override fun getText(): String {
        var propertyName = element.fqName?.asString() ?: element.name
        return KotlinBundle.message("change.element.type", propertyName, IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_IN_TYPES.renderType(type))
    }

    override fun getFamilyName()
            = KotlinBundle.message("change.type.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile)
            = super.isAvailable(project, editor, file) && !ErrorUtils.containsErrorType(type)

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val psiFactory = KtPsiFactory(file)

        assert(element.nameIdentifier != null) { "ChangeVariableTypeFix applied to variable without name" }

        val replacingTypeReference = psiFactory.createType(IdeDescriptorRenderers.SOURCE_CODE.renderType(type))
        val toShorten = ArrayList<KtTypeReference>()
        toShorten.add(element.setTypeReference(replacingTypeReference)!!)

        if (element is KtProperty) {
            val getterReturnTypeRef = element.getter?.returnTypeReference
            if (getterReturnTypeRef != null) {
                toShorten.add(getterReturnTypeRef.replace(replacingTypeReference) as KtTypeReference)
            }

            val setterParameterTypeRef = element.setter?.parameter?.typeReference
            if (setterParameterTypeRef != null) {
                toShorten.add(setterParameterTypeRef.replace(replacingTypeReference) as KtTypeReference)
            }
        }

        ShortenReferences.DEFAULT.process(toShorten)
    }

    companion object {

        fun createFactoryForComponentFunctionReturnTypeMismatch(): KotlinSingleIntentionActionFactory {
            return object : KotlinSingleIntentionActionFactory() {
                public override fun createAction(diagnostic: Diagnostic): IntentionAction? {
                    val entry = ChangeFunctionReturnTypeFix.getDestructuringDeclarationEntryThatTypeMismatchComponentFunction(diagnostic)
                    val context = entry.analyze()
                    val resolvedCall = context.get(BindingContext.COMPONENT_RESOLVED_CALL, entry) ?: return null
                    if (DescriptorToSourceUtils.descriptorToDeclaration(resolvedCall.candidateDescriptor) == null) return null
                    val expectedType = resolvedCall.candidateDescriptor.returnType ?: return null
                    return ChangeVariableTypeFix(entry, expectedType)
                }
            }
        }

        fun createFactoryForPropertyOrReturnTypeMismatchOnOverride(): KotlinIntentionActionsFactory {
            return object : KotlinIntentionActionsFactory() {
                override fun doCreateActions(diagnostic: Diagnostic): List<IntentionAction> {
                    val actions = LinkedList<IntentionAction>()

                    if (diagnostic.psiElement is KtProperty) {
                        val property = diagnostic.psiElement as KtProperty
                        val descriptor = property.resolveToDescriptor() as? PropertyDescriptor ?: return actions

                        var lowerBoundOfOverriddenPropertiesTypes = QuickFixUtil.findLowerBoundOfOverriddenCallablesReturnTypes(descriptor)

                        val propertyType = descriptor.returnType ?: error("Property type cannot be null if it mismatches something")

                        val overriddenMismatchingProperties = LinkedList<PropertyDescriptor>()
                        var canChangeOverriddenPropertyType = true
                        for (overriddenProperty in descriptor.overriddenDescriptors) {
                            val overriddenPropertyType = overriddenProperty.returnType
                            if (overriddenPropertyType != null) {
                                if (!KotlinTypeChecker.DEFAULT.isSubtypeOf(propertyType, overriddenPropertyType)) {
                                    overriddenMismatchingProperties.add(overriddenProperty)
                                }
                                else if (overriddenProperty.isVar && !KotlinTypeChecker.DEFAULT.equalTypes(overriddenPropertyType, propertyType)) {
                                    canChangeOverriddenPropertyType = false
                                }
                                if (overriddenProperty.isVar && lowerBoundOfOverriddenPropertiesTypes != null &&
                                    !KotlinTypeChecker.DEFAULT.equalTypes(lowerBoundOfOverriddenPropertiesTypes, overriddenPropertyType)) {
                                    lowerBoundOfOverriddenPropertiesTypes = null
                                }
                            }
                        }

                        if (lowerBoundOfOverriddenPropertiesTypes != null) {
                            actions.add(ChangeVariableTypeFix(property, lowerBoundOfOverriddenPropertiesTypes))
                        }

                        if (overriddenMismatchingProperties.size == 1 && canChangeOverriddenPropertyType) {
                            val overriddenProperty = DescriptorToSourceUtils.descriptorToDeclaration(overriddenMismatchingProperties.single())
                            if (overriddenProperty is KtProperty) {
                                actions.add(ChangeVariableTypeFix(overriddenProperty, propertyType))
                            }
                        }
                    }

                    return actions
                }
            }
        }
    }
}
