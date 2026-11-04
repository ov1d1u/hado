package com.google.android.appfunctions.schema.common.v1.types

import androidx.appfunctions.AppFunctionSerializable

/**
 * A date and time, optionally with a time zone.
 *
 * This mirrors the platform's `tasks` schema type, so it must keep this package and these
 * property names for assistants to recognise the documents HAdo exchanges.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class DateTime(
    /** The IANA time zone identifier, such as "Europe/Bucharest", when the time zone is known. */
    val timeZone: String? = null,
    /** The calendar date. */
    val date: Date? = null,
    /** The time of day. */
    val timeOfDay: TimeOfDay? = null,
)
