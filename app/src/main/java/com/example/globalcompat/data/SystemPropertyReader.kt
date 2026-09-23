package com.example.globalcompat.data

fun interface SystemPropertyReader {
    fun read(keys: Set<String>): Map<String, String>
}
class GetPropSystemPropertyReader : SystemPropertyReader {
    override fun read(keys: Set<String>): Map<String, String> = runCatching {
        val process = ProcessBuilder("/system/bin/getprop")
            .redirectErrorStream(true)
            .start()
        val values = process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull(::parseLine)
                .filter { (key, _) -> key in keys }
                .toMap()
        }
        process.waitFor()
        values
    }.getOrDefault(emptyMap())

    private fun parseLine(line: String): Pair<String, String>? {
        val match = PROPERTY_LINE.matchEntire(line) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private companion object {
        val PROPERTY_LINE = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
    }
}
