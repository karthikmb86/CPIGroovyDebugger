package com.sap.it.api.mapping

class Output {
    private final List values = []
    private final List contexts = []  // no-op store to satisfy addContextChange()

    void addValue(Object v) { values << (v == null ? "" : v.toString()) }

    // CPI has isSuppress(Object) to skip certain tokens; return false by default
    boolean isSuppress(Object o) { false }

    // Some user libs call output.isContextChange(token) and output.addContextChange()
    boolean isContextChange(Object o) { false }
    void addContextChange() { contexts << "***CTX***" }

    List getValues() { values }
}
