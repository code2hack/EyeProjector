package com.code2hack.eyeprojector.protocol

enum class ActivationGoal { USABLE, PROJECTED }
data class ReceiverIncarnation(val id: String, val sequence: Long)
