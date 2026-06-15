variable "app_image" {
  description = "Image for the Pekko app"
  type        = string
  default     = "elevator-app:local"
}

variable "api_image" {
  description = "Image for the Spring API"
  type        = string
  default     = "elevator-api:local"
}
