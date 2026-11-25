lexer grammar StvnLexer;

@members {
    // Isolated Lexer state to track exact fencing boundaries during Mode transitions
    private String currentFenceTag = "";
    private final java.util.Deque<String> mismatchedNestedTags = new java.util.ArrayDeque<>();

    public String getCurrentFenceTag() {
        return currentFenceTag;
    }

    private static boolean isValidFenceTagChar(int ch) {
        return (ch >= 'a' && ch <= 'z')
            || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9')
            || ch == '_'
            || ch == '-';
    }

    private boolean hasMatchingCloseAhead(String tag) {
        int start = _input.index();
        int size = _input.size();
        if (start >= size) {
            return false;
        }
        String rem = _input.getText(org.antlr.v4.runtime.misc.Interval.of(start, size - 1));
        return rem.contains("[" + tag + "]\"\"\"");
    }

    private boolean hasDepthZeroCandidateCloseAhead() {
        int start = _input.index();
        int size = _input.size();
        if (start >= size) {
            return false;
        }
        String rem = _input.getText(org.antlr.v4.runtime.misc.Interval.of(start, size - 1));
        int len = rem.length();
        java.util.Deque<String> nestedStack = new java.util.ArrayDeque<>();
        int i = 0;
        while (i < len) {
            if (rem.startsWith("\"\"\"[", i)) {
                int p = i + 4;
                while (p < len && isValidFenceTagChar(rem.charAt(p))) {
                    p++;
                }
                int tagLen = p - (i + 4);
                if (tagLen >= 1 && tagLen <= 256 && p < len && rem.charAt(p) == ']') {
                    int scan = p + 1;
                    while (scan < len && rem.charAt(scan) == ' ') {
                        scan++;
                    }
                    if (scan < len && (rem.charAt(scan) == '\r' || rem.charAt(scan) == '\n')) {
                        int afterBreak = (rem.charAt(scan) == '\r' && scan + 1 < len && rem.charAt(scan + 1) == '\n')
                            ? scan + 2
                            : scan + 1;
                        String innerTag = rem.substring(i + 4, p);
                        if (rem.indexOf("[" + innerTag + "]\"\"\"", afterBreak) >= 0) {
                            nestedStack.push(innerTag);
                            i = afterBreak;
                            continue;
                        }
                    }
                }
            }
            if (rem.charAt(i) == '[') {
                int closeBracket = rem.indexOf(']', i + 1);
                if (closeBracket > i) {
                    boolean validCloseSpan = true;
                    for (int k = i + 1; k < closeBracket; k++) {
                        char c = rem.charAt(k);
                        if (c == '\r' || c == '\n' || c == '[') {
                            validCloseSpan = false;
                            break;
                        }
                    }
                    if (validCloseSpan && rem.startsWith("\"\"\"", closeBracket + 1)) {
                        String closeTag = rem.substring(i + 1, closeBracket);
                        if (!nestedStack.isEmpty()) {
                            if (closeTag.equals(nestedStack.peek())) {
                                nestedStack.pop();
                            }
                            i = closeBracket + 4;
                            continue;
                        }
                        return true;
                    }
                }
            }
            i++;
        }
        return false;
    }

    private static String stripTrailingSingleLineBreak(String text) {
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n") || text.endsWith("\r")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }
}

// ============================================================================
// 1. STANDARD LEXER RULES
// ============================================================================

SPACE         : [ \r\n]+ -> skip ;
TAB_CHARACTER : '\t'+ -> channel(HIDDEN) ;
COMMENT       : '//' ~[\r\n]* -> channel(HIDDEN) ;

LBRACK : '[' ;
RBRACK : ']' ;
LPAREN : '(' ;
RPAREN : ')' ;
LBRACE : '{' ;
RBRACE : '}' ;
FSLASH : '/' ;

KW_META    : ':meta' ;
KW_DEFS    : ':defs' ;
KW_TYPE    : ':type' ;
KW_BODY    : ':body' ;
KW_INCLUDE : ':include' ;
KW_PACKAGE : ':package' ;
KW_USE     : ':use' ;
KW_STRIP   : '#strip' ;

KW_META_NAME   : '#name' ;
KW_META_DOMAIN : '#domain' ;
KW_META_KIND   : '#kind' ;

KW_KIND_BODY         : '#BODY' ;
KW_KIND_DEFS         : '#DEFS' ;
KW_KIND_BODY_INCLUDE : '#BODY_INCLUDE' ;
KW_KIND_DEFS_INCLUDE : '#DEFS_INCLUDE' ;

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
KW_OFFSET          : '#offset' ;
KW_ZONED           : '#zoned' ;
KW_AUDITED         : '#audited' ;

KW_SCALE_S         : '#s' ;
KW_SCALE_MS        : '#ms' ;
KW_SCALE_US        : '#us' ;
KW_SCALE_NS        : '#ns' ;

KW_TUPLE     : ':Tuple' ;
KW_ENUM      : ':Enum' ;
KW_OPTION    : ':Option' ;
KW_EITHER    : ':Either' ;
KW_UNION     : ':Union' ;

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
ATOM_TIME_EPOCH       : ':TimeEpoch' ;
ATOM_DATE_TIME        : ':DateTime' ;

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
LITERAL_STRING_SIMPLE : '"' (~["\\\r\n\t] | '\\' ~[\r\n])* '"' ;

fragment FENCE_TAG_CHAR : [a-zA-Z0-9_-] ;
fragment FENCE_NEWLINE  : '\r\n' | '\r' | '\n' ;

FENCE_START : '"""[' FENCE_TAG_CHAR+ ']' ' '* FENCE_NEWLINE {
    String text = getText();
    int start = text.indexOf('[') + 1;
    int end = text.indexOf(']', start);
    String tag = text.substring(start, end);
    if (tag.length() > 256) {
        setType(MALFORMED_FENCE_OPEN);
        pushMode(MALFORMED_FENCED_STRING);
    } else if (!hasMatchingCloseAhead(tag) && hasDepthZeroCandidateCloseAhead()) {
        setType(MALFORMED_FENCE_OPEN);
        mismatchedNestedTags.clear();
        pushMode(MISMATCHED_FENCED_STRING);
    } else {
        currentFenceTag = tag;
        pushMode(FENCED_STRING);
    }
} ;

PROHIBITED_FENCE_START : '"""->[' ~[\r\n]* FENCE_NEWLINE {
    setType(MALFORMED_FENCE_OPEN);
    pushMode(MALFORMED_FENCED_STRING);
} ;

MALFORMED_FENCE_INLINE : '"""' ~[\r\n]*? '"""' {
    setType(MALFORMED_FENCE_OPEN);
} ;

MALFORMED_FENCE_OPEN : '"""' ( ~[\r\n"] | '"' ~[\r\n"] | '""' ~[\r\n"] )* (FENCE_NEWLINE | EOF) {
    if (!stripTrailingSingleLineBreak(getText()).equals(getText())) {
        pushMode(MALFORMED_FENCED_STRING);
    }
} ;

MALFORMED_FENCE_CLOSE : '[' ~[\r\n\]]* ']"""' ;

// ============================================================================
// 2. ISOLATED LEXER MODES
// ============================================================================

mode FENCED_STRING;

// Semantic Predicate ensures that we only pop the lexer mode if the matching tag is mathematically identical!
FENCE_END : '[' FENCE_TAG_CHAR+ ']"""' { getText().substring(1, getText().length() - 4).equals(currentFenceTag) }? -> popMode ;

FENCE_CONTENT : . ;

mode MALFORMED_FENCED_STRING;

// Consume the closing delimiter on the default channel to return cleanly to DEFAULT_MODE while preserving AST slot arity
MALFORMED_FENCE_END : ('[' ~[\r\n\]]* ']"""' | '"""[' ~[\r\n\]]* ']') -> popMode ;
MALFORMED_BLOCK_END : '"""' -> popMode ;

// Retain body characters for malformedFencedString without cascading syntax errors in DEFAULT_MODE
MALFORMED_FENCE_CONTENT : . ;

mode MISMATCHED_FENCED_STRING;

MISMATCHED_NESTED_OPEN : '"""[' FENCE_TAG_CHAR+ ']' ' '* FENCE_NEWLINE {
    String text = getText();
    int closeBracket = text.indexOf(']');
    String innerTag = text.substring(4, closeBracket);
    if (innerTag.length() <= 256 && hasMatchingCloseAhead(innerTag)) {
        mismatchedNestedTags.push(innerTag);
    }
    setType(MALFORMED_FENCE_CONTENT);
} ;

MISMATCHED_CANDIDATE_END : '[' ~[\r\n[\]]* ']"""' {
    String text = getText();
    String closeTag = text.substring(1, text.length() - 4);
    if (!mismatchedNestedTags.isEmpty()) {
        if (closeTag.equals(mismatchedNestedTags.peek())) {
            mismatchedNestedTags.pop();
        }
        setType(MALFORMED_FENCE_CONTENT);
    } else {
        setType(MALFORMED_FENCE_END);
        popMode();
    }
} ;

MISMATCHED_FENCE_CHAR : . -> type(MALFORMED_FENCE_CONTENT) ;
