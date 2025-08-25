// File: src/main/groovy/ScriptRunner.groovy
import com.sap.gateway.ip.core.customdev.util.Message

class ScriptRunner {

    static void main(String[] args) {
        // 1) Resolve sample input file
        String samplePath = (args && args.length > 0) ? args[0] : "samples/MultipleOrders.xml"
        String sampleXml  = readOrFallback(samplePath, DEFAULT_ORDER_XML)

        // 2) Load your CPI Groovy script
        def scriptFile = new File("src/main/groovy/CreateOrderSummary.groovy")
        if (!scriptFile.exists()) {
            throw new FileNotFoundException("CreateOrderSummary.groovy not found at ${scriptFile.absolutePath}")
        }
        def script = new GroovyShell().parse(scriptFile)

        // 3) Build a CPI-like Message, set body, run the script
        def msg = new Message()
        msg.setBody(sampleXml)
        def result = script.processData(msg)

        // 4) Show results (console acts as our simple UI)
        println "=== OUTPUT BODY ==="
        println result.getBody(String)
        println "\n=== DEBUG PROPERTIES (if any) ==="
        result.properties.each { k, v -> println "${k} = ${v}" }
    }

    // Fallback sample XML in case samples/order.xml doesn't exist yet
    private static final String DEFAULT_ORDER_XML = """<Order>
  <Id>1001</Id>
  <Customer>
    <FirstName>Ada</FirstName>
    <LastName>Lovelace</LastName>
  </Customer>
  <Items>
    <Item><Sku>ABC</Sku><Qty>2</Qty></Item>
    <Item><Sku>XYZ</Sku><Qty>1</Qty></Item>
  </Items>
</Order>"""

    private static String readOrFallback(String path, String fallback) {
        def f = new File(path)
        return f.exists() ? f.getText("UTF-8") : fallback
    }
}
