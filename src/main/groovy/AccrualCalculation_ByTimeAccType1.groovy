import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.*
import groovy.xml.MarkupBuilder
import java.text.SimpleDateFormat
import java.time.*

def Message processData(Message message) {
    // ---- read inputs from properties
    def empXmlStr  = message.getProperty("EmpEmployment")               as String
    def timeXmlStr = message.getProperty("TimeAccountDetails_Enhanced") as String
    def rateXmlStr = message.getProperty("AccrualRates")                as String

    if (!empXmlStr || !timeXmlStr || !rateXmlStr) {
        def w = new StringWriter()
        new MarkupBuilder(w).Error(message: "Missing property(ies): EmpEmployment=${!!empXmlStr}, TimeAccountDetails=${!!timeXmlStr}, AccrualRates=${!!rateXmlStr}")
        message.setBody(w.toString())
        return message
    }

    // ---- parse
    def empXml  = new XmlSlurper(false, false).parseText(empXmlStr)
    def timeXml = new XmlSlurper(false, false).parseText(timeXmlStr)
    def rateXml = new XmlSlurper(false, false).parseText(rateXmlStr)

    // ---- helpers
    def tz = ZoneId.of("Australia/Sydney")
    //def today = LocalDate.now(tz)   //Commenting this to allow overriding of current Date
    //read property CurrentDate if present (this allows override from another step)
    def currentDateStr = message.getProperty("CurrentDate")

    def first3 = { String code ->
        if (!code) return null
        def p = code.split('/')
        p.size() >= 3 ? [p[0], p[1], p[2]].join('/') : code
    }

    def parseSfDate = { String s ->
        if (!s) return null
        s = s.trim()
        try {
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse(s)
        } catch (ignored) {
            try {
                new SimpleDateFormat("yyyy-MM-dd").parse(s.length() >= 10 ? s.substring(0,10) : s)
            } catch (e) { null }
        }
    }
    
    //Derive "today" based on property or system fallback
        def parsedCurrentDate = parseSfDate(currentDateStr)
        def today = parsedCurrentDate ?
        parsedCurrentDate.toInstant().atZone(tz).toLocalDate() :
        LocalDate.now(tz)
        
    //add debug property to verify which date source was used
    if (parsedCurrentDate) {
        message.setProperty("DEBUG_AccrualCalculation_TodayDate", "Using CurrentDate property value → ${today}")
    } else {
        message.setProperty("DEBUG_AccrualCalculation_TodayDate", "No valid CurrentDate property found, using system date → ${today}")
    }

    def toLocalDate = { Date d ->
        d ? d.toInstant().atZone(tz).toLocalDate() : null
    }

    def toBD = { String s ->
        try { new BigDecimal((s ?: "0").trim()) } catch (e) { null }
    }

    // ---- Accrual rate lookup
    def rateByCode3 = [:]
    rateXml.'**'.findAll { it.name() == 'cust_LeaveAccrualRateLookup' }.each { rn ->
        def code = rn.externalCode?.text()?.trim()
        if (code) {
            rateByCode3[code] = [
                LSL1   : rn.cust_clslStandard?.text()?.trim(),
                LSL1Ovr: rn.cust_clsl1?.text()?.trim(),
                LSL2Ovr: rn.cust_clsl2?.text()?.trim()
            ]
        }
    }

    // ---- EmpEmployment by userId
    def empByUser = [:]  // uid -> [upl, lslDate(LocalDate), code3]
    empXml.EmpEmployment.each { e ->
        def uid = e.userId?.text()?.trim()
        if (!uid) return
        def upl = (e.UnPaidParentalLeave?.text()?.trim() ?: "").equalsIgnoreCase("Yes") ? "Yes" : "No"
        def lslDate = toLocalDate(parseSfDate(e.customDate5?.text()))
        def code  = e.jobInfoNav?.EmpJob?.payScaleLevelNav?.PayScaleLevel?.code?.text()
        def code3 = first3(code)
        empByUser[uid] = [upl: upl, lslDate: lslDate, code3: code3]
    }

    // ---- time account details grouped by userId
    def timeByUser = [:].withDefault { [] }
    timeXml.'TimeAccountDetail'.each { t ->
        def uid = t.userId?.text()?.trim()
        if (uid) timeByUser[uid] << t
    }
    
    //--- Find Users without TimeAccountDetails for Primary Asn Query Later - START
    // ---users with NO TimeAccountDetail rows
    Set<String> empUids = (empByUser.keySet() as Set<String>)
    Set<String> tadUids = (timeByUser.findAll { k, v -> v && v.size() > 0 }.keySet() as Set<String>)
    Set<String> noTadUids = (empUids - tadUids) as Set<String>

    // handy properties for downstream steps / tracing
    message.setProperty("UsersWithoutTAD_Count", noTadUids.size().toString())
    message.setProperty("UsersWithoutTAD_CSV", noTadUids.join(','))

    // Optional: XML body with the missing userIds
    def wMissing = new StringWriter()
    def xMissing = new groovy.xml.MarkupBuilder(wMissing)
    xMissing.Record {
    noTadUids.each { uid -> userId(uid) }
    }
    message.setProperty("UsersWithoutTAD_XML", wMissing.toString())
    
    // --- flag whether XML has any values
    def hasMissing = !noTadUids.isEmpty()
    message.setProperty("UsersWithoutTAD_Exists", hasMissing ? "true" : "false")
    //--- Find Users without TimeAccountDetails for Primary Asn Query Later - END
    

    // ---- choose accrual rate based on customDate5 vs today
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
    
    //======== NEW: helpers for account-type aware selection ========
    def isZeroStr = { String s ->
        try { new BigDecimal((s ?: "0").trim()).compareTo(BigDecimal.ZERO) == 0 } catch (e) { false }
    }

    def decideRateByAccountType = { String accountType, Map row, LocalDate lslDate ->
        if (!row) return null
        switch ((accountType ?: "").trim()) {
            case "85":   //Standard account -> always cust_clslStandard
                return row.LSL1 ?: "0"
            case "86":   //Overtime account
                if (!lslDate || today.isBefore(lslDate)) {
                    return row.LSL1Ovr ?: row.LSL1 ?: "0"            //prefer cust_clsl1
                } else {
                    def r2 = row.LSL2Ovr                               //cust_clsl2
                    return (r2 && !isZeroStr(r2)) ? r2 : (row.LSL1Ovr ?: row.LSL1 ?: "0")
                }
            default:
                //Fallback to original logic if 85/86 not provided
                //return decideRate(row, lslDate) ?: "0"
                return "0"
        }
    }
    //======== NEW END ========

    // ---- build output rows
    def outRows = []
    empByUser.each { uid, meta ->
        def userRows = timeByUser[uid]
        if (!userRows) return

        if (meta.upl.equalsIgnoreCase("Yes")) {
            //For UPL = Yes, ALWAYS set calculated_AccrualRate = 0
            userRows.each { t -> outRows << [node: t, calc: "0"] }
        } else {
            def row = meta.code3 ? rateByCode3[meta.code3] : null
            //def picked = (decideRate(row, meta.lslDate) ?: "0")   //ensure not null
            //userRows.each { t -> outRows << [node: t, calc: picked] }
            
            userRows.each { t ->
                def acctType = t.accountType?.text()?.trim()      //expects <accountType>85/86</accountType>
                def picked   = decideRateByAccountType(acctType, row, meta.lslDate) ?: "0"
                outRows << [node: t, calc: picked]
            }
        }
    }

    // ---- render output
    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    xml.TimeAccountDetail {
        outRows.each { r ->
            TimeAccountDetail {
                // copy original children as-is
                //r.node.children().each { ch -> "${ch.name()}"(ch.text()) }
                def skip = ['calculated_AccrualRate','bookingType','bookingAmountNew'] as Set
r.node.children()
    .findAll { ch -> !(ch.name() in skip) }
    .each { ch -> "${ch.name()}"(ch.text()) }

                //always add calculated accrual rate
                calculated_AccrualRate(r.calc)

                // ---- NEW: bookingType-based handling + ALWAYS compute bookingAmountNew
                def bookingTypeIn = (r.node.bookingType?.text() ?: "").trim().toUpperCase()
                def bookingAmtBD  = (toBD(r.node.bookingAmount?.text()) ?: BigDecimal.ZERO)
                def calcBD        = new BigDecimal((r.calc?.toString()?.trim() ?: "0"))
                def delta         = calcBD.subtract(bookingAmtBD).stripTrailingZeros().toPlainString()

                //If bookingType missing or unknown, derive it from equality
                if (!["NEW","ACCRUAL","MANUAL_ADJUSTMENT"].contains(bookingTypeIn)) {
                    bookingTypeIn = (bookingAmtBD.compareTo(calcBD) == 0) ? "ACCRUAL" : "MANUAL_ADJUSTMENT"
                }

                //Write (possibly normalized/derived) bookingType
                bookingType(bookingTypeIn)

                //Always write bookingAmountNew (even when zero)
                bookingAmountNew(delta)
            }
        }
    }

    message.setBody(writer.toString())
    return message
}
