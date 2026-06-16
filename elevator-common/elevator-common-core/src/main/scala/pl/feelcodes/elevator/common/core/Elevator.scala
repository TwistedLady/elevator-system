package pl.feelcodes.elevator.common.core

import pl.feelcodes.elevator.common.core.Command.{Go, Stop}
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.{Moving, Stopped}

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

final case class ElevatorOrder(tag: OrderTag, floor: Floor) extends HasFloorNum, HasOrderTag, Ordered[ElevatorOrder]:
  override def compare(that: ElevatorOrder): Int = this.floor.num.compare(that.floor.num)

  override def num: FloorNum = floor.num


enum Direction:
  case Up, Down

  def swap: Direction = this match
    case Up => Down
    case Down => Up

enum Motion:
  case Moving, Stopped

enum Command:
  case Go(direction: Direction)
  case Stop()

final case class OrderElevatorCommand(order: ElevatorOrder, command: Command)

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
      case Go(Direction.Up) => floor ++
      case Go(Direction.Down) => floor --
      case Stop() => floor

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
          case Go(Direction.Up) => Up
          case Go(Direction.Down) => Down
          case Stop() => state.direction,
        motion = command match
          case Go(_) => Moving
          case Stop() => Stopped))
  }


  def stop(): Elevator = copy(state = state.copy(motion = Motion.Stopped))

  def floor(): Floor = state.floor

  def direction(): Direction = state.direction

  def motion(): Motion = state.motion


object Elevator:

  val defaultState = ElevatorState(
    direction = Direction.Up,
    motion = Motion.Stopped,
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

  def next(floor: Floor, direction: Direction, orders: Set[ElevatorOrder]): OrderElevatorCommand = {
    require(orders.nonEmpty, "Policy.next requires at least one order")
    val facts = Facts(
      direction = direction,
      stopHere = orders.exists(_.floor == floor),
      hasOrdersInCurrentDirection =
        (orders.exists(_.floor > floor) && direction == Direction.Up) ||
          (orders.exists(_.floor < floor) && direction == Direction.Down),
      hasOrdersInOppositeDirection =
        (orders.exists(_.floor > floor) && direction == Direction.Down) ||
          (orders.exists(_.floor < floor) && direction == Direction.Up),
    )
    val cmd = facts match {
      case Facts(direction, false, true, _) => Go(direction)
      case Facts(direction, false, _, true) => Go(direction swap)
      case Facts(_, _, _, _) => Stop()
    }
    val req = facts match {
      case Facts(_, true, _, _) => orders.find(_.floor == floor).get
      case Facts(direction, _, true, _) => direction match {
        case Up => orders.filter(_.floor > floor).min
        case Down => orders.filter(_.floor < floor).max
      }
      case Facts(direction, _, _, true) => direction.swap match {
        case Up => orders.filter(_.floor > floor).min
        case Down => orders.filter(_.floor < floor).max
      }
      case Facts(_, false, false, false) => throw new IllegalStateException("No request could be selected")
    }
    OrderElevatorCommand(order = req, command = cmd)
  }
