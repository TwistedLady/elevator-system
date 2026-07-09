package pl.feelcodes.elevator.common.core.engine

import pl.feelcodes.elevator.common.core.domain.Engine

import scala.concurrent.duration.*

final case class SlowEngine() extends Engine(2.seconds)

final case class FastEngine() extends Engine(100.millis)
