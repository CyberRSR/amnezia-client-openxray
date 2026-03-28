package org.amnezia.vpn.protocol

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

private const val STATE_KEY = "state"
private const val MESSAGE_KEY = "message"
private const val STEP_KEYS_KEY = "step_keys"
private const val STEP_STATES_KEY = "step_states"

@Suppress("DataClassPrivateConstructor")
data class Status private constructor(
    val state: ProtocolState,
    val message: String,
    val steps: List<StatusStep>
) {
    private constructor(builder: Builder) : this(builder.state, builder.message, builder.steps.toList())

    fun toJsonString(): String =
        JSONObject()
            .put("message", message)
            .put(
                "steps",
                JSONArray().apply {
                    steps.forEach { step ->
                        put(
                            JSONObject()
                                .put("key", step.key)
                                .put("state", step.state.ordinal)
                        )
                    }
                }
            )
            .toString()

    class Builder {
        lateinit var state: ProtocolState
            private set
        var message: String = ""
            private set
        internal val steps: MutableList<StatusStep> = mutableListOf()

        fun setState(state: ProtocolState) = apply { this.state = state }
        fun setMessage(message: String) = apply { this.message = message }
        fun setSteps(steps: List<StatusStep>) = apply {
            this.steps.clear()
            this.steps += steps
        }
        fun addStep(key: String, state: StatusStepState) = apply { this.steps += StatusStep(key, state) }

        fun build(): Status = Status(this)
    }

    companion object {
        inline fun build(block: Builder.() -> Unit): Status = Builder().apply(block).build()
    }
}

data class StatusStep(
    val key: String,
    val state: StatusStepState
)

enum class StatusStepState {
    PENDING,
    ACTIVE,
    SUCCESS,
    FAILURE
}

fun Bundle.putStatus(status: Status) {
    putInt(STATE_KEY, status.state.ordinal)
    putString(MESSAGE_KEY, status.message)
    putStringArrayList(STEP_KEYS_KEY, ArrayList(status.steps.map(StatusStep::key)))
    putIntArray(STEP_STATES_KEY, status.steps.map { it.state.ordinal }.toIntArray())
}

fun Bundle.putStatus(state: ProtocolState) {
    putStatus(Status.build { setState(state) })
}

fun Bundle.getStatus(): Status =
    Status.build {
        setState(ProtocolState.entries[getInt(STATE_KEY)])
        setMessage(getString(MESSAGE_KEY).orEmpty())
        val stepKeys = getStringArrayList(STEP_KEYS_KEY).orEmpty()
        val stepStates = getIntArray(STEP_STATES_KEY) ?: intArrayOf()
        setSteps(
            stepKeys.mapIndexed { index, key ->
                StatusStep(
                    key = key,
                    state = StatusStepState.entries.getOrElse(stepStates.getOrElse(index) { 0 }) { StatusStepState.PENDING }
                )
            }
        )
    }
