package com.tricrotism.windchill.telemetry

import java.lang.management.ManagementFactory
import javax.management.JMException
import javax.management.ObjectName

/**
 * The `jcmd` diagnostic commands, run in-process through the DiagnosticCommand MBean.
 *
 * Operation names are the command with its first dot removed and camel-cased: `Compiler.codelist` is
 * `compilerCodelist`, `AOT.end_recording` is `aotEndRecording`. Every one used here was listed by the
 * MBean on JDK 25.0.3.
 */
object DiagnosticCommand {

    private val NAME = ObjectName("com.sun.management:type=DiagnosticCommand")
    private val SIGNATURE = arrayOf(Array<String>::class.java.name)

    /**
     * @return the command's output, or null when this JVM does not offer it
     * @throws JMException when the command exists and failed
     */
    fun run(operation: String, vararg arguments: String): String? {
        val server = ManagementFactory.getPlatformMBeanServer()
        if (server.getMBeanInfo(NAME).operations.none { it.name == operation }) return null

        return server.invoke(NAME, operation, arrayOf<Any>(arrayOf(*arguments)), SIGNATURE) as? String
    }
}
