import org.jspecify.annotations.NullMarked;

/**
 * The main module for the STVN (Strongly Typed Value Notation) core library.
 * Provides compilation, validation, printing, binary encoding/decoding, and mapping capabilities
 * for STVN 2.0.0 documents.
 */
@SuppressWarnings("requires-automatic")
@NullMarked
module org.stvnadore.core {
    requires transitive static org.jspecify;

    exports org.stvnadore.core;
    exports org.stvnadore.core.stdlib;
    exports org.stvnadore.core.validation;
    exports org.stvnadore.core.ir;
    exports org.stvnadore.core.io;
    exports org.stvnadore.core.printer;
    exports org.stvnadore.core.annotations;
    exports org.stvnadore.core.utils;
    exports org.stvnadore.core.mapper;
    exports org.stvnadore.core.binary;
    exports org.stvnadore.core.binary.readers;
    exports org.stvnadore.core.binary.exceptions;

    requires org.antlr.antlr4.runtime;
}
