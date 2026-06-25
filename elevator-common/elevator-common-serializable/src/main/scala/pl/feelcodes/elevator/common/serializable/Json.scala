package pl.feelcodes.elevator.common.serializable

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

object Json {
  private val mapper = ObjectMapper().registerModule(DefaultScalaModule)

  def encode[T](obj: T): String = mapper.writeValueAsString(obj)

  def decode[T](bytes: Array[Byte], clazz: Class[T]): T = mapper.readValue(bytes, clazz)
}
