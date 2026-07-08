package pl.feelcodes.elevator.common.dto

import com.fasterxml.jackson.annotation.JsonProperty

sealed trait KeyedDto:
  def elevatorName: String

final case class CallDto(@JsonProperty("id") id: String,
                         @JsonProperty("elevatorName") elevatorName: String,
                         @JsonProperty("floor") floor: Int) extends KeyedDto

final case class ElevatorStateDto(@JsonProperty("elevatorName") elevatorName: String,
                                  @JsonProperty("direction") direction: String,
                                  @JsonProperty("motion") motion: String,
                                  @JsonProperty("floor") floor: Int) extends KeyedDto

final case class DoorStateDto(@JsonProperty("elevatorName") elevatorName: String,
                              @JsonProperty("floor") floor: Int,
                              @JsonProperty("doorState") doorState: String) extends KeyedDto

final case class OrderStateDto(@JsonProperty("orderId") orderId: String,
                               @JsonProperty("elevatorName") elevatorName: String,
                               @JsonProperty("floor") floor: Int,
                               @JsonProperty("status") status: String,
                               @JsonProperty("callIds") callIds: Set[String]) extends KeyedDto

final case class CallStateDto(@JsonProperty("id") id: String,
                              @JsonProperty("elevatorName") elevatorName: String,
                              @JsonProperty("floor") floor: Int,
                              @JsonProperty("status") status: String) extends KeyedDto
