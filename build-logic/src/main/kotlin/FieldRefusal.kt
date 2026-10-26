package eu.nordtal.season.build

/**
 * A refused change whose reason concerns one [field] of the form that asked for it, named as its `-P` option, or
 * `namespace` or `path` of an [Identifier].
 */
class FieldRefusal(
    val field: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
