package pl.feelcodes.elevator.app

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

final class EngineMode(initial: String):
  private val ref = new AtomicReference[String](EngineMode.validated(initial))

  def current: String = ref.get()

  def refreshFrom(file: Path): Option[String] =
    Try(Files.readString(file).trim.toLowerCase).toOption
      .filter(EngineMode.isValid)
      .flatMap(v => Option.when(ref.getAndSet(v) != v)(v))

object EngineMode:
  val Fast = "fast"
  val Slow = "slow"

  def isValid(v: String): Boolean = v == Fast || v == Slow

  def validated(v: String): String =
    val n = v.trim.toLowerCase
    if isValid(n) then n
    else throw new IllegalArgumentException(s"Unknown engine '$v'. Known: $Fast, $Slow")
