import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.*
import java.time.Duration
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.LocalDate

Message processData(Message message) {

    // -------- helpers --------
    def getProp = { String k -> (message.getProperty(k) ?: "").toString().trim() }

    def parseLocalDate = { String ts ->
        if (!ts) return null
        def idx = ts.indexOf('T')
        (idx > 0 ? ts.substring(0, idx) : ts).with { LocalDate.parse(it) }
    }

    def seg3 = { String code ->
        if (!code) return null
        def parts = code.split('/')
        if (parts.size() >= 3) return [parts[0], parts[1], parts[2]].join('/')
        code
    }

    def yearsBetween = { LocalDate from, LocalDate to ->
        if (!from || !to) return null
        Period.between(from, to).years
    }

    def within = { LocalDate d, LocalDate start, LocalDate end ->
        if (!d || !start || !end) return false
        !d.isBefore(start) && !d.isAfter(end)
    }

    def bd = { String s ->
        try { new BigDecimal(s ?: "0") } catch (ignored) { BigDecimal.ZERO }
    }

    def formatDiff = { BigDecimal d ->
        if (d == null) return "0"
        (d.compareTo(BigDecimal.ZERO) == 0)
                ? "0"
                : d.setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    String event_startDate = getProp("event_startDate")
    boolean is_viaEvent = (message.getProperty('viaEvent') ?: '').toString().trim().equalsIgnoreCase('true')

    // -------- read inputs --------
    String tacXml    = getProp("TimeAccDets_User")         // required
    String leavesXml = getProp("UserLeaveDetails")         // optional
    String ratesXml  = getProp("AccrualRates")             // required
    String empXml    = getProp("EmpEmployment_Historic")   // required

    if (!tacXml || !ratesXml || !empXml) {
        def w = new StringWriter()
        new MarkupBuilder(w).Error(
                message: "Missing property(ies). TimeAccDets_User=${!!tacXml}, AccrualRates=${!!ratesXml}, EmpEmployment_Historic=${!!empXml}"
        )
        message.setBody(w.toString())
        return message
    }

    // -------- parse xmls --------
    def slurper = new XmlSlurper(false, false)
    def tac = slurper.parseText(tacXml)
    def acc = slurper.parseText(ratesXml)
    def emp = slurper.parseText(empXml)
    def upl = leavesXml ? slurper.parseText(leavesXml) : null

    // -------- find current userId (if present via UPL) --------
    String currentUserId = null
    if (upl && upl.Employment && upl.Employment.size() == 1) {
        currentUserId = (upl.Employment.UserID.text() ?: "").trim()
    }
    if (!currentUserId) {
        def uniqUsers = (emp.EmpEmployment*.userId*.text()*.trim()).findAll { it }?.unique()
        if (uniqUsers && uniqUsers.size() == 1) currentUserId = uniqUsers[0]
    }

    // -------- UPL ranges for the user --------
    List<Map> uplRanges = []
    if (upl && currentUserId) {
        upl.Employment.findAll { (it.UserID.text() ?: "").trim() == currentUserId }.each { e ->
            e.Leaves.each { l ->
                def s = parseLocalDate(l.StartDate.text())
                def f = parseLocalDate(l.EndDate.text())
                if (s && f) uplRanges << [start: s, end: f]
            }
        }
    }

    // -------- primary employment & dates --------
    /*def primaryEmp = emp.EmpEmployment.find {
        ((it.userNav?.User?.isPrimaryAssignment?.text() ?: "false") == "true") &&
        (currentUserId ? (it.userId.text()?.trim() == currentUserId) : true)
    } ?: emp.EmpEmployment.find { (it.userNav?.User?.isPrimaryAssignment?.text() ?: "false") == "true" }
    /*
     */

    // --- Pick employment record matching the current userId ---
    def primaryEmp = emp.EmpEmployment.find {
        (it.userId?.text()?.trim() == currentUserId)
    }

    String fullPayScaleCode = primaryEmp?.jobInfoNav?.EmpJob?.payScaleLevelNav?.PayScaleLevel?.code?.text()
    String payScaleKey3 = seg3(fullPayScaleCode)
    LocalDate lslEligibility = parseLocalDate(primaryEmp?.customDate5?.text())

    // Employment Start Date
    // LocalDate employmentStart = parseLocalDate(primaryEmp?.startDate?.text())
    LocalDate employmentStart = (is_viaEvent&& event_startDate) ? parseLocalDate(event_startDate) : parseLocalDate(primaryEmp?.startDate?.text())

    if (!employmentStart) {
        def starts = (emp.EmpEmployment*.startDate*.text()).findAll { it }.collect { parseLocalDate(it) }.findAll { it != null }
        if (starts) employmentStart = starts.min()
    }
    if (!employmentStart) employmentStart = LocalDate.now()

    // -------- accrual rate lookup by 3-seg key --------
    Map<String, Map> rateByKey = [:]
    acc.'cust_LeaveAccrualRateLookup'.each { n ->
        String ext = n.externalCode?.text()
        def key3 = seg3(ext)
        if (key3) {
            rateByKey[key3] = [
                    L1    : bd(n.cust_clslStandard?.text()),   // Standard (cust_clslStandard)
                    L1Ovr : bd(n.cust_clsl1?.text()),          // Overtime tier 1 (cust_clsl1)
                    L2Ovr : bd(n.cust_clsl2?.text())           // Overtime tier 2 (cust_clsl2)
            ]
        }
    }

// // ---- LOG rateByKey for debugging ----
// messageLogFactory.getMessageLog(message)?.addAttachmentAsString(
//     "rateByKey",
//     rateByKey.collect { k, v -> "$k -> L1=${v.L1}, L1Ovr=${v.L1Ovr}, L2Ovr=${v.L2Ovr}" }.join('\n'),
//     "text/plain"
// )
    // -------- NEW: derive rate using accountType + date --------
    // accountType: 85=Standard, 86=Overtime
    def deriveRateFor = { LocalDate bookingDate, Integer accountType ->
        if (!payScaleKey3) return null
        def bucket = rateByKey[payScaleKey3]
        //if (!bucket) return null

        if (!bucket) {
            messageLogFactory.getMessageLog(message)?.addAttachmentAsString(
                    "RateDebug",
                    "No bucket found for key ${payScaleKey3}",
                    "text/plain"
            )
            return null
        }

        // messageLogFactory.getMessageLog(message)?.addAttachmentAsString(
        //     "RateDebug_"+bookingDate,
        //     "Key=${payScaleKey3}, L1=${bucket.L1}, L1Ovr=${bucket.L1Ovr}, L2Ovr=${bucket.L2Ovr}, acctType=${accountType}, LSL=${lslDatePlus10Years}",
        //     "text/plain"
        // )

        if (accountType == null) accountType = 85  // default to Standard if unknown

        if (accountType == 85) {
            // Standard: always L1; ignore LSL date
            return bucket.L1
        }

        // Overtime logic (86)
        def lslDatePlus10Years = lslEligibility.plusYears(10)
        if (bookingDate.isBefore(lslDatePlus10Years) || bookingDate.isEqual(lslDatePlus10Years)) {
            // Today <= lslDate + 10 years
            //return bucket.L1Ovr ?: "0"
            if (bucket.L1Ovr != null) {
                return bucket.L1Ovr
            } else {
                return "0"
            }
        } else {
            // Today > lslDate + 10 years
            //return bucket.L2Ovr ?: "0"
            if (bucket.L2Ovr != null) {
                return bucket.L2Ovr
            } else {
                return "0"
            }
        }

        // if (lslEligibility == null || (bookingDate != null && bookingDate.isBefore(lslEligibility))) {
        //     return bucket.L1Ovr
        // }

        // // bookingDate >= LSL date
        // def l2 = bucket.L2Ovr
        // if (l2 != null && l2.compareTo(BigDecimal.ZERO) != 0) {
        //     return l2
        // }
        // fallback to L1Ovr when L2Ovr is numeric 0
        // return bucket.L1Ovr
    }

    // -------- group existing details; KEY = taCode|date|accountType --------
    Map<String, Map> byKey = [:]  // key -> [sum, existing, hasAccrual, hasManual, accountType]
    LocalDate maxObservedDate = null

    // Also collect Time Account codes per accountType so we can fill gaps per type
    Set<String> taCodes85 = [] as Set
    Set<String> taCodes86 = [] as Set

    tac.TimeAccountDetail.each { row ->
        String taCode = (row.TimeAccount_externalCode?.text() ?: "").trim()
        LocalDate dt  = parseLocalDate(row.bookingDate?.text())
        String bType  = (row.bookingType?.text() ?: "").trim()
        BigDecimal amt = bd(row.bookingAmount?.text())

        Integer atype = null
        def atxt = (row.accountType?.text() ?: "").trim()
        if (atxt) {
            try { atype = Integer.valueOf(atxt) } catch (ignored) { atype = null }
        }
        if (atype == null) atype = 85 // default if missing

        //if (!taCode || !dt) return

        // --------------------------------------------------------------
        // CHANGE: collect TA codes even when bookingDate is blank
        // so gap-fill can create daily rows for new hires.

        // bucket codes per type
        if (taCode) {
            if (atype == 85) taCodes85 << taCode
            if (atype == 86) taCodes86 << taCode
        }
        // --------------------------------------------------------------

        // Skip grouping if required fields for the key are missing
        if (!taCode || !dt) return

        if (maxObservedDate == null || dt.isAfter(maxObservedDate)) maxObservedDate = dt

        String key = "${taCode}|${dt}|${atype}"
        def accObj = byKey[key] ?: [sum: BigDecimal.ZERO, existing: false, hasAccrual: false, hasManual: false, accountType: atype]
        if (bType in ["ACCRUAL", "MANUAL_ADJUSTMENT"]) {
            accObj.sum = accObj.sum.add(amt ?: BigDecimal.ZERO)
        }
        if (bType == "ACCRUAL") accObj.hasAccrual = true
        if (bType == "MANUAL_ADJUSTMENT") accObj.hasManual = true
        accObj.existing = true
        accObj.accountType = atype

        byKey[key] = accObj
    }

    // Flags (optional)
    message.setProperty("HasTA85", !taCodes85.isEmpty())
    message.setProperty("HasTA86", !taCodes86.isEmpty())

    // -------- determine range end --------
    LocalDate rangeEnd = (maxObservedDate ? (maxObservedDate.isAfter(LocalDate.now()) ? maxObservedDate : LocalDate.now()) : LocalDate.now())

    // -------- fill gaps with NEW (create BOTH 85 & 86 per day for their known TA codes) --------
    // Build the (taCode, accountType) pairs we must cover
    List<Map> taPairs = []
    taCodes85.each { ta -> taPairs << [ta: ta, at: 85] }
    taCodes86.each { ta -> taPairs << [ta: ta, at: 86] }

    long daysSpan = Duration.between(employmentStart.atStartOfDay(), rangeEnd.plusDays(1).atStartOfDay()).toDays()

    for (int i = 0; i < daysSpan; i++) {
        LocalDate d = employmentStart.plusDays(i)
        taPairs.each { p ->
            String k = "${p.ta}|${d}|${p.at}"
            if (!byKey.containsKey(k)) {
                byKey[k] = [sum: BigDecimal.ZERO, existing: false, hasAccrual: false, hasManual: false, accountType: p.at]
            } else if (byKey[k].accountType == null) {
                byKey[k].accountType = p.at
            }
        }
    }

    // -------- build outputs (always emit) --------
    List<String> sortedKeys = byKey.keySet().toList().sort { a, b ->
        def (taA, dtA, atA) = a.split('\\|', 3)
        def (taB, dtB, atB) = b.split('\\|', 3)
        int cmp = LocalDate.parse(dtA) <=> LocalDate.parse(dtB)
        if (cmp != 0) return cmp
        cmp = taA <=> taB
        if (cmp != 0) return cmp
        return (atA as Integer) <=> (atB as Integer)
    }

    List<Map> outputs = []
    int newCount = 0
    int existingCount = 0

    sortedKeys.each { key ->
        def (taCode, dtStr, atStr) = key.split('\\|', 3)
        LocalDate d = LocalDate.parse(dtStr)
        Integer atype = (atStr as Integer)
        def info = byKey[key]
        BigDecimal sumAmt = info.sum ?: BigDecimal.ZERO
        boolean existed = info.existing as boolean

        boolean isUPL = uplRanges.any { within(d, it.start, it.end) }

        // Derive rate per new rules, then apply UPL suppression (keep behavior as-is)
        // BigDecimal derivedRaw = (deriveRateFor(d, atype) ?: BigDecimal.ZERO)
        // BigDecimal calcAccrForOutput = isUPL ? BigDecimal.ZERO : derivedRaw
        // BigDecimal diff = calcAccrForOutput.subtract(sumAmt)

// Start- Taking tempholding into account when doing retro calc. Not just for event trigger
        def empRecordForDate = emp.EmpEmployment.find { empRec ->
            // Match userId if available
            boolean userMatches = !currentUserId || (empRec.userId?.text()?.trim() == currentUserId)
            return userMatches
        }

        def jobForDate = empRecordForDate?.jobInfoNav?.EmpJob?.find { job ->
            LocalDate jobStart = parseLocalDate(job.startDate?.text())
            LocalDate jobEnd = parseLocalDate(job.endDate?.text())

            boolean dateInRange = jobStart &&
                    !d.isBefore(jobStart) &&
                    (jobEnd == null || !d.isAfter(jobEnd))

            return dateInRange
        }

        String divisionCode = jobForDate?.division?.text()?.trim()
        boolean isDivision100001 = (divisionCode == "10000001")

        if (isDivision100001 && !existed) {
            return  // Skip to next iteration
        }

        // Derive rate per new rules, then apply UPL suppression (keep behavior as-is)
        BigDecimal derivedRaw = (deriveRateFor(d, atype) ?: BigDecimal.ZERO)
        BigDecimal calcAccrForOutput = isUPL ? BigDecimal.ZERO : derivedRaw

        if (isDivision100001 && info.existing && (info.sum ?: BigDecimal.ZERO).compareTo(BigDecimal.ZERO) == 0) {
            return  // Skip
        }

        BigDecimal diff
        if (isDivision100001 && info.existing) {
            if (calcAccrForOutput.compareTo(BigDecimal.ZERO) > 0) {
                diff = calcAccrForOutput.negate()
            } else {
                diff = calcAccrForOutput  // or BigDecimal.ZERO, depending on your logic
            }
        } else{
            diff = calcAccrForOutput.subtract(sumAmt)
        }
// End- Taking tempholding into account when doing retro calc. Not just for event trigger

        String outType
        if (!existed) {
            outType = "NEW"
            newCount++
        } else if (info.hasManual) {
            outType = "MANUAL_ADJUSTMENT"
            existingCount++
        } else if (info.hasAccrual) {
            outType = "ACCRUAL"
            existingCount++
        } else {
            outType = "MANUAL_ADJUSTMENT" // fallback
            existingCount++
        }

        outputs << [
                taCode      : taCode,
                date        : d,
                sumAmt      : sumAmt,
                isUPLDay    : isUPL,
                calcAccr    : calcAccrForOutput,
                diffStr     : formatDiff(diff),
                outType     : outType,
                accountType : atype
        ]
    }

    // -------- emit XML --------
    def sw = new StringWriter()
    def xb = new MarkupBuilder(sw)
    xb.mkp.xmlDeclaration(version: "1.0", encoding: "UTF-8")
    xb.TimeAccountDetail {
        outputs.each { a ->
            TimeAccountDetail {
                bookingType(a.outType)  // NEW | ACCRUAL | MANUAL_ADJUSTMENT
                TimeAccount_externalCode(a.taCode)
                bookingDate(a.date.toString() + "T00:00:00.000")
                bookingAmount(a.sumAmt.toPlainString())
                isUPLDay(a.isUPLDay ? "true" : "false")
                calculated_Accrual(a.calcAccr.toPlainString())
                difference(a.diffStr)  // 0 or up to 5 dp
                accountType(String.valueOf(a.accountType))
            }
        }
    }

    message.setBody(sw.toString())
    message.setProperty("CreatedAdjustmentCount", outputs.size())
    message.setProperty("FilledNewDaysCount", newCount)
    message.setProperty("ExistingDaysCount", existingCount)
    message.setProperty("RangeStart", employmentStart?.toString())
    message.setProperty("RangeEnd", rangeEnd?.toString())
    return message
}
