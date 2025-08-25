# CPIGroovyDebugger
SAP CPI Debugger for ease of Debugging

SAP CPI Groovy – Local Debug + Samples

Tiny toolkit to debug SAP Cloud Integration (CPI) Groovy scripts locally in IntelliJ IDEA with real sample payloads, then reuse the same scripts in CPI.

It includes:

Mock Message/Output classes (so scripts compile outside CPI)

A simple ScriptRunner to invoke scripts with sample XML

Example scripts (transformations, comparisons, masking, secure param read)

A samples/ folder for input payloads

Repo layout
.
├─ samples/
│  ├─ order.xml
│
├─ src/
│  └─ main/
│     └─ groovy/
│        ├─ ScriptRunner.groovy
│        ├─ CreateOrderSummary.groovy
│        └─ com/
│           └─ sap/
│              ├─ gateway/ip/core/customdev/util/Message.groovy
│              └─ it/api/mapping/{Output.groovy, MappingContext.groovy}
│
└─ (optional) build.gradle


Important: Folder paths under src/main/groovy must match the package names in mock classes, e.g.
src/main/groovy/com/sap/gateway/ip/core/customdev/util/Message.groovy ↔ package com.sap.gateway.ip.core.customdev.util.

Prerequisites

Java 17+ (Java 21 is fine)

One of:

Gradle (recommended), or

Groovy SDK added to IntelliJ (if you don’t use Gradle)

IntelliJ IDEA with the Groovy plugin

Option A — Run with Gradle (recommended)

Create build.gradle (if not present):

plugins {
id 'groovy'
id 'application'
}

repositories { mavenCentral() }

dependencies {
implementation 'org.apache.groovy:groovy:4.0.14'
implementation 'org.apache.groovy:groovy-xml:4.0.14'
testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.2'
}

java {
toolchain { languageVersion = JavaLanguageVersion.of(21) } // or your JDK
}

application {
mainClass = 'ScriptRunner' // ScriptRunner.groovy in default package
}

tasks.withType(GroovyCompile).configureEach {
groovyOptions.encoding = 'UTF-8'
}


In IntelliJ: Open as Gradle project (or “Link Gradle”).

Run the runner:

# From project root
gradlew run
# or pass a different input file path (optional)
gradlew run --args="samples/order.xml"

Option B — Run without Gradle (use IntelliJ Groovy SDK)

In IntelliJ:

File → Project Structure → Global Libraries → + → Groovy → choose Groovy 4.0.14 (download if needed).

File → Project Structure → Modules → Dependencies → add the Groovy 4.0.14 library.

Mark src/main/groovy as Sources Root (blue folder).

Open src/main/groovy/ScriptRunner.groovy → click Run (or Debug).

Do not mix Groovy versions on the classpath. If you ever see
“Conflicting module versions … loaded 4.0.14 and … 5.0.0-beta-1”,
remove the 5.x jars and keep only 4.0.14 (or vice versa, but don’t mix).

Running the sample
1) The simple demo: CreateOrderSummary.groovy

Input sample (already included): samples/order.xml

Run:

# Gradle
gradlew run --args="samples/order.xml"

# or in IntelliJ: Run ScriptRunner directly


You’ll see an output like:

<Summary>
  <OrderId>1001</OrderId>
  <CustomerFullName>Ada Lovelace</CustomerFullName>
  <ItemCount>2</ItemCount>
</Summary>

2) Switch to another script

ScriptRunner.groovy supports multiple targets. Edit the target variable in main or add a simple CLI switch if you want. For scripts that read from message properties, place the input XML into samples/ and let ScriptRunner load them and set properties before calling processData.


Using these scripts in your CPI tenant

Content Modifier → Body: paste your XML sample (type application/xml) so it becomes message.getBody(String).

Properties: add EC_Payload, ECP_Payload, TimeAccountDetailXML, etc., if your script reads from message.getProperty(...).

Groovy Script step: paste the script code; deploy and run with Trace to see MPL logs and debug properties like DEBUG_*.

Troubleshooting

cannot resolve XmlSlurper
Add Groovy SDK (or use Gradle with org.apache.groovy:groovy-xml:4.0.14).

Conflicting module versions
You have multiple Groovy versions on the classpath (e.g., 4.0.14). Remove one. Stick with 4.0.14 for stability.

Package/folder mismatch
Ensure mocks live under exact package paths:

com/sap/gateway/ip/core/customdev/util/Message.groovy

com/sap/it/api/mapping/Output.groovy

setProperty signature error
In the mock Message, void setProperty(String, Object) must return void (GroovyObject compatibility). Use the provided mock.

Notes

Keep filenames without spaces (e.g., rename CompareECP... (1).groovy → CompareECP... .groovy).

The mocks are intentionally minimal; extend them if your scripts use more CPI APIs.

Add more sample payloads under samples/ and point ScriptRunner to them.