// "Add parameter to function 'f'" "true"
interface Z {
    fun f()
}

interface ZZ {
    fun f()
}

interface ZZZ: Z, ZZ {
}

interface ZZZZ : ZZZ {
    override fun f()
}

fun usage(z: ZZZ) {
    z.f(<caret>3)
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddFunctionParametersFix