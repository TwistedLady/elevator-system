package pl.feelcodes.elevator.app.inbound

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.app.actors.Doorman
import pl.feelcodes.elevator.common.core.domain.Floor
import pl.feelcodes.elevator.common.dto.BoardDto
import pl.feelcodes.elevator.common.serializable.Json

final class BoardConsumerTests extends AnyFunSuite:

  test("decode | a board record becomes the Doorman.Boarded it was waiting for"):
    val bytes = Json.encode(BoardDto("lift-a", 4, "rider-3")).getBytes("UTF-8")
    assert(BoardConsumer.decode(bytes) == Doorman.Boarded("lift-a", Floor(4), "rider-3"))
