package top.ntutn.updater

/** Numeric update version. [name] is a display label and does not participate in ordering. */
public data class UpdateVersion(public val code: Long, public val name: String) {
    init {
        require(code > 0) { "Version code must be positive." }
        require(name.isNotBlank()) { "Version name must not be blank." }
    }

    /** Returns whether this release is newer than the currently installed version. */
    public fun isNewerThan(installed: UpdateVersion): Boolean = code > installed.code
}
