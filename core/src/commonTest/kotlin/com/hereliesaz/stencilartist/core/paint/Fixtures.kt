package com.hereliesaz.stencilartist.core.paint

/** A real UPC-A (12 digits) and its EAN-13 form, and a real EAN-13. All three have valid check digits. */
internal const val UPC = "036000291452"
internal const val UPC_AS_EAN = "0036000291452"
internal const val EAN = "4006381333931"

/** One row of a catalogue file, fields in file order. */
internal fun row(
    line: Int,
    name: String,
    code: String = "",
    hex: String = "",
    source: String = if (hex.isEmpty()) "" else "o",
    gtin: String = "",
    finish: String = "",
): String = listOf(line, name, finish, code, hex, source, gtin).joinToString("\t")

/** A catalogue file: brands "Montana Cans" (lines 0, 1) and "Loop Colors" (line 2), then [rows]. */
internal fun fixtureText(vararg rows: String): String = buildString {
    append("#stencil-artist-paints\t1\n")
    append("#brand\t0\tMontana Cans\twww.montana-cans.com\n")
    append("#brand\t1\tLoop Colors\tloopcolors.com\n")
    append("#line\t0\t0\tGOLD 400ml\n")
    append("#line\t1\t0\tMARBLE 400ml\n")
    append("#line\t2\t1\tColors 400ml\n")
    for (r in rows) append(r).append('\n')
}

/** The catalogue most tests use. */
internal fun fixtureCatalogue(): PaintCatalogue = PaintCatalogue.parse(
    fixtureText(
        row(0, "Shock Pink", "RV-252", "e6007e", gtin = EAN),
        row(0, "Rouge Écarlate", "RV-3020", "c1121c"),
        row(0, "Pink Panther", "RV-4", "f0a0c0"),
        row(0, "Moss", "RV-40", "6b8e23"),
        row(0, "Black", "RV-9011", "0a0a0a", gtin = UPC),
        row(1, "Marble Silver", "EM1000", finish = "Metallic"),
        row(2, "Pink Fantasy", "L252", "e6007e"),
        row(2, "Rouge", "L-100", "d01010", source = "s"),
        row(2, "Blue Sky", "L-200", "2060d0"),
    ),
)

internal fun PaintCatalogue.named(name: String): Can = cans.first { it.name == name }
