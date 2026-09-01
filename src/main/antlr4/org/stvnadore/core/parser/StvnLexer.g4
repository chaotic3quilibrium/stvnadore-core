lexer grammar StvnLexer;

@members {
    // Isolated Lexer state to track exact fencing boundaries during Mode transitions
    private String currentFenceTag = "";
}

// ============================================================================
// 1. STANDARD LEXER RULES
// ============================================================================

SPACE   : [ \t\r\n]+ -> skip ;
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

KW_EQUATABLE       : '#equatable' ;
KW_COMPARABLE      : '#comparable' ;
KW_PRESERVE_INDENT : '#preserveIndent' ;
KW_MIN_INCL        : '#minIncl' ;
KW_MIN_EXCL        : '#minExcl' ;
KW_MAX_INCL        : '#maxIncl' ;
KW_MAX_EXCL        : '#maxExcl' ;
KW_REGEX           : '#regex' ;

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
ATOM_UINT             : ':Uint' [0-9]* ;
ATOM_INT              : ':Int' [0-9]* ;
ATOM_FLOAT_EXACT      : ':FloatExact' ;
ATOM_FLOAT            : ':Float' [0-9]* ;
ATOM_STRING_FIXED     : ':StringFixed' [0-9]* ;
ATOM_STRING           : ':String' [0-9]* ;
ATOM_STRING_NON_EMPTY : ':StringNonEmpty' [0-9]* ;
ATOM_TIME_EPOCH_S     : ':TimeEpochS' ;
ATOM_TIME_EPOCH_MS    : ':TimeEpochMs' ;
ATOM_TIME_EPOCH_NS    : ':TimeEpochNs' ;
ATOM_DATE_TIME_OFFSET : ':DateTimeOffset' ;
ATOM_DATE_TIME_ZONED  : ':DateTimeZoned' ;
ATOM_DATE_TIME_AUDITED: ':DateTimeAudited' ;

COLL_SEQ               : ':Seq' ;
COLL_SEQ_NON_EMPTY    : ':SeqNonEmpty' ;
COLL_SET               : ':Set' ;
COLL_SET_NON_EMPTY    : ':SetNonEmpty' ;
COLL_MAP               : ':Map' ;
COLL_MAP_NON_EMPTY    : ':MapNonEmpty' ;
COLL_MAP_INV           : ':MapInv' ;
COLL_MAP_INV_NON_EMPTY : ':MapInvNonEmpty' ;

UNION_TAG_PREFIX : '#' [1-9] [0-9]* ;

TYPE_KEYWORD_BASE  : ':' [a-zA-Z_][a-zA-Z0-9_]* ;
VALUE_KEYWORD_BASE : '#' [a-zA-Z_][a-zA-Z0-9_]* ;
IDENTIFIER   : [a-zA-Z_][a-zA-Z0-9_]* ;

// Base-10, Hexadecimal (0x), Binary (0b), and Octal (0o) match rules
LITERAL_INTEGER : '-'? ('0' [xX] [0-9a-fA-F]+ | '0' [bB] [01]+ | '0' [oO] [0-7]+ | [1-9][0-9]* | '0') ;
LITERAL_FLOAT : '-'? [0-9]+ '.' [0-9]+ ([eE] [-+]? [0-9]+)? ;
LITERAL_STRING_SIMPLE : '"' (~["\\\r\n] | '\\' .)* '"' ;

BLOCK_STRING_TRIGGER : '"""' -> more, pushMode(STANDARD_BLOCK);

// ============================================================================
// 2. ISOLATED LEXER MODES
// ============================================================================

FENCE_START : '"""->[' [-a-zA-Z0-9_ ]* ']' ~[\n]* '\n' {
    String text = getText();
    int start = text.indexOf('[') + 1;
    int end = text.indexOf(']', start);
    currentFenceTag = text.substring(start, end);
    pushMode(FENCED_STRING);
} ;

mode FENCED_STRING;

// Semantic Predicate ensures that we only pop the lexer mode if the matching tag is mathematically identical!
FENCE_END : '[' [-a-zA-Z0-9_ ]* ']"""' { getText().substring(1, getText().length() - 4).equals(currentFenceTag) }? -> popMode ;

FENCE_CONTENT : . ;

mode STANDARD_BLOCK;

// Pops the mode and emits the accumulated text buffer as a single clean token
LITERAL_STRING_BLOCK : '"""' -> popMode ;

// Greedily collect multi-line characters into the current token buffer
BLOCK_CONTENT : . -> more ;