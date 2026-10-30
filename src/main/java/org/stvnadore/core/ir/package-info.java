/**
 * Pure, parser-decoupled, CPU-bias-free Canonical Intermediate Representation (IR)
 * for Strongly Typed Value Notation (STVN) 2.0.0.
 * <p>
 * All types in this package are deeply immutable Java records and sealed algebras
 * with zero dependencies on parser-generator runtime contexts or hardware-sized
 * numeric primitives. All integer quantities use {@link java.math.BigInteger}
 * and all decimal quantities use {@link java.math.BigDecimal}.
 *
 * @since 2.0.0
 */
@NullMarked
package org.stvnadore.core.ir;

import org.jspecify.annotations.NullMarked;
