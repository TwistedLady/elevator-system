package pl.feelcodes.elevator.app.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

/** The JSON codec shared by the Kafka boundary: orders come in as JSON, state goes out as JSON. */
object Json {
  private val mapper = ObjectMapper().registerModule(DefaultScalaModule)

  def encode[T](obj: T): String = mapper.writeValueAsString(obj)

  def decode[T](bytes: Array[Byte], clazz: Class[T]): T = mapper.readValue(bytes, clazz)
}
