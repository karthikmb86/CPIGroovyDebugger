import com.sap.gateway.ip.core.customdev.util.Message;
import java.util.HashMap;
import java.util.Calendar;
import java.util.TimeZone;
import groovy.xml.*;
import java.text.SimpleDateFormat;
import java.math.RoundingMode;
import java.math.BigDecimal;
import java.util.zip.CRC32;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.ZoneId;

def Message processData(Message message) {

    // Properties
    def properties = message.getProperties();
    def classificationAllowed = properties.get("classification_eligible");
    def svp_timetype = properties.get("exclude_svp_timetype");

    //Body
    def body = message.getBody(String.class)
    def xml = new XmlParser().parseText(body)

    def employeeData = getEmployeePayload(xml)
    def workScheduleDayModelAssignments = getWorkScheduleDayModelAssignmentPayload(xml)
    def payCompAssignments = getPayCompAssignmentsPayload(xml)

    def nesaStatus = getNesaStatus(getPrimaryEmployment(employeeData))
    def inactiveTimesheets = getInactiveTimesheet(xml)
    def empJobHistory = getEmpJobHistory(xml)
    def futureDateEmployment = getFutureDatedEmployment(xml)

    def priorServiceIncrements = readPriorServiceDetails(getPrimaryEmployment(employeeData))
    def primaryPayScaleLevel = processPrimaryEmployment(employeeData, payCompAssignments, workScheduleDayModelAssignments, priorServiceIncrements, nesaStatus, classificationAllowed,svp_timetype,inactiveTimesheets,empJobHistory)
    def concurrentEmploymentExist = processConcurrentEmployment(employeeData, payCompAssignments, workScheduleDayModelAssignments, nesaStatus, priorServiceIncrements, classificationAllowed, svp_timetype, inactiveTimesheets,empJobHistory,primaryPayScaleLevel)

    if (concurrentEmploymentExist) {
        //duplicate new/updated increment progression details
        duplicateIncrementProgressionDetails(employeeData)
        duplicatePriorServiceIncrements(employeeData)
    }

    def newIncrements = employeeData[0]?.PerPerson.employmentNav?.new_PriorServiceIncrementDetails ?: []
    if (newIncrements && futureDateEmployment){
        processFutureDatedAssignment(newIncrements,futureDateEmployment,payCompAssignments,employeeData)
    }

    // print(XmlUtil.serialize(employeeData[0]))
    message.setBody(new groovy.xml.XmlUtil().serialize(employeeData))

    return message;
}

// Get the root message from XML
def getEmployeePayload(def xml) {
    xml.'multimap:Message1'.root
}

def getInactiveTimesheet(def xml) {
    xml.'multimap:Message5'.InactiveTimeSheet
}

def getEmpJobHistory(def xml) {
    xml.'multimap:Message6'.JobHistory
}

def getFutureDatedEmployment(def xml) {
    xml.'multimap:Message7'.EmpEmployment
}

def duplicateIncrementProgressionDetails(employeeData) {

    def userIds = employeeData.PerPerson.employmentNav.EmpEmployment.findAll { it }.collect { it.userNav.User.userId.text() }.unique()

    userIds.each { userId ->
        // Find the node where cust_incrementProgression_externalCode matches userId
        def incrementProgressionDetails = employeeData.PerPerson.employmentNav.new_incrementProgressionDetails.cust_incrementProgressionDetails.findAll {
            it.cust_incrementProgression_externalCode.text() != userId
        }

        if(incrementProgressionDetails){

            def uniquePayScaleLevels = employeeData.PerPerson.employmentNav.new_incrementProgressionDetails.cust_incrementProgressionDetails
                    .collect { it.cust_payScaleLevel.text() }
                    .unique()


            incrementProgressionDetails.each{ nonMatchingNode ->

                def payScaleLevel = nonMatchingNode.cust_payScaleLevel.text()

                // Check if a node with the same userId and payScaleLevel already exists BEFORE adding
                def exists = employeeData.PerPerson.employmentNav[0].new_incrementProgressionDetails[0].cust_incrementProgressionDetails.find {
                    it.cust_incrementProgression_externalCode.text() == userId && it.cust_payScaleLevel.text() == payScaleLevel
                }

                if (!exists) {
                    def clonedNode = (Node) nonMatchingNode.clone()
                    clonedNode.'cust_incrementProgression_externalCode'[0].value = userId
                    employeeData.PerPerson.employmentNav[0].new_incrementProgressionDetails[0].children().add(clonedNode)

//                    uniquePayScaleLevels.remove(payScaleLevel)
                }
            }
        }
    }

}

def duplicatePriorServiceIncrements(employeeData) {

    def userIds = employeeData.PerPerson.employmentNav.EmpEmployment.findAll { it }.collect { it.userNav.User.userId.text() }.unique()

    userIds.each { userId ->
        // Find the node where cust_incrementProgression_externalCode matches userId
        def priorServiceIncrements = employeeData.PerPerson.employmentNav.new_PriorServiceIncrementDetails.cust_priorServiceIncrementDetails.findAll {
            it.cust_priorServiceIncrement_externalCode.text() != userId
        }

        if(priorServiceIncrements){

            def uniquePayScaleLevels = employeeData.PerPerson.employmentNav.new_PriorServiceIncrementDetails.cust_priorServiceIncrementDetails
                    .collect { it.cust_payScaleLevel.text() }
                    .unique()


            priorServiceIncrements.each{ nonMatchingNode ->

                def payScaleLevel = nonMatchingNode.cust_payScaleLevel.text()

                // Check if a node with the same userId and payScaleLevel already exists BEFORE adding
                def exists = employeeData.PerPerson.employmentNav[0].new_PriorServiceIncrementDetails[0].cust_priorServiceIncrementDetails.find {
                    it.cust_priorServiceIncrement_externalCode.text() == userId && it.cust_payScaleLevel.text() == payScaleLevel
                }

                if (!exists) {
                    def clonedNode = (Node) nonMatchingNode.clone()
                    clonedNode.'cust_priorServiceIncrement_externalCode'[0].value = userId
                    employeeData.PerPerson.employmentNav[0].new_PriorServiceIncrementDetails[0].children().add(clonedNode)

//                    uniquePayScaleLevels.remove(payScaleLevel)
                }
            }
        }
    }

}

// Get Primary Employment
def getPrimaryEmployment(def employeeData) {
    employeeData.PerPerson.employmentNav.EmpEmployment.find { it.userNav.User.isPrimaryAssignment.text().equalsIgnoreCase('true') }
}

// Get Concurrent Employment
def getConcurrentEmployment(def employeeData) {

    def timeZone = ZoneId.of("Australia/Sydney")
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    def today = LocalDate.now(timeZone)

    employeeData.PerPerson.employmentNav.EmpEmployment.find { it.userNav.User.isPrimaryAssignment.text().equalsIgnoreCase('false') }

    employeeData.PerPerson.employmentNav.EmpEmployment.find { empEmployment ->

        def isNotPrimary = empEmployment.userNav.User.isPrimaryAssignment.text().equalsIgnoreCase('false')
        def isNotHDA = !(empEmployment.isHDA.text().equalsIgnoreCase('true'))

        //DF459 - empJob needs to be valid
        def hasValidJob = empEmployment.jobInfoNav.EmpJob.any { empJob ->
            return  LocalDate.parse(empJob.startDate.text().substring(0, 10), formatter) <= today &&
                    (empJob.endDate.text().isEmpty() || LocalDate.parse(empJob.endDate.text().substring(0, 10), formatter) >= today)
        }

        return isNotPrimary && isNotHDA && hasValidJob
    }

//    return employeeData.PerPerson.employmentNav.EmpEmployment.any { empEmployment ->
//
//        def isNotPrimary = empEmployment.userNav.User.isPrimaryAssignment.text().equalsIgnoreCase('false')
//        def isNotHDA = !(empEmployment.isHDA.text().equalsIgnoreCase('true'))
//
//        def hasValidJob = empEmployment.jobInfoNav.EmpJob.any { empJob ->
//           return  LocalDate.parse(empJob.startDate.text().substring(0, 10), formatter) <= today &&
//                    (empJob.endDate.text().isEmpty() || LocalDate.parse(empJob.endDate.text().substring(0, 10), formatter) >= today)
//        }
//
//        return isNotPrimary && isNotHDA && hasValidJob
//    }

}

// Get WorkScheduleDayModelAssignment messages
def getWorkScheduleDayModelAssignmentPayload(def xml) {
    xml.'multimap:Message2'.WorkScheduleDayModelAssignment.WorkScheduleDayModelAssignment
}

// Get PayCompAssignments
def getPayCompAssignmentsPayload(def xml) {
    xml.'multimap:Message3'.PayScalePayComponent.PayScalePayComponent
}

