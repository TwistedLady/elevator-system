package pl.feelcodes.elevator.common.core.domain

import scala.concurrent.duration.*

/** How long two elevators sharing a floor pause before both are let go. */
object SuspendDwell:
  val duration: FiniteDuration = 3.seconds
