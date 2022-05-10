package io.xh.hoist.export

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