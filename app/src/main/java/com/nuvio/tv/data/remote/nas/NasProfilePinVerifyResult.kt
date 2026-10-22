package com.nuvio.tv.data.remote.nas

data class NasProfilePinVerifyResult(
    val unlocked: Boolean = false,
    val retryAfterSeconds: Int = 0
)
