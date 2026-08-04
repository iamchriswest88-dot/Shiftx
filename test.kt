fun main() {
    var value = 100
    val c = (0x20 or (value and 0x1f) + 63).toChar()
    println("Char is: " + c)
}
