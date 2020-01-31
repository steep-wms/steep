package model

import java.util.Calendar

/**
 * Steep version info
 * @param version Steep's version number
 * @param build the build ID from the CI server
 * @param commit the Git commit this build was based on
 * @param timestamp the timestamp when the build was executed
 * @author Michel Kraemer
 */
data class Version(
    val version: String,
    val build: String,
    val commit: String,
    val timestamp: Calendar
)
