package pl.feelcodes.elevator.app

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import scala.util.Try

final class EngineMode(initial: String):
  // Plain SLF4J logger: refreshFrom runs on the scheduler thread, where Pekko's ActorContext.log
  // must not be touched. This logger is thread-safe.
  private val log = LoggerFactory.getLogger(classOf[EngineMode])
  private val ref = new AtomicReference[String](EngineMode.validated(initial))

  def current: String = ref.get()

  def refreshFrom(file: Path): Option[String] =
    Try(Files.readString(file).trim.toLowerCase).toOption
      .filter(EngineMode.isValid)
      .flatMap(v => Option.when(ref.getAndSet(v) != v)(v))
      .map { v => log.info("engine mode -> {}", v); v }

object EngineMode:
  val Fast = "fast"
  val Slow = "slow"

  def isValid(v: String): Boolean = v == Fast || v == Slow

  def validated(v: String): String =
    val n = v.trim.toLowerCase
    if isValid(n) then n
    else throw new IllegalArgumentException(s"Unknown engine '$v'. Known: $Fast, $Slow")
