variable "region" {
  description = "AWS region for the temporary demo environment."
  type        = string
  default     = "eu-central-1"
}

variable "project_name" {
  description = "Lowercase project prefix for AWS resource names."
  type        = string
  default     = "orderflow"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,19}$", var.project_name))
    error_message = "Project name must contain 2-20 lowercase letters or digits and start with a letter."
  }
}

variable "db_engine_version" {
  description = "PostgreSQL 17 major or minor version; confirm availability in the target region before applying."
  type        = string
  default     = "17"

  validation {
    condition     = can(regex("^17(\\.[0-9]+)?$", var.db_engine_version))
    error_message = "The database engine version must be PostgreSQL 17, optionally with a minor version."
  }
}

variable "image_tag" {
  description = "Full Git commit SHA of application images; publish before enabling runtime."
  type        = string
  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "Image tag must be a full 40-character lowercase Git commit SHA."
  }
}
variable "runtime_enabled" {
  description = "Start tasks only after all discovery endpoints and application images exist."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "Exact GitHub owner/repository whose main branch may deploy."
  type        = string
  nullable    = false
  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "GitHub repository must be owner/repository without whitespace or wildcards."
  }
}
variable "github_owner_id" {
  description = "Positive numeric GitHub owner ID for the immutable OIDC subject."
  type        = string
  nullable    = false
  validation {
    condition     = can(regex("^[1-9][0-9]*$", var.github_owner_id))
    error_message = "GitHub owner ID must be a positive numeric string."
  }
}
variable "github_repository_id" {
  description = "Positive numeric GitHub repository ID for the immutable OIDC subject."
  type        = string
  nullable    = false
  validation {
    condition     = can(regex("^[1-9][0-9]*$", var.github_repository_id))
    error_message = "GitHub repository ID must be a positive numeric string."
  }
}
