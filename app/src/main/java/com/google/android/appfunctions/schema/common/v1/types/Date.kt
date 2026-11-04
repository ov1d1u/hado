package com.google.android.appfunctions.schema.common.v1.types

import androidx.appfunctions.AppFunctionSerializable

/**
 * A calendar date without a time zone.
 *
 * This mirrors the platform's `tasks` schema type, so it must keep this package and these
 * property names for assistants to recognise the documents HAdo exchanges.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Date(
    /** The year, such as 2026. */
    val year: Int,
    /** The month of the year, from 1 to 12. */
    val month: Int,
    /** The day of the month, from 1 to 31. */
    val day: Int,
)