// Process primary employment
String processPrimaryEmployment(def employeeData, def payCompAssignments, def workScheduleDayModelAssignments, priorServiceDetails, nesaProficient, classificationAllowedString, svp_timetype, inactiveTimesheets,empJobHistory) {
    def priorServiceRecord
    def teacherClassification = 'AUS/30'
    def assistPrincipalClassification = 'AUS/34'

    def primaryEmployment = getPrimaryEmployment(employeeData)

    def employeeId = employeeData.PerPerson.personIdExternal.text()
    if (primaryEmployment) {
//        priorServiceDetails = readPriorServiceDetails(primaryEmployment)

        if (primaryEmployment.jobInfoNav.EmpJob.position.text().trim().isEmpty()){
            // check HDA
            def empEmploymentsWithHDA = employeeData.'**'.findAll { node ->
                node.name() == 'EmpEmployment' && node.'isHDA'.text() == 'true'
            }

            empEmploymentsWithHDA.each { empEmployment ->
                println "Found EmpEmployment with isHDA = true"
                // Print additional details if needed, e.g., personId or other identifiers
                println "personIdExternal: ${empEmployment.personIdExternal.text()}"
                println "personIdExternal: ${empEmployment.startDate.text()}"
            }
            if (!empEmploymentsWithHDA.isEmpty() && empEmploymentsWithHDA[0]?.jobInfoNav?.EmpJob?.payScaleArea?.text() &&
                    (classificationAllowed(classificationAllowedString, empEmploymentsWithHDA[0].jobInfoNav.EmpJob.payScaleArea.text()) || (empEmploymentsWithHDA[0].jobInfoNav.EmpJob.payScaleArea.text() == assistPrincipalClassification && primaryEmployment?.jobInfoNav?.EmpJob?.payScaleArea?.text() == teacherClassification))) {
//            if(classificationAllowed(classificationAllowedString,empEmploymentsWithHDA[0].jobInfoNav?.EmpJob?.payScaleArea?.text()) || (empEmploymentsWithHDA[0].jobInfoNav?.EmpJob?.payScaleArea?.text() == assistPrincipalClassification && primaryEmployment.jobInfoNav?.EmpJob?.payScaleArea?.text() == teacherClassification )) {
                priorServiceRecord = null

                if(classificationAllowed(classificationAllowedString,empEmploymentsWithHDA[0].jobInfoNav.EmpJob.payScaleArea.text())) {
                    priorServiceRecord = priorServiceDetails.priorServiceDetail.find { it.payScaleLevel == empEmploymentsWithHDA[0].jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text() }
                } else{
                    priorServiceRecord = priorServiceDetails.priorServiceDetail.find { it.payScaleLevel == primaryEmployment.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text()}
                }

                if(!priorServiceRecord){
                    throw new Exception("$employeeId - Prior service history not found")
                }

//                def wsr = primaryEmployment.jobInfoNav.EmpJob.workscheduleCode.text()
                def wsr = primaryEmployment.jobInfoNav.EmpJob.WorkSchedule
                processIncrementProgressionDetails(employeeData, primaryEmployment, empEmploymentsWithHDA[0], workScheduleDayModelAssignments, payCompAssignments, priorServiceRecord, nesaProficient, wsr,svp_timetype, inactiveTimesheets,empJobHistory)
            }

        }

        else if(classificationAllowed(classificationAllowedString,primaryEmployment.jobInfoNav.EmpJob.payScaleArea.text())) {
            priorServiceRecord = priorServiceDetails.priorServiceDetail.find { it.payScaleLevel == primaryEmployment.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text() }
            if(!priorServiceRecord){
                throw new Exception("$employeeId - Prior service history not found")
            }
//            def wsr = primaryEmployment.jobInfoNav.EmpJob.workscheduleCode.text()
            def wsr = primaryEmployment.jobInfoNav.EmpJob.WorkSchedule
            processIncrementProgressionDetails(employeeData, primaryEmployment, null, workScheduleDayModelAssignments, payCompAssignments, priorServiceRecord, nesaProficient, wsr,svp_timetype,inactiveTimesheets,empJobHistory)
        }

        return priorServiceRecord?.payScaleLevel
    }
    return null
}

boolean classificationAllowed(classificationAllowedString,payScaleArea){
    def values = classificationAllowedString.split(',')
    return values.contains(payScaleArea)
}

// Process concurrent employment
def processConcurrentEmployment(def employeeData, def payCompAssignments, def workScheduleDayModelAssignments, nesaProficient, priorServiceDetails, classificationAllowedString, svp_timetype,inactiveTimesheets,empJobHistory,primaryPayScaleLevel) {
    def grad2Level = 'AUS/30/13/Graduate/Step 2'
    def grad2NextLevel = 'AUS/30/13/Proficient/Step 3'
    def concurrentEmployment = getConcurrentEmployment(employeeData)
    def employeeId = employeeData.PerPerson.personIdExternal.text()

    def newPriorServiceNode = employeeData.PerPerson.employmentNav.new_PriorServiceIncrementDetails

    if (concurrentEmployment) {
        // Compare and exit early if they're the same
        if ((newPriorServiceNode && primaryPayScaleLevel && concurrentEmployment.jobInfoNav.EmpJob.payScaleLevel?.text()) && concurrentEmployment.jobInfoNav.EmpJob.payScaleLevel?.text() == primaryPayScaleLevel) {
            println "Same PSL"
            // Is Grad 2?
            def currentPayScaleLevel = concurrentEmployment.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text()
            boolean isCurrentGrad2 = currentPayScaleLevel == grad2Level
            def newPayScaleLevel = isCurrentGrad2 ? grad2NextLevel : concurrentEmployment.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text()
            processJobInfoToNextLevel(concurrentEmployment, primaryPayScaleLevel, isCurrentGrad2, newPayScaleLevel, newPriorServiceNode.cust_priorServiceIncrementDetails.cust_serviceDate.text())
            processCompensationToNextLevel(concurrentEmployment, payCompAssignments, newPayScaleLevel, newPriorServiceNode.cust_priorServiceIncrementDetails.cust_serviceDate.text())
            return true
        }

        if(classificationAllowed(classificationAllowedString,concurrentEmployment.jobInfoNav.EmpJob.payScaleArea.text())) {
            def priorServiceRecord = priorServiceDetails.priorServiceDetail.find { it.payScaleLevel == concurrentEmployment.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text() }
            if(!priorServiceRecord){
                throw new Exception("$employeeId - Prior service history not found")
            }

            def wsr = concurrentEmployment.jobInfoNav.EmpJob.WorkSchedule
            processIncrementProgressionDetails(employeeData, concurrentEmployment, null, workScheduleDayModelAssignments, payCompAssignments, priorServiceRecord, nesaProficient, wsr,svp_timetype, inactiveTimesheets,empJobHistory)

//            def nodeToSerialize = employeeData.PerPerson[0].employmentNav[0].new_incrementProgressionDetails[0]
            def nodeToSerialize = employeeData.PerPerson[0]
            def serializedNode = XmlUtil.serialize(nodeToSerialize)
            println(serializedNode)

            return true
        }
    }

    return false
}

// Read Prior Service Increment
Map readPriorServiceDetails(def employmentRecord) {

    def result = [
            priorServiceDetail: []
    ]

    if (employmentRecord.cust_priorServiceIncrement) {
        // Assuming cust_priorServiceIncrementDetails is a list and iterating over it
        employmentRecord.cust_priorServiceIncrement.cust_priorServiceIncrementDetails.cust_priorServiceIncrementDetails.each { incrementDetail ->
            // For each increment detail,create a new detail map
            def detailMap = [
                    payScaleLevel: incrementDetail.cust_payScaleLevel.text(),
                    lastIncrementDateString: incrementDetail.cust_serviceDate.text(),
                    priorServiceHours: incrementDetail.cust_serviceHours.text() ? new BigDecimal(incrementDetail.cust_serviceHours.text()) : new BigDecimal("0.0"),
                    totalTeachingHours: (incrementDetail.cust_totalteachinghours.text() ?: "0.0").toBigDecimal()
//                    totalTeachingHours: (incrementDetail.cust_totalteachinghours.text() ?: "0.0").toBigDecimal() - (incrementDetail.cust_serviceHours.text() ?: "0.0").toBigDecimal()
            ]

            // Add this detail map to the list under the 'payScaleDetails' key
            result.priorServiceDetail.add(detailMap)
        }
    } else {
//        def defaultMap = [
//                payScaleLevel: null,
//                lastIncrementDateString: employmentRecord.originalStartDate.text(),
//                priorServiceHours: new BigDecimal("0.0"),
//                totalTeachingHours: new BigDecimal("0.0")
//        ]
//        result.priorServiceDetail.add(defaultMap)
    }

    return result

}

