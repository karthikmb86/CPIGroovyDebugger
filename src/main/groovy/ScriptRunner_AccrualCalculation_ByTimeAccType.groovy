// File: src/main/groovy/ScriptRunner.groovy
import com.sap.gateway.ip.core.customdev.util.Message

class ScriptRunner_AccrualCalculation_ByTimeAccType {

    static void main(String[] args) {
        // --- 0) Inputs (override with program args if you want) ---
        // arg0 = script path, arg1 = sample xml path
        String scriptPath            = (args?.length ?: 0) > 0 ? args[0] : "src/main/groovy/AccrualCalculation_ByTimeAccType1.groovy"
        String EmpEmploymentFile     = (args?.length ?: 0) > 1 ? args[1] : "samples/EmpEmployment_AccrualCalculation.xml"
        String TimeAccountDetailsFile = (args?.length ?: 0) > 2 ? args[2] : "samples/TimeAccountDetails_Enhanced2.xml"
        String AccrualRatesFile      = (args?.length ?: 0) > 3 ? args[3] : "samples/AccrualRates.xml"
        //String samplePath = (args?.length ?: 0) > 1 ? args[1] : "samples/190724_AutoIncr3_Shibin.xml"

        // --- 1) Load the script file ---
        File scriptFile = new File(scriptPath)
        if (!scriptFile.exists()) {
            throw new FileNotFoundException("Script not found at ${scriptFile.absolutePath}")
        }
        def script = new GroovyShell().parse(scriptFile)

        // --- 2) Load sample XML (or fallback tiny XML) ---
        //String sampleXml = readOrFallback(samplePath, DEFAULT_FALLBACK_XML)
        String EmpEmploymentXML = readOrFallback(EmpEmploymentFile, DEFAULT_FALLBACK_XML)
        String TimeAccountDetailsXML = readOrFallback(TimeAccountDetailsFile, DEFAULT_FALLBACK_XML)
        String AccrualRatesXML = readOrFallback(AccrualRatesFile, DEFAULT_FALLBACK_XML)

        // --- 3) Build CPI-like Message, set body + expected properties ---
        def msg = new Message()
        //msg.setBody(sampleXml)
        msg.setProperty("EmpEmployment", EmpEmploymentXML)
        msg.setProperty("TimeAccountDetails_Enhanced", TimeAccountDetailsXML)
        msg.setProperty("AccrualRates", AccrualRatesXML)


        // Properties used by the main script
        //msg.setProperty("classification_eligible",
        //        System.getenv("CLASS_ELIGIBLE") ?: "AUS/60,AUS/20,AUS/30,AUS/36,AUS/37,AUS/38,AUS/59")
        //msg.setProperty("exclude_svp_timetype",
         //       System.getenv("SVP_EXCLUDE") ?: "6023,6511")

        // --- 4) Run the script ---
        def result
        try {
            result = script.processData(msg)
        } catch (Throwable t) {
            System.err.println("Script execution failed: " + t.getMessage())
            t.printStackTrace()
            return
        }

        // --- 5) Show results ---
        println "==== OUTPUT BODY ===="
        println result.getBody(String)

        println "\n==== DEBUG PROPERTIES ===="
        // Use getProperties() (not 'properties') to avoid Groovy meta-props
        result.getProperties()
                .findAll { k, v -> String.valueOf(k).startsWith("DEBUG_") }
                .each   { k, v -> println "${k} = ${v}" }
    }

    private static String readOrFallback(String path, String fallback) {
        def f = new File(path)
        return f.exists() ? f.getText("UTF-8") : fallback
    }

    // Minimal fallback so the runner can start even without a samples file
    private static final String DEFAULT_FALLBACK_XML = """<root xmlns:multimap="urn:dummy">
  <multimap:Message1><root><PerPerson><employmentNav/></PerPerson></root></multimap:Message1>
  <multimap:Message2><WorkScheduleDayModelAssignment/></multimap:Message2>
  <multimap:Message3><PayScalePayComponent/></multimap:Message3>
  <multimap:Message5><InactiveTimeSheet/></multimap:Message5>
  <multimap:Message6><JobHistory/></multimap:Message6>
  <multimap:Message7><EmpEmployment/></multimap:Message7>
</root>"""
}
