package pl.feelcodes.elevator.common.dto

import com.fasterxml.jackson.annotation.JsonProperty

sealed trait KeyedDto:
  def elevatorName: String

final case class CallDto(@JsonProperty("id") id: String,
                         @JsonProperty("elevatorName") elevatorName: String,
                         @JsonProperty("floor") floor: Int,
                         @JsonProperty("passengerId") passengerId: String = null) extends KeyedDto

final case class ElevatorStateDto(@JsonProperty("elevatorName") elevatorName: String,
                                  @JsonProperty("direction") direction: String,
                                  @JsonProperty("motion") motion: String,
                                  @JsonProperty("floor") floor: Int,
                                  @JsonProperty("suspended") suspended: Boolean = false) extends KeyedDto

final case class DoorStateDto(@JsonProperty("elevatorName") elevatorName: String,
                              @JsonProperty("floor") floor: Int,
                              @JsonProperty("doorState") doorState: String) extends KeyedDto

final case class BoardDto(@JsonProperty("elevatorName") elevatorName: String,
                          @JsonProperty("floor") floor: Int,
                          @JsonProperty("passengerId") passengerId: String) extends KeyedDto

final case class OrderStateDto(@JsonProperty("orderId") orderId: String,
                               @JsonProperty("elevatorName") elevatorName: String,
                               @JsonProperty("floor") floor: Int,
                               @JsonProperty("status") status: String,
                               @JsonProperty("callIds") callIds: Set[String],
                               @JsonProperty("passengers") passengers: Int,
                               @JsonProperty("anonymous") anonymous: Int) extends KeyedDto

final case class CallStateDto(@JsonProperty("id") id: String,
                              @JsonProperty("elevatorName") elevatorName: String,
                              @JsonProperty("floor") floor: Int,
                              @JsonProperty("status") status: String) extends KeyedDto
