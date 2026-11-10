/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaParameterSignature

/**
 * Release-time structural signature check for `@RDMA` callables.
 *
 * The compatibility signal in the changelog is a whole-declaration content hash, which cannot
 * tell a *compatible* parameter-list change from a *breaking* one. Adding a defaulted parameter at
 * the end is forward/backward compatible (old callers simply pass fewer arguments and the runtime
 * fills the default); every other parameter-list change — reorder, remove, rename, type change,
 * non-trailing insertion, or adding a *required* parameter — breaks the positional contract and
 * must be rejected at release time rather than silently shipped as a broken bridge.
 */
object RdmaSignatureClassifier {

    data class Report(val errors: List<String> = emptyList()) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
    }

    /**
     * Compares each callable's current signature against the committed baseline [oldSignatures].
     * Only `@RDMA` boundary callables (which carry a non-null [RdmaDeclaration.signature]) are
     * considered; private helpers change freely.
     */
    fun classify(oldSignatures: Map<String, List<RdmaParameterSignature>>, current: List<RdmaDeclaration>): Report {
        val errors = mutableListOf<String>()
        for (decl in current) {
            if (!decl.isRdma) continue
            val newSig = decl.signature ?: continue
            val oldSig = oldSignatures[decl.fqn] ?: continue // newly added symbol — nothing to compare
            errors += classifyCallable(decl.fqn, oldSig, newSig)
        }
        return Report(errors)
    }

    private fun classifyCallable(fqn: String, oldSig: List<RdmaParameterSignature>, newSig: List<RdmaParameterSignature>): List<String> {
        // Identical: a body / default-value-only change (handled by the hash diff as MODIFY).
        if (oldSig == newSig) return emptyList()
        // Appending trailing *defaulted* parameters is compatible.
        if (newSig.size > oldSig.size &&
            newSig.take(oldSig.size) == oldSig &&
            newSig.drop(oldSig.size).all { it.hasDefault }
        ) {
            return emptyList()
        }
        return listOf(diagnose(fqn, oldSig, newSig))
    }

    private fun diagnose(fqn: String, oldSig: List<RdmaParameterSignature>, newSig: List<RdmaParameterSignature>): String {
        val common = minOf(oldSig.size, newSig.size)
        for (i in 0 until common) {
            val o = oldSig[i]
            val n = newSig[i]
            if (o != n) {
                return when {
                    o.name != n.name ->
                        "$fqn: parameter at index $i was renamed '${o.name}' -> '${n.name}' (incompatible change)"
                    o.type != n.type ->
                        "$fqn: parameter '${o.name}' changed type '${o.type}' -> '${n.type}' (incompatible change)"
                    o.hasDefault != n.hasDefault ->
                        "$fqn: parameter '${o.name}' changed default-ness (incompatible change)"
                    else -> "$fqn: parameter '${o.name}' changed incompatibly"
                }
            }
        }
        if (newSig.size < oldSig.size) {
            val removed = oldSig.drop(newSig.size).joinToString(", ") { "'${it.name}'" }
            return "$fqn: removed parameter(s) $removed (incompatible change)"
        }
        val added = newSig.drop(oldSig.size)
        val required = added.filter { !it.hasDefault }.joinToString(", ") { "'${it.name}'" }
        return if (required.isNotEmpty()) {
            "$fqn: added required parameter(s) $required; new parameters must be trailing and defaulted (incompatible change)"
        } else {
            "$fqn: incompatible parameter change (inserted a parameter before the end; only trailing defaulted parameters may be appended)"
        }
    }
}
