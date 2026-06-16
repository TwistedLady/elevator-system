package pl.feelcodes.elevator.app

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonSerializer, SerializerProvider}
import pl.feelcodes.elevator.common.core.{Command, Direction, Motion}
import pl.feelcodes.elevator.common.core.Command.{Go, Stop}

/**
 * Jackson support for the domain's Scala 3 enums/ADTs. jackson-module-scala treats them as
 * plain enums and chokes on the parameterized `Command.Go(direction)` ("Failed to create Enum
 * instance for Go(Up)"). We encode them as compact strings here — keeping the pure core module
 * free of any Jackson dependency. Registered via pekko.serialization.jackson.jackson-modules.
 */
private class DirectionSer extends JsonSerializer[Direction]:
  override def serialize(v: Direction, g: JsonGenerator, p: SerializerProvider): Unit = g.writeString(v.toString)

private class DirectionDe extends JsonDeserializer[Direction]:
  override def deserialize(p: JsonParser, c: DeserializationContext): Direction = Direction.valueOf(p.getText)

private class MotionSer extends JsonSerializer[Motion]:
  override def serialize(v: Motion, g: JsonGenerator, p: SerializerProvider): Unit = g.writeString(v.toString)

private class MotionDe extends JsonDeserializer[Motion]:
  override def deserialize(p: JsonParser, c: DeserializationContext): Motion = Motion.valueOf(p.getText)

private class CommandSer extends JsonSerializer[Command]:
  override def serialize(v: Command, g: JsonGenerator, p: SerializerProvider): Unit = v match
    case Go(d)  => g.writeString(s"Go:$d")
    case Stop() => g.writeString("Stop")

private class CommandDe extends JsonDeserializer[Command]:
  override def deserialize(p: JsonParser, c: DeserializationContext): Command = p.getText match
    case s if s.startsWith("Go:") => Go(Direction.valueOf(s.substring(3)))
    case _                        => Stop()

class DomainJacksonModule extends SimpleModule:
  addSerializer(classOf[Direction], new DirectionSer)
  addDeserializer(classOf[Direction], new DirectionDe)
  addSerializer(classOf[Motion], new MotionSer)
  addDeserializer(classOf[Motion], new MotionDe)
  addSerializer(classOf[Command], new CommandSer)
  addDeserializer(classOf[Command], new CommandDe)
