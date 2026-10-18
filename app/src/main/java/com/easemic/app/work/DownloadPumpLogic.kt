package com.easemic.app.work

/** Pump exit rule: stop after [maxIdleTicks] consecutive unproductive ticks. */
fun downloadPumpShouldStop(consecutiveIdleTicks: Int, maxIdleTicks: Int = 2): Boolean =
    consecutiveIdleTicks >= maxIdleTicks
