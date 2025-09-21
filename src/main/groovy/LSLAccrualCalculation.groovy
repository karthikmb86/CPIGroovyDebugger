import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.XmlSlurper
import groovy.xml.MarkupBuilder
import java.text.SimpleDateFormat
import java.time.*

def Message processData(Message message) {

    // --- tiny logger (console + optional CPI log property mirror) ---
    def DEBUG = (message.getProperty("DEBUG")?.toString()?.equalsIgnoreCase("true")) ?: true
    def trace = { String m ->
        if (DEBUG) {
            System.err.println("[TRACE] " + m)   // IntelliJ console
        }
    }

    // ---- read inputs from properties
    def empXmlStr  = message.getProperty("EmpEmployment")       as String
    def timeXmlStr = message.getProperty("TimeAccountDetails")  as String
    def rateXmlStr = message.getProperty("AccrualRates")        as String

    trace "Prop present? EmpEmployment=${empXmlStr?.size() ?: 0} chars, " +
            "TimeAccountDetails=${timeXmlStr?.size() ?: 0} chars, " +
            "AccrualRates=${rateXmlStr?.size() ?: 0} chars"

    if (!empXmlStr || !timeXmlStr || !rateXmlStr) {
        def w = new StringWriter()
        new MarkupBuilder(w).Error(message: "Missing property(ies): EmpEmployment=${!!empXmlStr}, TimeAccountDetails=${!!timeXmlStr}, AccrualRates=${!!rateXmlStr}")
        message.setBody(w.toString())
        return message
    }

    // ---- parse (Grampians style)
    def empXml  = new XmlSlurper(false, false).parseText(empXmlStr)
    def timeXml = new XmlSlurper(false, false).parseText(timeXmlStr)
    def rateXml = new XmlSlurper(false, false).parseText(rateXmlStr)

    trace "Roots: empXml.name=${empXml.name()}, timeXml.name=${timeXml.name()}, rateXml.name=${rateXml.name()}"
    trace "Root child counts: emp=${empXml.children().size()}, time=${timeXml.children().size()}, rate=${rateXml.children().size()}"

    // ---- helpers
    def tz = ZoneId.of("Australia/Sydney")
    def today = LocalDate.now(tz)

    def first3 = { String code ->
        if (!code) return null
        def p = code.split('/')
        p.size() >= 3 ? ([p[0], p[1], p[2]].join('/')) : code
    }

    def parseSfDate = { String s ->
        if (!s) return null
        s = s.trim()
        try {
            // yyyy-MM-dd'T'HH:mm:ss.SSS
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse(s)
        } catch (ignored) {
            try {
                // yyyy-MM-dd (or take first 10)
                new SimpleDateFormat("yyyy-MM-dd").parse(s.length() >= 10 ? s.substring(0,10) : s)
            } catch (e) { null }
        }
    }

    def toLocalDate = { Date d ->
        d ? d.toInstant().atZone(tz).toLocalDate() : null
    }

    def toBD = { String s ->
        try { new BigDecimal((s ?: "0").trim()) } catch (e) { null }
    }

    // ---- Accrual rate lookup (keyed by externalCode first 3 segments)
    def rateByCode3 = [:]
    rateXml.'**'.findAll { it.name() == 'cust_LeaveAccrualRateLookup' }.each { rn ->
        def code = rn.externalCode?.text()?.trim()
        if (code) {
            rateByCode3[code] = [
                LSL1   : rn.cust_AccrualRateLSL1?.text()?.trim(),
                LSL1Ovr: rn.cust_AccrualRateLSL1Ovr?.text()?.trim(),
                LSL2Ovr: rn.cust_AccrualRateLSL2Ovr?.text()?.trim()
            ]
        }
    }

    // ---- EmpEmployment by userId
    def empByUser = [:]  // uid -> [upl, lslDate(LocalDate), code3]
    empXml.EmpEmployment.each { e ->
        def uid = e.userId?.text()?.trim()
        if (!uid) return
        def upl = (e.UnPaidParentalLeave?.text()?.trim() ?: "").equalsIgnoreCase("Yes") ? "Yes" : "No"
        def lslDate = toLocalDate(parseSfDate(e.customDate4?.text()))
        def code  = e.jobInfoNav?.EmpJob?.payScaleLevelNav?.PayScaleLevel?.code?.text()
        def code3 = first3(code)
        empByUser[uid] = [upl: upl, lslDate: lslDate, code3: code3]
    }

    // ---- time account details grouped by userId
    def timeByUser = [:].withDefault { [] }
    timeXml.'TimeAccountDetail'.each { t ->  // children under the root
        def uid = t.userId?.text()?.trim()
        if (uid) timeByUser[uid] << t
    }

    // ---- choose accrual rate based on customDate4 vs today
    def decideRate = { Map row, LocalDate lslDate ->
        if (!row) return null
        if (!lslDate || today.isBefore(lslDate)) {
            return row.LSL1 ?: row.LSL1Ovr ?: row.LSL2Ovr
        }
        def years = java.time.Period.between(lslDate, today).years
        if (years < 10) {
            return row.LSL1Ovr ?: row.LSL1 ?: row.LSL2Ovr
        } else {
            return row.LSL2Ovr ?: row.LSL1Ovr ?: row.LSL1
        }
    }

    // ---- build output rows (UPDATED UPL rule)
    def outRows = []
    empByUser.each { uid, meta ->
        def userRows = timeByUser[uid]
        if (!userRows) return

        if (meta.upl.equalsIgnoreCase("Yes")) {
            // For UPL = Yes, ALWAYS set calculated_AccrualRate = 0 (regardless of bookingAmount)
            userRows.each { t ->
                outRows << [node: t, calc: "0"]
            }
        } else {
            // UPL = No → compute rate for ALL rows
            def row = meta.code3 ? rateByCode3[meta.code3] : null
            def picked = decideRate(row, meta.lslDate)
            userRows.each { t -> outRows << [node: t, calc: picked] }
        }
    }

    // ---- render output (emit <calculated_AccrualRate> ONLY when present)
    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    xml.TimeAccountDetail {
        outRows.each { r ->
            TimeAccountDetail {
                r.node.children().each { ch ->
                    "${ch.name()}"(ch.text())
                }
                if (r.calc != null) {
                    calculated_AccrualRate(r.calc)
                }
                
                // ---- NEW: bookingType (+ bookingAmountNew when different)
            def extCode      = r.node.externalCode?.text()?.trim()
            def bookingAmtBD = (toBD(r.node.bookingAmount?.text()) ?: BigDecimal.ZERO)
            def calcBD = (r.calc != null) ? new BigDecimal((r.calc.toString().trim() ?: "0")) : null

             if (!extCode) {
                // Override: blank externalCode => ACCRUAL
                bookingType('ACCRUAL')
            } else if (calcBD != null) {
                if (bookingAmtBD.compareTo(calcBD) == 0) {
                    bookingType('ACCRUAL')
                } else {
                    bookingType('MANUAL_ADJUSTMENT')
                    // delta to add to current bookingAmount to reach calculated_AccrualRate
                    bookingAmountNew(calcBD.subtract(bookingAmtBD).stripTrailingZeros().toPlainString())
                }
             }
            }
        }
    }

    message.setBody(writer.toString())
    return message
}
