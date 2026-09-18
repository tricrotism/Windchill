package com.tricrotism.windchill.aot

/**
 * Result of forking the assemble step. [command] is reported either way so an operator can run it by
 * hand when the fork fails, which is the likely outcome on a container with a stripped JDK.
 */
data class AotAssembly(
    val succeeded: Boolean,
    val command: List<String>,
    val output: String,
)
