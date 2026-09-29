package com.rhamers.osse.youtube

class FakePoTokenProvider(
    private val results: MutableList<Result<String>> = mutableListOf(),
    private val default: (contentBinding: String) -> Result<String> = { Result.success("token-for-$it") },
) : PoTokenProvider {

    val callArgsInOrder = mutableListOf<String>()
    val callCount get() = callArgsInOrder.size

    override suspend fun getToken(contentBinding: String): String {
        callArgsInOrder.add(contentBinding)
        val scripted = if (results.isNotEmpty()) results.removeAt(0) else default(contentBinding)
        return scripted.getOrThrow()
    }
}
