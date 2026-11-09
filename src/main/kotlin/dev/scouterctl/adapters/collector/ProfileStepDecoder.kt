package dev.scouterctl.adapters.collector

import dev.scouterctl.core.ProfileStep
import dev.scouterctl.core.ProfileStepDetails
import dev.scouterctl.core.ProfileTarget
import dev.scouterctl.core.SqlProfile
import scouter.lang.step.ApiCallStep
import scouter.lang.step.SocketStep
import scouter.lang.step.SqlStep
import scouter.lang.step.SqlStep3
import scouter.lang.step.Step
import scouter.lang.step.StepSingle
import java.net.InetAddress

internal fun Step.toProfileStep(includeStepDetails: Boolean, includeText: Boolean): ProfileStep {
    val sql = if (this is SqlStep) SqlProfile(hash, elapsed, error, (this as? SqlStep3)?.updated) else null
    val details = if (includeStepDetails && this is StepSingle) details(includeText) else null
    return ProfileStep(stepTypeName, order, sql, details)
}

private fun StepSingle.details(includeText: Boolean): ProfileStepDetails = when (this) {
    is SocketStep -> ProfileStepDetails(start_time, parent, elapsed, error, if (includeText) target() else null)
    is ApiCallStep -> ProfileStepDetails(start_time, parent, elapsed, error,
        if (includeText) address?.takeIf { it.isNotBlank() }?.let { ProfileTarget(it) } else null)
    else -> ProfileStepDetails(start_time, parent)
}

private fun SocketStep.target(): ProfileTarget? {
    val address = ipaddr?.takeIf { it.size == 4 || it.size == 16 }?.let { InetAddress.getByAddress(it).hostAddress }
    val targetPort = port.takeIf { it in 1..65535 }
    return if (address == null && targetPort == null) null else ProfileTarget(address, targetPort)
}
