/*
 * This file is part of takenaka, licensed under the Apache License, Version 2.0 (the "License").
 *
 * Copyright (c) 2023 Matous Kucera
 *
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.kcra.takenaka.core.mapping.analysis.impl

import me.kcra.takenaka.core.mapping.analysis.ProblemResolution
import me.kcra.takenaka.core.mapping.resolve.impl.VanillaMappingContributor
import me.kcra.takenaka.core.mapping.resolve.impl.interfaces
import me.kcra.takenaka.core.mapping.resolve.impl.superClass
import me.kcra.takenaka.core.mapping.util.contains
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MappingTreeView
import org.objectweb.asm.Opcodes

/**
 * A base implementation of [me.kcra.takenaka.core.mapping.analysis.MappingAnalyzer] that corrects problems defined in [StandardProblemKinds].
 *
 * @property analysisOptions the analysis configuration
 * @author Matouš Kučera
 */
open class MappingAnalyzerImpl(val analysisOptions: AnalysisOptions = AnalysisOptions()) : AbstractMappingAnalyzer() {
    /**
     * Visits a class for analysis.
     */
    override fun acceptClass(klass: MappingTree.ClassMapping): ClassAnalysisContext {
        checkElementModifiers(klass) {
            element.tree.classes.remove(element)
        }

        klass.tree.dstNamespaces.forEach { ns ->
            val nsId = klass.tree.getNamespaceId(ns)

            if (ns in analysisOptions.innerClassNameCompletionCandidates) {
                // completes missing inner/anonymous class mappings

                // works on the principle of presuming that the owner part
                // of the inner/anonymous class name should be completed with the owner's mapping

                val dollarIndex = klass.srcName.lastIndexOf('$')
                if (dollarIndex != -1 && klass.getDstName(nsId) == null) {
                    val owner = klass.srcName.substring(0, dollarIndex)

                    val ownerKlass = klass.tree.getClass(owner)
                    if (ownerKlass != null) { // owner is from the mapping tree
                        val ownerName = ownerKlass.getDstName(nsId)
                        if (ownerName != null) { // owner has a mapping
                            val name = klass.srcName.substring(dollarIndex + 1)

                            problem(klass, ns, StandardProblemKinds.INNER_CLASS_OWNER_NOT_MAPPED) {
                                klass.setDstName("$ownerName$$name", nsId)
                            }
                        }
                    }
                }
            }
        }

        return ClassContext(this, klass)
    }

    /**
     * Performs checks on modifiers of an element.
     *
     * @param element the element
     * @param removeResolution a resolution which removes the element
     * @param T the element type
     */
    protected fun <T : MappingTree.ElementMapping> checkElementModifiers(element: T, removeResolution: ProblemResolution<T>) {
        val mod = element.getName(VanillaMappingContributor.NS_MODIFIERS)?.toIntOrNull()

        if (mod == null) {
            problem(element, null, StandardProblemKinds.NON_EXISTENT_MAPPING, removeResolution)
        }
        if (mod != null && (mod and Opcodes.ACC_SYNTHETIC) != 0) {
            problem(element, null, StandardProblemKinds.SYNTHETIC, removeResolution)
        }
    }

    /**
     * A [MappingAnalyzerImpl]-specific class analysis context.
     *
     * @property analyzer the analyzer which created this context
     * @property klass the analyzed class
     */
    open class ClassContext(final override val analyzer: MappingAnalyzerImpl, final override val klass: MappingTree.ClassMapping) : ClassAnalysisContext {
        /**
         * Super types of the class.
         */
        protected val superTypes = klass.superTypes

        /**
         * Additional namespace IDs of the class' mapping tree.
         */
        protected val additionalNamespaceIds = analyzer.analysisOptions.inheritanceAdditionalNamespaces.map(klass.tree::getNamespaceId) - MappingTree.NULL_NAMESPACE_ID