def processIncrementProgressionDetails(employeeData, primaryEmploymentRecord, hdaRecord, workScheduleDayModelAssignments, payCompAssignments, priorServiceRecord, nesaStatus, wsr,svp_timetype, inactiveTimesheets,empJobHistory) {

    def grad2Level = 'AUS/30/13/Graduate/Step 2'
    def grad2NextLevel = 'AUS/30/13/Proficient/Step 3'
    def teacherClassification = 'AUS/30'
    def assistPrincipalClassification = 'AUS/34'
    def employmentRecord = primaryEmploymentRecord

    Map resultCalculation = [:]
//    def wsr = employmentRecord.jobInfoNav.EmpJob.workscheduleCode.text()
    def incrementProgressionDetails = primaryEmploymentRecord.cust_incrementProgressionDetails.findAll {
        it.cust_payScaleArea.text() == employmentRecord.jobInfoNav.EmpJob.payScaleArea.text() && it.cust_payScaleGroup.text() == employmentRecord.jobInfoNav.EmpJob.payScaleGroup.text() && it.cust_payScaleType.text() == employmentRecord.jobInfoNav.EmpJob.payScaleType.text()
    }

    if((hdaRecord) && hdaRecord.isHDA.text() == 'true'){

        incrementProgressionDetails = null

        def nominalEmploymentRecord = employeeData.'**'.find {
            it.name() == 'EmpEmployment' && it.userNav.User.isPrimaryAssignment.text().toBoolean()
        }

        if(nominalEmploymentRecord){
            incrementProgressionDetails = nominalEmploymentRecord.cust_incrementProgressionDetails.findAll {
                it.cust_payScaleArea.text() == nominalEmploymentRecord.jobInfoNav.EmpJob.payScaleArea.text() && it.cust_payScaleGroup.text() == nominalEmploymentRecord.jobInfoNav.EmpJob.payScaleGroup.text() && it.cust_payScaleType.text() == nominalEmploymentRecord.jobInfoNav.EmpJob.payScaleType.text()
            }
        }

    }

    def employeeLeaves = employeeData.PerPerson.employmentNav.EmpEmployment.EmployeeTime
    if (inactiveTimesheets.EmployeeTime) {
        employeeLeaves.addAll(inactiveTimesheets.EmployeeTime)
    }

    if(isGeneralClassification(employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text()))
    {
        def svpList = svp_timetype.split(',')
        employeeLeaves = employeeLeaves.findAll { employeeTime ->
            def timeType = employeeTime.timeType.text()
            !svpList.contains(timeType) && !timeType.startsWith('7')
        }

    }


    if (incrementProgressionDetails) {

//        def newIncrementProgressionDetail = employmentRecord[0].appendNode('cust_incrementProgression')
//        newIncrementProgressionDetail.appendNode('externalCode', employmentRecord.userNav.User.userId.text())

        incrementProgressionDetails.each { cust_incrementProgressionDetail ->

            BigDecimal newServiceHours
            BigDecimal targetHours
            if(isGeneralClassification(employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text())){
                targetHours = getCalendarYearDays(priorServiceRecord.lastIncrementDateString)
                resultCalculation = calculateDaysSinceGivenDate(priorServiceRecord.lastIncrementDateString,employeeLeaves, targetHours)
                newServiceHours = resultCalculation.totalDaysBetweenDates
                BigDecimal tempHoldingDaysWeekend = checkHoldingPositionWeekend(employmentRecord)
                newServiceHours = (newServiceHours - tempHoldingDaysWeekend).max(new BigDecimal("0.0"))
            } else{

                def recruitDate = primaryEmploymentRecord.startDate.text()
                //Ticket 11175 - Not calculating from Inactive job info which start date = increment date
                recruitDate = getAdjustedRecruitDate(recruitDate, priorServiceRecord.lastIncrementDateString)

                targetHours = cust_incrementProgressionDetail.cust_targetdays.text() ? cust_incrementProgressionDetail.cust_targetdays.text().toBigDecimal() : 0.0
                BigDecimal priorServiceHours = new BigDecimal(priorServiceRecord.priorServiceHours.toString())
                resultCalculation = calculateWorkingHoursSinceGivenDate(priorServiceRecord, workScheduleDayModelAssignments, wsr, employeeLeaves, targetHours, nesaStatus, recruitDate,empJobHistory)

                BigDecimal totalServiceHours = resultCalculation.totalHoursSinceGivenDate.add(new BigDecimal(priorServiceRecord.priorServiceHours.toString()))
                newServiceHours = totalServiceHours

            }


//            BigDecimal newServiceHours = workingHoursSinceGivenDate.add(new BigDecimal(priorServiceRecord.priorServiceHours.toString()))

            def jobInfoNextPayScaleLevel = employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text()
            def currentPayScaleLevel = employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text()
            def newStartDate = getTodayFormattedDate()

            if(resultCalculation.dateWhenTargetHit){
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                LocalDateTime localDateTime = LocalDateTime.parse(resultCalculation.dateWhenTargetHit, formatter)
                LocalDateTime newDateTime = localDateTime
                //Defect 945 1 year anniversary date for General EE
                if(isGeneralClassification(currentPayScaleLevel)) {
//                   newDateTime = localDateTime.plusDays(1)
                } else{
                    newDateTime = localDateTime.plusDays(1)
                }
                newStartDate = newDateTime.format(formatter)
//                newStartDate = resultCalculation.dateWhenTargetHit
            }

            // Is Grad 2?
            boolean isCurrentGrad2 = currentPayScaleLevel == grad2Level

            // Determine if hours are met to process increment
            if (newServiceHours >= targetHours) {
                if (jobInfoNextPayScaleLevel.trim() || ((jobInfoNextPayScaleLevel == null || jobInfoNextPayScaleLevel.trim().isEmpty()) && isCurrentGrad2 && nesaStatus.status)) {

                    BigDecimal priorServiceTotalHours = priorServiceRecord.totalTeachingHours ?: BigDecimal.ZERO
                    BigDecimal calculatedTotalHours = resultCalculation.totalHoursSinceGivenDate ?: BigDecimal.ZERO
                    BigDecimal newTotalTeachingHours = priorServiceTotalHours + calculatedTotalHours
//                    BigDecimal newTotalTeachingHours = priorServiceRecord.totalTeachingHours + resultCalculation.totalHoursSinceGivenDate ?: new BigDecimal("0.0")
                    if ((isCurrentGrad2)) {
//                  Defect 819 Chris confirmed start date when target hit-  if ((isCurrentGrad2) || jobInfoNextPayScaleLevel == grad2Level) {
//                  10/12 DF505 Chris confirmed if hours before hitting target, start date should be the date target hit not NESA Date
//                        newStartDate = (nesaStatus?.date) ? nesaStatus.date : getTodayFormattedDate()
                        def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                        def newStartDateLocal = newStartDate ? LocalDate.parse(newStartDate, formatter) : null
                        def nesaStatusDateLocal = nesaStatus?.date ? LocalDate.parse(nesaStatus.date, formatter) : null
//                        newStartDate = (nesaStatusDateLocal) ? (newStartDateLocal && newStartDateLocal >= nesaStatusDateLocal ? newStartDate : nesaStatus.date) : getTodayFormattedDate()
                        newStartDate = resultCalculation.retroNesa ? nesaStatusDateLocal : (nesaStatusDateLocal ? (newStartDateLocal && newStartDateLocal >= nesaStatusDateLocal ? newStartDate : nesaStatus.date) : getTodayFormattedDate())
                        //Defect 11103 LocalDate causes issue with HDA script
                        newStartDate = checkDateFormat(newStartDate)
//                        newStartDate = (nesaStatus?.date) ? (newStartDate && newStartDate >= nesaStatus.date ? newStartDate : nesaStatus.date) : getTodayFormattedDate()
//                        newTotalTeachingHours = priorServiceRecord.totalTeachingHours + (newServiceHours-targetHours)
//                        newTotalTeachingHours = priorServiceRecord.totalTeachingHours + (calculatedTotalHours - resultCalculation.remainingHoursAfterHittingTarget) 12/12/2024
                        if(resultCalculation.retroNesa){
                            newTotalTeachingHours = priorServiceRecord.totalTeachingHours + resultCalculation.profHoursCarryOver
                            newServiceHours = new BigDecimal("0.0")
                        } else {
                            newTotalTeachingHours = priorServiceRecord.totalTeachingHours + (calculatedTotalHours - resultCalculation.profHoursCarryOver)
                            newServiceHours = resultCalculation.profHoursCarryOver ?: new BigDecimal("0.0")
                        }
//                        jobInfoNextPayScaleLevel = grad2NextLevel
                    } else if(jobInfoNextPayScaleLevel == grad2Level){ // Defect 819
//                        newTotalTeachingHours = priorServiceRecord.totalTeachingHours + (newServiceHours-targetHours)
                        newTotalTeachingHours = priorServiceRecord.totalTeachingHours + (targetHours - priorServiceRecord.priorServiceHours)
                        newServiceHours = newServiceHours-targetHours
                    } else if(isTeacherClassification(jobInfoNextPayScaleLevel)){
                        newServiceHours = newServiceHours-targetHours
                        // Defect 820 - Chris confirmed, start date set when target hours hit
                        newTotalTeachingHours = priorServiceRecord.totalTeachingHours + (targetHours - priorServiceRecord.priorServiceHours)
                    }


                    def newPayScaleLevel = isCurrentGrad2 ? grad2NextLevel : employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text()
                    processJobInfoToNextLevel(employmentRecord, cust_incrementProgressionDetail.cust_payScaleLevel.text(), isCurrentGrad2, newPayScaleLevel, newStartDate)
                    processCompensationToNextLevel(employmentRecord, payCompAssignments, newPayScaleLevel, newStartDate)

                    def isHda = ((hdaRecord) && hdaRecord.isHDA.text() == 'true')
                    if(isHda){
                        processJobInfoToNextLevel(hdaRecord, cust_incrementProgressionDetail.cust_payScaleLevel.text(), isCurrentGrad2, newPayScaleLevel, newStartDate)
                        processCompensationToNextLevel(hdaRecord, payCompAssignments, newPayScaleLevel, newStartDate)
                    }

                    //Defect 859 - Chris confirm start date set when target hours hit
                    if(isGeneralClassification(newPayScaleLevel)){
                        newServiceHours = resultCalculation.remainingDaysAfterHittingTarget
                    }
                    processIncrementProgressionToNextLevel(employeeData, employmentRecord, cust_incrementProgressionDetail, isCurrentGrad2, nesaStatus, newPayScaleLevel, newStartDate,newServiceHours,newTotalTeachingHours)

                    if(!jobInfoNextPayScaleLevel.trim().isEmpty()) {
                        if (isTeacherClassification(jobInfoNextPayScaleLevel)) {
                            newServiceHours = resultCalculation.profHoursCarryOver ?: new BigDecimal("0.0")
                        }
                    }
                    def newNode = createPriorServiceIncrementDetailsNode(employmentRecord, newServiceHours, targetHours, isCurrentGrad2, newStartDate, newPayScaleLevel, newTotalTeachingHours)
                    addPriorServiceIncrementDetailsNode(employeeData, newNode)

                } else {
                    updateHoursIncrementProgressionDetails(employeeData, cust_incrementProgressionDetail, priorServiceRecord, newServiceHours)
                }
            } else {
                //Create initial record
                updateHoursIncrementProgressionDetails(employeeData, cust_incrementProgressionDetail, priorServiceRecord, newServiceHours)
            }
        }
    } else {

        def newNode = createNewIncrementProgressionDetails(employeeData, employmentRecord, workScheduleDayModelAssignments, priorServiceRecord, nesaStatus,empJobHistory)
        def newParentNode = employeeData.PerPerson.employmentNav[0].'new_incrementProgressionDetails'
        //Check in case new record already added to be created. In this case, update the new payload if primary and concurent are the same class level
        if (newParentNode) {

            def matchingIncrementProgressionDetails = null
            //check existing nodes
            newParentNode[0].'cust_incrementProgressionDetails'.each { node ->
                if (node.'cust_payScaleLevel'.text() == newNode.cust_payScaleLevel.text()) {
                    matchingIncrementProgressionDetails = node
                    return false // Break the loop once a match is found
                }
            }

            if (matchingIncrementProgressionDetails != null) {
                if(!isGeneralClassification(employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text())) {
                    def primaryServiceHours = matchingIncrementProgressionDetails.new_servicesHours.text() ? matchingIncrementProgressionDetails.new_servicesHours.text().toBigDecimal() : 0.0
                    def concurrentServiceHours = newNode.new_servicesHours.text() ? newNode.new_servicesHours.text().toBigDecimal() : 0.0
                    matchingIncrementProgressionDetails.'new_servicesHours'[0].value = primaryServiceHours + concurrentServiceHours
                }
            } else {
                newParentNode[0].children().add(newNode)
            }
        }

        //Create new increment progression detail record
        if (!newParentNode) {
            // If the new parent node does not exist, create it
            newParentNode = employeeData.PerPerson.employmentNav[0].appendNode('new_incrementProgressionDetails')
            newParentNode.append(newNode)
        }


    }
}

