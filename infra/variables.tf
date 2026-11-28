variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "aws_profile" {
  type    = string
  default = "card-auth-engine-deployer"
}

variable "name" {
  type    = string
  default = "card-auth"
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "auth_desired_count" {
  type    = number
  default = 2
}

variable "default_strategy" {
  type    = string
  default = "redis-lua"
}
