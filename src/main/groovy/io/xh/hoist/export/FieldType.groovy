package io.xh.hoist.export

// Server-side copy of the FieldType enum from Field.js in Hoist-React
enum FieldType {

    AUTO('auto'),
    BOOL('bool'),
    DATE('date'),
    INT('int'),
    JSON('json'),
    LOCAL_DATE('localDate'),
    NUMBER('number'),
    PWD('pwd'),
    STRING('string')


    final String typeString

    private FieldType(String formatString) {
        this.typeString = formatString
    }

    String toString() {typeString}

    String formatForJSON() {toString()}

    static FieldType parse(String str) {
        return values().find {it.typeString == str}
    }
}