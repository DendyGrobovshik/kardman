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

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName

/**
 * Enforces the kernel-module public-API rule: every non-`@RDMA` top-level declaration
 * must be `internal` (or `private`). This keeps the public surface bridgeable — a
 * non-bridgeable type cannot leak into a public `@RDMA` signature because Kotlin itself
 * rejects "public function exposes its internal parameter/return type".
 */
object RdmaPublicApiChecker {

    private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")

    // The C-ABI bridge is generated as `@CName`-annotated public functions; they are
    // framework scaffolding, not user API, so they must not trip the public-API rule.
    private val CNAME_ANNOTATION = FqName("kotlin.native.CName")

    // Framework entry points / scaffolding that are public by design but not `@RDMA`.
    private val allowlist = setOf("runRdmaApp", "rdmaVtableDispatch")

    fun check(moduleFragment: IrModuleFragment): List<String> {
        val errors = mutableListOf<String>()
        for (file in moduleFragment.files) {
            for (declaration in file.declarations) {
                when (declaration) {
                    is IrClass -> {
                        // Interfaces and objects are host-side scaffolding (e.g. a service
                        // provider interface / singleton registry) and are allowed to be
                        // public; only concrete classes risk leaking into @RDMA signatures.
                        // Synthetic declarations (names containing `$`, e.g. Kotlin/Native
                        // `$stableprop_getter` holders) are compiler-generated and ignored.
                        val isHostOnly = declaration.kind == ClassKind.INTERFACE || declaration.kind == ClassKind.OBJECT
                        val isSynthetic = declaration.name.asString().contains('$')
                        if (!isHostOnly && !isSynthetic &&
                            declaration.visibility == DescriptorVisibilities.PUBLIC &&
                            !declaration.hasAnnotation(RDMA_ANNOTATION)
                        ) {
                            errors += "class ${declaration.name}: non-@RDMA public declaration must be internal/private"
                        }
                    }
                    is IrSimpleFunction -> {
                        val name = declaration.name.asString()
                        val isSynthetic = name.contains('$')
                        val isCAbiScaffolding = name.startsWith("rdma_")
                        if (!isSynthetic &&
                            declaration.visibility == DescriptorVisibilities.PUBLIC &&
                            !declaration.hasAnnotation(RDMA_ANNOTATION) &&
                            !declaration.hasAnnotation(CNAME_ANNOTATION) &&
                            !isCAbiScaffolding &&
                            name !in allowlist
                        ) {
                            errors += "function $name: non-@RDMA public declaration must be internal/private"
                        }
                    }
                }
            }
        }
        return errors
    }
}
