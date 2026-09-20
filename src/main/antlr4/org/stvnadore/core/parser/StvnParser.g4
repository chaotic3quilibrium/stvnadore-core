parser grammar StvnParser;

options {
    tokenVocab = StvnLexer;
}

// ============================================================================
// 1. PARSER RULES
// ============================================================================

stvnDocument : LBRACE documentBody RBRACE EOF ;

documentBody : defsEntry? (typeEntry bodyEntry)? ;

// Inclusion definitions integrated alongside type structures
defsEntry : KW_DEFS LBRACE defsElement* RBRACE ;

defsElement : includeStmt
            | packageEnclosure
            | useStmt
            | typeDefinition
            | constantDefinition
            ;

packageEnclosure : KW_PACKAGE packagePath LBRACE packageElement* RBRACE ;
packagePath      : typeKeyword ;
packageElement   : useStmt
                 | typeDefinition
                 | constantDefinition
                 | nestedPackageIllegal
                 ;
nestedPackageIllegal : KW_PACKAGE packagePath LBRACE packageElement* RBRACE ;

useStmt : KW_USE LBRACK useTarget useOptionsBlock? useAliasBlock? RBRACK ;
useTarget : typeKeyword | useTargetIllegal ;
useTargetIllegal : typeKeyword FSLASH+ ;
useOptionsBlock : LBRACE KW_STRIP* RBRACE ;
useAliasBlock   : LBRACE useMapAlias* RBRACE ;
useMapAlias     : typeKeyword typeKeyword | valueKeyword valueKeyword ;

includeStmt       : KW_INCLUDE LBRACK includeElement+ RBRACK ;

includeElement      : stringLiteral includeOptionsBlock? includeAliasBlock? ;

includeOptionsBlock : LBRACE includeOption* RBRACE ;

includeOption       : KW_STRIP ;

includeAliasBlock   : LBRACE includeMapAlias* RBRACE ;

includeMapAlias     : typeKeyword typeKeyword ;

typeEntry : KW_TYPE schemaType ;
bodyEntry : KW_BODY value ;

typeDefinition     : typeDefTarget metadataMap? schemaType ;
typeDefTarget      : typeKeyword | reservedKeyword ;
constantDefinition : valueKeyword metadataMap? schemaType value ;

metadataMap    : LBRACE metadataEntry* RBRACE ;

// Enforced structural type verification branches
metadataEntry     : metadataBool | metadataNum | metadataString | metadataFilter | metadataDirective ;
metadataDirective : KW_STRIP ;
metadataBool      : (KW_EQUATABLE | KW_COMPARABLE | KW_PRESERVE_INDENT) metadataValue ;
metadataNum    : (KW_MIN_INCL | KW_MAX_INCL | KW_MIN_EXCL | KW_MAX_EXCL) metadataValue ;
metadataString : KW_REGEX metadataValue ;
metadataFilter : (KW_FILTER_INCL | KW_FILTER_EXCL) variantList ;

variantList    : LBRACK valueKeyword* RBRACK ;

metadataValue  : booleanLiteral
               | integerLiteral
               | floatLiteral
               | stringLiteral
               | valueKeyword
               ;

schemaType : schemaConstructor | typeKeyword ;

schemaConstructor : atomicType | collectionType | productType | sumType ;

atomicType : ATOM_BOOLEAN
           | ATOM_UINT
           | ATOM_INT
           | ATOM_FLOAT
           | ATOM_FLOAT_EXACT
           | ATOM_STRING_FIXED
           | ATOM_STRING
           | ATOM_STRING_NON_EMPTY
           ;

collectionType
    : (COLL_SEQ | COLL_SEQ_NON_EMPTY) LPAREN schemaType RPAREN
    | (COLL_SET | COLL_SET_NON_EMPTY) LPAREN schemaType RPAREN
    | (COLL_MAP | COLL_MAP_NON_EMPTY | COLL_MAP_INV | COLL_MAP_INV_NON_EMPTY) LPAREN schemaType schemaType RPAREN
    ;

