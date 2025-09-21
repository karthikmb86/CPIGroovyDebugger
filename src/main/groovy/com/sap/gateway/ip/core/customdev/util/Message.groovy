package com.sap.gateway.ip.core.customdev.util

class Message {
    private final Map<String,Object> headers = [:]
    private final Map<String,Object> properties = [:]
    private Object body

    // Body
    Object getBody(Class type = String) {
        if (type == String && body != null && !(body instanceof String)) return body.toString()
        return body
    }
    void setBody(Object b) { body = b }

    // Headers
    Object getHeader(String name) { headers[name] }
    void setHeader(String name, Object val) { headers[name] = val }

    // Properties (your script uses getProperties())
    Object getProperty(String name) { properties[name] }
    void setProperty(String name, Object val) { properties[name] = val }
    Map<String,Object> getProperties() { properties }  // <-- important for your code

    // convenience for tests
    Map<String,Object> debugHeaders() { headers }
}
