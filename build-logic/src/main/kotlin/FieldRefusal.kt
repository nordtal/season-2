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

/** A refused change that lacks a value for each of [fields], a map from each field's name to its kind. */
class FieldsNeeded(
    val fields: Map<String, String>,
) : IllegalArgumentException("Set " + fields.keys.joinToString(", ") + ".")
