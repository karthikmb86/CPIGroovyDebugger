package com.sap.it.api.mapping

class MappingContext {
    private final Map<String, Object> headers = [:]
    private final Map<String, Object> props = [:]

    MappingContext(Map<String, Object> headers = [:], Map<String, Object> props = [:]) {
        this.headers.putAll(headers ?: [:])
        this.props.putAll(props ?: [:])
    }

    Object getHeader(String name) { headers[name] }
    Object getProperty(String name) { props[name] }

    void setHeader(String name, Object v) { headers[name] = v }
    void setProperty(String name, Object v) { props[name] = v }
}