def addPriorServiceIncrementDetailsNode(employeeData, newPriorServiceIncrementNode) {

    //Find newly created Prior Service Increment parent node
    def newParentNode = employeeData.PerPerson.employmentNav[0].'new_PriorServiceIncrementDetails'

    if (newParentNode) {
        // Search for an existing 'cust_priorServiceIncrementDetails' with the same 'cust_payScaleLevel'
        def existingNode = newParentNode.'cust_priorServiceIncrementDetails'.find { node ->
            node.'cust_payScaleLevel'.text() == newPriorServiceIncrementNode.cust_payScaleLevel.text()
        }

        if (existingNode) {
//            BigDecimal currentHours = existingNode.'new_serviceHours'.text().toBigDecimal()
//            BigDecimal additionalHours = newPriorServiceIncrementNode.'new_serviceHours'.text().toBigDecimal()
//            existingNode.'new_serviceHours'[0].value = (currentHours + additionalHours).toString()
        } else {
            newParentNode[0].children().add(newPriorServiceIncrementNode)
        }

    } else {
        newParentNode = employeeData.PerPerson.employmentNav[0].appendNode('new_PriorServiceIncrementDetails')
        newParentNode.append(newPriorServiceIncrementNode)
    }

}

def getTargetHours(payScaleArea) {

    def counsellorTargetHours = 1976
    def generalTargetDays = 1.0
    def teacherTargetHours = 1542.8
    def teacherClassification = 'AUS/30'
//    def generalClassification = 'AUS/60'
    def counsellorClassification = 'AUS/20'

    if (payScaleArea == teacherClassification) {
        return teacherTargetHours
    } else if (payScaleArea == counsellorClassification){
        return counsellorTargetHours
    } else {
        return generalTargetDays
    }

}


def createNewIncrementProgressionDetails(employeeData, employmentRecord, workScheduleDayModelAssignments, priorServiceRecord, nesaStatus, empJobHistory) {

    def grad2Level = 'AUS/30/13/Graduate/Step 2'
    def grad2NextLevel = 'AUS/30/13/Proficient/Step 3'

//    def newIncrementProgression = employmentRecord.appendNode('cust_incrementProgression')
//    newIncrementProgression.appendNode('externalCode', employmentRecord.userNav.User.userId.text())
//    def wsr = employmentRecord.jobInfoNav.EmpJob.workscheduleCode.text()
    def wsr = employmentRecord.jobInfoNav.EmpJob.WorkSchedule
    def employeeLeaves = employeeData.PerPerson.employmentNav.EmpEmployment.EmployeeTime
    Map resultCalculation = [:]

    String newServiceHours
    if(isGeneralClassification(employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text())){
        resultCalculation = calculateDaysSinceGivenDate(priorServiceRecord.lastIncrementDateString,employeeLeaves, 0.0 )
        newServiceDays = resultCalculation.totalDaysBetweenDates

        BigDecimal tempHoldingDaysWeekend = checkHoldingPositionWeekend(employmentRecord)
        newServiceDays =(newServiceDays - tempHoldingDaysWeekend).max(new BigDecimal("0.0"))
        newServiceHours = newServiceDays.intValue() == 1 ? newServiceDays.intValue() + " day" : newServiceDays.intValue() + " days"
    } else {
        def recruitDate = employmentRecord.startDate.text()
        //Ticket 11175 - Not calculating from Inactive job info which start date = increment date
        recruitDate = getAdjustedRecruitDate(recruitDate, priorServiceRecord.lastIncrementDateString)

        priorServiceRecord.priorServiceHours = 0.0
        resultCalculation = calculateWorkingHoursSinceGivenDate(priorServiceRecord, workScheduleDayModelAssignments, wsr, employeeLeaves, 0.0, nesaStatus, recruitDate, empJobHistory)
        BigDecimal totalServiceHours = resultCalculation.totalHoursSinceGivenDate.add(new BigDecimal(priorServiceRecord.priorServiceHours.toString()))
        newServiceHours = totalServiceHours
    }

    def newNode = new groovy.util.Node(null, 'cust_incrementProgressionDetails')

    //DF459 - cust_incrementProgression_externalCode should be using userId rather than empId
    newNode.appendNode('cust_incrementProgression_externalCode', employmentRecord.userNav.User.userId.text())
    newNode.appendNode('externalCode', generateExternalCode(employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text()))
//    newNode.appendNode('externalCode', employmentRecord.userNav.User.userId.text())
    newNode.appendNode('cust_payScaleArea', employmentRecord.jobInfoNav.EmpJob.payScaleArea.text())
    newNode.appendNode('cust_payScaleGroup', employmentRecord.jobInfoNav.EmpJob.payScaleGroup.text())
    newNode.appendNode('cust_payScaleType', employmentRecord.jobInfoNav.EmpJob.payScaleType.text())
    newNode.appendNode('cust_payScaleLevel', employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text())
    newNode.appendNode('cust_servicedays', '0')
    newNode.appendNode('new_servicesHours', newServiceHours)
    newNode.appendNode('cust_lastmodified', processOnDateTime())

    if(isGeneralClassification(employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text())){
        newNode.appendNode('cust_targetdays', '1 Year')
    } else{
        newNode.appendNode('cust_targetdays', getTargetHours(employmentRecord.jobInfoNav.EmpJob.payScaleArea.text()))
    }

    def currentPayScaleLevel = employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text()
    def newStartDate = getTodayFormattedDate()

    // Is Grad 2?
    boolean isCurrentGrad2 = currentPayScaleLevel == grad2Level

    if (isCurrentGrad2) {
        newNode.appendNode('cust_classificationtomovetoPSG', grad2NextLevel.split('/')[0..3].join('/'))
        newNode.appendNode('cust_classificationtomovetoPSL', grad2NextLevel)
        if (nesaStatus.status) {
            newNode.appendNode('cust_requirements', 'NESA Proficiency (Completed)')
        } else {
            newNode.appendNode('cust_requirements', 'NESA Proficiency')
        }
    } else if ((employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text() != null && !employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text().trim().isEmpty()) && !isCurrentGrad2) {
        newNode.appendNode('cust_classificationtomovetoPSG', employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text().split('/')[0..3].join('/'))
        newNode.appendNode('cust_classificationtomovetoPSL', employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text())
        newNode.appendNode('cust_requirements', '')
    }

    if (isTeacherClassification(currentPayScaleLevel)) {
        def totalTeachingHours = priorServiceRecord.totalTeachingHours +  new BigDecimal(newServiceHours)
        newNode.appendNode('cust_totalteachinghours', totalTeachingHours)
    }

    return newNode
}

def processJobInfoToNextLevel(employmentRecord, payScaleLevel, isCurrentGrad2, newPayScaleLevel, newStartDate) {

//    def grad2Level = 'AUS/30/13/Graduate/Step 2'
//    def grad2NextLevel = 'AUS/30/13/Proficient/Step 3'
//    def proficientNextLevelClassification = 'AUS/30/13/Proficient/Step 4'
//    def teacherTargetHours = 1542.8
//    def teacherClassification = 'AUS/30'


    employmentRecord.jobInfoNav.EmpJob.findAll {
        payScaleLevel == it.payScaleLevel.text()
//        cust_incrementProgressionDetail.cust_payScaleArea.text() == it.payScaleArea.text() &&
//                cust_incrementProgressionDetail.cust_payScaleGroup.text() == it.payScaleGroup.text() &&
//                cust_incrementProgressionDetail.cust_payScaleType.text() == it.payScaleType.text()
    }.each { jobInfo ->

        //Check if currently Grad 2
        if (isCurrentGrad2) {
            jobInfo.appendNode('new_psGroup', newPayScaleLevel.split('/')[0..3].join('/'))
        }

        jobInfo.appendNode('new_psLevel', newPayScaleLevel)
        jobInfo.appendNode('new_event', '554')
        jobInfo.appendNode('new_eventReason', 'reclServ')
        jobInfo.appendNode('new_startDate', newStartDate)

    }

}

def processCompensationToNextLevel(employmentRecord, payCompAssignments, newPayScaleLevel, newStartDate) {
    def empCompensationNode = employmentRecord.compInfoNav.EmpCompensation

    if (empCompensationNode) {
        empCompensationNode.each { empCompensation ->
            empCompensation.appendNode('new_eventReason', 'reclServ')
            empCompensation.appendNode('new_startDate', newStartDate)


            // Create NewEmpCompensationRecurring node
            def newEmpCompRecurrings = empCompensation.appendNode('NewEmpCompensationRecurrings')

            empCompensation.empPayCompRecurringNav.EmpPayCompRecurring.each { empPayCompRecurring ->
                def payComponentValue = empPayCompRecurring.payComponent.text()

                payCompAssignments.each { payScalePayComponent ->
                    def payScaleComponentCode = payScalePayComponent.'code'.text()
                    def payScaleLevelCode = payScalePayComponent.'PayScaleLevel_code'.text()

                    if (payScaleComponentCode == payComponentValue && payScaleLevelCode == newPayScaleLevel) {
                        def amount = payScalePayComponent.'*'.find { it.name() == 'amount' }?.text()
                        def frequency = payScalePayComponent.'*'.find { it.name() == 'frequency' }?.text()
                        def currency = payScalePayComponent.'*'.find { it.name() == 'currency' }?.text()

                        if (amount && frequency) {
                            // Append NewEmpCompensationRecurring nodes inside NewEmpCompensationRecurrings
                            def newEmpCompRecurring = newEmpCompRecurrings.appendNode('NewEmpCompensationRecurring')
                            newEmpCompRecurring.appendNode('paycompvalue', amount)
                            newEmpCompRecurring.appendNode('payComponent', payComponentValue)
                            newEmpCompRecurring.appendNode('frequency', frequency)
                            newEmpCompRecurring.appendNode('currencyCode', currency)
                            newEmpCompRecurring.appendNode('startDate', newStartDate)
                        }
                    }
                }

            }
        }
    }
}

def processIncrementProgressionToNextLevel(employeeData, employmentRecord, cust_incrementProgressionDetail, isCurrentGrad2, nesaStatus, newPayScaleLevel, newStartDate, newServiceHours, totalTeachingHours) {

    def grad2Level = 'AUS/30/13/Graduate/Step 2'
    def grad2NextLevel = 'AUS/30/13/Proficient/Step 3'
    def proficientNextLevelClassification = 'AUS/30/13/Proficient/Step 4'
    def currentPayScalelevel = employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.code.text()

    //Initialise service hours & move to next level
    if (isCurrentGrad2) {
        cust_incrementProgressionDetail.appendNode('new_servicesHours', 0)
        cust_incrementProgressionDetail.'**'.findAll { it.name() == 'cust_payScaleLevel' }.each {
            it.replaceNode {
                cust_payScaleLevel(grad2NextLevel)
            }
        }

        cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_payScaleGroup' }.each {
            it.replaceNode {
                cust_payScaleGroup(grad2NextLevel.split('/')[0..3].join('/'))
            }
        }

        cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_requirements' }.each {
            it.replaceNode {
                cust_requirements('')
            }
        }
