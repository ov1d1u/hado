package com.google.android.appfunctions.schema.common.v1.types

import androidx.appfunctions.AppFunctionSerializable

/**
 * A time of day without a time zone.
 *
 * This mirrors the platform's `tasks` schema type, so it must keep this package and these
 * property names for assistants to recognise the documents HAdo exchanges.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class TimeOfDay(
    /** The hour of the day, from 0 to 23. */
    val hours: Int,
    /** The minute of the hour, from 0 to 59. */
    val minutes: Int,
    /** The second of the minute, from 0 to 59. */
    val seconds: Int,
    /** The nanosecond of the second, from 0 to 999999999. */
    val nanos: Int,
)