productType
    : KW_TUPLE LPAREN schemaType+ RPAREN               # TupleType
    ;

sumType : KW_OPTION LPAREN schemaType RPAREN
        | KW_ENUM enumDef
        | KW_EITHER LPAREN schemaType schemaType RPAREN
        | KW_UNION LPAREN schemaType+ RPAREN
        ;

enumDef : LBRACK valueKeyword+ RBRACK ;

value : explicitOptionValue
      | explicitEitherValue
      | explicitUnionValue
      | booleanLiteral
      | integerLiteral
      | floatLiteral
      | stringLiteral
      | valueKeyword
      | collectionValue
      ;

collectionValue : listLiteral | mapLiteral | tupleLiteral ;
listLiteral     : LBRACK value* RBRACK ;
mapLiteral      : LBRACE mapEntry* RBRACE ;
mapEntry        : LBRACK value value RBRACK ;
tupleLiteral    : LPAREN value+ RPAREN ;

booleanLiteral : KW_TRUE | KW_FALSE | KW_TRUE_SHORT | KW_FALSE_SHORT ;
integerLiteral : LITERAL_INTEGER ;
floatLiteral   : LITERAL_FLOAT ;

explicitOptionValue : KW_NONE | KW_NONE_SHORT | (KW_SOME | KW_SOME_SHORT) value ;
explicitEitherValue : (KW_RIGHT | KW_RIGHT_SHORT) value | (KW_LEFT | KW_LEFT_SHORT) value ;
explicitUnionValue  : UNION_TAG_PREFIX value ;

stringLiteral
    : LITERAL_STRING_SIMPLE  # StringSimple
    | LITERAL_STRING_BLOCK   # StringBlock
    | fencedString           # StringFenced
    ;

fencedString : FENCE_START FENCE_CONTENT* FENCE_END ;

// Support for slash-delimited pathways
typeKeyword : typeKeywordStart ( FSLASH IDENTIFIER )* ;

typeKeywordStart : TYPE_KEYWORD_BASE ;

reservedKeyword : ATOM_BOOLEAN
                | ATOM_UINT
                | ATOM_INT
                | ATOM_FLOAT
                | ATOM_FLOAT_EXACT
                | ATOM_STRING_FIXED
                | ATOM_STRING
                | ATOM_STRING_NON_EMPTY
                | COLL_SEQ
                | COLL_SEQ_NON_EMPTY
                | COLL_SET
                | COLL_SET_NON_EMPTY
                | COLL_MAP
                | COLL_MAP_NON_EMPTY
                | COLL_MAP_INV
                | COLL_MAP_INV_NON_EMPTY
                | KW_DEFS
                | KW_TYPE
                | KW_BODY
                | KW_INCLUDE
                | KW_PACKAGE
                | KW_USE
                | KW_TUPLE
                | KW_ENUM
                | KW_OPTION
                | KW_EITHER
                | KW_UNION
                ;

valueKeyword : valueKeywordStart ( FSLASH IDENTIFIER )* ;

valueKeywordStart : VALUE_KEYWORD_BASE
                  | UNION_TAG_PREFIX
                  | KW_FALSE | KW_FALSE_SHORT
                  | KW_TRUE  | KW_TRUE_SHORT
                  | KW_NONE  | KW_NONE_SHORT
                  | KW_SOME  | KW_SOME_SHORT
                  | KW_LEFT  | KW_LEFT_SHORT
                  | KW_RIGHT | KW_RIGHT_SHORT
                  | KW_EQUATABLE | KW_COMPARABLE | KW_PRESERVE_INDENT
                  | KW_MIN_INCL | KW_MIN_EXCL
                  | KW_MAX_INCL | KW_MAX_EXCL
                  | KW_REGEX
                  | KW_FILTER_INCL | KW_FILTER_EXCL
                  ;