//        cust_incrementProgressionDetail.appendNode('cust_totalteachinghours', newServiceHours + totalTeachingHours)
    } else {
        cust_incrementProgressionDetail.'**'.findAll { it.name() == 'cust_payScaleLevel' }.each {
            it.replaceNode {
                cust_payScaleLevel(newPayScaleLevel)
            }
        }

        if (newPayScaleLevel == grad2Level) {
            cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_requirements' }.each {
                if (nesaStatus.status) {
                    it.replaceNode {
                        cust_requirements('NESA Proficient (Completed)')
                    }
                } else {
                    it.replaceNode {
                        cust_requirements('NESA Proficient')
                    }
                }
            }
//            cust_incrementProgressionDetail.appendNode('cust_totalteachinghours', newServiceHours + totalTeachingHours)
        } else {
            cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_requirements' }.each {
                it.replaceNode {
                    cust_requirements('')
                }
            }
        }
//        cust_incrementProgressionDetail.appendNode('new_servicesHours', newServiceHours - cust_incrementProgressionDetail.cust_targetdays.text().toBigDecimal())
        cust_incrementProgressionDetail.appendNode('new_servicesHours', newServiceHours)
    }

    if (isTeacherClassification(newPayScaleLevel)) {
        def existingNode = cust_incrementProgressionDetail.'cust_totalteachinghours'
        // Check if the node exists (and has at least one element)
        if(existingNode && existingNode.size() > 0) {
            // Update the existing node's value. Here, we assume that the value is stored as a string.
            existingNode[0].value = newServiceHours + totalTeachingHours
        } else {
            // Node doesn't exist, so append it with the computed value.
            cust_incrementProgressionDetail.appendNode('cust_totalteachinghours', newServiceHours + totalTeachingHours)
        }
//        cust_incrementProgressionDetail.appendNode('cust_totalteachinghours', newServiceHours + totalTeachingHours)
//        Defect 820 - Chris confirmed start adte of next level on when target hours hit
//        cust_incrementProgressionDetail.appendNode('cust_totalteachinghours', totalTeachingHours)
    }

    //update next pay scale level & group
    def newNextPayScaleLevel = employmentRecord.jobInfoNav.EmpJob.payScaleLevelNav.PayScaleLevel.PayScaleLevel.nextPayScaleLevel.text()
    cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_classificationtomovetoPSL' }.each {
        it.replaceNode {
            if (newNextPayScaleLevel.trim()) {
                cust_classificationtomovetoPSL(newNextPayScaleLevel)
            } else {
                if (isCurrentGrad2 && nesaStatus.status) {
                    cust_classificationtomovetoPSL(proficientNextLevelClassification)
                } else if (newPayScaleLevel == grad2Level) {
                    cust_classificationtomovetoPSL(grad2NextLevel)
                } else {
                    cust_classificationtomovetoPSL('')
                }
            }
        }
    }

    cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_classificationtomovetoPSG' }.each {
        it.replaceNode {
            if (newNextPayScaleLevel.trim()) {
                cust_classificationtomovetoPSG(newNextPayScaleLevel.split('/')[0..3].join('/'))
            } else {
                if (isCurrentGrad2 && nesaStatus.status) {
                    cust_classificationtomovetoPSG(proficientNextLevelClassification.split('/')[0..3].join('/'))
                } else if (newPayScaleLevel == grad2Level) {
                    cust_classificationtomovetoPSG(grad2NextLevel.split('/')[0..3].join('/'))
                } else {
                    cust_classificationtomovetoPSG('')
                }
            }
        }
    }

    cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_lastmodified' }.each {
        it.replaceNode {
            cust_lastmodified(processOnDateTime())
        }
    }

    appendIncrementProgressionDetailsNode(employeeData, cust_incrementProgressionDetail)
}

def createPriorServiceIncrementDetailsNode(employmentRecord, newServiceHours, targetHours, isCurrentGrad2, newStartDate, newPayScaleLevel, totalTeachingHours) {
    def grad2NextLevel = 'AUS/30/13/Proficient/Step 3'

    def newNode = new groovy.util.Node(null, 'cust_priorServiceIncrementDetails')
    def newPriorServiceHours = 0
    def payScaleLevel

    if(!isGeneralClassification( employmentRecord.jobInfoNav.EmpJob.payScaleLevel.text())){
        if (isTeacherClassification(newPayScaleLevel)) {
//            newPriorServiceHours = newServiceHours
//            13/05 Defect 820- Chris confirmed increment date for teacher set when target hits. This will result 0 hours carry over.
//            newPriorServiceHours = new BigDecimal("0")
            // Defect 942
            newPriorServiceHours = newServiceHours
        } else{
            newPriorServiceHours = newServiceHours - targetHours
        }
    }

    // Set attributes and child nodes as needed
    newNode.appendNode('cust_serviceDate', newStartDate)
    newNode.appendNode('cust_payScaleArea', employmentRecord.jobInfoNav.EmpJob.payScaleArea.text())

//    if (isCurrentGrad2) {
//        payScaleLevel = grad2NextLevel
//        newPriorServiceHours = new BigDecimal("0")
//    } else {
    payScaleLevel = newPayScaleLevel
//    }

    newNode.appendNode('cust_payScaleGroup', payScaleLevel.split('/')[0..3].join('/'))
    newNode.appendNode('cust_payScaleLevel', payScaleLevel)
    newNode.appendNode('new_serviceHours', newPriorServiceHours.toString())
    newNode.appendNode('cust_payScaleType', employmentRecord.jobInfoNav.EmpJob.payScaleType.text())
    newNode.appendNode('cust_priorServiceIncrement_externalCode', employmentRecord.userNav.User.userId.text())
//    newNode.appendNode('cust_priorServiceIncrement_externalCode', employmentRecord.userNav.User.empId.text())
    newNode.appendNode('externalCode', generateExternalCode(payScaleLevel))

    if (isTeacherClassification(newPayScaleLevel)) {
//        newNode.appendNode('cust_totalteachinghours', totalTeachingHours)
        newNode.appendNode('cust_totalteachinghours', (newServiceHours + totalTeachingHours).toString())
    }

    return newNode
}

def isTeacherClassification(payScaleLevel) {

    def teacherClassification = 'AUS/30'
    def payScaleArea = payScaleLevel.split('/')[0..1].join('/')
    if (teacherClassification == payScaleArea) {
        return true
    }
    return false
}

boolean isGeneralClassification(payScaleLevel){

    def generalClassification = 'AUS/60,AUS/36,AUS/37,AUS/38,AUS/59'
    def payScaleArea = payScaleLevel.split('/')[0..1].join('/')
    def generalClassificationList = generalClassification.split(',')
    if(payScaleArea in generalClassificationList){
        return true
    }
    return false
}


def updateHoursIncrementProgressionDetails(employeeData, cust_incrementProgressionDetail, priorServiceRecord, newServiceHours) {

    if(isGeneralClassification(cust_incrementProgressionDetail.cust_payScaleLevel[0].text() )){
        def newServiceDays = newServiceHours == 1 ? newServiceHours.intValue() + " day" : newServiceHours.intValue() + " days"
        cust_incrementProgressionDetail.appendNode('new_servicesHours', newServiceDays)
    } else{
        cust_incrementProgressionDetail.appendNode('new_servicesHours', newServiceHours)
    }


    if (isTeacherClassification(cust_incrementProgressionDetail.cust_payScaleLevel[0].text() )) {
//        cust_incrementProgressionDetail.appendNode('cust_totalteachinghours', totalTeachingHours + newServiceHours)
        def priorTotalTeachingHours = priorServiceRecord.totalTeachingHours - priorServiceRecord.priorServiceHours
        cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_totalteachinghours' }.each {
            it.replaceNode {
                cust_totalteachinghours(priorTotalTeachingHours + newServiceHours)
            }
        }
    }

    cust_incrementProgressionDetail.'**'.findAll { it instanceof groovy.util.Node && it.name() == 'cust_lastmodified' }.each {
        it.replaceNode {
            cust_lastmodified(processOnDateTime())
        }
    }

    appendIncrementProgressionDetailsNode(employeeData, cust_incrementProgressionDetail)

}

def appendIncrementProgressionDetailsNode(employeeData, cust_incrementProgressionDetail) {
    def payscaleLevel = cust_incrementProgressionDetail.'cust_payScaleLevel'.text()

    def newServicesHours
    if(isGeneralClassification(cust_incrementProgressionDetail.cust_payScaleLevel[0].text() )) {
        newServicesHours = cust_incrementProgressionDetail.'new_servicesHours'.text()
    }else{
        newServicesHours = new BigDecimal(cust_incrementProgressionDetail.'new_servicesHours'.text())
    }

    def newIncrementProgressionDetailNode = new Node(null, cust_incrementProgressionDetail.name(), cust_incrementProgressionDetail.attributes())
    cust_incrementProgressionDetail.children().each { childNode ->
        if (childNode instanceof Node) {
            newIncrementProgressionDetailNode.appendNode(childNode.name(), childNode.attributes(), childNode.text())
        } else {
            // If there are any text nodes (unlikely in this context, but just to be safe)
            newIncrementProgressionDetailNode.setValue(childNode)
        }
    }

    def newParentNode = employeeData.PerPerson.employmentNav[0].'new_incrementProgressionDetails'


    //Check in case new record already added to be created. In this case, update the new payload if primary and concurent are the same class level
    if (newParentNode) {
        def existingNode = newParentNode.'cust_incrementProgressionDetails'.find { custIncrementProgressionDetailNode ->
            custIncrementProgressionDetailNode.'cust_payScaleLevel'.text() == payscaleLevel
        }
        if (existingNode) {
            if (isGeneralClassification(cust_incrementProgressionDetail.cust_payScaleLevel[0].text())) {
                existingNode.'new_servicesHours'[0].value = existingNode.'new_servicesHours'.text()
            } else {
                def existingServicesHours = new BigDecimal(existingNode.'new_servicesHours'.text())
                //             existingNode.'new_servicesHours'[0].value = (existingServicesHours + newServicesHours).toString()
                existingNode.'new_servicesHours'[0].value = (existingServicesHours).toString()
            }
        } else {
            newParentNode[0].children().add(newIncrementProgressionDetailNode)
        }

    } else {
        newParentNode = employeeData.PerPerson.employmentNav[0].appendNode('new_incrementProgressionDetails')
        newParentNode.append(newIncrementProgressionDetailNode)
    }

}

