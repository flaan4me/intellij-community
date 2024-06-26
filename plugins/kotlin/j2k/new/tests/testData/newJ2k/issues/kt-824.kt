package demo

import demo.One

internal class Container {
    var myBoolean: Boolean = true
}

internal object One {
    var myContainer: Container = Container()
}

internal class Test {
    fun test() {
        if (One.myContainer.myBoolean) println("Ok")
        val s = if (One.myContainer.myBoolean) "YES" else "NO"
        while (One.myContainer.myBoolean) println("Ok")
        do {
            println("Ok")
        } while (One.myContainer.myBoolean)
    }
}