        /**
         * Whether inheritance error correction should be skipped for further visited members.
         *
         * The class is exempt from inheritance correction if there are only `java.*` super types
         * and/or if both [VanillaMappingContributor.NS_INTERFACES] and [VanillaMappingContributor.NS_SUPER] are missing.
         */
        protected var skipInheritanceChecks = (VanillaMappingContributor.NS_INTERFACES !in klass.tree && VanillaMappingContributor.NS_SUPER !in klass.tree)
                || (klass.superClass.startsWith("java/") && klass.interfaces.all { it.startsWith("java/") })

        /**
         * Visits a field for analysis.
         */
        override fun acceptField(field: MappingTree.FieldMapping) {
            analyzer.checkElementModifiers(field) {
                element.owner.fields.remove(element)
            }
        }

        /**
         * Visits a method for analysis.
         */
        override fun acceptMethod(method: MappingTree.MethodMapping) {
            fun MappingTreeView.MethodMappingView.getAdditionalMappingSet(): Set<String> =
                additionalNamespaceIds.mapNotNullTo(mutableSetOf(), ::getDstName)

            analyzer.checkElementModifiers(method) {
                element.owner.methods.remove(element)
            }

            if (!skipInheritanceChecks) {
                // corrects inheritance errors

                // Intermediary & possibly more: overridden methods are not mapped, those are completed
                // Spigot: replaces wrong names with correct ones from supertypes

                val namespaceIdsToCorrect = (method.tree.dstNamespaces - analyzer.analysisOptions.inheritanceErrorExemptions)
                    .mapTo(mutableSetOf(), method.tree::getNamespaceId)

                namespaceIdsToCorrect.remove(MappingTree.NULL_NAMESPACE_ID) // pop null id, if it's present

                if (namespaceIdsToCorrect.isNotEmpty()) {
                    val srcMethodDesc = method.srcDesc.withoutReturnType
                    val additionalMappings by lazy(LazyThreadSafetyMode.NONE, method::getAdditionalMappingSet)

                    superTypes.forEach { superType ->
                        superType.methods.forEach superEach@ { superMethod ->
                            if (superMethod.srcDesc.withoutReturnType != srcMethodDesc) return@superEach
                            if (superMethod.srcName != method.srcName && additionalMappings.none(superMethod.getAdditionalMappingSet()::contains)) return@superEach

                            namespaceIdsToCorrect.removeIf { nsId ->
                                val superMethodName = superMethod.getDstName(nsId)
                                    ?: return@removeIf false
                                val methodName = method.getDstName(nsId)

                                if (methodName != superMethodName) {
                                    analyzer.problem(method, method.tree.getNamespaceName(nsId), StandardProblemKinds.INHERITANCE_ERROR) {
                                        method.setDstName(superMethodName, nsId)
                                    }
                                }
                                return@removeIf true
                            }
                            if (namespaceIdsToCorrect.isEmpty()) return // perf: return early when method is corrected on all namespaces
                        }
                    }
                }
            }
        }
    }
}

/**
 * Recursively collects all **mapped** supertypes of the class.
 */
val MappingTree.ClassMapping.superTypes: List<MappingTree.ClassMapping>
    get() {
        val superTypes = mutableSetOf<String>()
        val mappedSuperTypes = mutableListOf<MappingTree.ClassMapping>()

        fun processType(klassName: String) {
            if (superTypes.add(klassName)) {
                val klass = tree.getClass(klassName) ?: return
                mappedSuperTypes += klass

                processType(klass.superClass)
                klass.interfaces.forEach(::processType)
            }
        }

        processType(superClass)
        interfaces.forEach(::processType)

        return mappedSuperTypes
    }

/**
 * Returns a descriptor string with the return type removed.
 */
inline val String.withoutReturnType: String
    get() {
        val parenthesisIndex = lastIndexOf(')')
        if (parenthesisIndex == -1) {
            return this
        }

        return substring(0, parenthesisIndex + 1)
    }
