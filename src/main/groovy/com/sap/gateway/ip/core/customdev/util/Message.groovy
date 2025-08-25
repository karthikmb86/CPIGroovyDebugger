// File: src/main/groovy/com/sap/gateway/ip/core/customdev/util/Message.groovy
package com.sap.gateway.ip.core.customdev.util

class Message {
    Map<String, Object> headers = [:]
    Map<String, Object> properties = [:]
    Object body

    Object getBody(Class type = String) {
        if (type == String && body != null && !(body instanceof String)) {
            return body.toString()
        }
        return body
    }
    void setBody(Object b) { body = b }

    Object getHeader(String name) { headers[name] }
    void setHeader(String name, Object value) { headers[name] = value }

    // Must match GroovyObject signatures:
    Object getProperty(String name) { properties[name] }
    void setProperty(String name, Object value) { properties[name] = value }
}
