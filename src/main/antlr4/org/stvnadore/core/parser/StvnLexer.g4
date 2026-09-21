lexer grammar StvnLexer;

@members {
    // Isolated Lexer state to track exact fencing boundaries during Mode transitions
    private String currentFenceTag = "";

    public String getCurrentFenceTag() {
        return currentFenceTag;
    }
}

// ============================================================================
// 1. STANDARD LEXER RULES
// ============================================================================

SPACE         : [ \r\n]+ -> skip ;
TAB_CHARACTER : '\t'+ ;
COMMENT : '//' ~[\r\n]* -> skip ;

LBRACK : '[' ;
RBRACK : ']' ;
LPAREN : '(' ;
RPAREN : ')' ;
LBRACE : '{' ;
RBRACE : '}' ;
FSLASH : '/' ;

KW_DEFS    : ':defs' ;
KW_TYPE    : ':type' ;
KW_BODY    : ':body' ;
KW_INCLUDE : ':include' ;
KW_PACKAGE : ':package' ;
KW_USE     : ':use' ;
KW_STRIP   : '#strip' ;

KW_EQUATABLE       : '#equatable' ;
KW_COMPARABLE      : '#comparable' ;
KW_PRESERVE_INDENT : '#preserveIndent' ;
KW_MIN_INCL        : '#minIncl' ;
KW_MIN_EXCL        : '#minExcl' ;
KW_MAX_INCL        : '#maxIncl' ;
KW_MAX_EXCL        : '#maxExcl' ;
KW_REGEX           : '#regex' ;
KW_FILTER_INCL     : '#filterIncl' ;
KW_FILTER_EXCL     : '#filterExcl' ;
KW_SIZE            : '#size' ;
KW_UNSIGNED        : '#unsigned' ;
KW_EXACT           : '#exact' ;
KW_MIN_SIZE        : '#minSize' ;
KW_MAX_SIZE        : '#maxSize' ;
KW_INVERTIBLE      : '#invertible' ;
KW_UNIT            : '#unit' ;
KW_OFFSET          : '#offset' ;
KW_ZONED           : '#zoned' ;
KW_AUDITED         : '#audited' ;

KW_TUPLE     : ':Tuple' ;
KW_ENUM      : ':Enum' ;
KW_OPTION    : ':Option' ;
KW_EITHER    : ':Either' ;
KW_UNION     : ':Union' ;
KW_MAP_ENTRY : ':MapEntry' ;

KW_TRUE        : '#TRUE' ;
KW_FALSE       : '#FALSE' ;
KW_TRUE_SHORT  : '#T' ;
KW_FALSE_SHORT : '#F' ;

KW_NONE        : '#None' ;
KW_SOME        : '#Some' ;
KW_NONE_SHORT  : '#N' ;
KW_SOME_SHORT  : '#S' ;

KW_LEFT        : '#Left' ;
KW_RIGHT       : '#Right' ;
KW_LEFT_SHORT  : '#L' ;
KW_RIGHT_SHORT : '#R' ;

ATOM_BOOLEAN          : ':Boolean' ;
ATOM_INT              : ':Int' ;
ATOM_FLOAT            : ':Float' ;
ATOM_STRING           : ':String' ;

COLL_SEQ               : ':Seq' ;
COLL_SET               : ':Set' ;
COLL_MAP               : ':Map' ;

UNION_TAG_PREFIX : '#' [1-9] [0-9]* ;

TYPE_KEYWORD_BASE  : ':' [a-zA-Z_][a-zA-Z0-9_]* ;
VALUE_KEYWORD_BASE : '#' [a-zA-Z_][a-zA-Z0-9_]* ;
IDENTIFIER   : [a-zA-Z_][a-zA-Z0-9_]* ;

// Base-10, Hexadecimal (0x), Binary (0b), and Octal (0o) match rules
LITERAL_INTEGER : '-'? ('0' [xX] [0-9a-fA-F]+ | '0' [bB] [01]+ | '0' [oO] [0-7]+ | [1-9][0-9]* | '0') ;
LITERAL_FLOAT : '-'? [0-9]+ '.' [0-9]+ ([eE] [-+]? [0-9]+)? ;
LITERAL_STRING_SIMPLE : '"' (~["\\\r\n] | '\\' .)* '"' ;

fragment FENCE_TAG_CHAR : [a-zA-Z0-9_-] ;

FENCE_START : '"""[' FENCE_TAG_CHAR+ ']' [ \r]* '\n' {
    String text = getText();
    int start = text.indexOf('[') + 1;
    int end = text.indexOf(']', start);
    String tag = text.substring(start, end);
    if (tag.length() > 256) {
        setType(MALFORMED_FENCE_OPEN);
        pushMode(MALFORMED_FENCED_STRING);
    } else {
        currentFenceTag = tag;
        pushMode(FENCED_STRING);
    }
} ;

DEPRECATED_FENCE_START : '"""->[' ~[\r\n]* '\n' {
    setType(MALFORMED_FENCE_OPEN);
    pushMode(MALFORMED_FENCED_STRING);
} ;

MALFORMED_FENCE_OPEN : '"""[' ~[\r\n]* '\n' {
    pushMode(MALFORMED_FENCED_STRING);
} ;

MALFORMED_FENCE_CLOSE : '[' ~[\r\n\]]* ']"""' ;

BLOCK_STRING_TRIGGER : '"""' -> more, pushMode(STANDARD_BLOCK);

// ============================================================================
// 2. ISOLATED LEXER MODES
// ============================================================================

mode FENCED_STRING;

// Semantic Predicate ensures that we only pop the lexer mode if the matching tag is mathematically identical!
FENCE_END : '[' FENCE_TAG_CHAR+ ']"""' { getText().substring(1, getText().length() - 4).equals(currentFenceTag) }? -> popMode ;

FENCE_CONTENT : . ;

mode MALFORMED_FENCED_STRING;

// Consume and skip the closing delimiter to return cleanly to DEFAULT_MODE
MALFORMED_FENCE_END : '[' ~[\r\n\]]* ']"""' -> skip, popMode ;

// Silently skip body characters to prevent cascading syntax errors in DEFAULT_MODE
MALFORMED_FENCE_CONTENT : . -> skip ;

mode STANDARD_BLOCK;

// Pops the mode and emits the accumulated text buffer as a single clean token
LITERAL_STRING_BLOCK : '"""' -> popMode ;

// Greedily collect multi-line characters into the current token buffer
BLOCK_CONTENT : . -> more ;