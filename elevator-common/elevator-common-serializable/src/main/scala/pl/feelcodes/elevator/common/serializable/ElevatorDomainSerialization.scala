package pl.feelcodes.elevator.common.serializable

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonSerializer, SerializerProvider}
import pl.feelcodes.elevator.common.core.{Command, Direction, Motion}
import pl.feelcodes.elevator.common.core.Command.{Go, Stop}

private class DirectionSerializer extends JsonSerializer[Direction]:
  override def serialize(value: Direction, generator: JsonGenerator, provider: SerializerProvider): Unit =
    generator.writeString(value.toString)

private class DirectionDeserializer extends JsonDeserializer[Direction]:
  override def deserialize(parser: JsonParser, context: DeserializationContext): Direction =
    Direction.valueOf(parser.getText)

private class MotionSerializer extends JsonSerializer[Motion]:
  override def serialize(value: Motion, generator: JsonGenerator, provider: SerializerProvider): Unit =
    generator.writeString(value.toString)

private class MotionDeserializer extends JsonDeserializer[Motion]:
  override def deserialize(parser: JsonParser, context: DeserializationContext): Motion =
    Motion.valueOf(parser.getText)

private class CommandSerializer extends JsonSerializer[Command]:
  override def serialize(value: Command, generator: JsonGenerator, provider: SerializerProvider): Unit = value match
    case Go(direction) => generator.writeString(s"Go:$direction")
    case Stop()        => generator.writeString("Stop")

private class CommandDeserializer extends JsonDeserializer[Command]:
  override def deserialize(parser: JsonParser, context: DeserializationContext): Command = parser.getText match
    case text if text.startsWith("Go:") => Go(Direction.valueOf(text.substring(3)))
    case _                              => Stop()

class ElevatorDomainSerialization extends SimpleModule:
  addSerializer(classOf[Direction], new DirectionSerializer)
  addDeserializer(classOf[Direction], new DirectionDeserializer)
  addSerializer(classOf[Motion], new MotionSerializer)
  addDeserializer(classOf[Motion], new MotionDeserializer)
  addSerializer(classOf[Command], new CommandSerializer)
  addDeserializer(classOf[Command], new CommandDeserializer)
