package dev.etorix.panoscrobbler.widget

import dev.etorix.panoscrobbler.R
import dev.etorix.panoscrobbler.api.lastfm.LastfmPeriod
import dev.etorix.panoscrobbler.api.listenbrainz.ListenBrainzRange
import dev.etorix.panoscrobbler.charts.TimePeriod
import dev.etorix.panoscrobbler.charts.TimePeriodsGenerator
import dev.etorix.panoscrobbler.utils.AndroidStuff


enum class WidgetPeriod {
    THIS_WEEK,
    WEEK,
    THIS_MONTH,
    MONTH,
    QUARTER,
    HALF_YEAR,
    THIS_YEAR,
    YEAR,
    ALL_TIME;

    fun toTimePeriod(firstDayOfWeek: Int): TimePeriod {
        val context = AndroidStuff.applicationContext
        val timePeriodsGenerator by lazy {
            TimePeriodsGenerator(
                System.currentTimeMillis() - 1,
                System.currentTimeMillis(),
                firstDayOfWeek,
                generateFormattedStrings = true
            )
        }

        return when (this) {
            WEEK -> TimePeriod(
                LastfmPeriod.WEEK,
                name = context.resources.getQuantityString(R.plurals.num_weeks, 1, 1)
            )

            MONTH -> TimePeriod(
                LastfmPeriod.MONTH,
                name = context.resources.getQuantityString(R.plurals.num_months, 1, 1)
            )

            QUARTER -> TimePeriod(
                LastfmPeriod.QUARTER,
                name = context.resources.getQuantityString(R.plurals.num_months, 3, 3)
            )

            HALF_YEAR -> TimePeriod(
                LastfmPeriod.HALF_YEAR,
                name = context.resources.getQuantityString(R.plurals.num_months, 6, 6)
            )

            YEAR -> TimePeriod(
                LastfmPeriod.YEAR,
                name = context.resources.getQuantityString(R.plurals.num_years, 1, 1)
            )

            THIS_WEEK -> timePeriodsGenerator.weeks()[0].copy(
                listenBrainzRange = ListenBrainzRange.this_week
            )

            THIS_MONTH -> timePeriodsGenerator.months()[0].copy(
                listenBrainzRange = ListenBrainzRange.this_month
            )

            THIS_YEAR -> timePeriodsGenerator.years()[0].copy(
                listenBrainzRange = ListenBrainzRange.this_year
            )

            ALL_TIME -> TimePeriod(
                LastfmPeriod.OVERALL,
                name = context.getString(R.string.charts_overall)
            )
        }
    }
}