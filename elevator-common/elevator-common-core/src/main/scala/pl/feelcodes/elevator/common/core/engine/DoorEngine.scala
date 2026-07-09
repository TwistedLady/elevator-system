package pl.feelcodes.elevator.common.core.engine

import scala.concurrent.duration.FiniteDuration

/** Door motor; `openTime` is how long the door is held open (the only physical cost — open/close are instant). */
final case class DoorEngine(openTime: FiniteDuration):

  def burn(): Unit = Thread.sleep(openTime.toMillis)