Map getNesaStatus(def employmentRecord) {
    def nesa = [status: false, date: null] // Default response
    if (employmentRecord.cust_NESA) {
        if (employmentRecord.cust_NESA.cust_membershipStatus.text() == '30' && employmentRecord.cust_NESA.cust_accreditationLevel.text() == 'prof') {
            nesa.status = true
            nesa.date = employmentRecord.cust_NESA.cust_ptAchievedDate.text()
        }
    }
    return nesa
}

BigDecimal getCalendarYearDays( String lastIncrementDateString){
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
    Calendar startCal = Calendar.getInstance(TimeZone.getTimeZone("Australia/Sydney"))
    Date startDate = sdf.parse(lastIncrementDateString)
    startCal.setTime(startDate)

    startCal.add(Calendar.YEAR, 1)
    Date endDate = startCal.getTime()

    startCal.setTime(startDate)
    long daysBetween = (endDate.getTime() - startDate.getTime()) / (24 * 60 * 60 * 1000)
    return new BigDecimal(daysBetween)

}

Map calculateDaysSinceGivenDate(String lastIncrementDateString, employeeLeaves, BigDecimal targetHoursDays) {


    Map result = [
            totalDaysBetweenDates: new BigDecimal("0.0"),
            remainingDaysAfterHittingTarget: new BigDecimal("0.0"),
            dateWhenTargetHit: null
    ]

    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    def timeZone = ZoneId.of("Australia/Sydney")
    LocalDateTime startLocalDateTime = LocalDateTime.parse(lastIncrementDateString, dateTimeFormatter)
    LocalDate startLocalDate = startLocalDateTime.toLocalDate()
    LocalDate today = LocalDate.now(timeZone)

    BigDecimal diffDays = 0

    if (employeeLeaves == null || employeeLeaves.isEmpty()) {
        diffDays = new BigDecimal(ChronoUnit.DAYS.between(startLocalDate, today) + 1)
        BigDecimal remainingDaysAfterTarget = diffDays.compareTo(new BigDecimal(targetHoursDays)) > 0 ? diffDays.subtract(new BigDecimal(targetHoursDays)) : BigDecimal.ZERO
        long targetHoursDaysLong = targetHoursDays.longValue()
        LocalDate dateWhenTargetHit = startLocalDate.plusDays(targetHoursDaysLong)

        String formattedDateWhenTargetHit = dateWhenTargetHit.format(DateTimeFormatter.ISO_LOCAL_DATE) + 'T00:00:00.000'

        result.totalDaysBetweenDates = diffDays
        result.remainingDaysAfterHittingTarget = remainingDaysAfterTarget
        result.dateWhenTargetHit = formattedDateWhenTargetHit

    } else {
        LocalDate date = startLocalDate.plusDays(1)
        while (!date.isAfter(today)) {
            boolean isWithinPeriod = employeeLeaves.any { node ->
                LocalDate periodStart = LocalDateTime.parse(node.startDate.text(), dateTimeFormatter).toLocalDate()
                LocalDate periodEnd = LocalDateTime.parse(node.endDate.text(), dateTimeFormatter).toLocalDate()

                !(date.isBefore(periodStart) || date.isAfter(periodEnd))
            }

            if (!isWithinPeriod) {
                diffDays++
                println "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} - $diffDays"
            } else {
                println "$isWithinPeriod - ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
            }

            if((targetHoursDays != 0.0 && result.dateWhenTargetHit == null) && diffDays >= targetHoursDays){
                println "Increment date ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} at $diffDays days"

                String formattedDateWhenTargetHit = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + 'T00:00:00.000'
                result.dateWhenTargetHit = formattedDateWhenTargetHit
            }

            date = date.plusDays(1)
        }
    }

    result.totalDaysBetweenDates = diffDays

    println "Results: $result"
    println "Total days $diffDays"
//    return new BigDecimal(diffDays)
    return result

}

