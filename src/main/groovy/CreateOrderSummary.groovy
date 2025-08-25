// Name: CreateOrderSummary_SimpleSplit.groovy
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.MarkupBuilder
import groovy.xml.XmlSlurper

Message processData(Message message) {
    def body = message.getBody(String)
    def xml  = new XmlSlurper(false, false).parseText(body)

    def w = new StringWriter()
    def x = new MarkupBuilder(w)

    x.Records {
        xml.Record.each { r ->
            def orderId  = r.OrderId.text().trim()
            def fullName = r.CustomerFullName.text().trim()
            def parts    = fullName ? fullName.tokenize(' ') : []
            def first    = parts ? parts[0] : ""
            def last     = parts.size() > 1 ? parts[1..-1].join(' ') : ""
            def count    = r.ItemCount.text().trim()

            Record {
                OrderId(orderId)
                FirstName(first)
                LastName(last)
                ItemCount(count)
            }
        }
    }

    message.setBody(w.toString())
    return message
}
