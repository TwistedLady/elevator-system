package pl.feelcodes.elevator.common.dto

import com.fasterxml.jackson.annotation.JsonProperty

sealed trait TaggedDto {
  def tag: String
}

final case class ElevatorOrderDto(@JsonProperty("tag") tag: String,
                                  @JsonProperty("elevatorName") elevatorName: String,
                                  @JsonProperty("floor") floor: Int) extends TaggedDto

final case class ElevatorStateDto(@JsonProperty("tag") tag: String,
                                  @JsonProperty("elevatorName") elevatorName: String,
                                  @JsonProperty("direction") direction: String,
                                  @JsonProperty("motion") motion: String,
                                  @JsonProperty("floor") floor: Int) extends TaggedDto