// Calculate working hours since a given date
Map calculateWorkingHoursSinceGivenDate(priorServiceRecord, workScheduleDayModelAssignments, wsr, employeeLeaves, targetHours, nesaStatus, recruitDate,empJobHistory) {

    def timeZone = ZoneId.of("Australia/Sydney")
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    def grad2Level = 'AUS/30/13/Graduate/Step 2'
    BigDecimal priorServiceHours = new BigDecimal(priorServiceRecord.priorServiceHours.toString())

    Map result = [
            totalHoursSinceGivenDate: new BigDecimal("0.0"),
            totalTimesheetHours: new BigDecimal("0.0"),
            remainingHoursAfterHittingTarget: new BigDecimal("0.0"),
            dateWhenTargetHit: null,
            profHoursCarryOver: new BigDecimal("0.0"),
            retroNesa: null
    ]

    def givenStartDate = ((LocalDateTime.parse(priorServiceRecord.lastIncrementDateString, dateTimeFormatter)).isAfter(LocalDateTime.parse(recruitDate, dateTimeFormatter))) ? LocalDateTime.parse(priorServiceRecord.lastIncrementDateString, dateTimeFormatter) : LocalDateTime.parse(recruitDate, dateTimeFormatter)
    BigDecimal workingHoursSinceGivenDate = new BigDecimal("0.0")
    LocalDateTime startLocalDateTime = givenStartDate
//    LocalDateTime startLocalDateTime = LocalDateTime.parse(priorServiceRecord.lastIncrementDateString, dateTimeFormatter)
    LocalDate startLocalDate = startLocalDateTime.toLocalDate()
    LocalDate today = LocalDate.now(timeZone)
    //Ticket 11576 - DF1160 Michelle said calculate from start date of new increment
    LocalDate date = startLocalDate
    //    LocalDate date = startLocalDate.plusDays(1)
    LocalDate wsrStartingDate = LocalDateTime.parse(wsr.startingDate.text(), dateTimeFormatter).toLocalDate();
    def currentPayScaleLevel = priorServiceRecord.payScaleLevel
    boolean isCurrentGrad2 = currentPayScaleLevel == grad2Level
    LocalDate recruitDateLocalDate = LocalDateTime.parse(recruitDate, dateTimeFormatter).toLocalDate()

    LocalDate profLocalDate
    if(nesaStatus.status){
        profLocalDate = LocalDateTime.parse(nesaStatus.date, dateTimeFormatter).toLocalDate();
    }

    def dateTargetHitLocalDate = null

    while (!date.isAfter(today)) {

        if(result.dateWhenTargetHit && dateTargetHitLocalDate == null){
            dateTargetHitLocalDate = LocalDateTime.parse(result.dateWhenTargetHit,DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
        }

        // Change Partial Leaves: handle full day vs partial day leave
        BigDecimal leaveHoursForDay = BigDecimal.ZERO
        boolean fullDayLeave = false

        //boolean doNotCount = false
        //if(employeeLeaves){
            //doNotCount = isDateWithinLeaveRecords(employeeLeaves,date)
            println "-----------------------------------"
            //println "Leave period do not count - $doNotCount - $date"
        //}
        //if(!doNotCount || (dateTargetHitLocalDate != null && today.isEqual(dateTargetHitLocalDate))) {
            //Find workschedule from job history

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
            def matchedAllWsr = empJobHistory.EmpJobHistory.findAll { job ->
                LocalDate startDate = LocalDate.parse(job.startDate.text(), formatter)
                LocalDate endDate = LocalDate.parse(job.endDate.text(), formatter)
//                return (date.isEqual(startDate) || date.isAfter(startDate)) && date.isBefore(endDate.plusDays(1))

                // Check if the job is within the date range
                boolean isWithinDateRange = (date.isEqual(startDate) || date.isAfter(startDate)) && date.isBefore(endDate.plusDays(1))

                // Check if payscalelevel matches currentPayScaleLevel
                boolean isPayScaleLevelMatch = job.payScaleLevel.text() == currentPayScaleLevel

                // Check if workschedule is null, empty, or blank
                boolean isWorkScheduleValid = job.workscheduleCode?.text()?.trim()

                return isWithinDateRange && isPayScaleLevelMatch && isWorkScheduleValid
            }

            // First, check for jobs that do NOT have division == 9999999
            // Defect 10541 - Include end of contract
//            def validJobHistories = matchedAllWsr.findAll { job -> job.eventReason.text() != "tempHold" }

            def validJobHistories = matchedAllWsr.findAll { job ->
                job.eventReason?.text() != "tempHold" &&
                        job.emplStatusNav?.PicklistOption?.externalCode?.text() != "T"
            }
//            if (!validJobHistory) {
//                validJobHistory = matchedAllWsr.find()
//            }
//            def matchedWsr = validJobHistory?.WorkSchedule

            if (!validJobHistories) {
                validJobHistories = matchedAllWsr ? [matchedAllWsr[0]] : []
            }

            def allWsr = validJobHistories.collect { it.WorkSchedule }

            boolean counted = false

            double dailyWorkingHoursDouble = 0.0

            double dailyWorkingHours
            for (currentWsr in allWsr) {
                if (counted) {
                    break // Exit the loop if counted is true
                }
                int numberOfDayModel = countMatchingWorkScheduleDayModels(workScheduleDayModelAssignments, currentWsr)
                int currentDayOfWeek = getDayOfWeek(wsrStartingDate, date, numberOfDayModel)
                def calculatedResult = calculateTotalWorkingHoursForDay(workScheduleDayModelAssignments, currentDayOfWeek, currentWsr)
                dailyWorkingHours = calculatedResult.totalWorkingHours
                dailyWorkingHoursDouble = calculatedResult.totalWorkingHours
                counted = calculatedResult.counted
                //Commented - 8-Sep-25 - Karthik
                //workingHoursSinceGivenDate = workingHoursSinceGivenDate.add(new BigDecimal(dailyWorkingHours.toString()))

            }
            //Old
//            if(!(targetHours == null || targetHours == 0.0 || result.dateWhenTargetHit != null) && (workingHoursSinceGivenDate + priorServiceHours) >= targetHours){
//                println "Increment date ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} at $workingHoursSinceGivenDate Hours"
//
//                String formattedDateWhenTargetHit = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + 'T00:00:00.000'
//                result.dateWhenTargetHit = formattedDateWhenTargetHit
//            }
            //New
//            if(targetHours != null && targetHours != 0.0 && result.dateWhenTargetHit == null) {
//                BigDecimal totalHours = workingHoursSinceGivenDate.add(priorServiceHours)
//                println "Total Hours added - $totalHours"
//
//                if(totalHours.compareTo(targetHours) >= 0) { // If total hours equal or exceed targetHours
//                    // Calculate the moment the target is hit or exceeded
//                    BigDecimal excessHours = totalHours.subtract(targetHours)
//
//                    // Store the date when the target is hit
//                    String formattedDateWhenTargetHit = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + 'T00:00:00.000'
//                    result.dateWhenTargetHit = formattedDateWhenTargetHit
//
//                    // Print the information
//                    println "Target hit on ${formattedDateWhenTargetHit} with excess hours: ${excessHours} (Total: ${totalHours} hours)"
//
//                    // Additionally, if you want to keep track of this excess for later use, you can add it to your result map
//                    result.profHoursCarryOver = excessHours
//                }
//            }

        //8Sep25 - Partial Leave Case (Karthik)
        //boolean fullDayLeave = false
        //BigDecimal leaveHoursForDay = BigDecimal.ZERO

        BigDecimal plannedHoursForDay = new BigDecimal(dailyWorkingHoursDouble.toString())

        if (employeeLeaves) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

            //Full-day leave if date is inside any multi-day leave (timeType not starting with '7')
            fullDayLeave = employeeLeaves.any { node ->
                def tt = node.timeType.text()
                if (!tt.startsWith('7')) {
                    LocalDate s = LocalDate.parse(node.startDate.text(), fmt)
                    LocalDate e = LocalDate.parse(node.endDate.text(), fmt)
                    // multi-day leave covers the date → treat as full-day, unless we are exactly on target-hit confirmation day
                    if (!date.isBefore(s) && !date.isAfter(e)) {
                        boolean multiDay = !s.equals(e)
                        if (multiDay) {
                            return true
                        }
                    }
                }
                return false
            }

            //Accumulate partial day leave hours (single day leave entries with quantityInHours on this date)
            employeeLeaves.each { node ->
                def tt = node.timeType.text()
                if (!tt.startsWith('7')) {
                    LocalDate s = LocalDate.parse(node.startDate.text(), fmt)
                    LocalDate e = LocalDate.parse(node.endDate.text(), fmt)
                    if (s.equals(e) && s.equals(date)) {
                        def qty = node.quantityInHours?.text()
                        if (qty && qty.trim()) {
                            try {
                                leaveHoursForDay = leaveHoursForDay.add(new BigDecimal(qty.trim()))
                            } catch (ignored) {

                            }
                        }
                    }
                }
            }
        }

        //If it is the exact target-hit day check, allow the loop to run the hour logic
        boolean allowOnTargetCheckDay = (dateTargetHitLocalDate != null && today.isEqual(dateTargetHitLocalDate))

        //3) Only add planned hours when:
        //not full-day leave, OR
        //we are on the target-check day
        if (!fullDayLeave || allowOnTargetCheckDay) {
            //Subtract partial-day leave hours from planned, floor at 0
            BigDecimal dailyPlannedMinusLeave = plannedHoursForDay
            if (leaveHoursForDay.signum() > 0) {
                dailyPlannedMinusLeave = dailyPlannedMinusLeave.subtract(leaveHoursForDay).max(BigDecimal.ZERO)
            }

            //Add to running total the planned (minus partial leave) hours
            workingHoursSinceGivenDate = workingHoursSinceGivenDate.add(dailyPlannedMinusLeave)
            println "Planned from WSR: $plannedHoursForDay, partial-leave: $leaveHoursForDay, counted: $dailyPlannedMinusLeave on $date"

            //Attendance override (timeType starts with '7') still applies
            BigDecimal timesheetHours = addTimesheetEntries(employeeLeaves,date)
            if (timesheetHours.signum() > 0) {
                println "Total timesheet hours added - $timesheetHours"
            }
            workingHoursSinceGivenDate = workingHoursSinceGivenDate.add(timesheetHours)
        } else {
            println "Full-day leave on $date → no planned hours added (attendance entries may still add hours)."
            // Even on full-day leave, attendance entries still add hours
            BigDecimal timesheetHours = addTimesheetEntries(employeeLeaves,date)
            if (timesheetHours.signum() > 0) {
                println "Total timesheet hours added on full-day leave - $timesheetHours"
            }
            workingHoursSinceGivenDate = workingHoursSinceGivenDate.add(timesheetHours)
        }
        //=======================
        //End - Sep8 - Partial Leave Hours Case (Karthik)

            BigDecimal totalHours = workingHoursSinceGivenDate.add(priorServiceHours)
//            println "Total Hours added - $totalHours"
            BigDecimal timesheetHours = addTimesheetEntries(employeeLeaves,date)
            totalHours = totalHours.add(timesheetHours)
            workingHoursSinceGivenDate = workingHoursSinceGivenDate.add(timesheetHours)
            println "Total timesheet hours added - $timesheetHours"
            println "Total Hours added - $totalHours"

            if(nesaStatus.status && isCurrentGrad2 && targetHours != null && targetHours.signum() != 0 && result.dateWhenTargetHit == null && totalHours.compareTo(targetHours) >= 0 && (profLocalDate != null && (date.isEqual(profLocalDate) || date.isAfter(profLocalDate)))) {

                BigDecimal excessHours = totalHours.subtract(targetHours)
                if(dailyWorkingHours == 0){
                    dailyWorkingHours = timesheetHours
                }
                if(dailyWorkingHours > 0 && excessHours < dailyWorkingHours) {

                    String formattedDateWhenTargetHit = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + 'T00:00:00.000'
                    result.dateWhenTargetHit = formattedDateWhenTargetHit
                    result.profHoursCarryOver = excessHours
                    println "Target hit on ${formattedDateWhenTargetHit} with excess hours: ${excessHours} (Total: ${totalHours} hours)"
                } else if (recruitDateLocalDate.isEqual(date.minusDays(1))){
                    result.dateWhenTargetHit = nesaStatus.date
                    result.profHoursCarryOver = excessHours
                    println "Target hit on before data migration date ${recruitDate} with excess hours: ${excessHours} (Total: ${totalHours} hours)"
                    println "Service hours met target hours before data migration date - Potential Error"
                }
            } else if (!(targetHours == null || targetHours.signum() == 0) && result.dateWhenTargetHit == null && totalHours.compareTo(targetHours) >= 0) {
//            } else if (!nesaStatus.status && !(targetHours == null || targetHours.signum() == 0) && result.dateWhenTargetHit == null && totalHours.compareTo(targetHours) >= 0) {
                // Original logic for when testStatus is false
                println "Increment date ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} at $workingHoursSinceGivenDate Hours"
                String formattedDateWhenTargetHit = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + 'T00:00:00.000'
                result.dateWhenTargetHit = formattedDateWhenTargetHit
                //DF505 if service hours already exceed target then use pt achieve date
                if(isCurrentGrad2 && nesaStatus.status){
                    if(profLocalDate != null && (date.isEqual(profLocalDate) || date.isBefore(profLocalDate))) {
                        result.dateWhenTargetHit = nesaStatus.date
                        println "Service hours met target hours before data migration date - Potential Error"
                        println "Service hours already exceed target at pt achieve date. Possible retro entry for NESA"
                        result.retroNesa = true

                    }
                }
                BigDecimal excessHours = totalHours.subtract(targetHours)
                if(dailyWorkingHours == 0){
                    dailyWorkingHours = timesheetHours
                }
                if(dailyWorkingHours > 0 && excessHours < dailyWorkingHours) {
                    result.profHoursCarryOver = excessHours
                }
                println "Target hit on ${result.dateWhenTargetHit} with excess hours: ${excessHours} (Total: ${totalHours} hours)"
                // Original logic might not need to calculate or print excess hours explicitly
            } else if(nesaStatus.status && isCurrentGrad2 && targetHours != null && targetHours.signum() != 0 && result.retroNesa &&  (dateTargetHitLocalDate != null && date.isEqual(dateTargetHitLocalDate)) && (profLocalDate != null && (date.isEqual(profLocalDate) || date.isAfter(profLocalDate)))){
                result.profHoursCarryOver = excessHours = totalHours - priorServiceRecord.priorServiceHours
                println "Increment date ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} at $workingHoursSinceGivenDate Hours"
                println "Service hours already exceed target at pt achieve date. Possible retro entry for NESA with excess hours: ${excessHours} (Total: ${totalHours} hours)"
            }
        //}
        date = date.plusDays(1);
    }

    result.remainingHoursAfterHittingTarget = (workingHoursSinceGivenDate.add(priorServiceHours)).subtract(targetHours)
    result.remainingHoursAfterHittingTarget = result.remainingHoursAfterHittingTarget.max(new BigDecimal("0.0"))
    result.totalHoursSinceGivenDate = workingHoursSinceGivenDate
    println "==========================="
    println "Results: $result"
    println "==========================="
    return result
//    return workingHoursSinceGivenDate
}

// Get day of the week from date string (week starting on a given date)
int getDayOfWeek(LocalDate startingDate, LocalDate date, int daysPerWeek) {
//    int startingDayOfWsr = startingDate.getDayOfWeek().getValue()
//    int dayOfWeek = (date.getDayOfWeek().getValue() - startingDayOfWsr + daysPerWeek) % daysPerWeek + 1
//    println "Custom day of the week for $date based on $startingDate is: $dayOfWeek"
//    println "$date: day $dayOfWeek"
////    return wsDayOfWeek == 0 ? 7 : wsDayOfWeek
//    return dayOfWeek

    long dayDifference = ChronoUnit.DAYS.between(startingDate, date) //Calculate absolute days
    int dayOfWeek = (dayDifference % daysPerWeek) + 1  // Get day of the week
    println "Custom day of the week for $date based on $startingDate is: $dayOfWeek"
    println "$date: day $dayOfWeek"
    return dayOfWeek

}


