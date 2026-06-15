package pl.feelcodes.elevator.common.core

import pl.feelcodes.elevator.common.core.Command.{GO, STOP}
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.{MOVING, STOPPED}

import scala.language.postfixOps

type FloorNum = Int
type OrderTag = String
type ElevatorName = String

trait HasFloorNum:
  def num: FloorNum

final case class Floor(num: FloorNum) extends HasFloorNum, Ordered[Floor]:
  def ++ : Floor = Floor(num + 1)

  def -- : Floor = Floor(num - 1)

  override def compare(that: Floor): FloorNum = num.compare(that.num)

trait HasOrderTag:
  def tag: OrderTag

final case class OrderElevator(tag: OrderTag, floor: Floor) extends HasFloorNum, HasOrderTag, Ordered[OrderElevator]:
  override def compare(that: OrderElevator): Int = this.floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num


enum Direction:
  case UP, DOWN

  def swap: Direction = this match
    case UP => DOWN
    case DOWN => UP

enum Motion:
  case MOVING, STOPPED

enum Command:
  case GO(direction: Direction)
  case STOP()

final case class OrderElevatorCommand(order: OrderElevator, command: Command)

final case class ElevatorState(direction: Direction,
                               motion: Motion,
                               floor: Floor):
  require(direction != null, "Illegal state: direction must not be null")
  require(motion != null, "Illegal state: motion must not be null")
  require(floor != null, "Illegal floor: floor must not be null")

trait Engine(val cost: BigInt):

  protected def burn(): Unit =
    var i = 0
    while i < cost do i += 1

  def move(floor: Floor)(command: Command): Floor =
    burn()
    command match
      case GO(Direction.UP) => floor ++
      case GO(Direction.DOWN) => floor --
      case STOP() => floor

final case class SlowEngine() extends Engine(500_000_000)

final case class FastEngine() extends Engine(2_000)

final case class Elevator(name: ElevatorName,
                          private val engine: Engine,
                          private val state: ElevatorState):
  def move(command: Command): Elevator = {
    copy(
      state = state.copy(
        floor = engine.move(state.floor)(command),
        direction = command match
          case GO(Direction.UP) => UP
          case GO(Direction.DOWN) => DOWN
          case STOP() => state.direction,
        motion = command match
          case GO(_) => MOVING
          case STOP() => STOPPED))
  }


  def stop(): Elevator = copy(state = state.copy(motion = Motion.STOPPED))

  def floor(): Floor = state.floor

  def direction(): Direction = state.direction

  def motion(): Motion = state.motion


object Elevator:

  val defaultState = ElevatorState(
    direction = Direction.UP,
    motion = Motion.STOPPED,
    floor = Floor(0))

  def slow(name: ElevatorName)(state: ElevatorState = defaultState): Elevator =
    Elevator(name, SlowEngine(), state)

  def fast(name: ElevatorName)(state: ElevatorState = defaultState): Elevator =
    Elevator(name, FastEngine(), state)

object Policy:
  private final case class Facts(direction: Direction,
                                 stopHere: Boolean,
                                 hasOrdersInCurrentDirection: Boolean,
                                 hasOrdersInOppositeDirection: Boolean):
    require(stopHere || hasOrdersInCurrentDirection || hasOrdersInOppositeDirection,
      "one of: stopHere, hasOrdersInCurrentDirection, hasOrdersInOppositeDirection must be true")

  def next(floor: Floor, direction: Direction, orders: Set[OrderElevator]): OrderElevatorCommand = {
    require(orders.nonEmpty, "Policy.next requires at least one order")
    val facts = Facts(
      direction = direction,
      stopHere = orders.exists(_.floor == floor),
      hasOrdersInCurrentDirection =
        (orders.exists(_.floor > floor) && direction == Direction.UP) ||
          (orders.exists(_.floor < floor) && direction == Direction.DOWN),
      hasOrdersInOppositeDirection =
        (orders.exists(_.floor > floor) && direction == Direction.DOWN) ||
          (orders.exists(_.floor < floor) && direction == Direction.UP),
    )
    val cmd = facts match {
      case Facts(direction, false, true, _) => GO(direction)
      case Facts(direction, false, _, true) => GO(direction swap)
      case Facts(_, _, _, _) => STOP()
    }
    val req = facts match {
      case Facts(_, true, _, _) => orders.find(_.floor == floor).get
      case Facts(direction, _, true, _) => direction match {
        case UP => orders.filter(_.floor > floor).min
        case DOWN => orders.filter(_.floor < floor).max
      }
      case Facts(direction, _, _, true) => direction.swap match {
        case UP => orders.filter(_.floor > floor).min
        case DOWN => orders.filter(_.floor < floor).max
      }
      case Facts(_, false, false, false) => throw new IllegalStateException("No request could be selected")
    }
    OrderElevatorCommand(order = req, command = cmd)
  }
