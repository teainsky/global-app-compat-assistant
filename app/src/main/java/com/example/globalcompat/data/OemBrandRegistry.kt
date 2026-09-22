package com.example.globalcompat.data

data class OemBrandRegistration(
    val id: String,
    val displayName: String,
    val aliases: Set<String>,
    val probableRomFamily: RomFamily? = null,
)

data class OemBrandResolution(
    val registration: OemBrandRegistration?,
    val matchedAlias: String?,
) {
    val isKnown: Boolean get() = registration != null
}

class OemBrandRegistry(
    val registrations: List<OemBrandRegistration> = DEFAULT_REGISTRATIONS,
) {
    private val byAlias = registrations.flatMap { registration ->
        registration.aliases.map { alias -> alias.normalizedAlias() to registration }
    }.toMap()

    fun resolve(manufacturer: String, brand: String): OemBrandResolution {
        val candidates = listOf(manufacturer, brand)
            .map(String::normalizedAlias)
            .filter(String::isNotBlank)
        val match = candidates.firstNotNullOfOrNull { alias ->
            byAlias[alias]?.let { registration -> alias to registration }
        }
        return OemBrandResolution(match?.second, match?.first)
    }

    companion object {
        val DEFAULT_REGISTRATIONS = listOf(
            registration("SAMSUNG", "Samsung", RomFamily.ONE_UI, "samsung"),
            registration("GOOGLE", "Google Pixel", RomFamily.PIXEL_ANDROID, "google", "pixel"),
            registration("HUAWEI", "Huawei", null, "huawei"),
            registration("HONOR", "Honor", RomFamily.MAGIC_OS, "honor"),
            registration("XIAOMI", "Xiaomi", RomFamily.HYPER_OS, "xiaomi", "redmi", "poco"),
            registration("OPPO", "OPPO", RomFamily.COLOR_OS, "oppo"),
            registration("ONEPLUS", "OnePlus", RomFamily.OXYGEN_OS, "oneplus"),
            registration("REALME", "realme", RomFamily.REALME_UI, "realme"),
            registration("VIVO", "vivo", null, "vivo", "iqoo"),
            registration("MOTOROLA_LENOVO", "Motorola / Lenovo", RomFamily.MOTOROLA_ANDROID, "motorola", "lenovo"),
            registration("TRANSSION_TECNO", "TECNO", RomFamily.TECNO_HIOS, "tecno"),
            registration("TRANSSION_INFINIX", "Infinix", RomFamily.INFINIX_XOS, "infinix"),
            registration("TRANSSION_ITEL", "itel", RomFamily.ITEL_OS, "itel"),
            registration("SONY", "Sony", RomFamily.SONY_ANDROID, "sony"),
            registration("ASUS", "ASUS", RomFamily.ASUS_ANDROID, "asus"),
            registration("NOTHING", "Nothing", RomFamily.NOTHING_OS, "nothing"),
            registration("ZTE_NUBIA", "ZTE / nubia", RomFamily.MY_OS, "zte", "nubia"),
            registration("TCL", "TCL", RomFamily.TCL_UI, "tcl"),
            registration("HMD_NOKIA", "HMD / Nokia", RomFamily.HMD_ANDROID, "hmd", "nokia"),
            registration("FAIRPHONE", "Fairphone", RomFamily.AOSP, "fairphone"),
        )

        private fun registration(
            id: String,
            displayName: String,
            probableRomFamily: RomFamily?,
            vararg aliases: String,
        ) = OemBrandRegistration(
            id = id,
            displayName = displayName,
            aliases = aliases.toSet(),
            probableRomFamily = probableRomFamily,
        )
    }
}

private fun String.normalizedAlias(): String = trim().lowercase()