// Calculate total working hours for the day based on Work Schedule Model
Map calculateTotalWorkingHoursForDay(workScheduleDayModelAssignments, int day, wsr) {
    def workschedule = wsr.externalCode.text()
    boolean counted = false
    double totalWorkingHours = 0.0
    workScheduleDayModelAssignments.each { dayModel ->
        if (day == dayModel.day.text().toInteger() && workschedule == dayModel.WorkSchedule_externalCode.text()) {
            def wsrText = dayModel.WorkSchedule_externalCode.text()
            def dayText = dayModel.day.text()
            def dayCat = dayModel.category.text()
            if (dayModel.category.text() != 'OFF' && dayModel.dayModelNav.WorkScheduleDayModel) {
                dayModel.dayModelNav.WorkScheduleDayModel.each { model ->
                    totalWorkingHours += model.workingHours.text().toBigDecimal()
                    counted = true
                }
            }
            println "day: $dayText, category: $dayCat, wsr: $wsrText, totalHours:$totalWorkingHours"
        }

    }
    // Round to 1 decimal place
//    return totalWorkingHours
    return [totalWorkingHours: totalWorkingHours, counted: counted]
}

String getTodayFormattedDate() {

    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Australia/Sydney"))
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00.000")
    dateFormat.setTimeZone(calendar.getTimeZone())
    return dateFormat.format(calendar.getTime())
}

// Generate the current date-time string
String processOnDateTime() {
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
    Date today = new Date()
    String formattedDate = dateFormat.format(today)

    return formattedDate
}

long generateExternalCode(String payScaleLevel) {
    CRC32 crc = new CRC32()
    crc.update(payScaleLevel.getBytes())
    long crcValue = crc.getValue()
    return crcValue
}

boolean isDateWithinLeaveRecords(EmployeeLeaves,LocalDate inputDate) {
    EmployeeLeaves.any { node ->
        // Skip employee time attendances - timeType starts with '7'
        if (!node.timeType.text().startsWith('7')) {

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDate startDate = LocalDate.parse(node.startDate.text(), formatter)
            LocalDate endDate = LocalDate.parse(node.endDate.text(), formatter)

            (!inputDate.isBefore(startDate) && !inputDate.isAfter(endDate))
        }
    }
}

BigDecimal addTimesheetEntries(EmployeeLeaves,LocalDate inputDate) {

    BigDecimal totalHours = BigDecimal.ZERO
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    EmployeeLeaves.each { node ->
        // Process only employee attendances - timeType starts with '7'
        if (node.timeType.text().startsWith('7')) {
            LocalDate startDate = LocalDate.parse(node.startDate.text(), formatter)
            LocalDate endDate = LocalDate.parse(node.endDate.text(), formatter)

            if (inputDate.equals(startDate) && inputDate.equals(endDate)) {
                if (node.processedFlag && node.processedFlag.text() == "true") {
                    println("SKIP - Timesheet entry already counted")
                }
                else {
                    BigDecimal hours = new BigDecimal(node.quantityInHours.text())
                    totalHours = totalHours.add(hours)
                    node.appendNode("processedFlag", "true")
                }
            }
        }
    }

    return totalHours
}

BigDecimal checkHoldingPositionWeekend(employmentRecord) {

    def timeZone = ZoneId.of("Australia/Sydney")
    def todayDate = LocalDate.now(timeZone)
    DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME
//    LocalDate empStartDate = LocalDate.parse(employmentRecord.startDate.text(), formatter)

    BigDecimal totalTempHoldDays = 0.0

    employmentRecord.TempHold.findAll { (it.division.text() == '10000001') && (it.emplStatusNav.PicklistOption.externalCode.text() == 'A' && it.startDate.text()!=it.endDate.text()) }.each { empJob ->
        def startDate = LocalDate.parse(empJob.startDate.text().substring(0, 10))
        def endDate = LocalDate.parse(empJob.endDate.text().substring(0, 10))

        // Find the earlier of todayDate and endDate
        def earlyEndDate = todayDate.isBefore(endDate) ? todayDate : endDate
//            def earlyEndDate = empStartDate.isBefore(endDate) ? empStartDate : endDate

        // Calculate numberOfDays
        def numberOfDays = earlyEndDate.toEpochDay() - startDate.toEpochDay()+1

        //add to cumulative sum if numberOfDays >0

        println("Start Date: $startDate, Early End Date: $earlyEndDate, Number of Days: $numberOfDays")
        isWeekend = false
        if (numberOfDays == 2) {
            def startDayOfWeek = startDate.getDayOfWeek()
            def endDayOfWeek = earlyEndDate.getDayOfWeek()

            if (startDayOfWeek == DayOfWeek.SATURDAY && endDayOfWeek == DayOfWeek.SUNDAY) {
                isWeekend = true
                println("Start Date ($startDate) or Early End Date ($earlyEndDate) falls on a weekend.")
            }
        }
        if(isWeekend == true && numberOfDays >0)
        {
            totalTempHoldDays = totalTempHoldDays + numberOfDays
        }

    }
    return totalTempHoldDays
}

// Ticket 9531 - Count number of day model for a wsr external code
int countMatchingWorkScheduleDayModels(workScheduleDayModelAssignments, wsr) {
    def workschedule = wsr.externalCode.text()

    return workScheduleDayModelAssignments.count {
        it.WorkSchedule_externalCode.text() == workschedule
    }
}

def processFutureDatedAssignment(newIncrements,futureDateEmployment,payCompAssignments,employeeData){

    def uniquePayScaleLevels = newIncrements.cust_priorServiceIncrementDetails.cust_payScaleLevel*.text().unique()

    def matchingEmpJobs = futureDateEmployment.EmpEmployment.jobInfoNav.EmpJob.findAll { empJob ->
        uniquePayScaleLevels.contains(empJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text()) &&
                empJob.emplStatusNav.PicklistOption.externalCode.text() == 'A'
    }

    def futureEmpEmployment = new groovy.util.Node(null, "FutureEmpEmployment")
    def newEmpJobNode = new groovy.util.Node(futureEmpEmployment, "FutureEmpJobs")
    def newEmpCompNode = new groovy.util.Node(futureEmpEmployment, "FutureEmpCompensationRecurrings")

    matchingEmpJobs.each { empJob ->
        // Find the corresponding cust_priorServiceIncrementDetails with the same payScaleLevel
        def matchingIncrement = newIncrements.cust_priorServiceIncrementDetails.find { increment ->
            increment.cust_payScaleLevel.text() == empJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text()
        }


        def newJobNode = new groovy.util.Node(newEmpJobNode, "FutureEmpJob")

        // Add EmpJob
        newJobNode.appendNode("new_psLevel", empJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text())
        newJobNode.appendNode("new_event", "544")
        newJobNode.appendNode("new_eventReason", "reclServ")
        newJobNode.appendNode("new_startDate", empJob.startDate.text())
        newJobNode.appendNode("userId", empJob.userId.text())

        // Pay Comp Recurring
        def matchingCompensation = futureDateEmployment.EmpEmployment.compInfoNav.EmpCompensation.find { compensation ->
            compensation.startDate.text() == empJob.startDate.text()
        }

        matchingCompensation.empPayCompRecurringNav.EmpPayCompRecurring.each { empPayCompRecurring ->
            def payComponentValue = empPayCompRecurring.payComponent.text()
            payCompAssignments.each { payScalePayComponent ->
                def payScaleComponentCode = payScalePayComponent.'code'.text()
                def payScaleLevelCode = payScalePayComponent.'PayScaleLevel_code'.text()

                if (payScaleComponentCode == payComponentValue && payScaleLevelCode == empJob.payScaleLevelNav.PayScaleLevel.nextPayScaleLevel.text()) {
                    def amount = payScalePayComponent.'*'.find { it.name() == 'amount' }?.text()
                    def frequency = payScalePayComponent.'*'.find { it.name() == 'frequency' }?.text()
                    def currency = payScalePayComponent.'*'.find { it.name() == 'currency' }?.text()

                    if (amount && frequency) {
                        // Append NewEmpCompensationRecurring nodes inside NewEmpCompensationRecurrings
                        def newEmpCompRecurring = new groovy.util.Node(newEmpCompNode, "NewEmpCompensationRecurring")
                        newEmpCompRecurring.appendNode('paycompvalue', amount)
                        newEmpCompRecurring.appendNode('payComponent', payScalePayComponent.'code'.text())
                        newEmpCompRecurring.appendNode('frequency', frequency)
                        newEmpCompRecurring.appendNode('currencyCode', currency)
                        newEmpCompRecurring.appendNode('startDate', empJob.startDate.text())
                        newEmpCompRecurring.appendNode("userId", empJob.userId.text())
                    }
                }
            }
        }

        //Duplicate new prior service increment
        if (matchingIncrement) {
            def newIncrement = matchingIncrement.clone()
            newIncrement.cust_priorServiceIncrement_externalCode[0].value = empJob.userId.text()
            newIncrements[0].children().add(newIncrement)
        }


    }
    employeeData[0].PerPerson[0].children().add(futureEmpEmployment)
}

def checkDateFormat(input) {
    if (input instanceof LocalDate) {
        // If input is LocalDate, format it
        return input.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'00:00:00.000"))
    } else if (input instanceof String && input ==~ /\d{4}-\d{2}-\d{2}/) {
        // If input is a string in yyyy-MM-dd format, parse and format it
        def localDate = LocalDate.parse(input)
        return localDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'00:00:00.000"))
    }

    return input
}

String getAdjustedRecruitDate(String recruitDateString, String previousIncrementDateString) {
    if (!recruitDateString || !previousIncrementDateString) {
        return recruitDateString ?: previousIncrementDateString // return whichever is not null
    }

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    LocalDate recruitDate = LocalDate.parse(recruitDateString[0..9], formatter)
    LocalDate incrementDate = LocalDate.parse(previousIncrementDateString[0..9], formatter)

    return recruitDate.isBefore(incrementDate) ? recruitDateString : previousIncrementDateString
}

BigDecimal getLeaveHoursForDate(employeeLeaves, LocalDate date) {
    BigDecimal total = BigDecimal.ZERO
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    employeeLeaves.each { node ->
        def tt = node.timeType.text()
        if (!tt.startsWith('7')) { // leave only
            LocalDate s = LocalDate.parse(node.startDate.text(), fmt)
            LocalDate e = LocalDate.parse(node.endDate.text(), fmt)
            // partial leave is modeled as same-day entry with quantityInHours
            if (s.equals(e) && s.equals(date) && node.quantityInHours?.text()) {
                total = total.add(new BigDecimal(node.quantityInHours.text()))
            }
        }
    }
    return total
}